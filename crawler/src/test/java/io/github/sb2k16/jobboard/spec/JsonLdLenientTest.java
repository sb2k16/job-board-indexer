/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

/** JSON-LD as pages write it, not as JSON requires it. */
class JsonLdLenientTest {

    @Test
    void anUnneededBackslashEscapeIsRead() {
        // Jobvite, Nov 16: every Pilgrim's job page writes the apostrophe as \' (not legal JSON).
        final String page = "<script type=\"application/ld+json\">{\"@context\":\"http://schema.org\",\"@type\":\"JobPosting\","
                + "\"title\":\"Category Manager\",\"hiringOrganization\":{\"@type\":\"Organization\",\"name\":\"Pilgrim\\'s\","
                + "\"logo\":\"https:\\/\\/x.example\\/l.png\"}}</script>";
        final JsonNode posting = JsonLd.normalized(page);
        assertThat(posting, notNullValue());
        assertThat(posting.toString(), containsString("Pilgrim's"));
    }

    @Test
    void anEncodedPlusInTheScriptTypeIsFound() {
        final String page = "<script type=\"application/ld&#x2B;json\">{\"@type\":\"JobPosting\",\"title\":\"Acheteur\"}</script>";
        assertThat(JsonLd.normalized(page), notNullValue());
    }
}
