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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Runs db/V2__board_state.sql and the store against a real Postgres, in a throwaway schema.
 * Enabled with -Djbi.test.jdbc.url=jdbc:postgresql://localhost:55432/jbi (user/password jbi).
 */
@EnabledIf("enabled")
class JdbcBoardStateStoreIT {
    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final Instant T0 = Instant.parse("2026-11-13T00:00:00Z");
    private static HikariDataSource ds;
    private static JdbcBoardStateStore store;

    static boolean enabled() {
        return !System.getProperty("jbi.test.jdbc.url", "").isEmpty();
    }

    @BeforeAll
    static void setUp() throws Exception {
        final String url = System.getProperty("jbi.test.jdbc.url");
        try (HikariDataSource admin = pool(url)) {
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE SCHEMA " + SCHEMA);
            }
        }
        ds = pool(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        final String ddl = Files.readString(Path.of(System.getProperty("jbi.db.dir", "../db"), "V2__board_state.sql"));
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(ddl);
            s.execute(ddl); // idempotent
            s.execute(Files.readString(Path.of(System.getProperty("jbi.db.dir", "../db"), "V3__identity_and_closure.sql")));
        }
        store = new JdbcBoardStateStore(ds, 3);
    }

    private static HikariDataSource pool(final String url) {
        final HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(System.getProperty("jbi.test.jdbc.user", "jbi"));
        hc.setPassword(System.getProperty("jbi.test.jdbc.password", "jbi"));
        hc.setMaximumPoolSize(8);
        return new HikariDataSource(hc);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (ds != null) {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
            ds.close();
        }
    }

    private static Instant t(final int hours) {
        return T0.plus(Duration.ofHours(hours));
    }

    @Test
    void failuresNeverTouchHistory_andCountAStreak() {
        store.recordAttempt("gh", "f", t(0), t(0), Outcome.FAILED, null, "HTTP 503");
        store.recordAttempt("gh", "f", t(6), t(6), Outcome.PARTIAL, 3, "parsed 3 of 5");
        store.recordAttempt("gh", "f", t(12), t(12), Outcome.GONE, null, "HTTP 404");
        final BoardState s = store.get("gh", "f").orElseThrow();
        assertThat(s.getSnapshotHistory(), equalTo(List.of()));
        assertThat(s.getConsecutiveFailures(), equalTo(3));
        assertThat(s.getFirstFailureAt(), equalTo(t(0)));
        assertThat(s.getLastOutcome(), equalTo("GONE"));
        assertThat(s.getLastJobCount(), equalTo(3));
        assertThat(s.getLastConfirmed(), nullValue());
    }

    @Test
    void commits_areNewestFirst_deduplicated_capped_andResetTheStreak() {
        store.recordAttempt("gh", "c", t(0), t(0), Outcome.FAILED, null, "x");
        store.commitSnapshot("gh", "c", t(6), t(6), 10);
        store.commitSnapshot("gh", "c", t(18), t(18), 12);
        store.commitSnapshot("gh", "c", t(12), t(19), 11);   // a late acknowledgement from an older crawl
        store.commitSnapshot("gh", "c", t(18), t(20), 12);   // a repeated crawl
        store.commitSnapshot("gh", "c", t(24), t(24), 13);
        final BoardState s = store.get("gh", "c").orElseThrow();
        assertThat(s.getSnapshotHistory(), equalTo(List.of(t(24), t(18), t(12))));
        assertThat(s.getConsecutiveFailures(), equalTo(0));
        assertThat(s.getFirstFailureAt(), nullValue());
        assertThat(s.getLastConfirmed(), equalTo(t(24)));
        assertThat(s.getLastCrawlStarted(), equalTo(t(24)));
    }

    @Test
    void superseded_onlyByANewerCrawl() {
        store.recordAttempt("gh", "s", t(6), t(6), Outcome.COMPLETE, 1, null);
        assertThat(store.isSuperseded("gh", "s", t(0)), equalTo(true));
        assertThat(store.isSuperseded("gh", "s", t(6)), equalTo(false));
        assertThat(store.isSuperseded("gh", "s", t(12)), equalTo(false));
        assertThat(store.isSuperseded("gh", "unknown", t(0)), equalTo(false));
        // An older crawl's attempt never moves last_crawl_started backwards.
        store.recordAttempt("gh", "s", t(0), t(7), Outcome.FAILED, null, "late");
        assertThat(store.get("gh", "s").orElseThrow().getLastCrawlStarted(), equalTo(t(6)));
    }

    @Test
    void concurrentWritersFromManyPods_loseNothing() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        final List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                if (n % 2 == 0) {
                    store.recordAttempt("gh", "race", t(n), t(n), Outcome.FAILED, null, "e" + n);
                } else {
                    store.recordAttempt("gh", "race", t(n), t(n), Outcome.PARTIAL, 1, "p" + n);
                }
            }));
        }
        for (final Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        final BoardState s = store.get("gh", "race").orElseThrow();
        assertThat(s.getConsecutiveFailures(), equalTo(40));
        assertThat(s.getLastCrawlStarted(), equalTo(t(39)));
    }
    @Test
    void storesForTheSameDatabaseShareOnePool() {
        final String url = System.getProperty("jbi.test.jdbc.url") + (System.getProperty("jbi.test.jdbc.url").contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA;
        final String user = System.getProperty("jbi.test.jdbc.user", "jbi");
        final String password = System.getProperty("jbi.test.jdbc.password", "jbi");
        final int before = JdbcBoardStateStore.openPools();
        final JdbcBoardStateStore a = new JdbcBoardStateStore(url, user, password, 4, 3);
        final JdbcBoardStateStore b = new JdbcBoardStateStore(url, user, password, 4, 3);
        assertThat(JdbcBoardStateStore.openPools(), equalTo(before + 1));
        a.close();
        a.close(); // a second close must not release b's reference
        b.recordAttempt("gh", "shared", t(0), t(0), Outcome.FAILED, null, "HTTP 503");
        assertThat(b.get("gh", "shared").orElseThrow().getConsecutiveFailures(), equalTo(1));
        b.close();
        assertThat(JdbcBoardStateStore.openPools(), equalTo(before));
    }
}
