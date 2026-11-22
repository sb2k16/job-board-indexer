/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.fetch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdkHttpTransportTest {

    @SuppressWarnings("unchecked")
    private static HttpClient answering(final int status, final String body) {
        final HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        final HttpClient client = mock(HttpClient.class);
        when(client.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
        return client;
    }

    @SuppressWarnings("unchecked")
    private static HttpClient failing(final String message) {
        final HttpClient client = mock(HttpClient.class);
        when(client.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.failedFuture(new IOException(message)));
        return client;
    }

    @Test
    void deadClient_isReplacedOnce_andTheRequestRetried() throws Exception {
        final List<HttpClient> clients = List.of(failing("selector manager closed"), answering(200, "ok"));
        final AtomicInteger built = new AtomicInteger();
        final JdkHttpTransport transport = new JdkHttpTransport(() -> clients.get(built.getAndIncrement()));

        final HttpTransport.Response first = transport.get("https://h.test/a", Duration.ofSeconds(5));
        final HttpTransport.Response second = transport.get("https://h.test/b", Duration.ofSeconds(5));

        assertThat(first.getStatus(), equalTo(200));
        assertThat(second.getBody(), equalTo("ok"));
        assertThat(built.get(), equalTo(2));
    }

    @Test
    void ordinaryNetworkError_keepsTheClient() {
        final AtomicInteger built = new AtomicInteger();
        final JdkHttpTransport transport = new JdkHttpTransport(() -> {
            built.incrementAndGet();
            return failing("Connection reset");
        });

        final IOException e = assertThrows(IOException.class, () -> transport.get("https://h.test/a", Duration.ofSeconds(5)));

        assertThat(e.getMessage(), equalTo("Connection reset"));
        assertThat(built.get(), equalTo(1));
    }
}
