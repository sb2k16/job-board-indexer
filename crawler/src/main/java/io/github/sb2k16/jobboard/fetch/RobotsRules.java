/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.fetch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * robots.txt under RFC 9309: the group for our product token wins over {@code *}; within a group
 * the longest matching rule decides and a tie goes to allow. Python's urllib.robotparser applies
 * rules in file order instead, which wrongly blocked Eightfold and UltiPro in Phase 0.
 */
public final class RobotsRules {
    public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), null);
    public static final RobotsRules DISALLOW_ALL = new RobotsRules(List.of(new Rule(false, "/")), null);

    private final List<Rule> rules;
    private final Duration crawlDelay;

    private RobotsRules(final List<Rule> rules, final Duration crawlDelay) {
        this.rules = rules;
        this.crawlDelay = crawlDelay;
    }

    /** Crawl-delay of the matched group, or null. Not part of RFC 9309, but honoured. */
    public Duration getCrawlDelay() {
        return crawlDelay;
    }

    /** @param pathAndQuery the URL path plus query, e.g. {@code /v0/postings/x?mode=json} */
    public boolean allows(final String pathAndQuery) {
        final String path = pathAndQuery == null || pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        if (path.equals("/robots.txt")) {
            return true;
        }
        Rule best = null;
        for (final Rule rule : rules) {
            if (rule.matches(path) && (best == null || rule.length() > best.length()
                    || (rule.length() == best.length() && rule.allow))) {
                best = rule;
            }
        }
        return best == null || best.allow;
    }

    /** Parses a robots.txt body for the given product token (case-insensitive, e.g. "JobBoardIndexer"). */
    public static RobotsRules parse(final String body, final String productToken) {
        final String token = productToken.toLowerCase(Locale.ROOT);
        final List<Group> groups = new ArrayList<>();
        Group current = null;
        boolean lastWasAgent = false;
        for (final String rawLine : body.split("\\r?\\n|\\r")) {
            final int hash = rawLine.indexOf('#');
            final String line = (hash >= 0 ? rawLine.substring(0, hash) : rawLine).trim();
            final int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            final String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            final String value = line.substring(colon + 1).trim();
            switch (key) {
                case "user-agent":
                    if (current == null || !lastWasAgent) {
                        current = new Group();
                        groups.add(current);
                    }
                    current.agents.add(value.toLowerCase(Locale.ROOT));
                    lastWasAgent = true;
                    break;
                case "allow":
                case "disallow":
                    lastWasAgent = false;
                    if (current != null && !value.isEmpty()) {
                        current.rules.add(new Rule(key.equals("allow"), value));
                    }
                    break;
                case "crawl-delay":
                    lastWasAgent = false;
                    if (current != null) {
                        try {
                            current.crawlDelay = Duration.ofMillis((long) (Double.parseDouble(value) * 1000));
                        } catch (final NumberFormatException ignored) {
                            // malformed delay: ignore the line
                        }
                    }
                    break;
                default:
                    lastWasAgent = false; // sitemap and unknown lines end a run of user-agent lines
            }
        }
        // RFC 9309 2.2.1: every group naming our token is merged; otherwise every "*" group.
        List<Group> matched = groups.stream().filter(g -> g.agents.stream().anyMatch(a -> a.equals(token)))
                .collect(java.util.stream.Collectors.toList());
        if (matched.isEmpty()) {
            matched = groups.stream().filter(g -> g.agents.contains("*")).collect(java.util.stream.Collectors.toList());
        }
        final List<Rule> rules = new ArrayList<>();
        Duration delay = null;
        for (final Group g : matched) {
            rules.addAll(g.rules);
            if (g.crawlDelay != null && (delay == null || g.crawlDelay.compareTo(delay) > 0)) {
                delay = g.crawlDelay;
            }
        }
        return new RobotsRules(rules, delay);
    }

    private static final class Group {
        final List<String> agents = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
        Duration crawlDelay;
    }

    private static final class Rule {
        final boolean allow;
        final String pattern;
        final Pattern regex;

        Rule(final boolean allow, final String pattern) {
            this.allow = allow;
            this.pattern = pattern;
            final boolean anchored = pattern.endsWith("$");
            final String body = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
            final StringBuilder re = new StringBuilder();
            final StringBuilder literal = new StringBuilder();
            for (final char c : body.toCharArray()) {
                if (c == '*') {
                    if (literal.length() > 0) {
                        re.append(Pattern.quote(literal.toString()));
                        literal.setLength(0);
                    }
                    re.append(".*");
                } else {
                    literal.append(c);
                }
            }
            if (literal.length() > 0) {
                re.append(Pattern.quote(literal.toString()));
            }
            // A rule matches a path prefix unless it ends with "$".
            this.regex = Pattern.compile(re + (anchored ? "" : ".*"), Pattern.DOTALL);
        }

        boolean matches(final String path) {
            return regex.matcher(path).matches();
        }

        int length() {
            return pattern.length();
        }
    }
}
