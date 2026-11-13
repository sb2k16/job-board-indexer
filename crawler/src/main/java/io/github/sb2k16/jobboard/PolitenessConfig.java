/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/** Pipeline settings for the fetch layer; per-platform budgets live in the platform specs. */
public class PolitenessConfig {

    /** Pods running this pipeline. Each pod takes 1/replicas of every platform's budget. */
    @JsonProperty("replicas")
    private int replicas = 1;

    @JsonProperty("request_timeout")
    private Duration requestTimeout = Duration.ofSeconds(30);

    /** Attempts per request, including the first, for 429, 5xx and network failures. */
    @JsonProperty("max_attempts")
    private int maxAttempts = 3;

    @JsonProperty("backoff_base")
    private Duration backoffBase = Duration.ofSeconds(1);

    @JsonProperty("backoff_cap")
    private Duration backoffCap = Duration.ofSeconds(30);

    /** A Retry-After longer than this ends the fetch; the next refresh tries again. */
    @JsonProperty("max_retry_after")
    private Duration maxRetryAfter = Duration.ofSeconds(60);

    /** Consecutive failed fetches (after retries) that open a host's circuit. */
    @JsonProperty("breaker_threshold")
    private int breakerThreshold = 5;

    @JsonProperty("breaker_cooldown")
    private Duration breakerCooldown = Duration.ofMinutes(5);

    @JsonProperty("robots_ttl")
    private Duration robotsTtl = Duration.ofHours(24);

    public int getReplicas() {
        return replicas;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
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

    public int getBreakerThreshold() {
        return breakerThreshold;
    }

    public Duration getBreakerCooldown() {
        return breakerCooldown;
    }

    public Duration getRobotsTtl() {
        return robotsTtl;
    }
}
