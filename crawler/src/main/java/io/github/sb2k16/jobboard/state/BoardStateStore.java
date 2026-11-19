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

    /** Closure bookkeeping for a board (T2.5). */
    default ClosureState closureState(final String platform, final String boardId) {
        return ClosureState.NONE;
    }

    /** A closure run finished: how many jobs it closed links of. Clears a guard pause. */
    default void recordClosure(final String platform, final String boardId, final Instant at, final int closed) {
    }

    /** The mass-closure guard held this board's closures. */
    default void pauseClosure(final String platform, final String boardId, final Instant at, final String reason) {
    }

    /** The board is confirmed gone: it is marked retired (until a crawl succeeds again). */
    default void retire(final String platform, final String boardId, final Instant at) {
    }

    /** Retries scheduled since the board's last success or non-retryable outcome. */
    default int retryCount(final String platform, final String boardId) {
        return 0;
    }

    /** Schedules a sooner crawl of a board whose crawl failed for a reason that may pass. */
    default void scheduleRetry(final String platform, final String boardId, final Instant at) {
    }

    /** Drops the board's pending retry and resets its retry count. */
    default void clearRetry(final String platform, final String boardId) {
    }

    /**
     * Atomically takes the platform's boards whose retry is due: their retry_at is cleared, so each
     * is returned once even with two leaders racing.
     */
    default java.util.List<String> claimDueRetries(final String platform, final Instant now) {
        return java.util.List.of();
    }

    /** A full refresh is starting: every pending retry of the platform is dropped (the refresh covers it). */
    default void clearRetries(final String platform) {
    }

    /** When the refresh group last started a full refresh; empty if it never has. */
    default Optional<Instant> groupRefreshedAt(final String group) {
        return Optional.empty();
    }

    /** The refresh group started a full refresh at {@code at}. */
    default void recordGroupRefresh(final String group, final Instant at) {
    }

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
