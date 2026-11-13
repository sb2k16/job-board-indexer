/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.adapter;

/**
 * What an adapter uses to read a URL. The crawler hands each adapter a fetcher bound to the
 * adapter's platform ({@link io.github.sb2k16.jobboard.fetch.PoliteFetcher}), which applies
 * robots.txt, the platform's rate limit, retries and the per-host circuit breaker.
 */
public interface HttpFetcher {

    /** Returns the body of a 2xx response; anything else is a {@link BoardFetchException}. */
    String get(String url);
}
