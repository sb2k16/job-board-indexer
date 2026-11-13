/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.adapter;

import io.github.sb2k16.jobboard.PlatformConfig;
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.Tenant;

/**
 * One platform's fetch and mapping logic (Strategy). Implementations are looked up by name in
 * {@link AdapterRegistry} (Factory), so a new platform adds one class and one registration.
 */
public interface PlatformAdapter {

    String name();

    /**
     * Fetches one board completely. Throws {@link BoardFetchException} instead of returning an
     * empty snapshot when the response cannot be trusted.
     */
    BoardSnapshot fetchBoard(PlatformConfig platform, Tenant tenant, HttpFetcher fetcher);
}
