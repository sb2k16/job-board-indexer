/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.closure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sb2k16.jobboard.ClosureConfig;
import io.github.sb2k16.jobboard.state.BoardState;
import io.github.sb2k16.jobboard.state.BoardStateStore;
import io.github.sb2k16.jobboard.state.ClosureState;
import io.github.sb2k16.jobboard.state.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

class ClosureSweeperTest {
    private static final Instant T0 = Instant.parse("2026-11-13T00:00:00Z");

    /** Snapshot history newest first, a job count, closure state; records what the sweeper writes. */
    static class Store implements BoardStateStore {
        List<Instant> history = new ArrayList<>();
        Integer jobCount = 100;
        ClosureState closure = ClosureState.NONE;
        final List<String> writes = new ArrayList<>();

        @Override
        public void recordAttempt(final String p, final String b, final Instant c, final Instant a, final Outcome o, final Integer n,
                                  final String e) {
        }

        @Override
        public void commitSnapshot(final String p, final String b, final Instant c, final Instant a, final int n) {
        }

        @Override
        public boolean isSuperseded(final String p, final String b, final Instant c) {
            return false;
        }

        @Override
        public Optional<BoardState> get(final String p, final String b) {
            return Optional.of(new BoardState(p, b, "active", null, null, "COMPLETE", null, null, history, 0, null, jobCount));
        }

        @Override
        public ClosureState closureState(final String p, final String b) {
            return closure;
        }

        @Override
        public void recordClosure(final String p, final String b, final Instant at, final int closed) {
            writes.add("closed " + closed);
        }

        @Override
        public void pauseClosure(final String p, final String b, final Instant at, final String reason) {
            writes.add("paused " + reason);
        }

        @Override
        public void retire(final String p, final String b, final Instant at) {
            writes.add("retired");
        }
    }

    static class Index implements IndexClient {
        long candidates;
        final List<Map<String, Object>> updates = new ArrayList<>();
        final List<Map<String, Object>> counted = new ArrayList<>();

        @Override
        public void refresh() {
        }

        @Override
        public long count(final Map<String, Object> query) {
            counted.add(query);
            return candidates;
        }

        @Override
        public long updateByQuery(final Map<String, Object> query, final String script, final Map<String, Object> params) {
            updates.add(params);
            return candidates;
        }
    }

    private final Store store = new Store();
    private final Index index = new Index();

    private ClosureSweeper sweeper(final String yaml) throws Exception {
        final ClosureConfig c = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper()
                .findAndRegisterModules().readValue("opensearch_url: http://x\n" + yaml, ClosureConfig.class);
        return new ClosureSweeper(c, index, store);
    }

    private void history(final int n) {
        for (int i = 0; i < n; i++) {
            store.history.add(T0.minus(Duration.ofHours(i))); // newest first
        }
    }

    @Test
    void nothingClosesBeforeNCompleteSnapshots() throws Exception {
        history(2);
        index.candidates = 5;
        assertThat(sweeper("snapshots: 3").afterSnapshot("lever", "a", T0), equalTo(ClosureSweeper.Result.NOT_ENOUGH_SNAPSHOTS));
        assertThat(index.updates, hasSize(0));
    }

    @Test
    void cutoffIsTheOldestOfTheLastNSnapshots() throws Exception {
        history(5);
        index.candidates = 3;
        assertThat(sweeper("snapshots: 3").afterSnapshot("lever", "a", T0), equalTo(ClosureSweeper.Result.CLOSED));
        assertThat(index.updates.get(0).get("cutoff"), equalTo(T0.minus(Duration.ofHours(2)).toString()));
        assertThat(new ObjectMapper().writeValueAsString(index.counted.get(0)),
                containsString("\"lt\":\"" + T0.minus(Duration.ofHours(2)) + "\""));
        assertThat(store.writes, equalTo(List.of("closed 3")));
    }

    @Test
    void massClosureIsHeld() throws Exception {
        history(3);
        store.jobCount = 20;      // the board now lists 20; 60 more would close: 75% of 80
        index.candidates = 60;
        assertThat(sweeper("snapshots: 3").afterSnapshot("lever", "a", T0), equalTo(ClosureSweeper.Result.PAUSED));
        assertThat(index.updates, hasSize(0));
        assertThat(store.writes.get(0), containsString("would close 60 of 80"));
    }

    @Test
    void smallBoardsAreNotHeldBelowTheMinimum() throws Exception {
        history(3);
        store.jobCount = 1;
        index.candidates = 4;     // 80%, but only 4 jobs
        assertThat(sweeper("snapshots: 3").afterSnapshot("lever", "a", T0), equalTo(ClosureSweeper.Result.CLOSED));
    }

    @Test
    void anOperatorOverrideReleasesTheGuard() throws Exception {
        history(3);
        store.jobCount = 0;
        index.candidates = 50;
        store.closure = new ClosureState(0, T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(1)), null);
        assertThat(sweeper("snapshots: 3").afterSnapshot("lever", "a", T0), equalTo(ClosureSweeper.Result.CLOSED));
    }

    @Test
    void aBoardRetiresAfterMConfirmedGoneCrawls() throws Exception {
        final ClosureSweeper s = sweeper("retire_after: 3");
        store.closure = new ClosureState(2, null, null, null);
        assertThat(s.afterGone("lever", "a", T0), equalTo(ClosureSweeper.Result.NOT_YET_RETIRED));
        store.closure = new ClosureState(3, null, null, null);
        index.candidates = 7;
        assertThat(s.afterGone("lever", "a", T0), equalTo(ClosureSweeper.Result.RETIRED));
        assertThat(index.updates.get(0).get("cutoff"), nullValue());
        assertThat(store.writes, equalTo(List.of("retired", "closed 7")));
    }

    @Test
    void theScriptShipsInTheJar() {
        assertThat(ClosureSweeper.SCRIPT, containsString("ctx.op = 'noop'"));
    }
}
