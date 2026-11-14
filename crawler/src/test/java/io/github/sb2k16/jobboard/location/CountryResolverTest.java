/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.location;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Cases are real locations from the live crawl (2026-11-13). */
class CountryResolverTest {

    private static List<String> codes(final String csv) {
        return csv == null || csv.isEmpty() ? List.of() : Arrays.asList(csv.split(" "));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', emptyValue = "", value = {
        "New York, NY|US",
        "San Francisco, CA|US",
        "Alexandria, VA, 22311|US",
        "Ithaca, New York|US",
        "Washington, D.C.|US",
        "Pittsburgh, Pennsylvania|US",
        "Rochester, New York (US)|US",
        "SF, SEA, NYC|US",
        "US-NYC; US-San Francisco; US-Seattle; US-Remote; US- Chicago; US-Atlanta; Canada-Toronto; Canada-Remote|CA US",
        "Toronto Canada, San Francisco, Remote in US, Remote in Canada|CA US",
        "Toronto, ON|CA",
        "Winnipeg, MB|CA",
        "Waterloo (City), Ontario (CA)|CA",
        "Scarborough, Ontario|CA",
        "Hamburg (DE)|DE",
        "Warsaw (PL)|PL",
        "Doha (QA)|QA",
        "Taiwan (TW)|TW",
        "Oslo (Fylke), Østlandet (NO)|NO",
        "Lünen, Nordrhein-Westfalen, Deutschland|DE",
        "Wien, Wien, Österreich|AT",
        "İstanbul, İstanbul, Türkiye|TR",
        "Sofia, Sofia (stolitsa), Bulgaria|BG",
        "Amsterdam, Noord-Holland, Netherlands|NL",
        "Saxilby, UK, Lincoln|GB",
        "London or Dublin|GB IE",
        "Dublin or Germany (Berlin or Remote)|DE IE",
        "Poland - Remote OR Romania - Remote|PL RO",
        "Barcelona or Madrid, Spain|ES",
        "Bengaluru|IN",
        "Islamabad, Pakistan|PK",
        "Mexico City|MX",
        "Santa Fe, New Mexico|US",
        "Taipei,Taiwan|TW",
        "No.1 Jen Ai road section 1 Taipei 100 Taiwan R.O.C.|TW",
        "Bogotá, Colombia|CO",
        "South Carolina, United States|US",
        "Belfast, Northern Ireland|GB",
        "Sydney, New South Wales|AU",
        "Abu Dhabi, United Arab Emirates|AE",
        "Remote/Hybrid|",
        "N/A|",
        "Join us anywhere|",
        "Davis|",
    })
    void resolvesLocations(final String location, final String expected) {
        assertEquals(codes(expected), CountryResolver.countries(null, location));
    }

    @Test
    void structuredCountryWinsAndIsUnionedWithText() {
        assertEquals(List.of("US"), CountryResolver.countries("United States", "Remote"));
        assertEquals(List.of("US"), CountryResolver.countries("US", null));
        assertEquals(List.of("GB"), CountryResolver.countries("UK", ""));
        assertEquals(List.of("DE"), CountryResolver.countries("Deutschland", "Berlin, Berlin, Deutschland"));
        assertEquals(List.of("CA", "US"), CountryResolver.countries("US", "Toronto, ON"));
    }

    @Test
    void unknownStructuredValueIsIgnored() {
        assertNull(CountryResolver.resolveOne("Remote"));
        assertNull(CountryResolver.resolveOne("ZZ"));
        assertEquals(List.of(), CountryResolver.countries("Planet Earth", null));
    }
}
