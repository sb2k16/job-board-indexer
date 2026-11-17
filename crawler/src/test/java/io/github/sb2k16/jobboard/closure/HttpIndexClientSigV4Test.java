/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.closure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/** The sweeper's requests to Amazon OpenSearch Service carry SigV4 headers for the task role's session. */
class HttpIndexClientSigV4Test {
    private HttpServer server;
    private final List<Map<String, List<String>>> seen = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.add(Map.copyOf(ex.getRequestHeaders()));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final byte[] out = "{\"count\":7}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String header(final Map<String, List<String>> h, final String name) {
        return h.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> e.getValue().get(0))
                .findFirst().orElse(null);
    }

    @Test
    void signedRequests_carryV4AuthorizationForEs_andTheSessionToken() {
        final StaticCredentialsProvider creds = StaticCredentialsProvider.create(
                AwsSessionCredentials.create("AKIDEXAMPLE", "secret", "session-token-from-the-task-role"));
        final HttpIndexClient client = new HttpIndexClient(url(), "jobs", null, null, Duration.ofSeconds(5),
                HttpIndexClient.sigV4("us-east-1", creds));

        assertThat(client.count(Map.of("match_all", Map.of())), equalTo(7L));

        final Map<String, List<String>> h = seen.get(0);
        final String auth = header(h, "Authorization");
        assertThat(auth, matchesPattern("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/\\d{8}/us-east-1/es/aws4_request, "
                + "SignedHeaders=[a-z0-9;-]*host[a-z0-9;-]*, Signature=[0-9a-f]{64}"));
        assertThat(auth, containsString("x-amz-date"));
        assertThat(header(h, "X-Amz-Date"), matchesPattern("\\d{8}T\\d{6}Z"));
        assertThat(header(h, "X-Amz-Security-Token"), equalTo("session-token-from-the-task-role"));
        assertThat(bodies.get(0), containsString("match_all"));                 // the body the signature covers
    }

    @Test
    void withoutARegion_noSignature() {
        final HttpIndexClient client = new HttpIndexClient(url(), "jobs", null, null, Duration.ofSeconds(5));
        client.count(Map.of("match_all", Map.of()));
        assertThat(header(seen.get(0), "Authorization"), nullValue());
        assertThat(header(seen.get(0), "Content-Type"), notNullValue());
    }
}
