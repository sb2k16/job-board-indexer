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
import org.opensearch.dataprepper.plugins.source.source_crawler.base.CrawlerSourceConfig;

import java.time.Duration;
import java.util.List;

public class JobBoardSourceConfig implements CrawlerSourceConfig {

    @JsonProperty("platforms")
    @NotEmpty
    @Valid
    private List<PlatformConfig> platforms;

    @JsonProperty("workers")
    private int numberOfWorkers = DEFAULT_NUMBER_OF_WORKERS;

    @JsonProperty("acknowledgments")
    private boolean acknowledgments = true;

    /** How often the leader re-lists every board. */
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

    public List<PlatformConfig> getPlatforms() {
        return platforms;
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
