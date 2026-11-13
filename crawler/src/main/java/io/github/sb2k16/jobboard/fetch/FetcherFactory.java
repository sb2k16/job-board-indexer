/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.fetch;

import io.github.sb2k16.jobboard.adapter.HttpFetcher;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Builds one {@link PoliteFetcher} per platform. robots.txt, circuit breakers and Crawl-delay
 * limiters are per host and shared by every platform in the pod.
 */
public class FetcherFactory {
    private final HttpTransport transport;
    private final Ticker ticker;
    private final RobotsCache robots;
    private final CircuitBreaker breaker;
    private final ConcurrentMap<String, RateLimiter> hostDelays = new ConcurrentHashMap<>();

    public FetcherFactory(final HttpTransport transport, final Ticker ticker, final Duration robotsTtl,
                          final Duration requestTimeout, final int breakerThreshold, final Duration breakerCooldown) {
        this.transport = transport;
        this.ticker = ticker;
        this.robots = new RobotsCache(transport, ticker, robotsTtl, requestTimeout);
        this.breaker = new CircuitBreaker(breakerThreshold, breakerCooldown, ticker);
    }

    public HttpFetcher forPlatform(final String platform, final FetchPolicy policy) {
        return new PoliteFetcher(platform, policy, transport, robots, breaker, hostDelays, ticker);
    }
}
