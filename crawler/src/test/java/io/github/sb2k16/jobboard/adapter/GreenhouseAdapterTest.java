/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.adapter;

import org.junit.jupiter.api.Test;
import io.github.sb2k16.jobboard.PlatformConfig;
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.JobRecord;
import io.github.sb2k16.jobboard.model.Tenant;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GreenhouseAdapterTest {

    private final GreenhouseAdapter adapter = new GreenhouseAdapter();
    private final Tenant stripe = new Tenant("stripe", "Stripe");

    private static String fixture(final String name) throws IOException {
        try (InputStream in = GreenhouseAdapterTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parse_realStripeSample_mapsFieldsAndIsComplete() throws IOException {
        final BoardSnapshot snapshot = adapter.parse(fixture("greenhouse-stripe-3.json"), "greenhouse", stripe);
        assertThat(snapshot.isComplete(), equalTo(true));
        assertThat(snapshot.getJobs().size(), equalTo(3));
        final JobRecord first = snapshot.getJobs().get(0);
        assertThat(first.getTitle(), equalTo("Abuse Investigator"));
        assertThat(first.getCompany(), equalTo("Stripe"));
        assertThat(first.getLocation(), equalTo("Dublin"));
        assertThat(first.getUrl(), equalTo("https://stripe.com/jobs/search?gh_jid=8172487"));
        assertThat(first.getPlatformJobId(), equalTo("8172487"));
        assertThat(first.getPostedAt().toString(), equalTo("2026-09-03T17:30:34Z"));
        assertThat(first.getDescription(), containsString("Who we are"));
        assertThat(first.getDescription(), not(containsString("<")));
        assertThat(first.getDescription(), not(containsString("&lt;")));
    }

    @Test
    void parse_countBelowTotal_isIncomplete() {
        final BoardSnapshot snapshot = adapter.parse("{\"jobs\":[{\"id\":1,\"title\":\"x\"}],\"meta\":{\"total\":2}}", "greenhouse", stripe);
        assertThat(snapshot.isComplete(), equalTo(false));
        assertThat(snapshot.getReportedTotal(), equalTo(2));
    }

    @Test
    void parse_emptyBoardWithMarker_isCompleteZero() {
        final BoardSnapshot snapshot = adapter.parse("{\"jobs\":[],\"meta\":{\"total\":0}}", "greenhouse", stripe);
        assertThat(snapshot.isComplete(), equalTo(true));
        assertThat(snapshot.getJobs().size(), equalTo(0));
    }

    @Test
    void parse_missingMarker_throwsInsteadOfZeroJobs() {
        assertThrows(BoardFetchException.class, () -> adapter.parse("{\"error\":\"not found\"}", "greenhouse", stripe));
        assertThrows(BoardFetchException.class, () -> adapter.parse("<html>maintenance</html>", "greenhouse", stripe));
    }

    @Test
    void parse_noTenantCompany_usesPlatformCompanyName() {
        final BoardSnapshot snapshot = adapter.parse(
                "{\"jobs\":[{\"id\":1,\"title\":\"x\",\"company_name\":\"Figma\"}],\"meta\":{\"total\":1}}",
                "greenhouse", new Tenant("figma", null));
        assertThat(snapshot.getJobs().get(0).getCompany(), equalTo("Figma"));
    }

    @Test
    void fetchBoard_usesBaseUrlOverride() {
        final HttpFetcher fetcher = mock(HttpFetcher.class);
        when(fetcher.get("http://fixture:8080/v1/boards/stripe/jobs?content=true")).thenReturn("{\"jobs\":[],\"meta\":{\"total\":0}}");
        final PlatformConfig config = new PlatformConfig("greenhouse", null, "http://fixture:8080", List.of(stripe));
        assertThat(adapter.fetchBoard(config, stripe, fetcher).isComplete(), equalTo(true));
    }

    @Test
    void html_toText_handlesEntitiesAndBlocks() {
        assertThat(Html.toText(Html.unescape("&lt;p&gt;A &amp;amp; B&lt;/p&gt;&lt;p&gt;C&#39;s&lt;/p&gt;")), equalTo("A & B\nC's"));
        assertThat(Html.toText(null), equalTo(null));
        assertThat(Html.unescape("&#x41;&#66;"), equalTo("AB"));
    }
}
