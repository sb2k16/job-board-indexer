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

    @BeforeEach
    void setUp() throws Exception {
        metrics = mock(PluginMetrics.class);
        when(metrics.counter(anyString())).thenReturn(mock(Counter.class));
        adapter = mock(PlatformAdapter.class);
        when(adapter.name()).thenReturn("fake");
        coordinator = mock(EnhancedSourceCoordinator.class);
        config = new ObjectMapper().findAndRegisterModules().readValue(
                "{\"refresh_interval\":\"PT6H\",\"platforms\":[{\"name\":\"fake\",\"tenants\":"
                        + "[{\"board\":\"a\",\"company\":\"A\"},{\"board\":\"b\",\"company\":\"B\"}]}]}",
                JobBoardSourceConfig.class);
    }

    private JobBoardCrawler crawler() {
        return new JobBoardCrawler(config, metrics, new AdapterRegistry(List.of(adapter)), mock(HttpFetcher.class), new TenantLoader());
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
                () -> new JobBoardCrawler(config, metrics, empty, mock(HttpFetcher.class), new TenantLoader()));
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

    @Test
    void executePartition_fetchFailure_throwsAndWritesNothing() throws Exception {
        when(adapter.fetchBoard(any(), any(Tenant.class), any())).thenThrow(new BoardFetchException("HTTP 503", true));
        @SuppressWarnings("unchecked") final Buffer<Record<Event>> buffer = mock(Buffer.class);
        final AcknowledgementSet ack = mock(AcknowledgementSet.class);
        final SaaSCrawlerException e = assertThrows(SaaSCrawlerException.class, () -> crawler().executePartition(work(), buffer, ack));
        assertThat(e.isRetryable(), equalTo(true));
        verify(buffer, never()).writeAll(any(), anyInt());
        verify(ack, never()).complete();
    }

    @Test
    void toRecords_incompleteSnapshot_stillEmitsJobs() {
        final BoardSnapshot partial = new BoardSnapshot(List.of(new JobRecord().setTitle("T").setCompany("A").setUrl("u")), 5, false);
        final List<Record<Event>> records = JobBoardCrawler.toRecords(partial, Instant.parse("2026-11-13T00:00:00Z"));
        assertThat(records.size(), equalTo(1));
        assertThat(new ArrayList<>(records.get(0).getData().toMap().keySet()), hasItem("content_hash"));
    }
}
