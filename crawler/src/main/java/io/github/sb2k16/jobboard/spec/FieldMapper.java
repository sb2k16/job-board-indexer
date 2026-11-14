/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import io.github.sb2k16.jobboard.adapter.Html;
import io.github.sb2k16.jobboard.model.JobRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Applies a spec's field mapping to one item. */
final class FieldMapper {

    static final Set<String> TRANSFORMS = Set.of("html_unescape", "html_to_text", "trim", "normalize_list");
    static final Set<String> TYPES = Set.of("string", "datetime", "boolean");
    static final Set<String> DATETIME_FORMATS = Set.of("auto", "iso", "epoch_millis", "epoch_seconds", "rfc1123");

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{([^{}]+)}");
    private static final List<DateTimeFormatter> TEXT_DATES = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.US),
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.US));

    /** Field name to setter. Type conversion happens before the setter is called. */
    static final Map<String, BiConsumer<JobRecord, Object>> SETTERS = Map.ofEntries(
            Map.entry("platform_job_id", (r, v) -> r.setPlatformJobId((String) v)),
            Map.entry("title", (r, v) -> r.setTitle((String) v)),
            Map.entry("company", (r, v) -> r.setCompany((String) v)),
            Map.entry("description", (r, v) -> r.setDescription((String) v)),
            Map.entry("location", (r, v) -> r.setLocation((String) v)),
            Map.entry("city", (r, v) -> r.setCity((String) v)),
            Map.entry("region", (r, v) -> r.setRegion((String) v)),
            Map.entry("country", (r, v) -> r.setCountry((String) v)),
            Map.entry("requisition_id", (r, v) -> r.setRequisitionId((String) v)),
            Map.entry("employment_type", (r, v) -> r.setEmploymentType((String) v)),
            Map.entry("remote", (r, v) -> r.setRemote((Boolean) v)),
            Map.entry("posted_at", (r, v) -> r.setPostedAt((Instant) v)),
            Map.entry("url", (r, v) -> r.setUrl((String) v)),
            Map.entry("application_url", (r, v) -> r.setApplicationUrl((String) v)));

    /** Fields whose spec type is fixed by the job record. */
    static final Map<String, String> FIELD_TYPES = Map.of("remote", "boolean", "posted_at", "datetime");

    private FieldMapper() {
    }

    static JobRecord map(final Map<String, FieldSpec> fields, final Page.Node item, final Map<String, String> vars) {
        final JobRecord record = new JobRecord();
        for (final Map.Entry<String, FieldSpec> e : fields.entrySet()) {
            final Object value = convert(e.getValue(), raw(e.getValue(), item, vars));
            if (value != null) {
                SETTERS.get(e.getKey()).accept(record, value);
            }
        }
        return record;
    }

    private static String raw(final FieldSpec spec, final Page.Node item, final Map<String, String> vars) {
        String value;
        if (spec.getValue() != null) {
            value = spec.getValue();
        } else if (spec.getTemplate() != null) {
            value = render(spec.getTemplate(), vars, item);
        } else if (spec.getConcat() != null) {
            final List<String> parts = new ArrayList<>();
            for (final String path : spec.getConcat()) {
                item.values(path).stream().filter(v -> !v.isBlank()).forEach(parts::add);
            }
            value = parts.isEmpty() ? null : String.join(spec.getJoin() != null ? spec.getJoin() : "\n\n", parts);
        } else {
            value = null;
            for (final String path : spec.getPath()) {
                final List<String> found = item.values(path).stream().filter(v -> !v.isBlank()).collect(Collectors.toList());
                if (!found.isEmpty()) {
                    value = String.join(spec.getJoin() != null ? spec.getJoin() : ", ", found);
                    break;
                }
            }
        }
        for (final String t : spec.getTransform()) {
            value = transform(t, value);
        }
        if (spec.getRegex() != null && value != null) {
            value = extract(spec.getRegex(), value, spec.isKeepUnmatched());
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Renders {name} variables: request variables first, then item paths. Returns null when an
     * item path in the template has no value, so a half-built URL is never emitted.
     */
    static String render(final String template, final Map<String, String> vars, final Page.Node item) {
        final Matcher m = TEMPLATE_VAR.matcher(template);
        final StringBuilder sb = new StringBuilder();
        while (m.find()) {
            final String name = m.group(1);
            String replacement = vars.get(name);
            if (replacement == null && item != null) {
                final List<String> found = item.values(name);
                replacement = found.isEmpty() ? null : found.get(0);
            }
            if (replacement == null) {
                return null;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final java.util.concurrent.ConcurrentMap<String, Pattern> REGEX_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** First capturing group of the first match (whole match when the pattern has no group). */
    static String extract(final String regex, final String value, final boolean keepUnmatched) {
        final Matcher m = REGEX_CACHE.computeIfAbsent(regex, r -> Pattern.compile(r, Pattern.DOTALL)).matcher(value);
        if (!m.find()) {
            return keepUnmatched ? value : null;
        }
        return m.groupCount() >= 1 ? m.group(1) : m.group();
    }

    static String transform(final String name, final String value) {
        if (value == null) {
            return null;
        }
        switch (name) {
            case "html_unescape":
                return Html.unescape(value);
            case "html_to_text":
                return Html.toText(value);
            case "trim":
                return value.trim();
            case "normalize_list":
                return Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining(", "));
            default:
                throw new IllegalArgumentException("Unknown transform " + name);
        }
    }

    private static Object convert(final FieldSpec spec, final String raw) {
        if (raw == null) {
            return null;
        }
        switch (spec.getType()) {
            case "datetime":
                return instant(raw.trim(), spec.getFormat());
            case "boolean":
                final String v = raw.trim().toLowerCase(Locale.ROOT);
                return spec.getTrueValues().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).equals(v));
            default:
                return raw;
        }
    }

    /** Unparseable dates are dropped (null), never guessed. */
    static Instant instant(final String raw, final String format) {
        try {
            switch (format) {
                case "epoch_millis":
                    return Instant.ofEpochMilli(Long.parseLong(raw));
                case "epoch_seconds":
                    return Instant.ofEpochSecond(Long.parseLong(raw));
                case "iso":
                    return iso(raw);
                case "rfc1123":
                    return text(raw);
                default:
                    if (raw.chars().allMatch(Character::isDigit)) {
                        final long n = Long.parseLong(raw);
                        return n > 100_000_000_000L ? Instant.ofEpochMilli(n) : Instant.ofEpochSecond(n);
                    }
                    final Instant iso = iso(raw);
                    return iso != null ? iso : text(raw);
            }
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** "+0000" written without its colon (Phenom). */
    private static final Pattern BARE_OFFSET = Pattern.compile("([+-]\\d{2})(\\d{2})$");
    /** "2026-04-08 00:00:00": a space where ISO has T (ApplicantPro, iSolvedHire, Recooty JSON-LD). */
    private static final Pattern SPACED = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2})");

    private static Instant iso(final String input) {
        final String raw = BARE_OFFSET.matcher(SPACED.matcher(input.trim()).replaceFirst("$1T$2")).replaceFirst("$1:$2");
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (final DateTimeParseException ignored) {
            // next form
        }
        try {
            return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
        } catch (final DateTimeParseException ignored) {
            // next form
        }
        try {
            return LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (final DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * RSS dates carry US zone abbreviations. Java resolves "EST" to America/New_York, which turns
     * a summer "00:00 EST" into EDT; the abbreviation means a fixed offset, so map it to one.
     */
    private static final Map<String, String> US_ZONES = Map.of(
            "EST", "-0500", "EDT", "-0400", "CST", "-0600", "CDT", "-0500",
            "MST", "-0700", "MDT", "-0600", "PST", "-0800", "PDT", "-0700");
    private static final Pattern TRAILING_ZONE = Pattern.compile("\\s([ECMP][SD]T)$");

    private static Instant text(final String raw) {
        final Matcher zone = TRAILING_ZONE.matcher(raw);
        final String value = zone.find() ? raw.substring(0, zone.start()) + " " + US_ZONES.get(zone.group(1)) : raw;
        for (final DateTimeFormatter f : TEXT_DATES) {
            try {
                return ZonedDateTime.parse(value, f).toInstant();
            } catch (final DateTimeParseException ignored) {
                // next form
            }
        }
        return null;
    }
}
