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

/** The closure sweeper (T2.5). Absent: no job is ever closed. */
public class ClosureConfig {

    /** OpenSearch base URL; the sweeper writes closures directly, not through the sink. */
    @JsonProperty("opensearch_url")
    private String opensearchUrl;

    @JsonProperty("username")
    private String username;

    @JsonProperty("password")
    private String password;

    @JsonProperty("index")
    private String index = "jobs";

    /**
     * Set on Amazon OpenSearch Service: every request is signed with AWS SigV4 (service "es") using
     * the default credential chain, which on ECS is the task role. Unset: plain or basic auth.
     */
    @JsonProperty("aws_region")
    private String awsRegion;

    public String getAwsRegion() {
        return awsRegion;
    }

    /** A link goes after this many complete snapshots of its board without it. */
    @JsonProperty("snapshots")
    private int snapshots = 3;

    /** The guard holds a run that would close more than this share of a board's jobs... */
    @JsonProperty("mass_closure_ratio")
    private double massClosureRatio = 0.3;

    /** ...and at least this many. */
    @JsonProperty("mass_closure_min_jobs")
    private int massClosureMinJobs = 10;

    /** A board is retired after this many consecutive crawls confirm it is gone (404/410). */
    @JsonProperty("retire_after")
    private int retireAfter = 3;

    @JsonProperty("request_timeout")
    private Duration requestTimeout = Duration.ofSeconds(60);

    public String getOpensearchUrl() {
        return opensearchUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getIndex() {
        return index;
    }

    public int getSnapshots() {
        return snapshots;
    }

    public double getMassClosureRatio() {
        return massClosureRatio;
    }

    public int getMassClosureMinJobs() {
        return massClosureMinJobs;
    }

    public int getRetireAfter() {
        return retireAfter;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }
}
