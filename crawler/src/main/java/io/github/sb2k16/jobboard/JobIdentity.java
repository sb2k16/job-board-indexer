/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import io.github.sb2k16.jobboard.model.JobRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Job identity and change detection.
 *
 * <p>Rules come from the Phase 0 dedup test (phase0/dedup-test.md), not from the original design:
 * <ol>
 *   <li>When a board exposes usable requisition ids, the key is (company, requisition id, title).
 *       Requisition ids alone are not unique: Gem tenants reuse one id for different titles, so the
 *       title stays in the key.</li>
 *   <li>Otherwise the key is (company, title, canonical location token). The raw location string is
 *       never used: Workday's internal location names never match Phenom or Eightfold strings.</li>
 * </ol>
 * A requisition id is usable only if it contains a digit (Stripe's Greenhouse value is the literal
 * "See Opening ID") and the board's ids are mostly distinct (Stripe: 1 distinct value over 768 jobs).
 */
public final class JobIdentity {

    static final double MIN_DISTINCT_REQUISITION_SHARE = 0.5;

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*[0-9].*");
    private static final Pattern REGION_DASH_CITY = Pattern.compile("^([A-Za-z]{2})\\s*-\\s*(.+)$");

    private static final Map<String, String> US_STATES = Map.ofEntries(
            Map.entry("alabama", "al"), Map.entry("alaska", "ak"), Map.entry("arizona", "az"),
            Map.entry("arkansas", "ar"), Map.entry("california", "ca"), Map.entry("colorado", "co"),
            Map.entry("connecticut", "ct"), Map.entry("delaware", "de"), Map.entry("florida", "fl"),
            Map.entry("georgia", "ga"), Map.entry("hawaii", "hi"), Map.entry("idaho", "id"),
            Map.entry("illinois", "il"), Map.entry("indiana", "in"), Map.entry("iowa", "ia"),
            Map.entry("kansas", "ks"), Map.entry("kentucky", "ky"), Map.entry("louisiana", "la"),
            Map.entry("maine", "me"), Map.entry("maryland", "md"), Map.entry("massachusetts", "ma"),
            Map.entry("michigan", "mi"), Map.entry("minnesota", "mn"), Map.entry("mississippi", "ms"),
            Map.entry("missouri", "mo"), Map.entry("montana", "mt"), Map.entry("nebraska", "ne"),
            Map.entry("nevada", "nv"), Map.entry("new hampshire", "nh"), Map.entry("new jersey", "nj"),
            Map.entry("new mexico", "nm"), Map.entry("new york", "ny"), Map.entry("north carolina", "nc"),
            Map.entry("north dakota", "nd"), Map.entry("ohio", "oh"), Map.entry("oklahoma", "ok"),
            Map.entry("oregon", "or"), Map.entry("pennsylvania", "pa"), Map.entry("rhode island", "ri"),
            Map.entry("south carolina", "sc"), Map.entry("south dakota", "sd"), Map.entry("tennessee", "tn"),
            Map.entry("texas", "tx"), Map.entry("utah", "ut"), Map.entry("vermont", "vt"),
            Map.entry("virginia", "va"), Map.entry("washington", "wa"), Map.entry("west virginia", "wv"),
            Map.entry("wisconsin", "wi"), Map.entry("wyoming", "wy"), Map.entry("district of columbia", "dc"));

    private static final Map<String, String> COUNTRIES = Map.of(
            "united states", "us", "united states of america", "us", "usa", "us", "us", "us",
            "united kingdom", "gb", "uk", "gb", "canada", "ca");

    private JobIdentity() {
    }

    /** Lower-case, ASCII-fold, collapse every run of non-alphanumerics to one space. */
    public static String normalize(final String value) {
        if (value == null) {
            return "";
        }
        final String folded = DIACRITICS.matcher(Normalizer.normalize(value, Normalizer.Form.NFKD)).replaceAll("");
        return NON_ALNUM.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /**
     * Canonical location token: "city|region" when both are known, else "city|country", else the city
     * (a comma-less location string counts as the city), else the country, else empty. Structured parts
     * win over the free-text string.
     */
    public static String locationToken(final JobRecord job) {
        String city = normalize(job.getCity());
        String region = regionCode(job.getRegion());
        String country = countryCode(job.getCountry());
        if (city.isEmpty() && job.getLocation() != null) {
            final String raw = job.getLocation().trim();
            final java.util.regex.Matcher dash = REGION_DASH_CITY.matcher(raw);
            if (dash.matches()) {
                region = region.isEmpty() ? regionCode(dash.group(1)) : region;
                city = normalize(dash.group(2));
            } else {
                final String[] parts = raw.split(",");
                if (parts.length >= 2) {
                    city = normalize(parts[0]);
                    region = region.isEmpty() ? regionCode(parts[1]) : region;
                    if (parts.length >= 3 && country.isEmpty()) {
                        country = countryCode(parts[parts.length - 1]);
                    }
                } else {
                    // No separator ("Dublin", "Remote - US"): the whole string is the place. Dropping it
                    // would key the job on title alone and merge the same role across cities.
                    city = normalize(raw);
                }
            }
        }
        if (!city.isEmpty() && !region.isEmpty()) {
            return city + "|" + region;
        }
        if (!city.isEmpty() && !country.isEmpty()) {
            return city + "|" + country;
        }
        return city.isEmpty() ? country : city;
    }

    static String regionCode(final String region) {
        final String n = normalize(region);
        if (n.isEmpty()) {
            return "";
        }
        if (n.length() == 2) {
            return n;
        }
        return US_STATES.getOrDefault(n, n);
    }

    static String countryCode(final String country) {
        final String n = normalize(country);
        return COUNTRIES.getOrDefault(n, n);
    }

    /** True if this single value can identify a requisition. */
    static boolean isPlausibleRequisitionId(final String requisitionId) {
        return requisitionId != null && HAS_DIGIT.matcher(requisitionId).matches();
    }

    /**
     * Board-level check: the board's requisition ids are used only if plausible values cover at
     * least half the jobs with distinct values. Guards against tenant-wide placeholders.
     */
    public static boolean boardHasUsableRequisitionIds(final List<JobRecord> jobs) {
        if (jobs.isEmpty()) {
            return false;
        }
        final Set<String> distinct = new HashSet<>();
        for (final JobRecord job : jobs) {
            if (isPlausibleRequisitionId(job.getRequisitionId())) {
                distinct.add(normalize(job.getRequisitionId()));
            }
        }
        return distinct.size() >= MIN_DISTINCT_REQUISITION_SHARE * jobs.size();
    }

    public static String jobKey(final JobRecord job, final boolean useRequisitionId) {
        final String company = normalize(job.getCompany());
        final String title = normalize(job.getTitle());
        if (useRequisitionId && isPlausibleRequisitionId(job.getRequisitionId())) {
            return sha256("r|" + company + "|" + normalize(job.getRequisitionId()) + "|" + title);
        }
        return sha256("t|" + company + "|" + title + "|" + locationToken(job));
    }

    /** Hash of the descriptive fields; a change sets last_changed in the upsert script. */
    public static String contentHash(final JobRecord job) {
        return sha256(String.join("\u0001",
                normalize(job.getTitle()),
                normalize(job.getCompany()),
                Objects.toString(job.getLocation(), ""),
                Objects.toString(job.getDescription(), "").replaceAll("\\s+", " ").trim(),
                Objects.toString(job.getEmploymentType(), ""),
                Objects.toString(job.getRemote(), "")));
    }

    static String sha256(final String value) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
