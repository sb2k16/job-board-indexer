/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.sb2k16.jobboard.adapter.Html;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The schema.org JobPosting in a page's {@code <script type="application/ld+json">} blocks. */
final class JsonLd {
    private static final Pattern SCRIPT = Pattern.compile(
            // The + is sometimes HTML-encoded in the attribute (WeRecruit: ld&#x2B;json); browsers decode it.
            "<script[^>]*type\\s*=\\s*[\"']?application/ld(?:\\+|&#x2B;|&#43;|%2B)json[\"']?[^>]*>(.*?)</script>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /**
     * Pages put raw newlines and tabs inside strings, and escape characters JSON does not need
     * escaped (Jobvite writes "Pilgrim\\'s"); browsers accept both, so do we.
     */
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private JsonLd() {
    }

    /**
     * The first JobPosting on the page with derived fields the standard mapping reads, because the
     * vocabulary allows several shapes: {@code _location} (every place, "City, Region, Country",
     * joined by "; "), {@code _city}, {@code _region}, {@code _country} (first place), {@code _company},
     * {@code _identifier}, {@code _remote} ("true" for TELECOMMUTE). Null when there is none.
     */
    static JsonNode normalized(final String html) {
        final JsonNode posting = jobPosting(html);
        if (posting == null) {
            return null;
        }
        final ObjectNode out = ((ObjectNode) posting).deepCopy();
        final List<JsonNode> places = new ArrayList<>();
        final JsonNode loc = posting.get("jobLocation");
        if (loc != null && loc.isArray()) {
            loc.forEach(places::add);
        } else if (loc != null && loc.isObject()) {
            places.add(loc);
        }
        final Set<String> labels = new LinkedHashSet<>();
        for (final JsonNode place : places) {
            final JsonNode address = place.has("address") ? place.get("address") : place;
            final String city = text(address.get("addressLocality"));
            final String region = text(address.get("addressRegion"));
            final String country = name(address.get("addressCountry"));
            final List<String> parts = new ArrayList<>();
            for (final String part : new String[] {city, region, country}) {
                if (part != null && !part.isBlank() && !parts.contains(part)) {
                    parts.add(part);
                }
            }
            if (!parts.isEmpty()) {
                labels.add(String.join(", ", parts));
            }
            if (!out.has("_city") && city != null) {
                out.put("_city", city);
            }
            if (!out.has("_region") && region != null) {
                out.put("_region", region);
            }
            if (!out.has("_country") && country != null) {
                out.put("_country", country);
            }
        }
        if (!labels.isEmpty()) {
            out.put("_location", String.join("; ", labels));
        }
        final String company = name(posting.get("hiringOrganization"));
        if (company != null) {
            out.put("_company", company);
        }
        final JsonNode id = posting.get("identifier");
        final String identifier = id == null ? null : id.isObject() ? text(id.get("value")) : text(id);
        if (identifier != null) {
            out.put("_identifier", identifier);
        }
        final JsonNode type = posting.get("jobLocationType");
        if (type != null && type.toString().toUpperCase().contains("TELECOMMUTE")) {
            out.put("_remote", "true");
        }
        return out;
    }

    private static String text(final JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isArray()) {
            return n.size() == 0 ? null : text(n.get(0));
        }
        final String t = n.isValueNode() ? n.asText().trim() : null;
        return t == null || t.isEmpty() ? null : Html.unescape(t);
    }

    /** A Thing given as text, or as an object with a name. */
    private static String name(final JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isArray()) {
            return n.size() == 0 ? null : name(n.get(0));
        }
        return n.isObject() ? text(n.get("name")) : text(n);
    }

    /** The first JobPosting object on the page, or null. */
    static JsonNode jobPosting(final String html) {
        final Matcher m = SCRIPT.matcher(html);
        while (m.find()) {
            final String text = m.group(1).trim();
            JsonNode root;
            try {
                root = LENIENT.readTree(text);
            } catch (final Exception e) {
                try {
                    root = LENIENT.readTree(Html.unescape(text));
                } catch (final Exception e2) {
                    continue;
                }
            }
            final JsonNode found = find(root);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static JsonNode find(final JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            for (final JsonNode n : node) {
                final JsonNode f = find(n);
                if (f != null) {
                    return f;
                }
            }
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        if (isJobPosting(node.get("@type"))) {
            return node;
        }
        return find(node.get("@graph"));
    }

    private static boolean isJobPosting(final JsonNode type) {
        if (type == null) {
            return false;
        }
        final List<String> types = new ArrayList<>();
        if (type.isArray()) {
            type.forEach(t -> types.add(t.asText()));
        } else {
            types.add(type.asText());
        }
        return types.stream().anyMatch(t -> t.equals("JobPosting") || t.endsWith("/JobPosting"));
    }
}
