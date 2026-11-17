/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.acknowledgements.AcknowledgementSet;
import org.opensearch.dataprepper.model.buffer.Buffer;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.model.source.coordinator.enhanced.EnhancedSourceCoordinator;
import io.github.sb2k16.jobboard.adapter.AdapterRegistry;
import io.github.sb2k16.jobboard.adapter.BoardFetchException;
import io.github.sb2k16.jobboard.state.BoardState;
import io.github.sb2k16.jobboard.state.BoardStateException;
import io.github.sb2k16.jobboard.state.BoardStateStore;
import io.github.sb2k16.jobboard.state.InMemoryBoardStateStore;
import io.github.sb2k16.jobboard.state.Outcome;
import org.opensearch.dataprepper.model.acknowledgements.AcknowledgementSetManager;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import io.github.sb2k16.jobboard.adapter.HttpFetcher;
import io.github.sb2k16.jobboard.adapter.PlatformAdapter;
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.JobRecord;
import io.github.sb2k16.jobboard.model.Tenant;
import org.opensearch.dataprepper.plugins.source.source_crawler.coordination.partition.LeaderPartition;
import org.opensearch.dataprepper.plugins.source.source_crawler.coordination.partition.SaasSourcePartition;
import org.opensearch.dataprepper.plugins.source.source_crawler.coordination.state.PaginationCrawlerLeaderProgressState;
import org.opensearch.dataprepper.plugins.source.source_crawler.coordination.state.PaginationCrawlerWorkerProgressState;
import org.opensearch.dataprepper.plugins.source.source_crawler.exception.SaaSCrawlerException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobBoardCrawlerTest {

    private PluginMetrics metrics;
    private PlatformAdapter adapter;
    private JobBoardSourceConfig config;
    private EnhancedSourceCoordinator coordinator;
    private InMemoryBoardStateStore store;
    private static final Instant CRAWL = Instant.parse("2026-11-13T00:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        metrics = mock(PluginMetrics.class);
        when(metrics.counter(anyString())).thenReturn(mock(Counter.class));
        adapter = mock(PlatformAdapter.class);
        when(adapter.name()).thenReturn("fake");
        coordinator = mock(EnhancedSourceCoordinator.class);
        store = new InMemoryBoardStateStore(10);
        config = new ObjectMapper().findAndRegisterModules().readValue(
                "{\"refresh_interval\":\"PT6H\",\"platforms\":[{\"name\":\"fake\",\"tenants\":"
                        + "[{\"board\":\"a\",\"company\":\"A\"},{\"board\":\"b\",\"company\":\"B\"}]}]}",
                JobBoardSourceConfig.class);
    }

    private JobBoardCrawler crawler() {
        return new JobBoardCrawler(config, metrics, new AdapterRegistry(List.of(adapter)), mock(HttpFetcher.class), new TenantLoader(), store);
    }

    private LeaderPartition leader(final Instant lastPoll) {
        final LeaderPartition leader = mock(LeaderPartition.class);
        final PaginationCrawlerLeaderProgressState state = new PaginationCrawlerLeaderProgressState(lastPoll);
        when(leader.getProgressState()).thenReturn(Optional.of(state));
        return leader;
    }

    @Test
    void crawl_dueRefresh_createsOnePartitionPerBoard() {
        final LeaderPartition leader = leader(Instant.EPOCH);
        final Instant result = crawler().crawl(leader, coordinator);
        final ArgumentCaptor<SaasSourcePartition> captor = ArgumentCaptor.forClass(SaasSourcePartition.class);
        verify(coordinator, times(2)).createPartition(captor.capture());
        final PaginationCrawlerWorkerProgressState work =
                (PaginationCrawlerWorkerProgressState) captor.getAllValues().get(1).getProgressState().get();
        assertThat(work.getKeyAttributes().get("board"), equalTo("b"));
        assertThat(work.getKeyAttributes().get("company"), equalTo("B"));
        assertThat(captor.getAllValues().get(0).getPartitionKey(), equalTo("fake|a|" + result));
        verify(coordinator).saveProgressStateForPartition(eq(leader), any(Duration.class));
    }

    @Test
    void crawl_withinRefreshInterval_createsNothing() {
        final Instant recent = Instant.now().minus(Duration.ofMinutes(5));
        assertThat(crawler().crawl(leader(recent), coordinator), equalTo(recent));
        verify(coordinator, never()).createPartition(any());
    }

    @Test
    void constructor_unknownAdapter_fails() {
        final AdapterRegistry empty = new AdapterRegistry(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> new JobBoardCrawler(config, metrics, empty, mock(HttpFetcher.class), new TenantLoader(), store));
    }

    @Test
    void resolveAdapter_platformWithSpec_runsOnSpecEngine() {
        final String specs = System.getProperty("jbi.specs.dir", "../config/specs");
        final PlatformConfig lever = new PlatformConfig("lever", null, null, List.of()).setSpec(specs + "/lever.yaml");
        final PlatformAdapter resolved = JobBoardCrawler.resolveAdapter(lever, new AdapterRegistry(List.of()));
        assertThat(resolved instanceof io.github.sb2k16.jobboard.spec.SpecAdapter, equalTo(true));
        assertThat(resolved.name(), equalTo("lever"));
    }

    @Test
    void constructor_invalidSpec_failsAtStartup(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {
        final java.nio.file.Path bad = dir.resolve("bad.yaml");
        java.nio.file.Files.writeString(bad, "name: bad\nformat: json\nlist: { url: u, items: x }\nfields: { title: t }\n");
        final JobBoardSourceConfig withSpec = new ObjectMapper().findAndRegisterModules().readValue(
                "{\"platforms\":[{\"name\":\"bad\",\"spec\":\"" + bad + "\",\"tenants\":[{\"board\":\"a\"}]}]}",
                JobBoardSourceConfig.class);
        assertThrows(io.github.sb2k16.jobboard.spec.SpecLoader.InvalidSpecException.class,
                () -> new JobBoardCrawler(withSpec, metrics, new AdapterRegistry(List.of()), mock(HttpFetcher.class), new TenantLoader(), store));
    }

    private PaginationCrawlerWorkerProgressState work() {
        final PaginationCrawlerWorkerProgressState work = new PaginationCrawlerWorkerProgressState();
        work.setKeyAttributes(Map.of("platform", "fake", "board", "a", "company", "A", "crawl_started", "2026-11-13T00:00:00Z"));
        return work;
    }

    @SuppressWarnings("unchecked")
    @Test
    void executePartition_writesEveryJobInBatchesAndCompletesAck() throws Exception {
        final List<JobRecord> jobs = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            jobs.add(new JobRecord().setTitle("T" + i).setCompany("A").setLocation("Austin, TX").setUrl("u" + i)
                    .setPlatform("fake").setBoardId("a"));
        }
        when(adapter.fetchBoard(any(), any(Tenant.class), any())).thenReturn(new BoardSnapshot(jobs, 250, true));
        final Buffer<Record<Event>> buffer = mock(Buffer.class);
        final List<Record<Event>> written = new ArrayList<>();
        doAnswer(inv -> {
            written.addAll((Collection<Record<Event>>) inv.getArgument(0));
            return null;
        }).when(buffer).writeAll(any(), anyInt());
        final AcknowledgementSet ack = mock(AcknowledgementSet.class);

        crawler().executePartition(work(), buffer, ack);

        verify(buffer, times(3)).writeAll(any(), anyInt());
        assertThat(written.size(), equalTo(250));
        verify(ack, times(250)).add(any(Event.class));
        verify(ack).complete();
        final Event first = written.get(0).getData();
        assertThat(first.get("crawl_started", String.class), equalTo("2026-11-13T00:00:00Z"));
        assertThat(first.get("source_link/board_id", String.class), equalTo("a"));
        assertThat(first.get("source_link/state", String.class), equalTo("live"));
        final List<String> keys = new ArrayList<>();
        written.forEach(r -> keys.add(r.getData().get("job_key", String.class)));
        assertThat(keys.stream().distinct().count(), equalTo(250L));
    }

    @SuppressWarnings("unchecked")
    private static Buffer<Record<Event>> buffer() {
        return mock(Buffer.class);
    }

    private static List<JobRecord> jobs(final int n) {
        final List<JobRecord> jobs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            jobs.add(new JobRecord().setTitle("T" + i).setCompany("A").setLocation("Austin, TX").setUrl("u" + i)
                    .setPlatform("fake").setBoardId("a"));
        }
        return jobs;
    }

    @Test
    void executePartition_fetchFailures_areRecordedNotThrown_andNeverSnapshots() throws Exception {
        final Object[][] cases = {
                {BoardFetchException.http("u", 503), Outcome.FAILED},
                {BoardFetchException.http("u", 429), Outcome.FAILED},
                {new BoardFetchException("timed out", null, true, BoardFetchException.Kind.NETWORK, 0), Outcome.FAILED},
                {new BoardFetchException("board marker missing", false), Outcome.FAILED},
                {new BoardFetchException("circuit open", null, true, BoardFetchException.Kind.CIRCUIT_OPEN, 0), Outcome.FAILED},
                {BoardFetchException.http("u", 404), Outcome.GONE},
                {new BoardFetchException("robots", null, false, BoardFetchException.Kind.ROBOTS, 0), Outcome.BLOCKED},
        };
        int failures = 0;
        for (final Object[] c : cases) {
            org.mockito.Mockito.doThrow((BoardFetchException) c[0]).when(adapter).fetchBoard(any(), any(Tenant.class), any());
            final Buffer<Record<Event>> buffer = buffer();
            final AcknowledgementSet ack = mock(AcknowledgementSet.class);
            crawler().executePartition(work(), buffer, ack);
            verify(buffer, never()).writeAll(any(), anyInt());
            verify(ack).complete();                       // the partition is done; the next refresh retries the board
            final BoardState state = store.get("fake", "a").orElseThrow();
            assertThat(state.getLastOutcome(), equalTo(((Outcome) c[1]).name()));
            assertThat(state.getConsecutiveFailures(), equalTo(++failures));
            assertThat(state.getSnapshotHistory(), equalTo(List.of()));
        }
    }

    // ---- sooner retry ---------------------------------------------------------------------------

    private static PaginationCrawlerWorkerProgressState work(final Instant crawlStarted, final Instant refreshStarted) {
        final PaginationCrawlerWorkerProgressState work = new PaginationCrawlerWorkerProgressState();
        work.setKeyAttributes(Map.of("platform", "fake", "board", "a", "company", "A",
                "crawl_started", crawlStarted.toString(), "refresh_started", refreshStarted.toString()));
        return work;
    }

    @Test
    void retry_aFailureThatMayPass_isScheduled15Then30Then60MinutesLater() throws Exception {
        final Instant refresh = Instant.now();
        when(adapter.fetchBoard(any(), any(Tenant.class), any())).thenThrow(BoardFetchException.http("u", 429));
        final List<Duration> gaps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final Instant before = Instant.now();
            crawler().executePartition(work(refresh.plusSeconds(i), refresh), buffer(), mock(AcknowledgementSet.class));
            gaps.add(Duration.between(before, store.retryAt("fake", "a")));
        }
        assertThat(gaps.get(0).toMinutes(), equalTo(15L));
        assertThat(gaps.get(1).toMinutes(), equalTo(30L));
        assertThat(gaps.get(2).toMinutes(), equalTo(60L));
        assertThat(gaps.get(3).toMinutes(), equalTo(60L));                  // capped
        assertThat(store.retryCount("fake", "a"), equalTo(4));
    }

    @Test
    void retry_notForFailuresThatWillNotPass_norAfterTheNextRefresh() throws Exception {
        final Instant refresh = Instant.now();
        for (final BoardFetchException e : List.of(new BoardFetchException("board marker missing", false),
                BoardFetchException.http("u", 403), BoardFetchException.http("u", 404),
                new BoardFetchException("robots", null, false, BoardFetchException.Kind.ROBOTS, 0))) {
            org.mockito.Mockito.doThrow(e).when(adapter).fetchBoard(any(), any(Tenant.class), any());
            crawler().executePartition(work(refresh, refresh), buffer(), mock(AcknowledgementSet.class));
            assertThat(e.getMessage(), store.retryAt("fake", "a"), org.hamcrest.Matchers.nullValue());
        }
        // A 503 ten minutes before the next refresh (PT6H): the refresh retries it, so no retry.
        org.mockito.Mockito.doThrow(BoardFetchException.http("u", 503)).when(adapter).fetchBoard(any(), any(Tenant.class), any());
        final Instant oldRefresh = Instant.now().minus(Duration.ofHours(6)).plus(Duration.ofMinutes(10));
        crawler().executePartition(work(Instant.now(), oldRefresh), buffer(), mock(AcknowledgementSet.class));
        assertThat(store.retryAt("fake", "a"), org.hamcrest.Matchers.nullValue());
    }

    @Test
    void retry_aSuccessfulReadClearsThePendingRetryAndItsCount() throws Exception {
        final Instant refresh = Instant.now();
        when(adapter.fetchBoard(any(), any(Tenant.class), any())).thenThrow(BoardFetchException.http("u", 500));
        crawler().executePartition(work(refresh, refresh), buffer(), mock(AcknowledgementSet.class));
        assertThat(store.retryCount("fake", "a"), equalTo(1));
        org.mockito.Mockito.reset(adapter);
        when(adapter.name()).thenReturn("fake");
        when(adapter.fetchBoard(any(), any(Tenant.class), any())).thenReturn(new BoardSnapshot(jobs(2), 3, false));
        crawler().executePartition(work(refresh.plusSeconds(60), refresh), buffer(), mock(AcknowledgementSet.class));
        assertThat(store.retryAt("fake", "a"), org.hamcrest.Matchers.nullValue());
        assertThat(store.retryCount("fake", "a"), equalTo(0));
    }

    @Test
    void retry_theLeaderQueuesDueRetriesOnceBetweenRefreshes() {
        final Instant refresh = Instant.now().minus(Duration.ofMinutes(20));
        store.recordAttempt("fake", "a", refresh, refresh, Outcome.FAILED, null, "429");
        store.scheduleRetry("fake", "a", Instant.now().minus(Duration.ofMinutes(1)));     // due
        store.recordAttempt("fake", "b", refresh, refresh, Outcome.FAILED, null, "503");
        store.scheduleRetry("fake", "b", Instant.now().plus(Duration.ofMinutes(10)));     // not yet
        final JobBoardCrawler crawler = crawler();
        assertThat(crawler.crawl(leader(refresh), coordinator), equalTo(refresh));      // no refresh due
        final ArgumentCaptor<SaasSourcePartition> captor = ArgumentCaptor.forClass(SaasSourcePartition.class);
        verify(coordinator, times(1)).createPartition(captor.capture());
        final Map<String, Object> keys =
                ((PaginationCrawlerWorkerProgressState) captor.getValue().getProgressState().get()).getKeyAttributes();
        assertThat(keys.get("board"), equalTo("a"));
        assertThat(keys.get("company"), equalTo("A"));
        assertThat(keys.get("refresh_started"), equalTo(refresh.toString()));
        assertThat(captor.getValue().getPartitionKey().endsWith("|retry"), equalTo(true));
        crawler.crawl(leader(refresh), coordinator);                                     // claimed: not queued again
        verify(coordinator, times(1)).createPartition(any());
    }

    @Test
    void retry_aFullRefreshDropsPendingRetries() {
        store.recordAttempt("fake", "a", CRAWL, CRAWL, Outcome.FAILED, null, "429");
        store.scheduleRetry("fake", "a", Instant.now().plus(Duration.ofMinutes(10)));
        crawler().crawl(leader(Instant.EPOCH), coordinator);
        verify(coordinator, times(2)).createPartition(any());                            // the refresh: both boards
        assertThat(store.retryAt("fake", "a"), org.hamcrest.Matchers.nullValue());
    }

    @Test
    void executePartition_partialSnapshot_emitsJobsButNoSnapshot() throws Exception {
        when(adapter.fetchBoard(any(), any(Tenant.class), any())).thenReturn(new BoardSnapshot(jobs(3), 5, false));
        final Buffer<Record<Event>> buffer = buffer();
        final AcknowledgementSet ack = mock(AcknowledgementSet.class);
        crawler().executePartition(work(), buffer, ack);
        verify(ack, times(3)).add(any(Event.class));
        verify(ack).complete();
        assertThat(store.get("fake", "a").orElseThrow().getLastOutcome(), equalTo("PARTIAL"));
        assertThat(store.history("fake", "a"), equalTo(List.of()));
    }

    /** Captures the snapshot acknowledgement set and its callback. */
    private static final class Acks {
        final AcknowledgementSetManager manager = mock(AcknowledgementSetManager.class);
        final AcknowledgementSet snapshotAck = mock(AcknowledgementSet.class);
        final AtomicReference<Consumer<Boolean>> callback = new AtomicReference<>();

        Acks() {
            when(manager.create(any(), any(Duration.class))).thenAnswer(inv -> {
                callback.set(inv.getArgument(0));
                return snapshotAck;
            });
        }
    }

    @Test
    void executePartition_completeSnapshot_commitsOnlyAfterSinkConfirms() throws Exception {
        when(adapter.fetchBoard(any(), any(Tenant.class), any())).thenReturn(new BoardSnapshot(jobs(4), 4, true));
        final Acks acks = new Acks();
        final JobBoardCrawler crawler = crawler();
        crawler.setAcknowledgementSetManager(acks.manager);
        final AcknowledgementSet partitionAck = mock(AcknowledgementSet.class);

        crawler.executePartition(work(), buffer(), partitionAck);

        verify(acks.snapshotAck, times(4)).add(any(Event.class));   // events wait on our set,
        verify(acks.snapshotAck).complete();
        verify(partitionAck, never()).add(any(Event.class));        // not on the framework's
        verify(partitionAck, never()).complete();                   // so the partition stays open
        assertThat(store.history("fake", "a"), equalTo(List.of()));  // and nothing is committed yet

        acks.callback.get().accept(true);                           // the sink confirms every job
        assertThat(store.history("fake", "a"), equalTo(List.of(CRAWL)));
        assertThat(store.get("fake", "a").orElseThrow().getLastJobCount(), equalTo(4));
        verify(partitionAck).complete();
    }

    @Test
    void executePartition_sinkRejects_noSnapshot_partitionLeftToLease() throws Exception {
        when(adapter.fetchBoard(any(), any(Tenant.class), any())).thenReturn(new BoardSnapshot(jobs(2), 2, true));
        final Acks acks = new Acks();
        final JobBoardCrawler crawler = crawler();
        crawler.setAcknowledgementSetManager(acks.manager);
        final AcknowledgementSet partitionAck = mock(AcknowledgementSet.class);

        crawler.executePartition(work(), buffer(), partitionAck);
        acks.callback.get().accept(false);

        assertThat(store.history("fake", "a"), equalTo(List.of()));
        assertThat(store.get("fake", "a").orElseThrow().getLastOutcome(), equalTo("FAILED"));
        verify(partitionAck).cancel();
        verify(partitionAck, never()).complete();
    }

    @Test
    void executePartition_failureAfterSnapshots_keepsHistoryAndCountsStreak() throws Exception {
        store.commitSnapshot("fake", "a", CRAWL.minus(Duration.ofHours(12)), CRAWL, 4);
        store.commitSnapshot("fake", "a", CRAWL.minus(Duration.ofHours(6)), CRAWL, 4);
        when(adapter.fetchBoard(any(), any(Tenant.class), any())).thenThrow(BoardFetchException.http("u", 500));
        crawler().executePartition(work(), buffer(), mock(AcknowledgementSet.class));
        final BoardState state = store.get("fake", "a").orElseThrow();
        assertThat(state.getSnapshotHistory().size(), equalTo(2));
        assertThat(state.getConsecutiveFailures(), equalTo(1));
        assertThat(state.getLastConfirmed(), equalTo(CRAWL));
    }

    @Test
    void executePartition_supersededCrawl_isSkippedWithoutFetching() throws Exception {
        store.recordAttempt("fake", "a", CRAWL.plus(Duration.ofHours(6)), Instant.now(), Outcome.COMPLETE, 1, null);
        final AcknowledgementSet ack = mock(AcknowledgementSet.class);
        crawler().executePartition(work(), buffer(), ack);
        verify(adapter, never()).fetchBoard(any(), any(Tenant.class), any());
        verify(ack).complete();
    }

    @Test
    void executePartition_acknowledgmentsOff_commitsAfterBufferWrite() throws Exception {
        when(adapter.fetchBoard(any(), any(Tenant.class), any())).thenReturn(new BoardSnapshot(jobs(2), 2, true));
        crawler().executePartition(work(), buffer(), null);
        assertThat(store.history("fake", "a"), equalTo(List.of(CRAWL)));
    }

    @Test
    void executePartition_boardStateDown_isRetryable() throws Exception {
        final BoardStateStore down = mock(BoardStateStore.class);
        when(down.isSuperseded(any(), any(), any())).thenThrow(new BoardStateException("db down", null));
        final JobBoardCrawler crawler = new JobBoardCrawler(config, metrics, new AdapterRegistry(List.of(adapter)),
                mock(HttpFetcher.class), new TenantLoader(), down);
        final SaaSCrawlerException e = assertThrows(SaaSCrawlerException.class,
                () -> crawler.executePartition(work(), buffer(), mock(AcknowledgementSet.class)));
        assertThat(e.isRetryable(), equalTo(true));
    }

    @Test
    void toRecords_incompleteSnapshot_stillEmitsJobs() {
        final BoardSnapshot partial = new BoardSnapshot(List.of(new JobRecord().setTitle("T").setCompany("A").setUrl("u")), 5, false);
        final List<Record<Event>> records = JobBoardCrawler.toRecords(partial, Instant.parse("2026-11-13T00:00:00Z"));
        assertThat(records.size(), equalTo(1));
        assertThat(new ArrayList<>(records.get(0).getData().toMap().keySet()), hasItem("content_hash"));
    }
}
