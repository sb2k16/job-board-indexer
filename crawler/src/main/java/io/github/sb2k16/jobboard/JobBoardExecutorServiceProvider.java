/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import org.opensearch.dataprepper.plugins.source.source_crawler.base.PluginExecutorServiceProvider;

import javax.inject.Named;

/** Registers the framework's executor provider as a bean without scanning the whole framework package. */
@Named
public class JobBoardExecutorServiceProvider extends PluginExecutorServiceProvider {
}
