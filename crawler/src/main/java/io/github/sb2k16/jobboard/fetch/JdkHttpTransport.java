/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** java.net.http GET with the project's identity. */
public class JdkHttpTransport implements HttpTransport {
    public static final String USER_AGENT = "JobBoardIndexer/0.1 (+https://github.com/sb2k16/job-board-indexer)";
    /** robots.txt product token: the User-Agent's first word. */
    public static final String PRODUCT_TOKEN = "JobBoardIndexer";

    private final HttpClient client;

    public JdkHttpTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    JdkHttpTransport(final HttpClient client) {
        this.client = client;
    }

    @Override
    public Response get(final String url, final Duration timeout) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8")
                .GET()
                .build();
        final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(),
                response.headers().firstValue("Retry-After").map(JdkHttpTransport::retryAfter).orElse(null));
    }

    @Override
    public Response post(final String url, final String jsonBody, final Duration timeout) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(),
                response.headers().firstValue("Retry-After").map(JdkHttpTransport::retryAfter).orElse(null));
    }

    /** Only the delta-seconds form; an HTTP-date is ignored and the policy's own backoff applies. */
    static Duration retryAfter(final String value) {
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
