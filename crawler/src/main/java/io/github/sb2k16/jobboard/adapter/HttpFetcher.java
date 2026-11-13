/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Plain HTTPS GET with a fixed identity. Politeness (robots.txt, per-platform rate limits,
 * circuit breaker) is task T2.4 and wraps this class.
 */
public class HttpFetcher {
    static final String USER_AGENT = "JobBoardIndexer/0.1 (+https://github.com/sb2k16/job-board-indexer)";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;

    public HttpFetcher() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    HttpFetcher(final HttpClient client) {
        this.client = client;
    }

    public String get(final String url) {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
                .GET()
                .build();
        final HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final IOException e) {
            throw new BoardFetchException("GET " + url + " failed: " + e.getMessage(), e, true);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BoardFetchException("GET " + url + " interrupted", e, true);
        }
        final int status = response.statusCode();
        if (status < 200 || status >= 300) {
            final boolean retryable = status == 429 || status >= 500;
            throw new BoardFetchException("GET " + url + " returned HTTP " + status, retryable);
        }
        return response.body();
    }
}
