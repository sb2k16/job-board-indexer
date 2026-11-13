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
import java.util.List;

/** One row of {@code board_state}. */
public class BoardState {
    private final String platform;
    private final String boardId;
    private final String status;
    private final Instant lastAttempt;
    private final Instant lastCrawlStarted;
    private final String lastOutcome;
    private final String lastError;
    private final Instant lastConfirmed;
    private final List<Instant> snapshotHistory;
    private final int consecutiveFailures;
    private final Instant firstFailureAt;
    private final Integer lastJobCount;

    public BoardState(final String platform, final String boardId, final String status, final Instant lastAttempt,
                      final Instant lastCrawlStarted, final String lastOutcome, final String lastError, final Instant lastConfirmed,
                      final List<Instant> snapshotHistory, final int consecutiveFailures, final Instant firstFailureAt,
                      final Integer lastJobCount) {
        this.platform = platform;
        this.boardId = boardId;
        this.status = status;
        this.lastAttempt = lastAttempt;
        this.lastCrawlStarted = lastCrawlStarted;
        this.lastOutcome = lastOutcome;
        this.lastError = lastError;
        this.lastConfirmed = lastConfirmed;
        this.snapshotHistory = snapshotHistory;
        this.consecutiveFailures = consecutiveFailures;
        this.firstFailureAt = firstFailureAt;
        this.lastJobCount = lastJobCount;
    }

    public String getPlatform() {
        return platform;
    }

    public String getBoardId() {
        return boardId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getLastAttempt() {
        return lastAttempt;
    }

    public Instant getLastCrawlStarted() {
        return lastCrawlStarted;
    }

    public String getLastOutcome() {
        return lastOutcome;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getLastConfirmed() {
        return lastConfirmed;
    }

    /** Crawl-start times of the most recent complete snapshots, newest first. */
    public List<Instant> getSnapshotHistory() {
        return snapshotHistory;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getFirstFailureAt() {
        return firstFailureAt;
    }

    public Integer getLastJobCount() {
        return lastJobCount;
    }
}
