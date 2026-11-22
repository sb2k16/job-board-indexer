/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.closure;

import io.github.sb2k16.jobboard.ClosureConfig;
import io.github.sb2k16.jobboard.adapter.BoardFetchException;
import io.github.sb2k16.jobboard.adapter.HttpFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Job page verification: the evidence the sweeper cannot get on a board that is never read
 * completely. The sweeper closes a link only after three complete snapshots miss it, so on a board
 * whose list is capped (Workday CVS, Phenom past 10,000, a feed of the newest 50) removed jobs stayed
 * open forever. After a partial crawl, this checks the board's oldest unseen live links on their own
 * pages, through the platform's fetcher (robots.txt, rate limits, breaker):
 *
 * <ul>
 *   <li>404 or 410, or the page says the job is gone: the link goes (and the job closes when it was
 *       its last live link);</li>
 *   <li>the page answers and carries the job's title: the link is seen now (last_seen, last_checked);</li>
 *   <li>anything else (errors, robots, a page we cannot read): nothing changes, it is tried again.</li>
 * </ul>
 *
 * Time alone never closes anything: an unseen job on a failing or capped board is usually still open.
 */
public class JobVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(JobVerifier.class);

    /** Wording job pages use once a posting is down. Matched case-insensitively. */
    static final Pattern GONE = Pattern.compile(String.join("|",
            "no longer (?:available|accepting applications|accepting candidates|open|active|exists)",
            "(?:job|position|posting|vacancy|role|opening) (?:has been|was|is) (?:filled|closed|removed|expired)",
            "(?:job|position|posting|vacancy|opening) (?:has )?expired",
            "(?:job|position|posting) (?:not found|could not be found|does not exist)",
            "this (?:job|position|posting) is (?:closed|no longer)",
            "applications? (?:are|is) (?:now )?closed"), Pattern.CASE_INSENSITIVE);

    /** Consecutive pages that could not be read before the run stops: the host is having trouble. */
    static final int STOP_AFTER_ERRORS = 5;

    enum Verdict { GONE, ALIVE, UNKNOWN }

    /** What one run did. */
    public static final class Result {
        final int checked;
        final int gone;
        final int alive;
        final int unknown;
        final boolean held;

        Result(final int checked, final int gone, final int alive, final int unknown, final boolean held) {
            this.checked = checked;
            this.gone = gone;
            this.alive = alive;
            this.unknown = unknown;
            this.held = held;
        }

        public int getChecked() { return checked; }
        public int getGone() { return gone; }
        public int getAlive() { return alive; }
        public int getUnknown() { return unknown; }
        public boolean isHeld() { return held; }
    }

    private final ClosureConfig config;
    private final IndexClient index;

    public JobVerifier(final ClosureConfig config, final IndexClient index) {
        this.config = config;
        this.index = index;
    }

    /** After a partial crawl of the board. Never throws for a page; index errors propagate. */
    public Result verify(final String platform, final String board, final HttpFetcher fetcher, final Instant now,
                         final Commands out) {
        final int max = config.getVerifyMaxPerCrawl();
        if (max <= 0) {
            return new Result(0, 0, 0, 0, false);
        }
        final Instant cutoff = now.minus(config.getVerifyAfter());
        final List<Map<String, Object>> docs = index.search(candidates(platform, board, cutoff), max,
                List.of("job_key", "title", "source_links"), List.of(Map.of("last_checked", Map.of("order", "asc"))));

        final Map<String, String> gone = new LinkedHashMap<>();   // job_key -> link url
        final Map<String, String> alive = new LinkedHashMap<>();
        int unknown = 0;
        int errorsInARow = 0;
        for (final Map<String, Object> doc : docs) {
            final String url = staleLink(doc, platform, board, cutoff);
            if (url == null) {
                continue; // matched on fields of different links (source_links is not nested)
            }
            final String jobKey = String.valueOf(doc.get("job_key"));
            final Verdict v;
            try {
                v = judge(fetcher.get(url), (String) doc.get("title"));
                errorsInARow = 0;
            } catch (final BoardFetchException e) {
                if (e.isBoardGone()) {
                    gone.put(jobKey, url);
                    errorsInARow = 0;
                    continue;
                }
                unknown++;
                if (++errorsInARow >= STOP_AFTER_ERRORS) {
                    LOG.info("{}/{}: verification stopped after {} unreadable job pages in a row ({})", platform, board,
                            errorsInARow, e.getMessage());
                    break;
                }
                continue;
            }
            if (v == Verdict.GONE) {
                gone.put(jobKey, url);
            } else if (v == Verdict.ALIVE) {
                alive.put(jobKey, url);
            } else {
                unknown++;
            }
        }

        final int checked = gone.size() + alive.size() + unknown;
        final boolean held = gone.size() >= config.getMassClosureMinJobs() && gone.size() > config.getVerifyGoneRatio() * checked;
        if (held) {
            LOG.warn("{}/{}: verification held: {} of {} job pages read as gone; closing none", platform, board, gone.size(), checked);
        } else {
            apply(platform, board, gone, "gone", now, out);
        }
        apply(platform, board, alive, "alive", now, out);
        if (checked > 0) {
            LOG.info("{}/{}: verified {} job pages unseen since {}: {} gone{}, {} still listed, {} unreadable", platform, board,
                    checked, cutoff, gone.size(), held ? " (held)" : "", alive.size(), unknown);
        }
        return new Result(checked, gone.size(), alive.size(), unknown, held);
    }

    /** GONE if the page says so; ALIVE if it carries the job's title; otherwise UNKNOWN. */
    static Verdict judge(final String body, final String title) {
        if (body == null) {
            return Verdict.UNKNOWN;
        }
        if (GONE.matcher(body).find()) {
            return Verdict.GONE;
        }
        final String t = normalize(title);
        if (t.length() >= 4 && normalize(body).contains(t)) {
            return Verdict.ALIVE;
        }
        return Verdict.UNKNOWN;
    }

    private static String normalize(final String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("&amp;", "&").replaceAll("&#0?39;|&rsquo;|’", "'")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    static Map<String, Object> candidates(final String platform, final String board, final Instant cutoff) {
        return Map.of("bool", Map.of("filter", List.of(
                Map.of("term", Map.of("status", "open")),
                Map.of("term", Map.of("source_links.platform", platform)),
                Map.of("term", Map.of("source_links.board_id", board)),
                Map.of("term", Map.of("source_links.state", "live")),
                Map.of("range", Map.of("source_links.last_seen", Map.of("lt", cutoff.toString()))))));
    }

    /** The doc's live link on this board that has gone unseen since the cutoff, or null. */
    @SuppressWarnings("unchecked")
    static String staleLink(final Map<String, Object> doc, final String platform, final String board, final Instant cutoff) {
        final Object links = doc.get("source_links");
        if (!(links instanceof List)) {
            return null;
        }
        for (final Object o : (List<Object>) links) {
            if (!(o instanceof Map)) {
                continue;
            }
            final Map<String, Object> l = (Map<String, Object>) o;
            final Object seen = l.get("last_seen");
            final Object url = l.get("url");
            if (platform.equals(l.get("platform")) && board.equals(l.get("board_id")) && "live".equals(l.get("state"))
                    && seen != null && url instanceof String && ((String) url).matches("(?i)https?://.+")) {
                try {
                    if (Instant.parse(String.valueOf(seen)).isBefore(cutoff)) {
                        return (String) url;
                    }
                } catch (final RuntimeException e) {
                    // unparseable date: leave it to the sweeper
                }
            }
        }
        return null;
    }

    private static void apply(final String platform, final String board, final Map<String, String> byJob, final String verdict,
                              final Instant now, final Commands out) {
        if (byJob.isEmpty()) {
            return;
        }
        final List<Map<String, Object>> commands = new ArrayList<>(byJob.size());
        byJob.forEach((jobKey, url) -> commands.add(Commands.verify(jobKey, platform, board, url, verdict, now)));
        out.write(commands);
    }
}
