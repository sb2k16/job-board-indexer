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

/**
 * OpenSearch REST over the JDK client, for the closure sweeper. Plain, basic auth, or AWS SigV4
 * (Amazon OpenSearch Service: {@link #sigV4}, with the default credential chain, which on ECS is
 * the task role; the SDK classes come from Data Prepper's own lib/).
 */
public class HttpIndexClient implements IndexClient {
    /** Adds authentication headers to a request about to be sent. */
    public interface Signer {
        Map<String, String> headers(String method, URI uri, Map<String, String> headers, String body);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;
    private final String index;
    private final String authorization;
    private final Duration timeout;
    private final Signer signer;

    public HttpIndexClient(final String baseUrl, final String index, final String username, final String password,
                           final Duration timeout) {
        this(baseUrl, index, username, password, timeout, null);
    }

    public HttpIndexClient(final String baseUrl, final String index, final String username, final String password,
                           final Duration timeout, final Signer signer) {
        this.base = baseUrl.replaceAll("/+$", "");
        this.index = index;
        this.timeout = timeout;
        this.signer = signer;
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
            final URI uri = URI.create(base + path);
            final String text = body == null ? "" : JSON.writeValueAsString(body);
            final HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(text));
            if (authorization != null) {
                b.header("Authorization", authorization);
            }
            if (signer != null) {
                signer.headers(method, uri, Map.of("Content-Type", "application/json"), text).forEach((k, v) -> {
                    // The JDK client sets Host itself, from the same URI the signature covers.
                    if (!k.equalsIgnoreCase("host") && !k.equalsIgnoreCase("content-type")) {
                        b.header(k, v);
                    }
                });
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

    /** SigV4 for Amazon OpenSearch Service (signing name "es"), credentials from the default chain. */
    public static Signer sigV4(final String region) {
        return sigV4(region, software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.builder().build());
    }

    static Signer sigV4(final String region, final software.amazon.awssdk.auth.credentials.AwsCredentialsProvider credentials) {
        final software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner v4 =
                software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner.create();
        return (method, uri, headers, body) -> {
            final software.amazon.awssdk.http.SdkHttpRequest.Builder req = software.amazon.awssdk.http.SdkHttpRequest.builder()
                    .method(software.amazon.awssdk.http.SdkHttpMethod.fromValue(method))
                    .uri(uri);
            headers.forEach(req::putHeader);
            final software.amazon.awssdk.http.auth.spi.signer.SignedRequest signed = v4.sign(r -> r
                    .identity(credentials.resolveCredentials())
                    .request(req.build())
                    .payload(software.amazon.awssdk.http.ContentStreamProvider.fromUtf8String(body))
                    .putProperty(software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner.SERVICE_SIGNING_NAME, "es")
                    .putProperty(software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner.REGION_NAME, region));
            final Map<String, String> out = new LinkedHashMap<>();
            signed.request().headers().forEach((k, v) -> out.put(k, String.join(",", v)));
            return out;
        };
    }
}
