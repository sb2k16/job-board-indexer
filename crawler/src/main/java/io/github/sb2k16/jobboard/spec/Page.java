/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import java.util.List;

/** One parsed response, JSON or XML, queried by the paths written in a spec. */
interface Page {

    /** True when the path resolves to at least one non-null node. */
    boolean exists(String path);

    /** The job items, or null when the items path does not resolve to a list (not a board). */
    List<Node> items(String path);

    /** Every scalar value the path yields from the page root. */
    List<String> values(String path);

    /** A node inside the page (the root or one item) that paths are resolved against. */
    interface Node {
        List<String> values(String path);
    }
}
