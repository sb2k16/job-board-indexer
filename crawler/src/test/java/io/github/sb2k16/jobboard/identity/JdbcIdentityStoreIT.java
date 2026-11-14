/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.identity;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.sb2k16.jobboard.model.JobRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/** Enabled with -Djbi.test.jdbc.url=jdbc:postgresql://localhost:55432/jbi (see JdbcBoardStateStoreIT). */
@EnabledIf("enabled")
class JdbcIdentityStoreIT {
    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static HikariDataSource ds;

    static boolean enabled() {
        return !System.getProperty("jbi.test.jdbc.url", "").isEmpty();
    }

    @BeforeAll
    static void setUp() throws Exception {
        final String url = System.getProperty("jbi.test.jdbc.url");
        try (HikariDataSource admin = pool(url); Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA);
        }
        ds = pool(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        final Path db = Path.of(System.getProperty("jbi.db.dir", "../db"));
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(Files.readString(db.resolve("V2__board_state.sql")));
            s.execute(Files.readString(db.resolve("V3__identity_and_closure.sql")));
            s.execute(Files.readString(db.resolve("V3__identity_and_closure.sql"))); // idempotent
        }
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

    private static JobRecord job(final String platform, final String board, final String id, final String title, final String req) {
        return new JobRecord().setPlatform(platform).setBoardId(board).setPlatformJobId(id).setCompany("Dollar Tree")
                .setTitle(title).setLocation("Chesapeake, VA").setRequisitionId(req).setUrl("https://x/" + id);
    }

    @Test
    void rulesHoldOnPostgres() {
        final IdentityResolver r = new IdentityResolver(new JdbcIdentityStore(ds));
        final Map<String, String> a = r.resolve("phenom", "dt", List.of(job("phenom", "dt", "p1", "Store Manager", "R-501"),
                job("phenom", "dt", "p2", "Store Manager", "R-502")));
        assertThat(a.get("p1"), not(equalTo(a.get("p2"))));
        final Map<String, String> b = r.resolve("workday", "dt", List.of(job("workday", "dt", "w1", "Store Manager - Retail", "R-501"),
                job("workday", "dt", "w2", "Assistant Manager", "R-502")));
        assertThat(b.get("w1"), equalTo(a.get("p1")));
        assertThat(b.get("w2"), equalTo(a.get("p2")));
        // stable on re-crawl, through a fresh resolver (another pod)
        final Map<String, String> again = new IdentityResolver(new JdbcIdentityStore(ds))
                .resolve("phenom", "dt", List.of(job("phenom", "dt", "p2", "Store Manager", "R-502"),
                        job("phenom", "dt", "p1", "Store Manager", "R-501")));
        assertThat(again, equalTo(a));
    }

    @Test
    void twoPodsBindingTheSameBoardAtOnceAgree() throws Exception {
        final List<JobRecord> jobs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            jobs.add(job("lever", "race", "id" + i, "Role " + i, null));
        }
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            final List<Future<Map<String, String>>> runs = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                final Callable<Map<String, String>> run = () -> new IdentityResolver(new JdbcIdentityStore(ds)).resolve("lever", "race", jobs);
                runs.add(pool.submit(run));
            }
            final Map<String, String> first = runs.get(0).get();
            for (final Future<Map<String, String>> f : runs) {
                assertThat(f.get(), equalTo(first));
            }
        } finally {
            pool.shutdown();
        }
    }
}
