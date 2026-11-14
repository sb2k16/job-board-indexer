/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.closure;

import java.util.Map;

/** The three index calls the sweeper needs. */
public interface IndexClient {

    /** Makes the sink's latest writes visible to search. */
    void refresh();

    long count(Map<String, Object> query);

    /** Runs the closure script on every match; returns how many documents it changed. */
    long updateByQuery(Map<String, Object> query, String script, Map<String, Object> params);
}
