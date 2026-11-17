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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/** Same rules as the JDBC store, in memory, for unit tests. */
public class InMemoryBoardStateStore implements BoardStateStore {
    private final Map<String, Row> rows = new HashMap<>();
    private final int historySize;

    public InMemoryBoardStateStore(final int historySize) {
        this.historySize = historySize;
    }

    private static final class Row {
        Instant lastAttempt;
        Instant lastCrawlStarted;
        String lastOutcome;
        String lastError;
        Instant lastConfirmed;
        TreeSet<Instant> history = new TreeSet<>(Comparator.reverseOrder());
        int failures;
        Instant firstFailureAt;
        Integer lastJobCount;
        Instant retryAt;
        int retryCount;
    }

    private Row row(final String p, final String b) {
        return rows.computeIfAbsent(p + "|" + b, k -> new Row());
    }

    private static Instant max(final Instant a, final Instant b) {
        return a == null ? b : (b == null || a.isAfter(b) ? a : b);
    }

    @Override
    public synchronized void recordAttempt(final String p, final String b, final Instant crawlStarted, final Instant attemptedAt,
                                           final Outcome outcome, final Integer jobCount, final String error) {
        final Row r = row(p, b);
        r.lastAttempt = attemptedAt;
        r.lastCrawlStarted = max(r.lastCrawlStarted, crawlStarted);
        r.lastOutcome = outcome.name();
        r.lastError = error;
        if (outcome != Outcome.COMPLETE) {
            r.failures++;
            if (r.firstFailureAt == null) {
                r.firstFailureAt = attemptedAt;
            }
        }
        if (jobCount != null) {
            r.lastJobCount = jobCount;
        }
    }

    @Override
    public synchronized void commitSnapshot(final String p, final String b, final Instant crawlStarted, final Instant confirmedAt,
                                            final int jobCount) {
        final Row r = row(p, b);
        r.history.add(crawlStarted);
        while (r.history.size() > historySize) {
            r.history.pollLast();
        }
        r.lastCrawlStarted = max(r.lastCrawlStarted, crawlStarted);
        r.lastConfirmed = max(r.lastConfirmed, confirmedAt);
        r.lastAttempt = r.lastAttempt == null ? confirmedAt : r.lastAttempt;
        r.lastOutcome = Outcome.COMPLETE.name();
        r.lastError = null;
        r.failures = 0;
        r.firstFailureAt = null;
        r.lastJobCount = jobCount;
    }

    @Override
    public synchronized boolean isSuperseded(final String p, final String b, final Instant crawlStarted) {
        final Row r = rows.get(p + "|" + b);
        return r != null && r.lastCrawlStarted != null && r.lastCrawlStarted.isAfter(crawlStarted);
    }

    @Override
    public synchronized Optional<BoardState> get(final String p, final String b) {
        final Row r = rows.get(p + "|" + b);
        if (r == null) {
            return Optional.empty();
        }
        return Optional.of(new BoardState(p, b, "active", r.lastAttempt, r.lastCrawlStarted, r.lastOutcome, r.lastError,
                r.lastConfirmed, new ArrayList<>(r.history), r.failures, r.firstFailureAt, r.lastJobCount));
    }

    public synchronized List<Instant> history(final String p, final String b) {
        return get(p, b).map(BoardState::getSnapshotHistory).orElse(List.of());
    }

    @Override
    public synchronized int retryCount(final String p, final String b) {
        final Row r = rows.get(p + "|" + b);
        return r == null ? 0 : r.retryCount;
    }

    @Override
    public synchronized void scheduleRetry(final String p, final String b, final Instant at) {
        final Row r = row(p, b);
        r.retryAt = at;
        r.retryCount++;
    }

    @Override
    public synchronized void clearRetry(final String p, final String b) {
        final Row r = rows.get(p + "|" + b);
        if (r != null) {
            r.retryAt = null;
            r.retryCount = 0;
        }
    }

    @Override
    public synchronized List<String> claimDueRetries(final String p, final Instant now) {
        final List<String> due = new ArrayList<>();
        for (final Map.Entry<String, Row> e : rows.entrySet()) {
            final Row r = e.getValue();
            if (e.getKey().startsWith(p + "|") && r.retryAt != null && !r.retryAt.isAfter(now)) {
                due.add(e.getKey().substring(p.length() + 1));
                r.retryAt = null;
            }
        }
        java.util.Collections.sort(due);
        return due;
    }

    @Override
    public synchronized void clearRetries(final String p) {
        rows.forEach((k, r) -> {
            if (k.startsWith(p + "|")) {
                r.retryAt = null;
            }
        });
    }

    public synchronized Instant retryAt(final String p, final String b) {
        final Row r = rows.get(p + "|" + b);
        return r == null ? null : r.retryAt;
    }
}
