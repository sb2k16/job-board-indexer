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
 * Limits for one platform in one pod. The platform budget comes from its spec and is divided by
 * the replica count, because every pod leaves through the same NAT address (design: "Each pod
 * enforces its share").
 */
public final class FetchPolicy {
    private final double requestsPerSecond;
    private final int maxConcurrency;
    private final int replicas;
    private final int maxAttempts;
    private final Duration backoffBase;
    private final Duration backoffCap;
    private final Duration maxRetryAfter;
    private final Duration requestTimeout;

    public FetchPolicy(final double requestsPerSecond, final int maxConcurrency, final int replicas, final int maxAttempts,
                       final Duration backoffBase, final Duration backoffCap, final Duration maxRetryAfter,
                       final Duration requestTimeout) {
        if (requestsPerSecond <= 0 || maxConcurrency < 1 || replicas < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("rate, concurrency, replicas and attempts must be positive");
        }
        this.requestsPerSecond = requestsPerSecond;
        this.maxConcurrency = maxConcurrency;
        this.replicas = replicas;
        this.maxAttempts = maxAttempts;
        this.backoffBase = backoffBase;
        this.backoffCap = backoffCap;
        this.maxRetryAfter = maxRetryAfter;
        this.requestTimeout = requestTimeout;
    }

    /** Minimum spacing between this pod's requests to the platform. */
    public Duration podInterval() {
        return Duration.ofNanos((long) (1_000_000_000L * replicas / requestsPerSecond));
    }

    /** This pod's share of the platform's concurrent requests, at least one. */
    public int podConcurrency() {
        return Math.max(1, maxConcurrency / replicas);
    }

    public int getReplicas() {
        return replicas;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Duration getBackoffBase() {
        return backoffBase;
    }

    public Duration getBackoffCap() {
        return backoffCap;
    }

    public Duration getMaxRetryAfter() {
        return maxRetryAfter;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }
}
