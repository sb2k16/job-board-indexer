/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.closure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** OpenSearch REST over the JDK client. Basic auth when configured; AWS SigV4 comes with T1.5. */
public class HttpIndexClient implements IndexClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;
    private final String index;
    private final String authorization;
    private final Duration timeout;

    public HttpIndexClient(final String baseUrl, final String index, final String username, final String password,
                           final Duration timeout) {
        this.base = baseUrl.replaceAll("/+$", "");
        this.index = index;
        this.timeout = timeout;
        this.authorization = username == null ? null
                : "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void refresh() {
        call("POST", "/" + index + "/_refresh", null);
    }

    @Override
    public long count(final Map<String, Object> query) {
        return call("POST", "/" + index + "/_count", Map.of("query", query)).path("count").asLong();
    }

    @Override
    public long updateByQuery(final Map<String, Object> query, final String script, final Map<String, Object> params) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("script", Map.of("lang", "painless", "source", script, "params", params));
        final JsonNode r = call("POST", "/" + index + "/_update_by_query?conflicts=proceed&refresh=true", body);
        if (r.path("failures").size() > 0) {
            throw new IllegalStateException("closure update failed: " + r.path("failures").get(0));
        }
        return r.path("updated").asLong();
    }

    private JsonNode call(final String method, final String path, final Object body) {
        try {
            final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            if (authorization != null) {
                b.header("Authorization", authorization);
            }
            final HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                throw new IllegalStateException(method + " " + path + " returned HTTP " + res.statusCode() + ": "
                        + res.body().substring(0, Math.min(300, res.body().length())));
            }
            return JSON.readTree(res.body());
        } catch (final IOException e) {
            throw new IllegalStateException(method + " " + path + " failed: " + e.getMessage(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + path + " interrupted", e);
        }
    }
}
