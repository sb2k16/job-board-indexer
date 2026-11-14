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
import io.github.sb2k16.jobboard.model.JobRecord;
import io.github.sb2k16.jobboard.model.Tenant;
import io.github.sb2k16.jobboard.spec.SpecAdapter;
import io.github.sb2k16.jobboard.spec.SpecLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Runs specs against live tenants with the production fetcher (robots.txt, rate limits, retries),
 * for writing and checking a spec. Off by default:
 * <pre>./gradlew :crawler:test --tests '*LiveSpecRun' -Djbi.live=workable:tiger-analytics,breezy:vetsez</pre>
 * Writes build/live/&lt;platform&gt;-&lt;board&gt;.txt: outcome, count, completeness, field coverage, samples.
 */
@EnabledIf("enabled")
class LiveSpecRun {
    static boolean enabled() {
        return !System.getProperty("jbi.live", "").isEmpty();
    }

    @Test
    void run() throws Exception {
        final Path specs = Path.of(System.getProperty("jbi.specs.dir", "../config/specs"));
        final Path out = Path.of("build/live");
        Files.createDirectories(out);
        final PolitenessConfig p = new PolitenessConfig();
        final FetcherFactory factory = new FetcherFactory(new JdkHttpTransport(), Ticker.SYSTEM, p.getRobotsTtl(),
                p.getRequestTimeout(), p.getBreakerThreshold(), p.getBreakerCooldown());
        for (final String target : System.getProperty("jbi.live").split(",")) {
            final String[] pb = target.trim().split(":", 2);
            final StringBuilder sb = new StringBuilder(target).append('\n');
            final long t0 = System.currentTimeMillis();
            try {
                final SpecAdapter adapter = new SpecAdapter(SpecLoader.load(specs.resolve(pb[0] + ".yaml")));
                final HttpFetcher fetcher = factory.forPlatform(pb[0], JobBoardCrawler.policy(adapter, p));
                final BoardSnapshot s = adapter.fetchBoard(new PlatformConfig(pb[0], null, null, null).setSpec("x"),
                        new Tenant(pb[1], null), fetcher);
                final List<JobRecord> jobs = s.getJobs();
                sb.append(String.format("jobs=%d reported=%s complete=%s seconds=%d%n", jobs.size(), s.getReportedTotal(),
                        s.isComplete(), (System.currentTimeMillis() - t0) / 1000));
                sb.append(coverage(jobs, "title", JobRecord::getTitle)).append(coverage(jobs, "company", JobRecord::getCompany))
                        .append(coverage(jobs, "location", JobRecord::getLocation))
                        .append(coverage(jobs, "description", JobRecord::getDescription))
                        .append(coverage(jobs, "posted_at", j -> j.getPostedAt() == null ? null : j.getPostedAt().toString()))
                        .append(coverage(jobs, "requisition_id", JobRecord::getRequisitionId))
                        .append(coverage(jobs, "employment_type", JobRecord::getEmploymentType))
                        .append(coverage(jobs, "remote", j -> j.getRemote() == null ? null : j.getRemote().toString()))
                        .append(coverage(jobs, "platform_job_id", JobRecord::getPlatformJobId))
                        .append('\n');
                for (final JobRecord j : jobs.subList(0, Math.min(2, jobs.size()))) {
                    sb.append(String.format("- %s | %s | %s | %s | posted %s | req %s | type %s | remote %s%n  %s%n  %s%n",
                            j.getPlatformJobId(), j.getTitle(), j.getCompany(), j.getLocation(), j.getPostedAt(),
                            j.getRequisitionId(), j.getEmploymentType(), j.getRemote(), j.getUrl(),
                            j.getDescription() == null ? "(no description)"
                                    : j.getDescription().substring(0, Math.min(160, j.getDescription().length())).replace('\n', ' ')));
                }
            } catch (final RuntimeException e) {
                sb.append("FAILED: ").append(e).append('\n');
            }
            Files.writeString(out.resolve(target.trim().replace(':', '-').replace('/', '_') + ".txt"), sb.toString());
        }
    }

    private static String coverage(final List<JobRecord> jobs, final String name, final Function<JobRecord, String> f) {
        final List<String> have = new ArrayList<>();
        for (final JobRecord j : jobs) {
            final String v = f.apply(j);
            if (v != null && !v.isBlank()) {
                have.add(v);
            }
        }
        return String.format("%s=%d/%d ", name, have.size(), jobs.size());
    }
}
