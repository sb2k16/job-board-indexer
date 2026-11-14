/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.identity;

import io.github.sb2k16.jobboard.state.BoardStateException;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** job_posting and job_alias in Postgres (db/V3). Shares the board_state pool. */
public class JdbcIdentityStore implements IdentityStore {
    private final DataSource dataSource;

    public JdbcIdentityStore(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Map<String, String> postings(final String platform, final String board, final Collection<String> ids) {
        final Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        final String sql = "SELECT posting_id, job_key FROM job_posting WHERE platform = ? AND board_id = ? AND posting_id = ANY(?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, platform);
            ps.setString(2, board);
            ps.setArray(3, c.createArrayOf("varchar", ids.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
            return out;
        } catch (final SQLException e) {
            throw new BoardStateException("job_posting read failed for " + platform + "/" + board, e);
        }
    }

    @Override
    public Map<String, String> aliases(final Collection<String> wanted) {
        final Map<String, String> out = new HashMap<>();
        if (wanted.isEmpty()) {
            return out;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT alias, job_key FROM job_alias WHERE alias = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("varchar", wanted.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
            return out;
        } catch (final SQLException e) {
            throw new BoardStateException("job_alias read failed", e);
        }
    }

    @Override
    public Map<String, Set<String>> postingsOnBoard(final String platform, final String board, final Collection<String> keys) {
        final Map<String, Set<String>> out = new HashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        final String sql = "SELECT job_key, posting_id FROM job_posting WHERE platform = ? AND board_id = ? AND job_key = ANY(?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, platform);
            ps.setString(2, board);
            ps.setArray(3, c.createArrayOf("varchar", keys.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
                }
            }
            return out;
        } catch (final SQLException e) {
            throw new BoardStateException("job_posting read failed for " + platform + "/" + board, e);
        }
    }

    @Override
    public Map<String, String> bind(final String platform, final String board, final Map<String, String> postingKeys,
                                    final Map<String, String> aliasKeys) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!aliasKeys.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO job_alias (alias, job_key) SELECT * FROM unnest(?::varchar[], ?::varchar[])"
                                    + " ON CONFLICT (alias) DO NOTHING")) {
                        ps.setArray(1, c.createArrayOf("varchar", aliasKeys.keySet().toArray()));
                        ps.setArray(2, c.createArrayOf("varchar", aliasKeys.values().toArray()));
                        ps.executeUpdate();
                    }
                }
                final Map<String, String> out = new HashMap<>();
                if (!postingKeys.isEmpty()) {
                    final List<String> ids = new ArrayList<>(postingKeys.keySet());
                    final List<String> keys = new ArrayList<>();
                    ids.forEach(id -> keys.add(postingKeys.get(id)));
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO job_posting (platform, board_id, posting_id, job_key)"
                                    + " SELECT ?, ?, p, k FROM unnest(?::varchar[], ?::varchar[]) AS u(p, k)"
                                    + " ON CONFLICT (platform, board_id, posting_id) DO NOTHING")) {
                        ps.setString(1, platform);
                        ps.setString(2, board);
                        ps.setArray(3, c.createArrayOf("varchar", ids.toArray()));
                        ps.setArray(4, c.createArrayOf("varchar", keys.toArray()));
                        ps.executeUpdate();
                    }
                    // Another pod may have bound a posting first: read back the winners.
                    out.putAll(postingsWith(c, platform, board, ids));
                }
                c.commit();
                return out;
            } catch (final SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (final SQLException e) {
            throw new BoardStateException("identity bind failed for " + platform + "/" + board, e);
        }
    }

    private static Map<String, String> postingsWith(final Connection c, final String platform, final String board,
                                                    final List<String> ids) throws SQLException {
        final Map<String, String> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT posting_id, job_key FROM job_posting WHERE platform = ? AND board_id = ? AND posting_id = ANY(?)")) {
            ps.setString(1, platform);
            ps.setString(2, board);
            final Array arr = c.createArrayOf("varchar", ids.toArray());
            ps.setArray(3, arr);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return out;
    }
}
