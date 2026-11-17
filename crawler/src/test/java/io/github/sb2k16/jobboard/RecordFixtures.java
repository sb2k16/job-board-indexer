/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import io.github.sb2k16.jobboard.adapter.HttpFetcher;
import io.github.sb2k16.jobboard.fetch.FetcherFactory;
import io.github.sb2k16.jobboard.fetch.JdkHttpTransport;
import io.github.sb2k16.jobboard.fetch.Ticker;
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.Tenant;
import io.github.sb2k16.jobboard.spec.Recording;
import io.github.sb2k16.jobboard.spec.SpecAdapter;
import io.github.sb2k16.jobboard.spec.SpecLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Records a fixture per platform from a live tenant, with the production fetcher (robots.txt, rate
 * limits). Off by default: tests/record_fixtures.sh greenhouse workday ... Each target is
 * "platform" (the first tenant in local/live/tenants/&lt;platform&gt;.csv) or "platform:board".
 */
@EnabledIf("enabled")
class RecordFixtures {
    static boolean enabled() {
        return !System.getProperty("jbi.record", "").isEmpty();
    }

    @Test
    void record() throws Exception {
        final Path specs = Path.of(System.getProperty("jbi.specs.dir", "../config/specs"));
        final Path tenants = Path.of(System.getProperty("jbi.tenants.dir", "../local/live/tenants"));
        final Path out = Path.of("src/test/resources/fixtures/recorded");
        final Path log = Path.of("build/record");
        Files.createDirectories(log);
        final PolitenessConfig p = new PolitenessConfig();
        final FetcherFactory factory = new FetcherFactory(new JdkHttpTransport(), Ticker.SYSTEM, p.getRobotsTtl(),
                p.getRequestTimeout(), p.getBreakerThreshold(), p.getBreakerCooldown());
        for (final String target : System.getProperty("jbi.record").split(",")) {
            final String[] pb = target.trim().split(":", 2);
            final String platform = pb[0];
            final StringBuilder sb = new StringBuilder(target).append(": ");
            try {
                final Tenant tenant = pb.length > 1 ? new Tenant(pb[1], null) : firstTenant(tenants.resolve(platform + ".csv"));
                final SpecAdapter adapter = Recording.capped(new SpecAdapter(SpecLoader.load(specs.resolve(platform + ".yaml"))), 1);
                final HttpFetcher live = factory.forPlatform(platform, JobBoardCrawler.policy(adapter, p));
                final Recording rec = new Recording();
                rec.platform = platform;
                rec.board = tenant.getBoard();
                rec.company = tenant.getCompany();
                rec.recorded = LocalDate.now().toString();
                final BoardSnapshot s = adapter.fetchBoard(new PlatformConfig(platform, null, null, null).setSpec("x"),
                        tenant, rec.recorder(live, 4));
                rec.save(out.resolve(platform + ".json.gz"));
                sb.append(String.format("recorded %d requests, %d jobs%n", rec.status.size(), s.getJobs().size()));
            } catch (final RuntimeException e) {
                sb.append("FAILED: ").append(e).append('\n');
            }
            Files.writeString(log.resolve(platform + ".txt"), sb.toString());
        }
    }

    private static Tenant firstTenant(final Path csv) throws Exception {
        final List<String> lines = Files.readAllLines(csv);
        for (final String line : lines.subList(1, lines.size())) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            final int comma = line.lastIndexOf(',');
            final String board = line.substring(0, comma).trim();
            final String company = line.substring(comma + 1).trim();
            return new Tenant(board, company.isEmpty() ? null : company);
        }
        throw new IllegalStateException("no tenant in " + csv);
    }
}
