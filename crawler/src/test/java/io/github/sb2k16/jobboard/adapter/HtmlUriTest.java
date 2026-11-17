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

import java.net.URI;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/** Links as pages write them become URIs the way browsers make them. */
class HtmlUriTest {

    @Test
    void illegalCharactersArePercentEncoded_validUrlsAreUnchanged() {
        // CareerPlug on Nov 17: the board failed on every crawl because of the '|'.
        assertThat(Html.uri("/jobs/779498403-tpc-sawgrass-|-commercial-plumbing-hvac-technician").toString(),
                equalTo("/jobs/779498403-tpc-sawgrass-%7C-commercial-plumbing-hvac-technician"));
        assertThat(Html.uri("https://x.example/jobs/a b?q=c d").toString(), equalTo("https://x.example/jobs/a%20b?q=c%20d"));
        assertThat(Html.uri("https://x.example/emploi/ingénieur").toString(), equalTo("https://x.example/emploi/ing%C3%A9nieur"));
        final String ok = "https://x.example/jobs/12?a=1&b=%7C#top";
        assertThat(Html.uri(ok).toString(), equalTo(ok));
    }

    @Test
    void resolvesAgainstAPageLikeABrowser() {
        assertThat(URI.create("https://lil-kickers.careerplug.com/jobs").resolve(Html.uri("/jobs/1-a|b")).toString(),
                equalTo("https://lil-kickers.careerplug.com/jobs/1-a%7Cb"));
    }
}
