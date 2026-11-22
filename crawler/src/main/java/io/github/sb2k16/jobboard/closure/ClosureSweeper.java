/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.closure;

import io.github.sb2k16.jobboard.ClosureConfig;
import io.github.sb2k16.jobboard.state.BoardState;
import io.github.sb2k16.jobboard.state.BoardStateStore;
import io.github.sb2k16.jobboard.state.ClosureState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Closes what a board no longer lists (T2.5). Runs for one board right after a complete snapshot
 * of it is confirmed by the sink, while the crawling pod still holds the board's lease, so two
 * pods never sweep the same board at once.
 *
 * <ul>
 *   <li>A link goes when the board's last N complete snapshots all missed it: its {@code last_seen}
 *       is older than the oldest of them. Failed and partial crawls never enter the snapshot
 *       history, so they can never close anything.</li>
 *   <li>A job closes when all its links are gone, and reopens when any source lists it again
 *       (config/upsert.painless).</li>
 *   <li>Mass-closure guard: a run that would close more than {@code mass_closure_ratio} of the board's
 *       jobs (and at least {@code mass_closure_min_jobs}) is held and the board is marked paused; it
 *       is more likely a platform change than a hiring freeze. An operator releases it by setting
 *       {@code closure_override_until}.</li>
 *   <li>Retirement: after {@code retire_after} consecutive crawls confirm the board is gone, every live
 *       link of the board goes, with no guard; the board is marked retired.</li>
 * </ul>
 */
public class ClosureSweeper {
    private static final Logger LOG = LoggerFactory.getLogger(ClosureSweeper.class);
    /** At most this many jobs close per sweep (one search); the rest close at the next snapshot. */
    static final int MAX_PER_SWEEP = 10_000;

    private final ClosureConfig config;
    private final IndexClient index;
    private final BoardStateStore store;

    public ClosureSweeper(final ClosureConfig config, final IndexClient index, final BoardStateStore store) {
        this.config = config;
        this.index = index;
        this.store = store;
    }

    public enum Result { NOT_ENOUGH_SNAPSHOTS, NOTHING_TO_CLOSE, CLOSED, PAUSED, RETIRED, NOT_YET_RETIRED }

    /** After a confirmed complete snapshot of the board. */
    public Result afterSnapshot(final String platform, final String board, final Instant now, final Commands out) {
        final BoardState state = store.get(platform, board).orElse(null);
        final List<Instant> history = state == null ? List.of() : state.getSnapshotHistory();
        if (history.size() < config.getSnapshots()) {
            return Result.NOT_ENOUGH_SNAPSHOTS;
        }
        final Instant cutoff = history.get(config.getSnapshots() - 1);
        final Map<String, Object> query = candidates(platform, board, cutoff);
        index.refresh();
        final long candidates = index.count(query);
        if (candidates == 0) {
            store.recordClosure(platform, board, now, 0);
            return Result.NOTHING_TO_CLOSE;
        }
        final int listed = state.getLastJobCount() == null ? 0 : state.getLastJobCount();
        final long before = listed + candidates;
        final ClosureState closure = store.closureState(platform, board);
        if (candidates >= config.getMassClosureMinJobs() && candidates > config.getMassClosureRatio() * before
                && !closure.isOverridden(now)) {
            final String reason = String.format("would close %d of %d jobs (over %.0f%%); held", candidates, before,
                    config.getMassClosureRatio() * 100);
            store.pauseClosure(platform, board, now, reason);
            LOG.warn("{}/{}: mass-closure guard: {}", platform, board, reason);
            return Result.PAUSED;
        }
        final List<String> keys = index.jobKeys(query, (int) Math.min(candidates, MAX_PER_SWEEP));
        out.write(Commands.close(keys, platform, board, cutoff, now));
        store.recordClosure(platform, board, now, keys.size());
        LOG.info("{}/{}: {} job(s) no longer listed in the last {} complete snapshots; closing their links", platform, board,
                keys.size(), config.getSnapshots());
        return Result.CLOSED;
    }

    /** After a crawl that confirmed the board is gone (404/410). */
    public Result afterGone(final String platform, final String board, final Instant now, final Commands out) {
        final ClosureState closure = store.closureState(platform, board);
        if (closure.getGoneStreak() < config.getRetireAfter()) {
            return Result.NOT_YET_RETIRED;
        }
        final Map<String, Object> query = candidates(platform, board, null);
        final List<String> keys = index.jobKeys(query, MAX_PER_SWEEP);
        out.write(Commands.close(keys, platform, board, null, now));
        store.retire(platform, board, now);
        store.recordClosure(platform, board, now, keys.size());
        LOG.warn("{}/{}: gone for {} consecutive crawls; board retired, closing {} job(s)", platform, board,
                closure.getGoneStreak(), keys.size());
        return Result.RETIRED;
    }

    /**
     * Documents with a live link of this board last seen before the cutoff. Links are plain objects,
     * not nested, so a document can match on fields of two different links; the script re-checks
     * each link (config/upsert.painless), so such a match is a no-op, and the guard's count errs high, never low.
     */
    static Map<String, Object> candidates(final String platform, final String board, final Instant cutoff) {
        final List<Object> filters = new java.util.ArrayList<>(List.of(
                Map.of("term", Map.of("source_links.platform", platform)),
                Map.of("term", Map.of("source_links.board_id", board)),
                Map.of("term", Map.of("source_links.state", "live"))));
        if (cutoff != null) {
            filters.add(Map.of("range", Map.of("source_links.last_seen", Map.of("lt", cutoff.toString()))));
        }
        return Map.of("bool", Map.of("filter", filters));
    }

}
