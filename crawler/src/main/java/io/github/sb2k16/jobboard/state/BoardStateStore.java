/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.state;

import java.time.Instant;
import java.util.Optional;

/**
 * The application's per-board record (design: {@code board_state}). The closure step (T2.5)
 * reads only {@code snapshot_history}; everything else is for operations and coverage.
 */
public interface BoardStateStore extends AutoCloseable {

    /**
     * Records that a crawl of the board was attempted and how it ended. Any outcome other than
     * COMPLETE counts as a consecutive failure. Never touches {@code snapshot_history}.
     */
    void recordAttempt(String platform, String boardId, Instant crawlStarted, Instant attemptedAt, Outcome outcome,
                       Integer jobCount, String error);

    /**
     * Adds a complete, sink-confirmed snapshot: its crawl-start time joins the history (deduplicated,
     * newest first, capped), and the failure streak resets. Idempotent for a repeated crawl.
     */
    void commitSnapshot(String platform, String boardId, Instant crawlStarted, Instant confirmedAt, int jobCount);

    /** True when a newer crawl of this board has already been attempted, so this one is stale. */
    boolean isSuperseded(String platform, String boardId, Instant crawlStarted);

    Optional<BoardState> get(String platform, String boardId);

    @Override
    default void close() {
    }

    /** Used when no {@code board_state} database is configured: nothing is recorded. */
    BoardStateStore NONE = new BoardStateStore() {
        @Override
        public void recordAttempt(final String p, final String b, final Instant c, final Instant a, final Outcome o,
                                  final Integer n, final String e) {
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
            return Optional.empty();
        }
    };
}
