/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.state;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code board_state} in Postgres (Aurora in production), created by db/V2__board_state.sql.
 * Each write is one atomic upsert, so concurrent workers on different pods cannot lose updates.
 */
public class JdbcBoardStateStore implements BoardStateStore {

    private static final String RETRY_COUNT = "SELECT retry_count FROM board_state WHERE platform = ? AND board_id = ?";
    private static final String SCHEDULE_RETRY = "UPDATE board_state SET retry_at = ?, retry_count = retry_count + 1, "
            + "updated_at = now() WHERE platform = ? AND board_id = ?";
    private static final String CLEAR_RETRY = "UPDATE board_state SET retry_at = NULL, retry_count = 0, updated_at = now() "
            + "WHERE platform = ? AND board_id = ? AND (retry_at IS NOT NULL OR retry_count <> 0)";
    private static final String CLAIM_RETRIES = "UPDATE board_state SET retry_at = NULL, updated_at = now() "
            + "WHERE platform = ? AND retry_at <= ? RETURNING board_id";
    private static final String CLEAR_RETRIES = "UPDATE board_state SET retry_at = NULL, updated_at = now() "
            + "WHERE platform = ? AND retry_at IS NOT NULL";
    private static final String RECORD_ATTEMPT =
            "INSERT INTO board_state (platform, board_id, last_attempt, last_crawl_started, last_outcome, last_error,"
            + " consecutive_failures, first_failure_at, last_job_count, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN 0 ELSE 1 END, CASE WHEN ? THEN NULL ELSE ?::timestamptz END, ?, now())"
            + " ON CONFLICT (platform, board_id) DO UPDATE SET"
            + "  last_attempt = EXCLUDED.last_attempt,"
            + "  last_crawl_started = GREATEST(board_state.last_crawl_started, EXCLUDED.last_crawl_started),"
            + "  last_outcome = EXCLUDED.last_outcome,"
            + "  last_error = EXCLUDED.last_error,"
            + "  consecutive_failures = CASE WHEN ? THEN board_state.consecutive_failures ELSE board_state.consecutive_failures + 1 END,"
            + "  first_failure_at = CASE WHEN ? THEN board_state.first_failure_at"
            + "                          ELSE COALESCE(board_state.first_failure_at, EXCLUDED.last_attempt) END,"
            + "  last_job_count = COALESCE(EXCLUDED.last_job_count, board_state.last_job_count),"
            + "  updated_at = now()";

    private static final String COMMIT_SNAPSHOT =
            "INSERT INTO board_state (platform, board_id, last_attempt, last_crawl_started, last_outcome, last_error,"
            + " last_confirmed, snapshot_history, consecutive_failures, first_failure_at, last_job_count, updated_at)"
            + " VALUES (?, ?, ?, ?, 'COMPLETE', NULL, ?, ARRAY[?::timestamptz], 0, NULL, ?, now())"
            + " ON CONFLICT (platform, board_id) DO UPDATE SET"
            + "  snapshot_history = (SELECT array_agg(t ORDER BY t DESC) FROM ("
            + "      SELECT DISTINCT t FROM unnest(board_state.snapshot_history || EXCLUDED.snapshot_history) AS u(t)"
            + "      ORDER BY t DESC LIMIT ?) s),"
            + "  last_crawl_started = GREATEST(board_state.last_crawl_started, EXCLUDED.last_crawl_started),"
            + "  last_confirmed = GREATEST(board_state.last_confirmed, EXCLUDED.last_confirmed),"
            + "  last_outcome = 'COMPLETE',"
            + "  last_error = NULL,"
            + "  consecutive_failures = 0,"
            + "  first_failure_at = NULL,"
            + "  last_job_count = EXCLUDED.last_job_count,"
            + "  updated_at = now()";

    /** Retirement streak: a confirmed-gone crawl extends it; anything else ends it. A crawl that read the board un-retires it. */
    private static final String GONE_STREAK =
            "UPDATE board_state SET gone_streak = CASE WHEN ? THEN gone_streak + 1 ELSE 0 END,"
            + " retired_at = CASE WHEN ? THEN NULL ELSE retired_at END,"
            + " status = CASE WHEN ? THEN 'active' ELSE status END"
            + " WHERE platform = ? AND board_id = ?";

    private static final String CLOSURE_STATE =
            "SELECT gone_streak, closure_paused_at, closure_override_until, retired_at FROM board_state WHERE platform = ? AND board_id = ?";

    private static final String RECORD_CLOSURE =
            "UPDATE board_state SET last_closure_at = ?, last_closed_count = ?, closure_paused_at = NULL,"
            + " closure_paused_reason = NULL, updated_at = now() WHERE platform = ? AND board_id = ?";

    private static final String PAUSE_CLOSURE =
            "UPDATE board_state SET closure_paused_at = COALESCE(closure_paused_at, ?), closure_paused_reason = ?,"
            + " updated_at = now() WHERE platform = ? AND board_id = ?";

    private static final String RETIRE =
            "UPDATE board_state SET status = 'retired', retired_at = COALESCE(retired_at, ?), updated_at = now()"
            + " WHERE platform = ? AND board_id = ?";

    private static final String SUPERSEDED =
            "SELECT 1 FROM board_state WHERE platform = ? AND board_id = ? AND last_crawl_started > ?";

    private static final String GET =
            "SELECT platform, board_id, status, last_attempt, last_crawl_started, last_outcome, last_error, last_confirmed,"
            + " snapshot_history, consecutive_failures, first_failure_at, last_job_count"
            + " FROM board_state WHERE platform = ? AND board_id = ?";

    /**
     * One pool per database per process. Every pipeline builds its own source and therefore its own
     * store; with ten group pipelines in a pod, private pools would mean ten pools per pod. Stores for
     * the same URL and user borrow one reference-counted pool, closed when the last store closes.
     */
    private static final Map<String, SharedPool> POOLS = new HashMap<>();

    private static final class SharedPool {
        final HikariDataSource dataSource;
        int users;

        SharedPool(final HikariDataSource dataSource) {
            this.dataSource = dataSource;
        }
    }

    private final DataSource dataSource;
    private final int historySize;
    private final String poolKey;
    private boolean closed;

    public JdbcBoardStateStore(final String url, final String username, final String password, final int maxPoolSize,
                               final int historySize) {
        this.poolKey = url + "|" + username;
        this.dataSource = acquire(poolKey, url, username, password, maxPoolSize);
        this.historySize = historySize;
    }

    JdbcBoardStateStore(final DataSource dataSource, final int historySize) {
        this.dataSource = dataSource;
        this.historySize = historySize;
        this.poolKey = null;
    }

    private static synchronized DataSource acquire(final String key, final String url, final String username,
                                                   final String password, final int maxPoolSize) {
        SharedPool pool = POOLS.get(key);
        if (pool == null) {
            final HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(url);
            hc.setUsername(username);
            hc.setPassword(password);
            hc.setMaximumPoolSize(maxPoolSize);
            hc.setPoolName("jbi-board-state");
            // Do not connect while the pipeline starts. Hikari's default fails the constructor when the
            // first connection is refused, which kills Data Prepper; on Nov 17 a deploy whose new tasks
            // met a full Postgres ("remaining connection slots are reserved") crash-looped that way.
            // Unset, the pool opens connections when first asked and keeps retrying in the background;
            // a store call made while Postgres refuses waits up to the connection timeout, then fails
            // that one call, which the crawler already treats as a failed attempt (the lease retries).
            hc.setInitializationFailTimeout(-1);
            pool = new SharedPool(new HikariDataSource(hc));
            POOLS.put(key, pool);
        }
        pool.users++;
        return pool.dataSource;
    }

    /** The pool this store uses; other tables in the same database (job identity) share it. */
    public DataSource dataSource() {
        return dataSource;
    }

    /** Number of distinct pools open in this process (tests). */
    static synchronized int openPools() {
        return POOLS.size();
    }

    @Override
    public void recordAttempt(final String platform, final String boardId, final Instant crawlStarted, final Instant attemptedAt,
                              final Outcome outcome, final Integer jobCount, final String error) {
        final boolean ok = outcome == Outcome.COMPLETE;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(RECORD_ATTEMPT)) {
            int i = 1;
            ps.setString(i++, platform);
            ps.setString(i++, boardId);
            ps.setTimestamp(i++, Timestamp.from(attemptedAt));
            ps.setTimestamp(i++, Timestamp.from(crawlStarted));
            ps.setString(i++, outcome.name());
            ps.setString(i++, truncate(error));
            ps.setBoolean(i++, ok);
            ps.setBoolean(i++, ok);
            ps.setTimestamp(i++, Timestamp.from(attemptedAt));
            if (jobCount == null) {
                ps.setNull(i++, java.sql.Types.INTEGER);
            } else {
                ps.setInt(i++, jobCount);
            }
            ps.setBoolean(i++, ok);
            ps.setBoolean(i, ok);
            ps.executeUpdate();
            try (PreparedStatement streak = c.prepareStatement(GONE_STREAK)) {
                final boolean read = outcome == Outcome.COMPLETE || outcome == Outcome.PARTIAL;
                streak.setBoolean(1, outcome == Outcome.GONE);
                streak.setBoolean(2, read);
                streak.setBoolean(3, read);
                streak.setString(4, platform);
                streak.setString(5, boardId);
                streak.executeUpdate();
            }
        } catch (final SQLException e) {
            throw new BoardStateException("board_state write failed for " + platform + "/" + boardId, e);
        }
    }

    @Override
    public int retryCount(final String platform, final String boardId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(RETRY_COUNT)) {
            ps.setString(1, platform);
            ps.setString(2, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (final SQLException e) {
            throw new BoardStateException("board_state read failed for " + platform + "/" + boardId, e);
        }
    }

    @Override
    public void scheduleRetry(final String platform, final String boardId, final Instant at) {
        update(SCHEDULE_RETRY, platform, boardId, ps -> {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setString(2, platform);
            ps.setString(3, boardId);
        });
    }

    @Override
    public void clearRetry(final String platform, final String boardId) {
        update(CLEAR_RETRY, platform, boardId, ps -> {
            ps.setString(1, platform);
            ps.setString(2, boardId);
        });
    }

    @Override
    public List<String> claimDueRetries(final String platform, final Instant now) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(CLAIM_RETRIES)) {
            ps.setString(1, platform);
            ps.setTimestamp(2, Timestamp.from(now));
            final List<String> boards = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boards.add(rs.getString(1));
                }
            }
            return boards;
        } catch (final SQLException e) {
            throw new BoardStateException("board_state retry claim failed for " + platform, e);
        }
    }

    @Override
    public void clearRetries(final String platform) {
        update(CLEAR_RETRIES, platform, "*", ps -> ps.setString(1, platform));
    }

    @Override
    public ClosureState closureState(final String platform, final String boardId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(CLOSURE_STATE)) {
            ps.setString(1, platform);
            ps.setString(2, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ClosureState.NONE;
                }
                return new ClosureState(rs.getInt(1), instant(rs, "closure_paused_at"), instant(rs, "closure_override_until"),
                        instant(rs, "retired_at"));
            }
        } catch (final SQLException e) {
            throw new BoardStateException("board_state read failed for " + platform + "/" + boardId, e);
        }
    }

    @Override
    public void recordClosure(final String platform, final String boardId, final Instant at, final int closed) {
        update(RECORD_CLOSURE, platform, boardId, ps -> {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setInt(2, closed);
            ps.setString(3, platform);
            ps.setString(4, boardId);
        });
    }

    @Override
    public void pauseClosure(final String platform, final String boardId, final Instant at, final String reason) {
        update(PAUSE_CLOSURE, platform, boardId, ps -> {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setString(2, reason.length() <= 500 ? reason : reason.substring(0, 500));
            ps.setString(3, platform);
            ps.setString(4, boardId);
        });
    }

    @Override
    public void retire(final String platform, final String boardId, final Instant at) {
        update(RETIRE, platform, boardId, ps -> {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setString(2, platform);
            ps.setString(3, boardId);
        });
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void update(final String sql, final String platform, final String boardId, final Binder binder) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new BoardStateException("board_state write failed for " + platform + "/" + boardId, e);
        }
    }

    @Override
    public void commitSnapshot(final String platform, final String boardId, final Instant crawlStarted, final Instant confirmedAt,
                               final int jobCount) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(COMMIT_SNAPSHOT)) {
            ps.setString(1, platform);
            ps.setString(2, boardId);
            ps.setTimestamp(3, Timestamp.from(confirmedAt));
            ps.setTimestamp(4, Timestamp.from(crawlStarted));
            ps.setTimestamp(5, Timestamp.from(confirmedAt));
            ps.setTimestamp(6, Timestamp.from(crawlStarted));
            ps.setInt(7, jobCount);
            ps.setInt(8, historySize);
            ps.executeUpdate();
        } catch (final SQLException e) {
            throw new BoardStateException("board_state snapshot commit failed for " + platform + "/" + boardId, e);
        }
    }

    @Override
    public boolean isSuperseded(final String platform, final String boardId, final Instant crawlStarted) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SUPERSEDED)) {
            ps.setString(1, platform);
            ps.setString(2, boardId);
            ps.setTimestamp(3, Timestamp.from(crawlStarted));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (final SQLException e) {
            throw new BoardStateException("board_state read failed for " + platform + "/" + boardId, e);
        }
    }

    @Override
    public Optional<BoardState> get(final String platform, final String boardId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(GET)) {
            ps.setString(1, platform);
            ps.setString(2, boardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                final Array history = rs.getArray("snapshot_history");
                final List<Instant> snapshots = new ArrayList<>();
                if (history != null) {
                    Arrays.stream((Object[]) history.getArray()).map(o -> ((Timestamp) o).toInstant()).forEach(snapshots::add);
                }
                snapshots.sort(Comparator.reverseOrder());
                final int count = rs.getInt("last_job_count");
                final Integer jobCount = rs.wasNull() ? null : count;
                return Optional.of(new BoardState(rs.getString("platform"), rs.getString("board_id"), rs.getString("status"),
                        instant(rs, "last_attempt"), instant(rs, "last_crawl_started"), rs.getString("last_outcome"),
                        rs.getString("last_error"), instant(rs, "last_confirmed"), snapshots,
                        rs.getInt("consecutive_failures"), instant(rs, "first_failure_at"), jobCount));
            }
        } catch (final SQLException e) {
            throw new BoardStateException("board_state read failed for " + platform + "/" + boardId, e);
        }
    }

    private static Instant instant(final ResultSet rs, final String column) throws SQLException {
        final Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static String truncate(final String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    @Override
    public void close() {
        if (poolKey == null) {
            return;
        }
        synchronized (JdbcBoardStateStore.class) {
            if (closed) {
                return;
            }
            closed = true;
            final SharedPool pool = POOLS.get(poolKey);
            if (pool != null && --pool.users == 0) {
                POOLS.remove(poolKey);
                pool.dataSource.close();
            }
        }
    }
}
