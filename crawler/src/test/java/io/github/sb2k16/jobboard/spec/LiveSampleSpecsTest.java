/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import io.github.sb2k16.jobboard.PlatformConfig;
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.JobRecord;
import io.github.sb2k16.jobboard.model.Tenant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Specs added for the live run, against responses fetched 2026-11-13 and trimmed to 3 jobs. */
class LiveSampleSpecsTest {

    private static BoardSnapshot run(final String platform, final Tenant tenant, final String fixture, final String expectedUrl) {
        final FakeFetcher fetcher = new FakeFetcher().respond(url -> FakeFetcher.resource(fixture));
        final BoardSnapshot s = ShippedSpecsTest.adapter(platform)
                .fetchBoard(new PlatformConfig(platform, null, null, List.of()), tenant, fetcher);
        assertThat(fetcher.requests.get(0), equalTo(expectedUrl));
        assertThat(s.getJobs().size(), equalTo(3));
        for (final JobRecord j : s.getJobs()) {
            assertThat(platform + " title", j.getTitle(), notNullValue());
            assertThat(platform + " url", j.getUrl(), notNullValue());
            assertThat(platform + " id", j.getPlatformJobId(), notNullValue());
            assertThat(platform + " company", j.getCompany(), notNullValue());
            assertThat(platform + " description", j.getDescription(), notNullValue());
            assertThat(platform + " description is text", j.getDescription(), not(containsString("</")));
        }
        return s;
    }

    @Test
    void ashby() {
        final BoardSnapshot s = run("ashby", new Tenant("ramp", "Ramp"), "ashby-ramp-3.json",
                "https://api.ashbyhq.com/posting-api/job-board/ramp?includeCompensation=false");
        final JobRecord j = s.getJobs().get(0);
        assertThat(j.getTitle(), equalTo("Software Engineer, Security, Cloud"));   // leading space trimmed
        assertThat(j.getCity(), equalTo("New York City"));
        assertThat(j.getRemote(), equalTo(true));
        assertThat(j.getPostedAt(), notNullValue());
        assertThat(s.isComplete(), equalTo(true));
    }

    @Test
    void recruitee() {
        final BoardSnapshot s = run("recruitee", new Tenant("bunq", null), "recruitee-bunq-3.json",
                "https://bunq.recruitee.com/api/offers/");
        assertThat(s.getJobs().get(0).getCompany(), equalTo("bunq"));
        assertThat(s.getJobs().get(0).getLocation(), equalTo("Amsterdam, Noord-Holland, Netherlands"));
        assertThat(s.getJobs().get(0).getPostedAt().toString(), equalTo("2026-11-11T13:21:40Z"));
    }

    @Test
    void pinpoint() {
        final BoardSnapshot s = run("pinpoint", new Tenant("indrive", "inDrive"), "pinpoint-indrive-3.json",
                "https://indrive.pinpointhq.com/postings.json");
        assertThat(s.getJobs().get(0).getLocation(), equalTo("Limassol, Cyprus"));
        assertThat(s.getJobs().get(0).getRemote(), equalTo(false));
    }

    @Test
    void gem() {
        final BoardSnapshot s = run("gem", new Tenant("the-boring-company", "The Boring Company"), "gem-boring-3.json",
                "https://api.gem.com/job_board/v0/the-boring-company/job_posts/");
        assertThat(s.getJobs().get(0).getLocation(), equalTo("Las Vegas, United States"));
        assertThat(s.getJobs().get(0).getRequisitionId(), equalTo("R239"));
    }

    @Test
    void wpJobManager_jobBoard_takesEmployerFromEachItem() {
        final BoardSnapshot s = run("wpjobmanager", new Tenant("lathire.com", null), "wpjm-lathire-3.xml",
                "https://lathire.com/?feed=job_feed&paged=1&posts_per_page=500");
        final JobRecord j = s.getJobs().get(0);
        assertThat(j.getTitle(), equalTo("Remote Office Assistant"));
        assertThat(j.getCompany(), equalTo("A Leading Web Design & SEO Company"));
        assertThat(j.getLocation(), equalTo("Worldwide"));
        assertThat(j.getDescription(), startsWith("Coalition Technologies is seeking"));
        assertThat(s.isComplete(), equalTo(true));
    }

    @Test
    void madgex_jobBoard_splitsEmployerFromTitle_andDropsTracking() {
        final BoardSnapshot s = run("madgex", new Tenant("jobs.sciencecareers.org", null), "madgex-sciencecareers-3.xml",
                "https://jobs.sciencecareers.org/jobsrss/?page=1");
        for (final JobRecord j : s.getJobs()) {
            assertThat(j.getUrl(), not(containsString("?")));
            assertThat(j.getUrl(), containsString("/job/" + j.getPlatformJobId() + "/"));
            assertThat(j.getTitle(), not(startsWith(j.getCompany() + ":")));
            assertThat(j.getLocation(), notNullValue());
        }
        // The board reports 523 jobs and the fixture holds 3: never a complete snapshot.
        assertThat(s.getReportedTotal(), equalTo(523));
        assertThat(s.isComplete(), equalTo(false));
    }
}
