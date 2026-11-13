/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.Test;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds the plugin's beans the way Data Prepper does: a Spring context scanning the plugin package,
 * with the plugin config and PluginMetrics registered as beans. Catches constructor wiring errors
 * that plain unit tests (which call constructors directly) do not.
 */
class JobBoardPluginWiringTest {

    @Test
    void springContext_scanningPluginPackage_createsCrawlerAndExecutorProvider() {
        final PluginMetrics pluginMetrics = mock(PluginMetrics.class);
        when(pluginMetrics.counter(anyString())).thenReturn(mock(Counter.class));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JobBoardSourceConfig.class, () -> mock(JobBoardSourceConfig.class));
            context.registerBean(PluginMetrics.class, () -> pluginMetrics);
            context.scan(JobBoardCrawlerSource.class.getPackageName());
            context.refresh();

            assertThat(context.getBean(JobBoardCrawler.class), notNullValue());
            assertThat(context.getBean(JobBoardExecutorServiceProvider.class), notNullValue());
        }
    }
}
