/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.identity;

import io.github.sb2k16.jobboard.model.JobRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

class IdentityResolverTest {
    private final MemoryIdentityStore store = new MemoryIdentityStore();
    private final IdentityResolver resolver = new IdentityResolver(store);

    private static JobRecord job(final String platform, final String board, final String id, final String title,
                                 final String location, final String req) {
        return new JobRecord().setPlatform(platform).setBoardId(board).setPlatformJobId(id).setCompany("CVS Health")
                .setTitle(title).setLocation(location).setRequisitionId(req).setUrl("https://" + platform + "/" + board + "/" + id);
    }

    private String key(final JobRecord job) {
        return resolver.resolve(job.getPlatform(), job.getBoardId(), List.of(job)).get(job.getPlatformJobId());
    }

    @Test
    void aPostingKeepsItsKeyWhenEdited() {
        final String first = key(job("workday", "cvs", "1", "Pharmacist", "Austin, TX", null));
        final String edited = key(job("workday", "cvs", "1", "Staff Pharmacist (Retail)", "Dallas, TX", null));
        assertThat(edited, equalTo(first));
    }

    @Test
    void twoPostingsOnOneBoardStayTwoJobs_evenWithTheSameTitleAndCity() {
        final JobRecord a = job("greenhouse", "stripe", "8379538", "Account Executive", "Chicago", null);
        final JobRecord b = job("greenhouse", "stripe", "7277406", "Account Executive", "Chicago", null);
        final Map<String, String> keys = resolver.resolve("greenhouse", "stripe", List.of(a, b));
        assertThat(keys.get("8379538"), not(equalTo(keys.get("7277406"))));
        // and across crawls, in the other order
        final Map<String, String> again = resolver.resolve("greenhouse", "stripe", List.of(b, a));
        assertThat(again, equalTo(keys));
    }

    @Test
    void aSecondPostingOnTheSameBoardInALaterCrawlStaysSeparate() {
        final String first = key(job("greenhouse", "stripe", "1", "Account Executive", "Chicago", null));
        final String second = key(job("greenhouse", "stripe", "2", "Account Executive", "Chicago", null));
        assertThat(second, not(equalTo(first)));
    }

    @Test
    void sameJobOnTwoPlatformsMergesByRequisitionId_evenWhenTitleAndLocationDiffer() {
        // Phase 0 pair: CVS Health on Phenom and Workday; location strings never match, the req id does.
        final List<JobRecord> phenom = boardWithReqIds("phenom", "cvs", "Pharmacy Technician", "Austin, Texas");
        final List<JobRecord> workday = boardWithReqIds("workday", "cvs", "Pharmacy Technician - Retail", "TX - Austin");
        final Map<String, String> p = resolver.resolve("phenom", "cvs", phenom);
        final Map<String, String> w = resolver.resolve("workday", "cvs", workday);
        assertThat(w.get("R-0"), equalTo(p.get("R-0")));
        assertThat(w.get("R-1"), equalTo(p.get("R-1")));
    }

    @Test
    void sameJobMergesByTitleAndLocation_whenOnlyOneBoardHasRequisitionIds() {
        // The old per-board choice keyed these two differently and never merged them.
        final List<JobRecord> withReq = boardWithReqIds("workday", "cvs", "Pharmacist", "Austin, TX");
        final JobRecord noReq = job("careerplug", "cvs", "x9", "Pharmacist", "Austin, Texas", null);
        final Map<String, String> w = resolver.resolve("workday", "cvs", withReq);
        assertThat(key(noReq), equalTo(w.get("R-0")));
    }

    @Test
    void differentCitiesAreDifferentJobs() {
        assertThat(key(job("lever", "a", "1", "Pharmacist", "Austin, TX", null)),
                not(equalTo(key(job("ashby", "b", "2", "Pharmacist", "Boston, MA", null)))));
    }

    @Test
    void aNewJobIsKeyedByItsAlias_soTwoPodsFindingItAtOnceAgree() {
        final IdentityResolver other = new IdentityResolver(new MemoryIdentityStore());
        final JobRecord j = job("lever", "a", "1", "Pharmacist", "Austin, TX", null);
        assertThat(key(j), equalTo(other.resolve("lever", "a", List.of(j)).get("1")));
    }

    private static List<JobRecord> boardWithReqIds(final String platform, final String board, final String title, final String loc) {
        final List<JobRecord> jobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            jobs.add(job(platform, board, "R-" + i, i == 0 || i == 1 ? title + (i == 1 ? " II" : "") : "Other " + i, loc,
                    "R-10" + i));
        }
        return jobs;
    }
}
