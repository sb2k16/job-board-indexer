/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import io.micrometer.core.instrument.Counter;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.acknowledgements.AcknowledgementSet;
import org.opensearch.dataprepper.model.buffer.Buffer;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.event.EventType;
import org.opensearch.dataprepper.model.event.JacksonEvent;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.model.source.coordinator.enhanced.EnhancedSourceCoordinator;
import io.github.sb2k16.jobboard.adapter.AdapterRegistry;
import io.github.sb2k16.jobboard.adapter.BoardFetchException;
import io.github.sb2k16.jobboard.adapter.HttpFetcher;
import io.github.sb2k16.jobboard.fetch.FetchPolicy;
import io.github.sb2k16.jobboard.fetch.FetcherFactory;
import io.github.sb2k16.jobboard.fetch.JdkHttpTransport;
import io.github.sb2k16.jobboard.fetch.Ticker;
import io.github.sb2k16.jobboard.state.BoardStateException;
import io.github.sb2k16.jobboard.state.BoardStateStore;
import io.github.sb2k16.jobboard.state.JdbcBoardStateStore;
import io.github.sb2k16.jobboard.state.Outcome;
import org.opensearch.dataprepper.model.acknowledgements.AcknowledgementSetManager;
import io.github.sb2k16.jobboard.adapter.PlatformAdapter;
import io.github.sb2k16.jobboard.spec.PlatformSpec;
import io.github.sb2k16.jobboard.spec.SpecAdapter;
import io.github.sb2k16.jobboard.spec.SpecLoader;
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.JobRecord;
import io.github.sb2k16.jobboard.model.Tenant;
import io.github.sb2k16.jobboard.closure.ClosureSweeper;
import io.github.sb2k16.jobboard.closure.HttpIndexClient;
import io.github.sb2k16.jobboard.identity.IdentityResolver;
import io.github.sb2k16.jobboard.identity.JdbcIdentityStore;
import io.github.sb2k16.jobboard.identity.MemoryIdentityStore;
import io.github.sb2k16.jobboard.state.JdbcBoardStateStore;
import org.opensearch.dataprepper.plugins.source.source_crawler.base.Crawler;
import org.opensearch.dataprepper.plugins.source.source_crawler.base.LeaderProgressState;
import org.opensearch.dataprepper.plugins.source.source_crawler.coordination.partition.LeaderPartition;
import org.opensearch.dataprepper.plugins.source.source_crawler.coordination.partition.SaasSourcePartition;
import org.opensearch.dataprepper.plugins.source.source_crawler.coordination.state.PaginationCrawlerWorkerProgressState;
import org.opensearch.dataprepper.plugins.source.source_crawler.exception.SaaSCrawlerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.opensearch.dataprepper.plugins.source.source_crawler.coordination.scheduler.LeaderScheduler.DEFAULT_EXTEND_LEASE_MINUTES;

/**
 * Leader: once per refresh interval of each refresh group, creates one worker partition per board
 * of the group. A pipeline may hold several groups (one OpenSearch sink for all of them); each
 * group's last refresh is kept in {@code group_refresh}, so a new leader keeps the cadence.
 * Worker: fetches one board through its platform adapter and politeness layer, writes one event
 * per job, and records the outcome in {@code board_state}.
 *
 * <p>A complete snapshot joins the board's {@code snapshot_history} only after the sink has
 * confirmed every job: its events go into an acknowledgement set of our own, whose callback
 * commits the snapshot and only then completes the framework's set (which completes the
 * partition). If the sink fails or the pod dies first, nothing is committed and the partition's
 * lease lapses, so another pod retries it.
 *
 * <p>A failed fetch is recorded and its partition completed, not thrown: the framework would
 * otherwise hold the partition until its lease lapses and retry it every lease (retryable) or
 * daily for 30 days (non-retryable). The board is fetched again at the next refresh.
 */
@Named
public class JobBoardCrawler implements Crawler<PaginationCrawlerWorkerProgressState> {
    static final String PLATFORM = "platform";
    static final String BOARD = "board";
    static final String COMPANY = "company";
    static final String CRAWL_STARTED = "crawl_started";
    /** The refresh a crawl belongs to; a retry carries its refresh's time, so it never runs past the next one. */
    static final String REFRESH_STARTED = "refresh_started";
    /**
     * Sooner retry of a crawl that failed for a reason that may pass (429, 5xx, timeout, network, an
     * open circuit, robots.txt unavailable): 15, 30, then 60 minutes after the failure, but never at or
     * after the group's next refresh, which retries the board anyway.
     */
    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(60));
    static final int WRITE_BATCH = 100;
    /** Below the framework's own 2 h acknowledgement timeout, so ours always resolves first. */
    static final Duration SNAPSHOT_ACK_TIMEOUT = Duration.ofHours(1);
    private static final int BUFFER_WRITE_TIMEOUT_MILLIS = (int) Duration.ofSeconds(30).toMillis();
    /**
     * How long one batch may wait for buffer space before the crawl fails. A full buffer is the
     * sink's back-pressure, not an error: failing on the first 30 s wait threw away whole crawled
     * boards and re-crawled them, which kept the buffer full (Nov 20 and 21). Well inside
     * SNAPSHOT_ACK_TIMEOUT.
     */
    static final Duration BUFFER_WRITE_PATIENCE = Duration.ofMinutes(10);
    private static final Logger LOG = LoggerFactory.getLogger(JobBoardCrawler.class);

    private final JobBoardSourceConfig config;
    private final List<GroupConfig> groups;
    private final Map<String, GroupConfig> groupOfPlatform = new HashMap<>();
    private final Map<String, PlatformConfig> platforms = new HashMap<>();
    /** Refreshes this leader started, by group: the cadence without a database, or when a write to it failed. */
    private final Map<String, Instant> refreshedHere = new HashMap<>();
    private final Map<String, PlatformAdapter> adapters = new HashMap<>();
    private final Map<String, HttpFetcher> fetchers = new HashMap<>();
    private final TenantLoader tenantLoader;
    private final BoardStateStore store;
    private final IdentityResolver identity;
    /** Null when no closure block is configured: jobs are never closed. */
    private final ClosureSweeper sweeper;
    private volatile AcknowledgementSetManager acknowledgementSetManager;
    private final Counter boardsComplete;
    private final Counter boardsIncomplete;
    private final Counter boardsFailed;
    private final Counter boardsGone;
    private final Counter boardsBlocked;
    private final Counter boardsSuperseded;
    private final Counter snapshotsCommitted;
    private final Counter snapshotsRejected;
    private final Counter jobsEmitted;
    private final Counter retriesScheduled;
    private final Counter retriesQueued;

    @Inject
    public JobBoardCrawler(final JobBoardSourceConfig config, final PluginMetrics pluginMetrics) {
        this(config, pluginMetrics, AdapterRegistry.defaultRegistry(), null, new TenantLoader(), openStore(config));
    }

    /** @param fetcher used for every platform when not null (tests); otherwise each platform gets a polite fetcher */
    JobBoardCrawler(final JobBoardSourceConfig config, final PluginMetrics pluginMetrics, final AdapterRegistry registry,
                    final HttpFetcher fetcher, final TenantLoader tenantLoader, final BoardStateStore store) {
        this.config = config;
        this.groups = config.getGroups();
        for (final GroupConfig group : groups) {
            for (final PlatformConfig platform : group.getPlatforms()) {
                groupOfPlatform.put(platform.getName(), group);
                platforms.put(platform.getName(), platform);
            }
        }
        this.tenantLoader = tenantLoader;
        this.store = store;
        this.identity = new IdentityResolver(store instanceof JdbcBoardStateStore
                ? new JdbcIdentityStore(((JdbcBoardStateStore) store).dataSource()) : new MemoryIdentityStore());
        this.sweeper = openSweeper(config, store);
        this.boardsComplete = pluginMetrics.counter("boardsComplete");
        this.boardsIncomplete = pluginMetrics.counter("boardsIncomplete");
        this.boardsFailed = pluginMetrics.counter("boardsFailed");
        this.boardsGone = pluginMetrics.counter("boardsGone");
        this.boardsBlocked = pluginMetrics.counter("boardsBlocked");
        this.boardsSuperseded = pluginMetrics.counter("boardsSuperseded");
        this.snapshotsCommitted = pluginMetrics.counter("snapshotsCommitted");
        this.snapshotsRejected = pluginMetrics.counter("snapshotsRejected");
        this.jobsEmitted = pluginMetrics.counter("jobsEmitted");
        this.retriesScheduled = pluginMetrics.counter("boardRetriesScheduled");
        this.retriesQueued = pluginMetrics.counter("boardRetriesQueued");
        final PolitenessConfig p = config.getPoliteness();
        final FetcherFactory factory = fetcher != null ? null : new FetcherFactory(new JdkHttpTransport(), Ticker.SYSTEM,
                p.getRobotsTtl(), p.getRequestTimeout(), p.getBreakerThreshold(), p.getBreakerCooldown());
        for (final PlatformConfig platform : config.getPlatforms()) {
            final PlatformAdapter adapter = resolveAdapter(platform, registry);
            adapters.put(platform.getName(), adapter);
            fetchers.put(platform.getName(), fetcher != null ? fetcher : factory.forPlatform(platform.getName(), policy(adapter, p)));
        }
    }

    /** The source hands over its acknowledgement manager; snapshots need it to wait for the sink. */
    public void setAcknowledgementSetManager(final AcknowledgementSetManager manager) {
        this.acknowledgementSetManager = manager;
    }

    private static ClosureSweeper openSweeper(final JobBoardSourceConfig config, final BoardStateStore store) {
        final ClosureConfig c = config.getClosure();
        if (c == null || c.getOpensearchUrl() == null) {
            LOG.warn("No closure block configured: jobs that disappear from their boards are never closed");
            return null;
        }
        if (store == BoardStateStore.NONE) {
            LOG.warn("Closure needs board_state (snapshot history); jobs are never closed");
            return null;
        }
        final HttpIndexClient.Signer signer = c.getAwsRegion() == null ? null : HttpIndexClient.sigV4(c.getAwsRegion());
        return new ClosureSweeper(c, new HttpIndexClient(c.getOpensearchUrl(), c.getIndex(), c.getUsername(), c.getPassword(),
                c.getRequestTimeout(), signer), store);
    }

    private static BoardStateStore openStore(final JobBoardSourceConfig config) {
        final BoardStateConfig c = config.getBoardState();
        if (c == null || c.getUrl() == null) {
            LOG.warn("No board_state database configured: outcomes and snapshot history are not recorded");
            return BoardStateStore.NONE;
        }
        return new JdbcBoardStateStore(c.getUrl(), c.getUsername(), c.getPassword(), c.getMaxPoolSize(), c.getHistorySize());
    }

    static FetchPolicy policy(final PlatformAdapter adapter, final PolitenessConfig p) {
        final PlatformSpec.RateLimitSpec rate = adapter instanceof SpecAdapter
                ? ((SpecAdapter) adapter).getSpec().getRateLimit() : new PlatformSpec.RateLimitSpec();
        return new FetchPolicy(rate.getRequestsPerSecond(), rate.getMaxConcurrency(), p.getReplicas(), p.getMaxAttempts(),
                p.getBackoffBase(), p.getBackoffCap(), p.getMaxRetryAfter(), p.getRequestTimeout());
    }

    @Override
    public Instant crawl(final LeaderPartition leaderPartition, final EnhancedSourceCoordinator coordinator) {
        final LeaderProgressState state = leaderPartition.getProgressState().get();
        final Instant now = Instant.now();
        boolean refreshed = false;
        for (final GroupConfig group : groups) {
            final Instant last;
            try {
                last = lastRefresh(group, state);
            } catch (final BoardStateException e) {
                // Not knowing when the group last ran is no reason to crawl all of it again: wait.
                LOG.warn("{}: could not read its last refresh ({}); checking again next round", label(group), e.getMessage());
                continue;
            }
            if (last != null && Duration.between(last, now).compareTo(group.getRefreshInterval()) < 0) {
                queueDueRetries(coordinator, group, last, now);
                continue;
            }
            refresh(group, coordinator, now);
            markRefreshed(group, state, now);
            refreshed = true;
        }
        if (refreshed) {
            coordinator.saveProgressStateForPartition(leaderPartition, DEFAULT_EXTEND_LEASE_MINUTES);
        }
        return state.getLastPollTime();
    }

    private void refresh(final GroupConfig group, final EnhancedSourceCoordinator coordinator, final Instant now) {
        int created = 0;
        for (final PlatformConfig platform : group.getPlatforms()) {
            try {
                store.clearRetries(platform.getName()); // this refresh crawls every board anyway
            } catch (final BoardStateException e) {
                LOG.warn("{}: could not drop pending retries before the refresh ({})", platform.getName(), e.getMessage());
            }
            for (final Tenant tenant : tenantLoader.load(platform)) {
                final PaginationCrawlerWorkerProgressState work = new PaginationCrawlerWorkerProgressState();
                final Map<String, Object> keys = new HashMap<>();
                keys.put(PLATFORM, platform.getName());
                keys.put(BOARD, tenant.getBoard());
                keys.put(COMPANY, tenant.getCompany());
                keys.put(CRAWL_STARTED, now.toString());
                keys.put(REFRESH_STARTED, now.toString());
                work.setKeyAttributes(keys);
                work.setItemIds(List.of(tenant.getBoard()));
                work.setExportStartTime(now);
                coordinator.createPartition(new SaasSourcePartition(work, platform.getName() + "|" + tenant.getBoard() + "|" + now));
                created++;
            }
        }
        LOG.info("Refresh {} of {}: created {} board partitions", now, label(group), created);
    }

    /**
     * The group's last refresh: for the one unnamed group of a {@code platforms} pipeline, the leader
     * partition's poll time (as before groups existed); for a named group, the later of its
     * {@code group_refresh} row and any refresh this leader started.
     */
    private Instant lastRefresh(final GroupConfig group, final LeaderProgressState state) {
        if (group.getName() == null) {
            return state.getLastPollTime();
        }
        final Instant stored = store.groupRefreshedAt(group.getName()).orElse(null);
        final Instant here = refreshedHere.get(group.getName());
        if (stored == null || here == null) {
            return stored != null ? stored : here;
        }
        return stored.isAfter(here) ? stored : here;
    }

    private void markRefreshed(final GroupConfig group, final LeaderProgressState state, final Instant now) {
        state.setLastPollTime(now);
        if (group.getName() == null) {
            return;
        }
        refreshedHere.put(group.getName(), now);
        try {
            store.recordGroupRefresh(group.getName(), now);
        } catch (final BoardStateException e) {
            LOG.warn("{}: could not record its refresh ({}); a new leader may refresh it early", label(group), e.getMessage());
        }
    }

    private static String label(final GroupConfig group) {
        return group.getName() == null ? "the pipeline" : "group " + group.getName();
    }

    /**
     * Between refreshes: queues a crawl of every board whose retry is due. Claiming clears the
     * board's retry_at in the same statement, so a board is queued once however many leaders ask.
     */
    private void queueDueRetries(final EnhancedSourceCoordinator coordinator, final GroupConfig group, final Instant refreshStarted,
                                 final Instant now) {
        for (final PlatformConfig platform : group.getPlatforms()) {
            final List<String> due;
            try {
                due = store.claimDueRetries(platform.getName(), now);
            } catch (final BoardStateException e) {
                LOG.warn("{}: could not read due retries ({}); the next refresh covers them", platform.getName(), e.getMessage());
                continue;
            }
            if (due.isEmpty()) {
                continue;
            }
            final Map<String, Tenant> tenants = new HashMap<>();
            for (final Tenant t : tenantLoader.load(platform)) {
                tenants.put(t.getBoard(), t);
            }
            for (final String board : due) {
                final Tenant tenant = tenants.get(board);
                if (tenant == null) {
                    continue; // no longer in the tenant list
                }
                final PaginationCrawlerWorkerProgressState work = new PaginationCrawlerWorkerProgressState();
                final Map<String, Object> keys = new HashMap<>();
                keys.put(PLATFORM, platform.getName());
                keys.put(BOARD, board);
                keys.put(COMPANY, tenant.getCompany());
                keys.put(CRAWL_STARTED, now.toString());
                keys.put(REFRESH_STARTED, refreshStarted.toString());
                work.setKeyAttributes(keys);
                work.setItemIds(List.of(board));
                work.setExportStartTime(now);
                coordinator.createPartition(new SaasSourcePartition(work, platform.getName() + "|" + board + "|" + now + "|retry"));
                retriesQueued.increment();
                LOG.info("{}/{}: retrying after a failed crawl", platform.getName(), board);
            }
        }
    }

    /**
     * After a failed crawl: a failure that may pass gets a sooner retry; anything else, and any
     * failure whose retry would fall at or after the next refresh, clears the board's retry.
     */
    void scheduleOrClearRetry(final String platform, final String board, final boolean retryable, final Instant failedAt,
                              final Instant refreshStarted) {
        try {
            if (!retryable) {
                store.clearRetry(platform, board);
                return;
            }
            final GroupConfig group = groupOfPlatform.get(platform);
            if (group == null) {
                store.clearRetry(platform, board); // no longer configured here
                return;
            }
            final int n = store.retryCount(platform, board);
            final Instant at = failedAt.plus(RETRY_DELAYS.get(Math.min(n, RETRY_DELAYS.size() - 1)));
            if (!at.isBefore(refreshStarted.plus(group.getRefreshInterval()))) {
                store.clearRetry(platform, board);
                return;
            }
            store.scheduleRetry(platform, board, at);
            retriesScheduled.increment();
            LOG.info("{}/{}: retry {} scheduled for {}", platform, board, n + 1, at);
        } catch (final BoardStateException e) {
            LOG.warn("{}/{}: could not schedule a retry ({}); the next refresh covers it", platform, board, e.getMessage());
        }
    }

    @Override
    public void executePartition(final PaginationCrawlerWorkerProgressState state, final Buffer<Record<Event>> buffer,
                                 final AcknowledgementSet acknowledgementSet) {
        final Map<String, Object> keys = state.getKeyAttributes();
        final String platformName = (String) keys.get(PLATFORM);
        final Tenant tenant = new Tenant((String) keys.get(BOARD), (String) keys.get(COMPANY));
        final Instant crawlStarted = Instant.parse((String) keys.get(CRAWL_STARTED));
        // Partitions queued before retries existed carry no refresh time: their crawl start is it.
        final Instant refreshStarted = keys.get(REFRESH_STARTED) == null ? crawlStarted
                : Instant.parse((String) keys.get(REFRESH_STARTED));
        final PlatformConfig platform = platforms.get(platformName);
        if (platform == null) {
            throw new SaaSCrawlerException("Platform " + platformName + " is no longer configured", false);
        }
        final String board = tenant.getBoard();

        try {
            if (store.isSuperseded(platformName, board, crawlStarted)) {
                // A newer crawl of this board already ran; this one is a leftover (a retry after a lapsed lease).
                boardsSuperseded.increment();
                LOG.info("{}/{}: crawl {} superseded by a newer crawl, skipped", platformName, board, crawlStarted);
                completeEmpty(acknowledgementSet);
                return;
            }

            final Instant attemptedAt = Instant.now();
            final BoardSnapshot snapshot;
            try {
                snapshot = adapters.get(platformName).fetchBoard(platform, tenant, fetchers.get(platformName));
            } catch (final BoardFetchException e) {
                final Outcome outcome = classify(e);
                count(outcome);
                store.recordAttempt(platformName, board, crawlStarted, attemptedAt, outcome, null, e.getMessage());
                LOG.warn("{}/{}: {} ({})", platformName, board, outcome, e.getMessage());
                scheduleOrClearRetry(platformName, board, outcome == Outcome.FAILED && e.isRetryable(), attemptedAt, refreshStarted);
                if (outcome == Outcome.GONE && sweeper != null) {
                    sweepSafely(() -> sweeper.afterGone(platformName, board, Instant.now()), platformName, board);
                }
                completeEmpty(acknowledgementSet);
                return;
            }

            final Outcome outcome = snapshot.isComplete() ? Outcome.COMPLETE : Outcome.PARTIAL;
            count(outcome);
            store.recordAttempt(platformName, board, crawlStarted, attemptedAt, outcome, snapshot.getJobs().size(),
                    outcome == Outcome.PARTIAL ? "parsed " + snapshot.getJobs().size() + " jobs, platform reports "
                            + snapshot.getReportedTotal() + (snapshot.getResultLimit() == null ? ""
                            : "; its search serves at most " + snapshot.getResultLimit() + " results") : null);
            // The board was read: a partial read is the platform's limit, not a failure that may pass.
            scheduleOrClearRetry(platformName, board, false, attemptedAt, refreshStarted);
            if (outcome == Outcome.PARTIAL) {
                LOG.warn("{}/{}: parsed {} jobs but platform reports {}; not a snapshot", platformName, board,
                        snapshot.getJobs().size(), snapshot.getReportedTotal());
            }

            final List<Record<Event>> records = toRecords(snapshot, crawlStarted, identity);
            final AcknowledgementSetManager manager = acknowledgementSetManager;
            if (outcome == Outcome.COMPLETE && acknowledgementSet != null && manager != null) {
                final int jobCount = records.size();
                final AcknowledgementSet snapshotAck = manager.create(
                        result -> onSnapshotAcknowledged(result, platformName, board, crawlStarted, jobCount, acknowledgementSet),
                        SNAPSHOT_ACK_TIMEOUT);
                write(records, buffer, snapshotAck, platformName, board);
                snapshotAck.complete();
            } else {
                write(records, buffer, acknowledgementSet, platformName, board);
                if (acknowledgementSet != null) {
                    acknowledgementSet.complete();
                }
                if (outcome == Outcome.COMPLETE) {
                    // Acknowledgments off: the buffer write is the strongest confirmation available.
                    store.commitSnapshot(platformName, board, crawlStarted, Instant.now(), records.size());
                    snapshotsCommitted.increment();
                    sweep(platformName, board);
                }
            }
            jobsEmitted.increment(records.size());
            LOG.info("{}/{}: emitted {} jobs ({})", platformName, board, records.size(), outcome);
        } catch (final BoardStateException e) {
            // board_state unavailable: leave the partition to be retried rather than lose the outcome.
            throw new SaaSCrawlerException("board_state unavailable for " + platformName + "/" + board + ": " + e.getMessage(), e, true);
        }
    }

    private void onSnapshotAcknowledged(final boolean result, final String platform, final String board, final Instant crawlStarted,
                                        final int jobCount, final AcknowledgementSet partitionAck) {
        // Runs on the acknowledgement executor, which discards exceptions: log everything here.
        try {
            if (result) {
                store.commitSnapshot(platform, board, crawlStarted, Instant.now(), jobCount);
                snapshotsCommitted.increment();
                // Still under the board's lease: the partition completes only after the sweep.
                sweep(platform, board);
                partitionAck.complete();
            } else {
                snapshotsRejected.increment();
                store.recordAttempt(platform, board, crawlStarted, Instant.now(), Outcome.FAILED, jobCount,
                        "sink did not confirm every job");
                LOG.warn("{}/{}: sink did not confirm crawl {}; no snapshot, partition left to its lease", platform, board, crawlStarted);
                partitionAck.cancel();
            }
        } catch (final RuntimeException e) {
            LOG.error("{}/{}: could not record the outcome of crawl {}; partition left to its lease", platform, board, crawlStarted, e);
            partitionAck.cancel();
        }
    }

    private void sweep(final String platform, final String board) {
        if (sweeper != null) {
            sweepSafely(() -> sweeper.afterSnapshot(platform, board, Instant.now()), platform, board);
        }
    }

    /** A failed sweep closes nothing and is retried after the next snapshot; it never fails the crawl. */
    private static void sweepSafely(final java.util.function.Supplier<ClosureSweeper.Result> run, final String platform,
                                    final String board) {
        try {
            run.get();
        } catch (final RuntimeException e) {
            LOG.error("{}/{}: closure sweep failed; it runs again after the next snapshot", platform, board, e);
        }
    }

    private static void write(final List<Record<Event>> records, final Buffer<Record<Event>> buffer, final AcknowledgementSet ack,
                              final String platform, final String board) {
        try {
            for (int i = 0; i < records.size(); i += WRITE_BATCH) {
                final List<Record<Event>> batch = records.subList(i, Math.min(records.size(), i + WRITE_BATCH));
                if (ack != null) {
                    batch.forEach(r -> ack.add(r.getData()));
                }
                writePatiently(batch, buffer, platform, board);
            }
        } catch (final Exception e) {
            if (ack != null) {
                ack.cancel();
            }
            throw new SaaSCrawlerException("Buffer write failed for " + platform + "/" + board, e, true);
        }
    }

    private static void writePatiently(final List<Record<Event>> batch, final Buffer<Record<Event>> buffer, final String platform,
                                       final String board) throws Exception {
        final long deadline = System.nanoTime() + BUFFER_WRITE_PATIENCE.toNanos();
        int waits = 0;
        while (true) {
            try {
                buffer.writeAll(batch, BUFFER_WRITE_TIMEOUT_MILLIS);
                return;
            } catch (final java.util.concurrent.TimeoutException e) {
                if (System.nanoTime() >= deadline) {
                    throw e;
                }
                if (++waits == 1 || waits % 10 == 0) {
                    LOG.info("{}/{}: buffer full, waiting for the sink ({} s so far)", platform, board,
                            waits * BUFFER_WRITE_TIMEOUT_MILLIS / 1000);
                }
            }
        }
    }

    private static void completeEmpty(final AcknowledgementSet acknowledgementSet) {
        if (acknowledgementSet != null) {
            acknowledgementSet.complete();
        }
    }

    static Outcome classify(final BoardFetchException e) {
        if (e.getKind() == BoardFetchException.Kind.ROBOTS && !e.isRetryable()) {
            return Outcome.BLOCKED;
        }
        return e.isBoardGone() ? Outcome.GONE : Outcome.FAILED;
    }

    private void count(final Outcome outcome) {
        switch (outcome) {
            case COMPLETE:
                boardsComplete.increment();
                break;
            case PARTIAL:
                boardsIncomplete.increment();
                break;
            case GONE:
                boardsGone.increment();
                break;
            case BLOCKED:
                boardsBlocked.increment();
                break;
            default:
                boardsFailed.increment();
        }
    }

    /**
     * A platform with a spec file runs on the spec engine (class A); one without names a coded
     * adapter (class C). Either way a bad platform fails the pipeline at start-up.
     */
    static PlatformAdapter resolveAdapter(final PlatformConfig platform, final AdapterRegistry registry) {
        if (platform.getSpec() != null) {
            final PlatformSpec spec = SpecLoader.load(Path.of(platform.getSpec()));
            LOG.info("Platform {}: spec {} ({} format, {} pagination)", platform.getName(), platform.getSpec(),
                    spec.getFormat(), spec.getPagination().getStyle());
            return new SpecAdapter(spec);
        }
        return registry.get(platform.getAdapter()).orElseThrow(() -> new IllegalArgumentException(
                "Platform " + platform.getName() + " has no spec and no adapter named '" + platform.getAdapter() + "'"));
    }

    /** Without a database: identity held in memory (tests, single-pod runs). */
    static List<Record<Event>> toRecords(final BoardSnapshot snapshot, final Instant crawlStarted) {
        return toRecords(snapshot, crawlStarted, new IdentityResolver(new MemoryIdentityStore()));
    }

    static List<Record<Event>> toRecords(final BoardSnapshot snapshot, final Instant crawlStarted, final IdentityResolver identity) {
        final List<JobRecord> jobs = snapshot.getJobs();
        final boolean useRequisitionIds = JobIdentity.boardHasUsableRequisitionIds(jobs);
        final Map<String, String> keys = jobs.isEmpty() ? Map.of()
                : identity.resolve(jobs.get(0).getPlatform(), jobs.get(0).getBoardId(), jobs);
        final List<Record<Event>> records = new ArrayList<>(jobs.size());
        for (final JobRecord job : jobs) {
            final String posting = IdentityResolver.postingId(job);
            final String key = posting != null && keys.containsKey(posting) ? keys.get(posting)
                    : JobIdentity.jobKey(job, useRequisitionIds);
            final Map<String, Object> doc = job.toDocument(key, JobIdentity.contentHash(job), crawlStarted);
            records.add(new Record<>(JacksonEvent.builder()
                    .withEventType(EventType.DOCUMENT.toString())
                    .withData(doc)
                    .build()));
        }
        return records;
    }
}
