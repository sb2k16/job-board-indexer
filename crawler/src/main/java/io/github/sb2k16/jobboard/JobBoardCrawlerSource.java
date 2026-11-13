/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.acknowledgements.AcknowledgementSetManager;
import org.opensearch.dataprepper.model.annotations.DataPrepperPlugin;
import org.opensearch.dataprepper.model.annotations.DataPrepperPluginConstructor;
import org.opensearch.dataprepper.model.plugin.PluginFactory;
import org.opensearch.dataprepper.model.source.Source;
import org.opensearch.dataprepper.plugins.source.source_crawler.base.CrawlerSourcePlugin;
import org.opensearch.dataprepper.plugins.source.source_crawler.base.LeaderProgressState;
import org.opensearch.dataprepper.plugins.source.source_crawler.coordination.state.PaginationCrawlerLeaderProgressState;

import java.time.Instant;

@DataPrepperPlugin(name = JobBoardCrawlerSource.PLUGIN_NAME,
        pluginType = Source.class,
        pluginConfigurationType = JobBoardSourceConfig.class,
        packagesToScan = {JobBoardCrawlerSource.class})
public class JobBoardCrawlerSource extends CrawlerSourcePlugin {
    static final String PLUGIN_NAME = "job_board_crawler";

    @DataPrepperPluginConstructor
    public JobBoardCrawlerSource(final PluginMetrics pluginMetrics,
                                 final JobBoardSourceConfig config,
                                 final PluginFactory pluginFactory,
                                 final AcknowledgementSetManager acknowledgementSetManager,
                                 final JobBoardCrawler crawler,
                                 final JobBoardExecutorServiceProvider executorServiceProvider) {
        super(PLUGIN_NAME, pluginMetrics, config, pluginFactory, acknowledgementSetManager, crawler, executorServiceProvider);
    }

    @Override
    protected LeaderProgressState createLeaderProgressState() {
        return new PaginationCrawlerLeaderProgressState(Instant.EPOCH);
    }
}
