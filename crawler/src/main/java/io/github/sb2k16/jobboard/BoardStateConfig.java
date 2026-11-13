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

/** Connection for the application's {@code board_state} table (same database as the coordination store). */
public class BoardStateConfig {

    @JsonProperty("url")
    private String url;

    @JsonProperty("username")
    private String username;

    @JsonProperty("password")
    private String password;

    @JsonProperty("max_pool_size")
    private int maxPoolSize = 2;

    /** Complete snapshots kept per board; must be at least the closure rule's N (T2.5). */
    @JsonProperty("history_size")
    private int historySize = 10;

    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public int getHistorySize() {
        return historySize;
    }
}
