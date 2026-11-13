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
import io.github.sb2k16.jobboard.adapter.PlatformAdapter;
import io.github.sb2k16.jobboard.spec.PlatformSpec;
import io.github.sb2k16.jobboard.spec.SpecAdapter;
import io.github.sb2k16.jobboard.spec.SpecLoader;
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.JobRecord;
import io.github.sb2k16.jobboard.model.Tenant;
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
 * Leader: once per refresh interval, creates one worker partition per board.
 * Worker: fetches one board through its platform adapter and writes one event per job.
 */
@Named
public class JobBoardCrawler implements Crawler<PaginationCrawlerWorkerProgressState> {
    static final String PLATFORM = "platform";
    static final String BOARD = "board";
    static final String COMPANY = "company";
    static final String CRAWL_STARTED = "crawl_started";
    static final int WRITE_BATCH = 100;
    private static final int BUFFER_WRITE_TIMEOUT_MILLIS = (int) Duration.ofSeconds(30).toMillis();
    private static final Logger LOG = LoggerFactory.getLogger(JobBoardCrawler.class);

    private final JobBoardSourceConfig config;
    private final Map<String, PlatformAdapter> adapters = new HashMap<>();
    private final HttpFetcher fetcher;
    private final TenantLoader tenantLoader;
    private final Counter boardsComplete;
    private final Counter boardsIncomplete;
    private final Counter boardsFailed;
    private final Counter jobsEmitted;

    @Inject
    public JobBoardCrawler(final JobBoardSourceConfig config, final PluginMetrics pluginMetrics) {
        this(config, pluginMetrics, AdapterRegistry.defaultRegistry(), new HttpFetcher(), new TenantLoader());
    }

    JobBoardCrawler(final JobBoardSourceConfig config, final PluginMetrics pluginMetrics, final AdapterRegistry registry,
                    final HttpFetcher fetcher, final TenantLoader tenantLoader) {
        this.config = config;
        this.fetcher = fetcher;
        this.tenantLoader = tenantLoader;
        this.boardsComplete = pluginMetrics.counter("boardsComplete");
        this.boardsIncomplete = pluginMetrics.counter("boardsIncomplete");
        this.boardsFailed = pluginMetrics.counter("boardsFailed");
        this.jobsEmitted = pluginMetrics.counter("jobsEmitted");
        for (final PlatformConfig platform : config.getPlatforms()) {
            adapters.put(platform.getName(), resolveAdapter(platform, registry));
        }
    }

    @Override
    public Instant crawl(final LeaderPartition leaderPartition, final EnhancedSourceCoordinator coordinator) {
        final LeaderProgressState state = leaderPartition.getProgressState().get();
        final Instant last = state.getLastPollTime();
        final Instant now = Instant.now();
        if (last != null && Duration.between(last, now).compareTo(config.getRefreshInterval()) < 0) {
            return last;
        }
        int created = 0;
        for (final PlatformConfig platform : config.getPlatforms()) {
            for (final Tenant tenant : tenantLoader.load(platform)) {
                final PaginationCrawlerWorkerProgressState work = new PaginationCrawlerWorkerProgressState();
                final Map<String, Object> keys = new HashMap<>();
                keys.put(PLATFORM, platform.getName());
                keys.put(BOARD, tenant.getBoard());
                keys.put(COMPANY, tenant.getCompany());
                keys.put(CRAWL_STARTED, now.toString());
                work.setKeyAttributes(keys);
                work.setItemIds(List.of(tenant.getBoard()));
                work.setExportStartTime(now);
                coordinator.createPartition(new SaasSourcePartition(work, platform.getName() + "|" + tenant.getBoard() + "|" + now));
                created++;
            }
        }
        state.setLastPollTime(now);
        coordinator.saveProgressStateForPartition(leaderPartition, DEFAULT_EXTEND_LEASE_MINUTES);
        LOG.info("Refresh {}: created {} board partitions", now, created);
        return now;
    }

    @Override
    public void executePartition(final PaginationCrawlerWorkerProgressState state, final Buffer<Record<Event>> buffer,
                                 final AcknowledgementSet acknowledgementSet) {
        final Map<String, Object> keys = state.getKeyAttributes();
        final String platformName = (String) keys.get(PLATFORM);
        final Tenant tenant = new Tenant((String) keys.get(BOARD), (String) keys.get(COMPANY));
        final Instant crawlStarted = Instant.parse((String) keys.get(CRAWL_STARTED));
        final PlatformConfig platform = config.getPlatforms().stream()
                .filter(p -> p.getName().equals(platformName))
                .findFirst()
                .orElseThrow(() -> new SaaSCrawlerException("Platform " + platformName + " is no longer configured", false));
        final PlatformAdapter adapter = adapters.get(platform.getName());

        final BoardSnapshot snapshot;
        try {
            snapshot = adapter.fetchBoard(platform, tenant, fetcher);
        } catch (final BoardFetchException e) {
            boardsFailed.increment();
            throw new SaaSCrawlerException(e.getMessage(), e, e.isRetryable());
        }
        if (snapshot.isComplete()) {
            boardsComplete.increment();
        } else {
            boardsIncomplete.increment();
            LOG.warn("{}/{}: parsed {} jobs but platform reports {}", platformName, tenant.getBoard(),
                    snapshot.getJobs().size(), snapshot.getReportedTotal());
        }

        final List<Record<Event>> records = toRecords(snapshot, crawlStarted);
        try {
            for (int i = 0; i < records.size(); i += WRITE_BATCH) {
                final List<Record<Event>> batch = records.subList(i, Math.min(records.size(), i + WRITE_BATCH));
                if (acknowledgementSet != null) {
                    batch.forEach(r -> acknowledgementSet.add(r.getData()));
                }
                buffer.writeAll(batch, BUFFER_WRITE_TIMEOUT_MILLIS);
            }
        } catch (final Exception e) {
            throw new SaaSCrawlerException("Buffer write failed for " + platformName + "/" + tenant.getBoard(), e, true);
        }
        if (acknowledgementSet != null) {
            acknowledgementSet.complete();
        }
        jobsEmitted.increment(records.size());
        LOG.info("{}/{}: emitted {} jobs (complete={})", platformName, tenant.getBoard(), records.size(), snapshot.isComplete());
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

    static List<Record<Event>> toRecords(final BoardSnapshot snapshot, final Instant crawlStarted) {
        final boolean useRequisitionIds = JobIdentity.boardHasUsableRequisitionIds(snapshot.getJobs());
        final List<Record<Event>> records = new ArrayList<>(snapshot.getJobs().size());
        for (final JobRecord job : snapshot.getJobs()) {
            final Map<String, Object> doc = job.toDocument(
                    JobIdentity.jobKey(job, useRequisitionIds), JobIdentity.contentHash(job), crawlStarted);
            records.add(new Record<>(JacksonEvent.builder()
                    .withEventType(EventType.DOCUMENT.toString())
                    .withData(doc)
                    .build()));
        }
        return records;
    }
}
