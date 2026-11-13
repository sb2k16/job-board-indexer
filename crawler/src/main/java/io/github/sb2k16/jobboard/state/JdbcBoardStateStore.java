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
import java.util.List;
import java.util.Optional;

/**
 * {@code board_state} in Postgres (Aurora in production), created by db/V2__board_state.sql.
 * Each write is one atomic upsert, so concurrent workers on different pods cannot lose updates.
 */
public class JdbcBoardStateStore implements BoardStateStore {

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

    private static final String SUPERSEDED =
            "SELECT 1 FROM board_state WHERE platform = ? AND board_id = ? AND last_crawl_started > ?";

    private static final String GET =
            "SELECT platform, board_id, status, last_attempt, last_crawl_started, last_outcome, last_error, last_confirmed,"
            + " snapshot_history, consecutive_failures, first_failure_at, last_job_count"
            + " FROM board_state WHERE platform = ? AND board_id = ?";

    private final DataSource dataSource;
    private final int historySize;
    private final HikariDataSource owned;

    public JdbcBoardStateStore(final String url, final String username, final String password, final int maxPoolSize,
                               final int historySize) {
        final HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(username);
        hc.setPassword(password);
        hc.setMaximumPoolSize(maxPoolSize);
        hc.setPoolName("jbi-board-state");
        this.owned = new HikariDataSource(hc);
        this.dataSource = owned;
        this.historySize = historySize;
    }

    JdbcBoardStateStore(final DataSource dataSource, final int historySize) {
        this.dataSource = dataSource;
        this.historySize = historySize;
        this.owned = null;
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
        if (owned != null) {
            owned.close();
        }
    }
}
