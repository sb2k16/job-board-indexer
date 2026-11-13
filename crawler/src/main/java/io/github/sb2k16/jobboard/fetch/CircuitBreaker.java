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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-host breaker: after {@code threshold} consecutive failed fetches (retries already spent),
 * the host is skipped for {@code cooldown}, so one failing platform cannot hold every worker.
 * After the cooldown requests go through again; one more failure re-opens it at once, and a
 * success closes it.
 */
public class CircuitBreaker {
    private final int threshold;
    private final long cooldownNanos;
    private final Ticker ticker;
    private final ConcurrentMap<String, State> hosts = new ConcurrentHashMap<>();

    public CircuitBreaker(final int threshold, final Duration cooldown, final Ticker ticker) {
        this.threshold = threshold;
        this.cooldownNanos = cooldown.toNanos();
        this.ticker = ticker;
    }

    /** Remaining open time, or zero when requests may go to the host. */
    public Duration openFor(final String host) {
        final State s = hosts.get(host);
        if (s == null) {
            return Duration.ZERO;
        }
        synchronized (s) {
            final long left = s.openUntil - ticker.nanos();
            return s.openUntil == 0 || left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
        }
    }

    public void success(final String host) {
        final State s = hosts.get(host);
        if (s != null) {
            synchronized (s) {
                s.failures = 0;
                s.openUntil = 0;
            }
        }
    }

    public void failure(final String host) {
        final State s = hosts.computeIfAbsent(host, h -> new State());
        synchronized (s) {
            s.failures++;
            if (s.failures >= threshold) {
                s.openUntil = ticker.nanos() + cooldownNanos;
            }
        }
    }

    private static final class State {
        int failures;
        long openUntil;
    }
}
