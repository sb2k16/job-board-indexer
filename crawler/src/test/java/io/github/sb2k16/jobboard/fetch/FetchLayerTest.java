/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.fetch;

import io.github.sb2k16.jobboard.adapter.BoardFetchException;
import io.github.sb2k16.jobboard.adapter.HttpFetcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FetchLayerTest {

    /** Time only moves when someone sleeps. */
    static final class FakeTicker implements Ticker {
        long now = 1_000_000_000L;
        final List<Duration> sleeps = new ArrayList<>();

        @Override
        public long nanos() {
            return now;
        }

        @Override
        public void sleep(final Duration d) {
            sleeps.add(d);
            now += d.toNanos();
        }
    }

    /** Scripted responses per URL; an IOException entry simulates a timeout. */
    static final class FakeTransport implements HttpTransport {
        final Map<String, Deque<Object>> script = new HashMap<>();
        final List<String> requests = new ArrayList<>();

        FakeTransport on(final String url, final Object... responses) {
            script.computeIfAbsent(url, u -> new ArrayDeque<>()).addAll(List.of(responses));
            return this;
        }

        @Override
        public Response get(final String url, final Duration timeout) throws IOException {
            requests.add(url);
            final Deque<Object> q = script.get(url);
            final Object next = q == null || q.isEmpty() ? new Response(404, "", null) : (q.size() > 1 ? q.poll() : q.peek());
            if (next instanceof IOException) {
                throw (IOException) next;
            }
            return (Response) next;
        }
    }

    static HttpTransport.Response ok(final String body) {
        return new HttpTransport.Response(200, body, null);
    }

    static HttpTransport.Response status(final int code) {
        return new HttpTransport.Response(code, "", null);
    }

    private final FakeTicker ticker = new FakeTicker();
    private final FakeTransport transport = new FakeTransport();

    private FetchPolicy policy(final double rps, final int replicas, final int attempts) {
        return new FetchPolicy(rps, 2, replicas, attempts, Duration.ofSeconds(1), Duration.ofSeconds(30),
                Duration.ofSeconds(60), Duration.ofSeconds(5));
    }

    private HttpFetcher fetcher(final FetchPolicy policy) {
        return new FetcherFactory(transport, ticker, Duration.ofHours(24), Duration.ofSeconds(5), 3, Duration.ofMinutes(5))
                .forPlatform("p", policy);
    }

    // ---- robots.txt ---------------------------------------------------------------------------

    @Test
    void robots_longestMatchWins_notFileOrder() {
        // Eightfold-style file from Phase 0: a blanket Disallow first, then specific Allows.
        final RobotsRules r = RobotsRules.parse("User-agent: *\nDisallow: /\nAllow: /careers\nAllow: /api/apply/\n", "JobBoardIndexer");
        assertThat(r.allows("/api/apply/v2/jobs?domain=x"), equalTo(true));
        assertThat(r.allows("/careers"), equalTo(true));
        assertThat(r.allows("/admin"), equalTo(false));
        assertThat(r.allows("/robots.txt"), equalTo(true));
    }

    @Test
    void robots_wildcardsAnchorsAndTies() {
        // UltiPro-style: */JobBoard/ allowed, */JobBoardView disallowed.
        final RobotsRules r = RobotsRules.parse("User-agent: *\nAllow: */JobBoard/\nDisallow: */JobBoardView\nDisallow: /*.pdf$\n"
                + "Allow: /tie\nDisallow: /tie\n", "JobBoardIndexer");
        assertThat(r.allows("/X/JobBoard/abc"), equalTo(true));
        assertThat(r.allows("/X/JobBoardView/1"), equalTo(false));
        assertThat(r.allows("/files/a.pdf"), equalTo(false));
        assertThat(r.allows("/files/a.pdf?x=1"), equalTo(true));
        assertThat(r.allows("/tie"), equalTo(true));     // equal length: allow wins
    }

    @Test
    void robots_ownGroupBeatsStar_andCrawlDelay() {
        final String body = "User-agent: *\nDisallow: /\n\nUser-agent: Googlebot\nUser-agent: jobboardindexer\nAllow: /jobs\nCrawl-delay: 2\n"
                + "Disallow: /\n# comment\nSitemap: https://x/s.xml\n";
        final RobotsRules ours = RobotsRules.parse(body, "JobBoardIndexer");
        assertThat(ours.allows("/jobs/1"), equalTo(true));
        assertThat(ours.allows("/other"), equalTo(false));
        assertThat(ours.getCrawlDelay(), equalTo(Duration.ofSeconds(2)));
        final RobotsRules other = RobotsRules.parse(body, "SomeoneElse");
        assertThat(other.allows("/jobs/1"), equalTo(false));
        assertThat(RobotsRules.parse("User-agent: *\nDisallow:\n", "x").allows("/a"), equalTo(true)); // empty Disallow
    }

    @Test
    void robots_disallowedUrl_isBlockedWithoutRequest() {
        transport.on("https://h.test/robots.txt", ok("User-agent: *\nDisallow: /private/\n"));
        final BoardFetchException e = assertThrows(BoardFetchException.class, () -> fetcher(policy(10, 1, 3)).get("https://h.test/private/jobs"));
        assertThat(e.getKind(), equalTo(BoardFetchException.Kind.ROBOTS));
        assertThat(e.isRetryable(), equalTo(false));
        assertThat(transport.requests, equalTo(List.of("https://h.test/robots.txt")));
    }

    @Test
    void robots_404MeansAllowAll_5xxMeansRetryLater_cachedPerHost() {
        transport.on("https://a.test/robots.txt", status(404)).on("https://a.test/jobs", ok("A"));
        final HttpFetcher f = fetcher(policy(10, 1, 1));
        assertThat(f.get("https://a.test/jobs"), equalTo("A"));
        assertThat(f.get("https://a.test/jobs"), equalTo("A"));
        assertThat(transport.requests.stream().filter(u -> u.endsWith("robots.txt")).count(), equalTo(1L));

        transport.on("https://b.test/robots.txt", status(503));
        final BoardFetchException e = assertThrows(BoardFetchException.class, () -> f.get("https://b.test/jobs"));
        assertThat(e.getKind(), equalTo(BoardFetchException.Kind.ROBOTS));
        assertThat(e.isRetryable(), equalTo(true));
        assertThat(transport.requests.contains("https://b.test/jobs"), equalTo(false));
    }

    // ---- rate limit ---------------------------------------------------------------------------

    @Test
    void rateLimit_isPerPlatformAndSplitAcrossReplicas() {
        transport.on("https://h.test/robots.txt", status(404)).on("https://h.test/a", ok("x")).on("https://h.test/b", ok("y"));
        final HttpFetcher f = fetcher(policy(2, 3, 1));    // 2 rps platform-wide, 3 pods: one request per 1.5 s here
        f.get("https://h.test/a");
        f.get("https://h.test/b");
        f.get("https://h.test/a");
        assertThat(ticker.sleeps, equalTo(List.of(Duration.ofMillis(1500), Duration.ofMillis(1500))));
    }

    @Test
    void crawlDelay_appliesPerHost_scaledByReplicas() {
        transport.on("https://h.test/robots.txt", ok("User-agent: *\nCrawl-delay: 1\n")).on("https://h.test/a", ok("x"));
        final HttpFetcher f = fetcher(policy(100, 2, 1));
        f.get("https://h.test/a");
        f.get("https://h.test/a");
        final long slept = ticker.sleeps.stream().mapToLong(Duration::toMillis).sum();
        assertThat(slept, equalTo(2000L));                  // Crawl-delay 1 s x 2 pods
    }

    // ---- retries, backoff, breaker ------------------------------------------------------------

    @Test
    void retries_5xx429AndTimeouts_thenSucceeds() {
        transport.on("https://h.test/robots.txt", status(404))
                .on("https://h.test/a", status(503), new SocketTimeoutException("read timed out"), ok("finally"));
        assertThat(fetcher(policy(100, 1, 3)).get("https://h.test/a"), equalTo("finally"));
        assertThat(transport.requests.stream().filter(u -> u.endsWith("/a")).count(), equalTo(3L));
    }

    @Test
    void retries_exhausted_throwsRetryableWithLastCause() {
        transport.on("https://h.test/robots.txt", status(404)).on("https://h.test/a", status(500));
        final BoardFetchException e = assertThrows(BoardFetchException.class, () -> fetcher(policy(100, 1, 3)).get("https://h.test/a"));
        assertThat(e.getStatus(), equalTo(500));
        assertThat(e.isRetryable(), equalTo(true));
        assertThat(transport.requests.stream().filter(u -> u.endsWith("/a")).count(), equalTo(3L));
    }

    @Test
    void notFound_isNotRetried() {
        transport.on("https://h.test/robots.txt", status(404)).on("https://h.test/a", status(404));
        final BoardFetchException e = assertThrows(BoardFetchException.class, () -> fetcher(policy(100, 1, 3)).get("https://h.test/a"));
        assertThat(e.isBoardGone(), equalTo(true));
        assertThat(transport.requests.stream().filter(u -> u.endsWith("/a")).count(), equalTo(1L));
    }

    @Test
    void retryAfter_isHonoured_andTooLongEndsTheFetch() {
        transport.on("https://h.test/robots.txt", status(404))
                .on("https://h.test/a", new HttpTransport.Response(429, "", Duration.ofSeconds(20)), ok("x"));
        assertThat(fetcher(policy(100, 1, 3)).get("https://h.test/a"), equalTo("x"));
        assertThat(ticker.sleeps.get(ticker.sleeps.size() - 1), equalTo(Duration.ofSeconds(20)));

        transport.on("https://h.test/b", new HttpTransport.Response(429, "", Duration.ofSeconds(600)), ok("never"));
        assertThrows(BoardFetchException.class, () -> fetcher(policy(100, 1, 3)).get("https://h.test/b"));
        assertThat(transport.requests.stream().filter(u -> u.endsWith("/b")).count(), equalTo(1L));
    }

    @Test
    void backoff_isFullJitterUnderExponentialCap() {
        final PoliteFetcher f = (PoliteFetcher) fetcher(policy(100, 1, 3));
        for (int i = 0; i < 200; i++) {
            assertThat(f.backoff(1, null).toMillis(), lessThanOrEqualTo(1000L));
            assertThat(f.backoff(3, null).toMillis(), lessThanOrEqualTo(4000L));
            assertThat(f.backoff(10, null).toMillis(), lessThanOrEqualTo(30_000L));
            assertThat(f.backoff(1, Duration.ofSeconds(7)).toMillis(), greaterThanOrEqualTo(7000L));
        }
    }

    @Test
    void breaker_opensAfterThresholdFailedFetches_thenRecovers() {
        transport.on("https://h.test/robots.txt", status(404)).on("https://h.test/a", status(502));
        final HttpFetcher f = fetcher(policy(100, 1, 1));   // threshold 3 in fetcher()
        for (int i = 0; i < 3; i++) {
            assertThrows(BoardFetchException.class, () -> f.get("https://h.test/a"));
        }
        final int sent = transport.requests.size();
        final BoardFetchException open = assertThrows(BoardFetchException.class, () -> f.get("https://h.test/a"));
        assertThat(open.getKind(), equalTo(BoardFetchException.Kind.CIRCUIT_OPEN));
        assertThat(open.getMessage(), containsString("circuit open"));
        assertThat(transport.requests.size(), equalTo(sent)); // nothing sent while open

        ticker.now += Duration.ofMinutes(6).toNanos();
        transport.script.put("https://h.test/a", new ArrayDeque<>(List.of(ok("back"))));
        assertThat(f.get("https://h.test/a"), equalTo("back"));
        assertThat(f.get("https://h.test/a"), equalTo("back"));
    }

    @Test
    void breaker_ignores404s_whichAreAboutTheBoardNotTheHost() {
        transport.on("https://h.test/robots.txt", status(404)).on("https://h.test/gone", status(404)).on("https://h.test/a", ok("x"));
        final HttpFetcher f = fetcher(policy(100, 1, 1));
        for (int i = 0; i < 5; i++) {
            assertThrows(BoardFetchException.class, () -> f.get("https://h.test/gone"));
        }
        assertThat(f.get("https://h.test/a"), equalTo("x"));
    }
}
