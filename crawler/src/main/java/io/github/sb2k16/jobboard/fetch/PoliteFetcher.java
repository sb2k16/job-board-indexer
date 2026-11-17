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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The fetcher an adapter gets for one platform. Each request: robots.txt must allow the URL; the
 * host's circuit must be closed; the platform's concurrency slot and rate slot (and the host's
 * Crawl-delay) are taken; then 429, 5xx and network failures are retried with exponential
 * backoff and full jitter, honouring Retry-After. Other 4xx fail at once. A fetch that still
 * fails after its retries counts once against the host's breaker.
 */
public class PoliteFetcher implements HttpFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(PoliteFetcher.class);

    private final String platform;
    private final FetchPolicy policy;
    private final HttpTransport transport;
    private final RobotsCache robots;
    private final CircuitBreaker breaker;
    private final ConcurrentMap<String, RateLimiter> hostDelays;
    private final RateLimiter platformLimiter;
    /** Hosts that answered 429: their own, slower spacing (see {@link #throttle}). */
    private final ConcurrentMap<String, Throttle> throttles = new java.util.concurrent.ConcurrentHashMap<>();
    private final Semaphore concurrency;
    private final Ticker ticker;

    PoliteFetcher(final String platform, final FetchPolicy policy, final HttpTransport transport, final RobotsCache robots,
                  final CircuitBreaker breaker, final ConcurrentMap<String, RateLimiter> hostDelays, final Ticker ticker) {
        this.platform = platform;
        this.policy = policy;
        this.transport = transport;
        this.robots = robots;
        this.breaker = breaker;
        this.hostDelays = hostDelays;
        this.ticker = ticker;
        this.platformLimiter = new RateLimiter(policy.podInterval(), ticker);
        this.concurrency = new Semaphore(policy.podConcurrency(), true);
    }

    @Override
    public String get(final String url) {
        try {
            return fetch(url, null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BoardFetchException("GET " + url + " interrupted", e, true, BoardFetchException.Kind.NETWORK, 0);
        }
    }

    @Override
    public String post(final String url, final String jsonBody) {
        try {
            return fetch(url, jsonBody);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BoardFetchException("POST " + url + " interrupted", e, true, BoardFetchException.Kind.NETWORK, 0);
        }
    }

    /** GET when body is null, else POST; robots.txt, limits, retries and the breaker apply to both. */
    private String fetch(final String url, final String body) throws InterruptedException {
        final URI uri = URI.create(url);
        final String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        final String pathAndQuery = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());

        final RobotsRules rules = robots.rulesFor(origin);
        if (!rules.allows(pathAndQuery)) {
            throw new BoardFetchException(platform + ": robots.txt on " + origin + " disallows " + pathAndQuery,
                    null, false, BoardFetchException.Kind.ROBOTS, 0);
        }
        final RateLimiter hostDelay = rules.getCrawlDelay() == null ? null
                : hostDelays.computeIfAbsent(origin, o -> new RateLimiter(
                        rules.getCrawlDelay().multipliedBy(policy.getReplicas()), ticker));

        BoardFetchException last = null;
        for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
            final Duration open = breaker.openFor(origin);
            if (!open.isZero()) {
                throw new BoardFetchException(platform + ": circuit open for " + origin + " (" + open.toSeconds() + "s left)",
                        null, true, BoardFetchException.Kind.CIRCUIT_OPEN, 0);
            }
            Duration retryAfter = null;
            concurrency.acquire();
            try {
                platformLimiter.acquire();
                if (hostDelay != null) {
                    hostDelay.acquire();
                }
                final Throttle throttled = throttles.get(origin);
                if (throttled != null) {
                    throttled.limiter.acquire();
                }
                final HttpTransport.Response response = body == null ? transport.get(url, policy.getRequestTimeout())
                        : transport.post(url, body, policy.getRequestTimeout());
                final int status = response.getStatus();
                if (status >= 200 && status < 300) {
                    recovered(origin);
                    breaker.success(origin);
                    return response.getBody();
                }
                last = BoardFetchException.http(url, status);
                if (!last.isRetryable()) {
                    breaker.success(origin); // the host answered; a 404 is about the board, not the host
                    throw last;
                }
                retryAfter = response.getRetryAfter().orElse(null);
                if (status == 429) {
                    throttle(origin);
                    if (retryAfter == null) {
                        retryAfter = throttleFor(origin); // no Retry-After: wait at least the new spacing
                    }
                }
            } catch (final IOException e) {
                last = new BoardFetchException("GET " + url + " failed: " + e.getMessage(), e, true,
                        BoardFetchException.Kind.NETWORK, 0);
            } finally {
                concurrency.release();
            }
            if (attempt < policy.getMaxAttempts()) {
                if (retryAfter != null && retryAfter.compareTo(policy.getMaxRetryAfter()) > 0) {
                    break; // the platform asks us to stay away longer than a retry is worth; the next refresh tries again
                }
                final Duration wait = backoff(attempt, retryAfter);
                LOG.info("{}: attempt {} of {} for {} failed ({}); retrying in {} ms", platform, attempt,
                        policy.getMaxAttempts(), url, last.getMessage(), wait.toMillis());
                ticker.sleep(wait);
            }
        }
        breaker.failure(origin);
        throw last;
    }

    /** Full jitter: uniform in [0, min(cap, base * 2^(attempt-1))], but never below Retry-After. */
    Duration backoff(final int attempt, final Duration retryAfter) {
        final long ceiling = Math.min(policy.getBackoffCap().toMillis(),
                policy.getBackoffBase().toMillis() * (1L << Math.min(attempt - 1, 20)));
        final Duration jitter = Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling + 1));
        return retryAfter != null && retryAfter.compareTo(jitter) > 0 ? retryAfter : jitter;
    }

    static final Duration THROTTLE_FLOOR = Duration.ofSeconds(2);
    static final Duration THROTTLE_CAP = Duration.ofSeconds(60);
    static final int THROTTLE_RECOVERY = 50;

    /**
     * A 429 halves the host's request rate for this platform: its spacing becomes twice the
     * platform's (at least 2 s), doubling again on each further 429, up to 60 s. Some hosts allow
     * a short burst and then refuse for a few seconds without a Retry-After (Netflix on Eightfold:
     * five requests at 3.5 s, then 429), which short jittered retries alone cannot outlast.
     */
    void throttle(final String origin) {
        final Throttle t = throttles.computeIfAbsent(origin, o -> new Throttle(new RateLimiter(
                max(platformLimiter.getInterval(), THROTTLE_FLOOR.dividedBy(2)), ticker)));
        synchronized (t) {
            final Duration next = min(max(t.limiter.getInterval().multipliedBy(2), THROTTLE_FLOOR), THROTTLE_CAP);
            t.limiter.setInterval(next);
            t.successes = 0;
            LOG.info("{}: {} answered 429; one request every {} ms from now on", platform, origin, next.toMillis());
        }
    }

    /** After 50 successes in a row the spacing shrinks by a quarter; below 2 s (or the platform's own rate) the throttle ends. */
    private void recovered(final String origin) {
        final Throttle t = throttles.get(origin);
        if (t == null) {
            return;
        }
        synchronized (t) {
            if (++t.successes < THROTTLE_RECOVERY) {
                return;
            }
            t.successes = 0;
            final Duration next = t.limiter.getInterval().multipliedBy(3).dividedBy(4);
            if (next.compareTo(THROTTLE_FLOOR) < 0 || next.compareTo(platformLimiter.getInterval()) <= 0) {
                throttles.remove(origin);
            } else {
                t.limiter.setInterval(next);
            }
        }
    }

    Duration throttleFor(final String origin) {
        final Throttle t = throttles.get(origin);
        return t == null ? Duration.ZERO : t.limiter.getInterval();
    }

    private static Duration max(final Duration a, final Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Duration min(final Duration a, final Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static final class Throttle {
        final RateLimiter limiter;
        int successes;

        Throttle(final RateLimiter limiter) {
            this.limiter = limiter;
        }
    }
}
