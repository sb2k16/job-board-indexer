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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.containsString;

/**
 * Every shipped spec against a recording of a live tenant (tests/record_fixtures.sh): the spec
 * still asks for the recorded requests and still maps the recorded responses to jobs with a title,
 * a URL, an id and a company.
 */
class RecordedSpecsTest {
    /** Specs with no live tenant to record. */
    private static final List<String> NOT_RECORDED = List.of("fixture");
    /**
     * Platforms whose data never names the employer, so the company comes from the tenant list
     * alone; the recorded ADP tenant's employer is not named anywhere in its public data.
     */
    private static final List<String> COMPANY_FROM_TENANT = List.of("adp");

    static Stream<String> platforms() {
        final File[] files = ShippedSpecsTest.specsDir().toFile().listFiles((d, n) -> n.endsWith(".yaml"));
        return Arrays.stream(files).map(f -> f.getName().replace(".yaml", ""))
                .filter(p -> !NOT_RECORDED.contains(p)).sorted();
    }

    private static Recording recording(final String platform) throws Exception {
        final InputStream in = RecordedSpecsTest.class.getResourceAsStream("/fixtures/recorded/" + platform + ".json.gz");
        return in == null ? null : Recording.load(in);
    }

    @Test
    void everyShippedSpecHasARecording() throws Exception {
        final List<String> missing = new ArrayList<>();
        for (final String p : platforms().collect(java.util.stream.Collectors.toList())) {
            if (recording(p) == null) {
                missing.add(p);
            }
        }
        assertThat("specs without a recording (tests/record_fixtures.sh)", missing, empty());
    }

    @ParameterizedTest
    @MethodSource("platforms")
    void specReadsItsRecording(final String platform) throws Exception {
        final Recording rec = recording(platform);
        if (rec == null) {
            return; // reported by everyShippedSpecHasARecording
        }
        final Recording.FakeReplay replay = rec.replayer();
        final SpecAdapter adapter = Recording.capped(ShippedSpecsTest.adapter(platform), 1);
        final BoardSnapshot s = adapter.fetchBoard(new PlatformConfig(platform, null, null, List.of()),
                new Tenant(rec.board, rec.company), replay);
        assertThat(platform + ": unrecorded requests", replay.unrecorded, empty());
        assertThat(platform + ": jobs", s.getJobs().size(), greaterThan(0));
        for (final JobRecord j : s.getJobs()) {
            assertThat(platform + " title", j.getTitle(), notNullValue());
            assertThat(platform + " url", j.getUrl(), notNullValue());
            assertThat(platform + " url is absolute", j.getUrl(), containsString("://"));
            assertThat(platform + " id", j.getPlatformJobId(), notNullValue());
            if (!COMPANY_FROM_TENANT.contains(platform) || rec.company != null) {
                assertThat(platform + " company", j.getCompany(), notNullValue());
            }
            if (j.getDescription() != null) {
                assertThat(platform + " description is text", j.getDescription(), not(containsString("</p>")));
            }
        }
    }
}
