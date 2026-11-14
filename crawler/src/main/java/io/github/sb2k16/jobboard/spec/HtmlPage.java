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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A server-rendered HTML page queried with regular expressions. Paths are regexes; a path's value
 * is the first capturing group of each match (the whole match when there is none). Items are the
 * distinct first-group values of the items regex, typically links to job detail pages. Item paths:
 * {@code .} the captured value, {@code url} it resolved against the page URL, {@code 1}, {@code 2}...
 * other groups of the same match.
 */
final class HtmlPage implements Page {
    private static final ConcurrentMap<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private final String body;
    private final URI base;

    HtmlPage(final String body, final String pageUrl) {
        this.body = body;
        this.base = URI.create(pageUrl);
    }

    static Pattern pattern(final String regex) {
        return CACHE.computeIfAbsent(regex, r -> Pattern.compile(r, Pattern.DOTALL));
    }

    @Override
    public boolean exists(final String path) {
        return pattern(path).matcher(body).find();
    }

    @Override
    public List<Node> items(final String path) {
        final Matcher m = pattern(path).matcher(body);
        final Set<String> seen = new LinkedHashSet<>();
        final List<Node> items = new ArrayList<>();
        while (m.find()) {
            final String value = Html.unescape(m.groupCount() >= 1 ? m.group(1) : m.group());
            if (value == null || !seen.add(value)) {
                continue;
            }
            final List<String> groups = new ArrayList<>();
            for (int g = 0; g <= m.groupCount(); g++) {
                groups.add(m.group(g));
            }
            items.add(p -> itemValue(p, value, groups));
        }
        return items;
    }

    private List<String> itemValue(final String path, final String value, final List<String> groups) {
        final String p = path == null ? "." : path.trim();
        if (p.equals(".") || p.equals("$") || p.isEmpty()) {
            return List.of(value);
        }
        if (p.equals("url")) {
            return List.of(base.resolve(value.replace(" ", "%20")).toString());
        }
        if (p.chars().allMatch(Character::isDigit)) {
            final int g = Integer.parseInt(p);
            final String v = g < groups.size() ? groups.get(g) : null;
            return v == null ? List.of() : List.of(Html.unescape(v));
        }
        return List.of();
    }

    @Override
    public List<String> values(final String path) {
        final Matcher m = pattern(path).matcher(body);
        final List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(Html.unescape(m.groupCount() >= 1 ? m.group(1) : m.group()));
        }
        return out;
    }
}
