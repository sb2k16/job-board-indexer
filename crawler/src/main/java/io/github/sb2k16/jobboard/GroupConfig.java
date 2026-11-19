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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.time.Duration;
import java.util.List;

/**
 * A refresh group inside one pipeline: platforms refreshed together on their own cadence.
 * Several groups share a pipeline (and so one OpenSearch sink) because the domain refuses more than
 * a few bulk requests in flight (docs/aws-deploy-plan-2026-11-17.md, "OpenSearch write rejections").
 * The name keys the group's last refresh in {@code group_refresh}, so it must stay stable and be
 * unique across every pipeline that shares the database.
 */
public class GroupConfig {

    @JsonProperty("name")
    @NotEmpty
    private String name;

    @JsonProperty("refresh_interval")
    private Duration refreshInterval = Duration.ofHours(6);

    @JsonProperty("platforms")
    @NotEmpty
    @Valid
    private List<PlatformConfig> platforms;

    public GroupConfig() {
    }

    GroupConfig(final String name, final Duration refreshInterval, final List<PlatformConfig> platforms) {
        this.name = name;
        this.refreshInterval = refreshInterval;
        this.platforms = platforms;
    }

    /** Null for the one implicit group of a pipeline configured without {@code groups}. */
    public String getName() {
        return name;
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public List<PlatformConfig> getPlatforms() {
        return platforms;
    }
}
