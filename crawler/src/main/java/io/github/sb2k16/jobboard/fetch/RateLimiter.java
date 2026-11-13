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

/**
 * Spaces requests at least {@code interval} apart across every thread that shares it. A caller
 * reserves the next slot under the lock and sleeps outside it, so waiting threads queue in order.
 */
public class RateLimiter {
    private final long intervalNanos;
    private final Ticker ticker;
    private long nextFree = Long.MIN_VALUE;

    public RateLimiter(final Duration interval, final Ticker ticker) {
        this.intervalNanos = interval.toNanos();
        this.ticker = ticker;
    }

    public Duration getInterval() {
        return Duration.ofNanos(intervalNanos);
    }

    public void acquire() throws InterruptedException {
        final long wait;
        synchronized (this) {
            final long now = ticker.nanos();
            final long slot = nextFree == Long.MIN_VALUE ? now : Math.max(now, nextFree);
            nextFree = slot + intervalNanos;
            wait = slot - now;
        }
        if (wait > 0) {
            ticker.sleep(Duration.ofNanos(wait));
        }
    }
}
