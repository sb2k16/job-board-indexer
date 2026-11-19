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
import org.opensearch.dataprepper.plugins.source.source_crawler.base.CrawlerSourceConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JobBoardSourceConfig implements CrawlerSourceConfig {

    /** The platforms of a pipeline with one group; refreshed every {@code refresh_interval}. Or use {@code groups}. */
    @JsonProperty("platforms")
    @Valid
    private List<PlatformConfig> platforms;

    /** Several refresh groups in one pipeline, each with its own platforms and cadence. Or use {@code platforms}. */
    @JsonProperty("groups")
    @Valid
    private List<GroupConfig> groups;

    @JsonProperty("workers")
    private int numberOfWorkers = DEFAULT_NUMBER_OF_WORKERS;

    @JsonProperty("acknowledgments")
    private boolean acknowledgments = true;

    /** How often the leader re-lists every board of a pipeline configured with {@code platforms}. */
    @JsonProperty("refresh_interval")
    private Duration refreshInterval = Duration.ofHours(6);

    /** How often the leader loop wakes up to check whether a refresh is due. */
    @JsonProperty("leader_interval")
    private Duration leaderInterval = Duration.ofMinutes(1);

    @JsonProperty("politeness")
    private PolitenessConfig politeness = new PolitenessConfig();

    /** Optional; without it nothing is recorded and no snapshot history exists (local tests only). */
    @JsonProperty("board_state")
    private BoardStateConfig boardState;

    @JsonProperty("closure")
    private ClosureConfig closure;

    public ClosureConfig getClosure() {
        return closure;
    }

    public PolitenessConfig getPoliteness() {
        return politeness;
    }

    public BoardStateConfig getBoardState() {
        return boardState;
    }

    /**
     * The pipeline's refresh groups: those configured, or one unnamed group of {@code platforms}
     * and {@code refresh_interval}. Fails when neither or both are set, or a platform or group name
     * appears twice, so a bad pipeline never starts.
     */
    public List<GroupConfig> getGroups() {
        final boolean hasPlatforms = platforms != null && !platforms.isEmpty();
        final boolean hasGroups = groups != null && !groups.isEmpty();
        if (hasPlatforms == hasGroups) {
            throw new IllegalArgumentException("job_board_crawler needs exactly one of 'platforms' and 'groups'");
        }
        final List<GroupConfig> result = hasGroups ? groups : List.of(new GroupConfig(null, refreshInterval, platforms));
        final Set<String> names = new HashSet<>();
        final Set<String> platformNames = new HashSet<>();
        for (final GroupConfig g : result) {
            if (hasGroups && (g.getName() == null || !names.add(g.getName()))) {
                throw new IllegalArgumentException("job_board_crawler: group name missing or used twice: " + g.getName());
            }
            for (final PlatformConfig p : g.getPlatforms()) {
                if (!platformNames.add(p.getName())) {
                    throw new IllegalArgumentException("job_board_crawler: platform " + p.getName() + " is configured twice");
                }
            }
        }
        return result;
    }

    /** Every platform of every group. */
    public List<PlatformConfig> getPlatforms() {
        final List<PlatformConfig> all = new ArrayList<>();
        getGroups().forEach(g -> all.addAll(g.getPlatforms()));
        return all;
    }

    @Override
    public int getNumberOfWorkers() {
        return numberOfWorkers;
    }

    @Override
    public boolean isAcknowledgments() {
        return acknowledgments;
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    @Override
    public Duration getLeaseInterval() {
        return leaderInterval;
    }
}
