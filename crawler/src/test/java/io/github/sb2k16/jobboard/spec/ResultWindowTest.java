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
import io.github.sb2k16.jobboard.model.Tenant;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/** pagination.max_results: a search that serves fewer results than it reports (Phenom: 10,000). */
class ResultWindowTest {

    private static final String SPEC = "name: t\nformat: json\nlist:\n  url: \"https://x.example.com/s\"\n"
            + "  items: data.jobs\n  marker: [totalHits]\n  total: totalHits\n"
            + "pagination:\n  style: offset\n  param: from\n  page_size: 2\n  max_results: 4\nfields:\n"
            + "  platform_job_id: id\n  title: title\n  url: { template: \"https://x.example.com/j/{id}\" }\n";

    private static String page(final int reported, final int... ids) {
        final StringBuilder b = new StringBuilder("{\"totalHits\":" + reported + ",\"data\":{\"jobs\":[");
        for (int i = 0; i < ids.length; i++) {
            b.append(i == 0 ? "" : ",").append("{\"id\":\"").append(ids[i]).append("\",\"title\":\"T\"}");
        }
        return b.append("]}}").toString();
    }

    private static BoardSnapshot run(final String spec, final FakeFetcher f) throws Exception {
        final Path p = Files.createTempFile("spec", ".yaml");
        Files.writeString(p, spec);
        return new SpecAdapter(SpecLoader.load(p)).fetchBoard(new PlatformConfig("p", null, null, null),
                new Tenant("b", null), f);
    }

    @Test
    void stopsAtTheWindowAndIsPartialWithTheReason() throws Exception {
        final FakeFetcher f = new FakeFetcher()
                .on("https://x.example.com/s?from=0", page(9, 1, 2))
                .on("https://x.example.com/s?from=2", page(9, 3, 4));
        final BoardSnapshot s = run(SPEC, f);
        assertThat(s.getJobs(), hasSize(4));
        assertThat(s.isComplete(), equalTo(false));
        assertThat(s.getResultLimit(), equalTo(4));
        assertThat(s.getReportedTotal(), equalTo(9));
    }

    @Test
    void anEmptyPageAtTheWindowEdgeIsTheEndNotABrokenBoard() throws Exception {
        // Pages come back larger than page_size (Phenom serves 25 where 10 is asked for).
        final String spec = SPEC.replace("max_results: 4", "max_results: 6");
        final FakeFetcher f = new FakeFetcher()
                .on("https://x.example.com/s?from=0", page(9, 1, 2, 3))
                .on("https://x.example.com/s?from=3", "{\"totalHits\":0,\"data\":{}}");
        final BoardSnapshot s = run(spec, f);
        assertThat(s.getJobs(), hasSize(3));
        assertThat(s.isComplete(), equalTo(false));
        assertThat(s.getResultLimit(), equalTo(6));
    }

    @Test
    void aBoardInsideTheWindowIsComplete() throws Exception {
        final FakeFetcher f = new FakeFetcher()
                .on("https://x.example.com/s?from=0", page(3, 1, 2))
                .on("https://x.example.com/s?from=2", page(3, 3));
        final BoardSnapshot s = run(SPEC, f);
        assertThat(s.getJobs(), hasSize(3));
        assertThat(s.isComplete(), equalTo(true));
        assertThat(s.getResultLimit(), nullValue());
    }

    @Test
    void aPageOfOnlyRepeatsStopsPagingAndIsNotComplete() throws Exception {
        // A platform that ignores the offset serves the same page forever.
        final String spec = SPEC.replace("  max_results: 4\n", "").replace("  total: totalHits\n", "");
        final FakeFetcher f = new FakeFetcher().respond(url -> page(9, 1, 2));
        final BoardSnapshot s = run(spec, f);
        assertThat(s.getJobs(), hasSize(2));
        assertThat(s.isComplete(), equalTo(false));
        assertThat(f.requests, hasSize(2));
    }

    private static final String SINGLE = SPEC.replace("pagination:\n  style: offset\n  param: from\n  page_size: 2\n  max_results: 4\n",
            "pagination:\n  style: none\n  max_results: 3\n").replace("  total: totalHits\n", "");

    @Test
    void aSingleListAtItsCapIsPartial() throws Exception {
        final BoardSnapshot s = run(SINGLE, new FakeFetcher().on("https://x.example.com/s", page(0, 1, 2, 3)));
        assertThat(s.getJobs(), hasSize(3));
        assertThat(s.isComplete(), equalTo(false));
        assertThat(s.getResultLimit(), equalTo(3));
    }

    @Test
    void aSingleListUnderItsCapIsComplete() throws Exception {
        final BoardSnapshot s = run(SINGLE, new FakeFetcher().on("https://x.example.com/s", page(0, 1, 2)));
        assertThat(s.getJobs(), hasSize(2));
        assertThat(s.isComplete(), equalTo(true));
        assertThat(s.getResultLimit(), nullValue());
    }
}
