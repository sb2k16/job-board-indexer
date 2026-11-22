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

    /** The _source of up to {@code size} matches, in {@code sort} order, limited to {@code fields}. */
    java.util.List<Map<String, Object>> search(Map<String, Object> query, int size, java.util.List<String> fields,
                                               java.util.List<Map<String, Object>> sort);

    /** The job keys of up to {@code size} matches. */
    default java.util.List<String> jobKeys(final Map<String, Object> query, final int size) {
        final java.util.List<String> keys = new java.util.ArrayList<>();
        for (final Map<String, Object> doc : search(query, size, java.util.List.of("job_key"),
                java.util.List.of(Map.of("job_key", Map.of("order", "asc"))))) {
            if (doc.get("job_key") != null) {
                keys.add(String.valueOf(doc.get("job_key")));
            }
        }
        return keys;
    }
}
