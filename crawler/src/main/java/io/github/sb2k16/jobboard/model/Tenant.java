/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One board on a platform: the slug the platform uses and the employer name for job identity. */
public class Tenant {
    @JsonProperty("board")
    private String board;

    @JsonProperty("company")
    private String company;

    public Tenant() {
    }

    public Tenant(final String board, final String company) {
        this.board = board;
        this.company = company;
    }

    public String getBoard() {
        return board;
    }

    public String getCompany() {
        return company;
    }
}
