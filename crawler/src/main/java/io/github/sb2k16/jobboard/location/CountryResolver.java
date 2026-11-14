/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Countries (ISO 3166-1 alpha-2) a job is located in, from the platform's structured country when
 * it has one and from the free-text location, which can name several places ("SF, NYC or Remote
 * in Canada" gives US and CA). Best effort and conservative: an unrecognised location gives no
 * country rather than a guess.
 *
 * <p>Matching, longest phrase first so "New Mexico" is never read as Mexico and "Northern Ireland"
 * never as Ireland:
 * <ul>
 *   <li>Names, case-insensitive on word boundaries: countries (English and six other languages, so
 *       "Deutschland" and "Österreich" count), US states, Canadian provinces, major cities.</li>
 *   <li>Codes, uppercase only, so the "us" in "join us" is not the United States: US, USA, UK, UAE
 *       and city abbreviations (SF, NYC) anywhere; a two-letter code after a comma
 *       ("Portland, OR", "Toronto, ON") is a US state or Canadian province; a two-letter code in
 *       brackets ("Hamburg (DE)") is a country.</li>
 * </ul>
 */
public final class CountryResolver {

    private static final Locale[] NAME_LANGUAGES = {
        Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH, new Locale("es"), new Locale("nl"), Locale.ITALIAN, new Locale("pt"),
    };

    /** Country names that are also common place or person names in job locations. */
    private static final Set<String> AMBIGUOUS_NAMES = Set.of(
            "georgia", "jersey", "granada", "chad", "jordan", "guernsey", "man", "columbia", "niger");

    private static final String[][] ALIASES = {
        {"US", "United States of America"}, {"US", "U.S.A."}, {"US", "U.S."}, {"US", "Washington, D.C."},
        {"US", "Washington D.C."}, {"US", "D.C."},
        {"GB", "England"}, {"GB", "Scotland"}, {"GB", "Wales"}, {"GB", "Northern Ireland"}, {"GB", "Great Britain"},
        {"GB", "Britain"}, {"GB", "U.K."},
        {"AE", "United Arab Emirates"}, {"AE", "Dubai"}, {"AE", "Abu Dhabi"},
        {"HK", "Hong Kong"}, {"MO", "Macau"}, {"MO", "Macao"}, {"MM", "Burma"}, {"MM", "Myanmar"},
        {"KR", "Korea"}, {"KR", "South Korea"}, {"CZ", "Czech Republic"}, {"NL", "Holland"}, {"TR", "Turkey"},
        {"TR", "Türkiye"}, {"TW", "R.O.C."}, {"CI", "Ivory Coast"}, {"RU", "Russia"}, {"VN", "Vietnam"},
        {"AU", "New South Wales"}, {"AU", "Queensland"}, {"AU", "NSW"},
    };

    private static final String[][] US_STATES = {
        {"AL", "Alabama"}, {"AK", "Alaska"}, {"AZ", "Arizona"}, {"AR", "Arkansas"}, {"CA", "California"},
        {"CO", "Colorado"}, {"CT", "Connecticut"}, {"DE", "Delaware"}, {"DC", "District of Columbia"},
        {"FL", "Florida"}, {"GA", "Georgia"}, {"HI", "Hawaii"}, {"ID", "Idaho"}, {"IL", "Illinois"},
        {"IN", "Indiana"}, {"IA", "Iowa"}, {"KS", "Kansas"}, {"KY", "Kentucky"}, {"LA", "Louisiana"},
        {"ME", "Maine"}, {"MD", "Maryland"}, {"MA", "Massachusetts"}, {"MI", "Michigan"}, {"MN", "Minnesota"},
        {"MS", "Mississippi"}, {"MO", "Missouri"}, {"MT", "Montana"}, {"NE", "Nebraska"}, {"NV", "Nevada"},
        {"NH", "New Hampshire"}, {"NJ", "New Jersey"}, {"NM", "New Mexico"}, {"NY", "New York"},
        {"NC", "North Carolina"}, {"ND", "North Dakota"}, {"OH", "Ohio"}, {"OK", "Oklahoma"}, {"OR", "Oregon"},
        {"PA", "Pennsylvania"}, {"RI", "Rhode Island"}, {"SC", "South Carolina"}, {"SD", "South Dakota"},
        {"TN", "Tennessee"}, {"TX", "Texas"}, {"UT", "Utah"}, {"VT", "Vermont"}, {"VA", "Virginia"},
        {"WA", "Washington"}, {"WV", "West Virginia"}, {"WI", "Wisconsin"}, {"WY", "Wyoming"}, {"PR", "Puerto Rico"},
    };

    private static final String[][] CA_PROVINCES = {
        {"ON", "Ontario"}, {"QC", "Quebec"}, {"QC", "Québec"}, {"BC", "British Columbia"}, {"AB", "Alberta"},
        {"MB", "Manitoba"}, {"SK", "Saskatchewan"}, {"NS", "Nova Scotia"}, {"NL", "Newfoundland"},
        {"PE", "Prince Edward Island"}, {"NT", "Northwest Territories"}, {"YT", "Yukon"}, {"NU", "Nunavut"},
    };

    /** Cities that appear without a country often enough to be worth knowing. Unambiguous ones only. */
    private static final String[][] CITIES = {
        {"US", "San Francisco"}, {"US", "New York City"}, {"US", "Seattle"}, {"US", "Chicago"}, {"US", "Atlanta"},
        {"US", "Boston"}, {"US", "Los Angeles"}, {"US", "Austin"}, {"US", "Denver"}, {"US", "Miami"},
        {"US", "Palo Alto"}, {"US", "Mountain View"}, {"US", "Menlo Park"}, {"US", "San Jose"}, {"US", "San Mateo"},
        {"US", "Sunnyvale"}, {"US", "Oakland"}, {"US", "Berkeley"}, {"US", "Brooklyn"}, {"US", "Manhattan"},
        {"US", "Philadelphia"}, {"US", "Pittsburgh"}, {"US", "Houston"}, {"US", "Dallas"}, {"US", "San Diego"},
        {"US", "Portland"}, {"US", "Phoenix"}, {"US", "Minneapolis"}, {"US", "Detroit"}, {"US", "Nashville"},
        {"US", "Salt Lake City"}, {"US", "Baltimore"}, {"US", "Tallahassee"}, {"US", "Honolulu"},
        {"US", "South San Francisco"}, {"US", "Redwood City"}, {"US", "Bay Area"}, {"US", "Silicon Valley"},
        {"CA", "Toronto"}, {"CA", "Vancouver"}, {"CA", "Montreal"}, {"CA", "Montréal"}, {"CA", "Ottawa"},
        {"CA", "Calgary"}, {"CA", "Waterloo"}, {"CA", "Mississauga"}, {"CA", "Winnipeg"},
        {"GB", "London"}, {"GB", "Manchester"}, {"GB", "Edinburgh"}, {"GB", "Oxford"}, {"GB", "Cambridge, UK"},
        {"GB", "Bristol"}, {"GB", "Glasgow"}, {"GB", "Belfast"},
        {"IE", "Dublin"}, {"IE", "Cork"}, {"FR", "Paris"}, {"DE", "Berlin"}, {"DE", "Munich"}, {"DE", "München"},
        {"DE", "Hamburg"}, {"DE", "Frankfurt"}, {"DE", "Cologne"}, {"DE", "Köln"}, {"NL", "Amsterdam"},
        {"NL", "Rotterdam"}, {"BE", "Brussels"}, {"CH", "Zurich"}, {"CH", "Zürich"}, {"CH", "Geneva"},
        {"AT", "Vienna"}, {"AT", "Wien"}, {"ES", "Madrid"}, {"ES", "Barcelona"}, {"PT", "Lisbon"},
        {"IT", "Milan"}, {"IT", "Rome"}, {"SE", "Stockholm"}, {"DK", "Copenhagen"}, {"NO", "Oslo"},
        {"FI", "Helsinki"}, {"PL", "Warsaw"}, {"PL", "Kraków"}, {"PL", "Krakow"}, {"CZ", "Prague"},
        {"RO", "Bucharest"}, {"BG", "Sofia"}, {"GR", "Athens"}, {"TR", "Istanbul"}, {"TR", "İstanbul"},
        {"IL", "Tel Aviv"}, {"AE", "Dubai"}, {"QA", "Doha"}, {"SA", "Riyadh"},
        {"IN", "Bangalore"}, {"IN", "Bengaluru"}, {"IN", "Mumbai"}, {"IN", "New Delhi"}, {"IN", "Delhi"},
        {"IN", "Gurgaon"}, {"IN", "Gurugram"}, {"IN", "Hyderabad"}, {"IN", "Pune"}, {"IN", "Chennai"},
        {"IN", "Noida"}, {"PK", "Islamabad"}, {"PK", "Karachi"}, {"PK", "Lahore"},
        {"SG", "Singapore"}, {"JP", "Tokyo"}, {"JP", "Osaka"}, {"KR", "Seoul"}, {"CN", "Beijing"},
        {"CN", "Shanghai"}, {"CN", "Shenzhen"}, {"TW", "Taipei"}, {"AU", "Sydney"}, {"AU", "Melbourne"},
        {"AU", "Brisbane"}, {"NZ", "Auckland"}, {"BR", "São Paulo"}, {"BR", "Sao Paulo"}, {"MX", "Mexico City"},
        {"CO", "Bogotá"}, {"CO", "Bogota"}, {"AR", "Buenos Aires"}, {"CL", "Santiago de Chile"},
        {"ZA", "Cape Town"}, {"ZA", "Johannesburg"}, {"NG", "Lagos"}, {"KE", "Nairobi"}, {"EG", "Cairo"},
    };

    /** Uppercase-only tokens matched anywhere. */
    private static final String[][] CODES = {
        {"US", "US"}, {"US", "USA"}, {"GB", "UK"}, {"AE", "UAE"}, {"US", "SF"}, {"US", "NYC"}, {"US", "SEA"},
        {"US", "CHI"}, {"US", "ATL"}, {"US", "DC"}, {"US", "SFO"}, {"US", "LA"},
    };

    private static final Set<String> ISO = Set.of(Locale.getISOCountries());
    private static final Map<String, String> US_CODES = new HashMap<>();
    private static final Map<String, String> CA_CODES = new HashMap<>();
    private static final List<Phrase> PHRASES = new ArrayList<>();
    private static final Map<String, String> BY_NAME = new HashMap<>();

    /** A two-letter code after a comma, or in brackets. */
    private static final Pattern AFTER_COMMA = Pattern.compile(",\\s*([A-Z]{2})(?![\\p{L}])");
    private static final Pattern IN_BRACKETS = Pattern.compile("\\(([A-Z]{2})\\)");

    private static final class Phrase {
        final String text;
        final String country;
        final boolean caseSensitive;

        Phrase(final String text, final String country, final boolean caseSensitive) {
            this.text = text;
            this.country = country;
            this.caseSensitive = caseSensitive;
        }
    }

    static {
        final Map<String, String> names = new HashMap<>();
        for (final String code : Locale.getISOCountries()) {
            final Locale region = new Locale("", code);
            for (final Locale language : NAME_LANGUAGES) {
                final String name = region.getDisplayCountry(language);
                if (name.length() >= 4 && !name.equals(code) && !AMBIGUOUS_NAMES.contains(name.toLowerCase(Locale.ROOT))
                        && !name.contains("(")) {
                    names.putIfAbsent(name, code);
                }
            }
        }
        for (final String[] a : ALIASES) {
            names.put(a[1], a[0]);
        }
        for (final String[] s : US_STATES) {
            US_CODES.put(s[0], s[1]);
            names.put(s[1], "US");
        }
        for (final String[] p : CA_PROVINCES) {
            CA_CODES.put(p[0], p[1]);
            names.put(p[1], "CA");
        }
        for (final String[] c : CITIES) {
            names.putIfAbsent(c[1], c[0]);
        }
        for (final Map.Entry<String, String> e : names.entrySet()) {
            PHRASES.add(new Phrase(e.getKey(), e.getValue(), false));
            BY_NAME.put(fold(e.getKey()), e.getValue());
        }
        for (final String[] c : CODES) {
            PHRASES.add(new Phrase(c[1], c[0], true));
        }
        PHRASES.sort((a, b) -> b.text.length() - a.text.length());
    }

    private CountryResolver() {
    }

    /** Sorted ISO codes, possibly empty. Any argument may be null. */
    public static List<String> countries(final String structuredCountry, final String location) {
        final Set<String> out = new TreeSet<>();
        final String fromField = resolveOne(structuredCountry);
        if (fromField != null) {
            out.add(fromField);
        }
        if (location != null && !location.isBlank()) {
            out.addAll(scan(location));
        }
        return out.isEmpty() ? Collections.emptyList() : new ArrayList<>(out);
    }

    /** One value that should be a single country: an ISO code or a name in any known language. */
    static String resolveOne(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final String v = value.trim();
        if (v.length() == 2 && ISO.contains(v.toUpperCase(Locale.ROOT))) {
            final String code = v.toUpperCase(Locale.ROOT);
            return code.equals("UK") ? "GB" : code;
        }
        if (v.equalsIgnoreCase("UK")) {
            return "GB";
        }
        if (v.equals("USA") || v.equals("UAE")) {
            return v.equals("USA") ? "US" : "AE";
        }
        final String byName = BY_NAME.get(fold(v));
        if (byName != null) {
            return byName;
        }
        final List<String> scanned = scan(v);
        return scanned.size() == 1 ? scanned.get(0) : null;
    }

    private static List<String> scan(final String text) {
        final Set<String> found = new HashSet<>();
        final boolean[] used = new boolean[text.length()];

        // Codes in brackets name countries ("Hamburg (DE)"); after a comma, states and provinces.
        final Matcher brackets = IN_BRACKETS.matcher(text);
        while (brackets.find()) {
            final String code = brackets.group(1).equals("UK") ? "GB" : brackets.group(1);
            if (ISO.contains(code)) {
                found.add(code);
                mark(used, brackets.start(), brackets.end());
            }
        }
        final Matcher comma = AFTER_COMMA.matcher(text);
        while (comma.find()) {
            if (used[comma.start(1)]) {
                continue;
            }
            final String code = comma.group(1);
            if (US_CODES.containsKey(code)) {
                found.add("US");
            } else if (CA_CODES.containsKey(code)) {
                found.add("CA");
            } else if (code.equals("UK")) {
                found.add("GB");
            } else if (ISO.contains(code)) {
                found.add(code);
            } else {
                continue;
            }
            mark(used, comma.start(1), comma.end(1));
        }

        final String lower = text.toLowerCase(Locale.ROOT);
        for (final Phrase p : PHRASES) {
            final String hay = p.caseSensitive ? text : lower;
            final String needle = p.caseSensitive ? p.text : p.text.toLowerCase(Locale.ROOT);
            int from = 0;
            while (true) {
                final int at = hay.indexOf(needle, from);
                if (at < 0) {
                    break;
                }
                final int end = at + needle.length();
                from = at + 1;
                if (boundary(text, at - 1) && boundary(text, end) && free(used, at, end)) {
                    found.add(p.country);
                    mark(used, at, end);
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static boolean boundary(final String s, final int i) {
        return i < 0 || i >= s.length() || !Character.isLetterOrDigit(s.charAt(i));
    }

    private static boolean free(final boolean[] used, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (used[i]) {
                return false;
            }
        }
        return true;
    }

    private static void mark(final boolean[] used, final int from, final int to) {
        for (int i = from; i < to && i < used.length; i++) {
            used[i] = true;
        }
    }

    private static String fold(final String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
