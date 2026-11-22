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

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * robots.txt per scheme+host, cached for {@code ttl}. RFC 9309 2.3.1: a 4xx means no
 * restrictions; a 5xx or an unreachable server means assume complete disallow, which here is a
 * retryable failure (the board is tried again later), never a reason to crawl.
 */
public class RobotsCache {
    private static final Duration UNAVAILABLE_TTL = Duration.ofMinutes(5);

    private final HttpTransport transport;
    private final Ticker ticker;
    private final Duration ttl;
    private final Duration timeout;
    private final ConcurrentMap<String, Entry> hosts = new ConcurrentHashMap<>();

    public RobotsCache(final HttpTransport transport, final Ticker ticker, final Duration ttl, final Duration timeout) {
        this.transport = transport;
        this.ticker = ticker;
        this.ttl = ttl;
        this.timeout = timeout;
    }

    /** @param origin scheme://host[:port] */
    public RobotsRules rulesFor(final String origin) throws InterruptedException {
        final Entry entry = hosts.computeIfAbsent(origin, o -> new Entry());
        synchronized (entry) {
            final long now = ticker.nanos();
            if (entry.fetchedAt == 0 || now - entry.fetchedAt > (entry.rules == null ? UNAVAILABLE_TTL : ttl).toNanos()) {
                entry.rules = fetch(origin, entry);
                entry.fetchedAt = now == 0 ? 1 : now;
            }
            if (entry.rules == null) {
                throw new BoardFetchException(origin + "/robots.txt is unavailable (" + entry.failure
                        + "); RFC 9309 treats that as disallow-all", null, true, BoardFetchException.Kind.ROBOTS, 0);
            }
            return entry.rules;
        }
    }

    /** Null means unavailable (5xx or network failure); {@code entry.failure} then says which. */
    private RobotsRules fetch(final String origin, final Entry entry) throws InterruptedException {
        final HttpTransport.Response response;
        try {
            response = transport.get(origin + "/robots.txt", timeout);
        } catch (final IOException e) {
            // The cause is part of the board's failure reason: on Nov 20 and 21 a generic message
            // hid a dead HTTP client for a day and a half.
            entry.failure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return null;
        }
        final int status = response.getStatus();
        entry.failure = "HTTP " + status;
        if (status >= 200 && status < 300) {
            return RobotsRules.parse(response.getBody() == null ? "" : response.getBody(), JdkHttpTransport.PRODUCT_TOKEN);
        }
        if (status >= 400 && status < 500) {
            return RobotsRules.ALLOW_ALL;
        }
        return null;
    }

    private static final class Entry {
        long fetchedAt;
        RobotsRules rules;
        String failure;
    }
}
