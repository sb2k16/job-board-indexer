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
    static final int BODY_DEADLINE_FACTOR = 10;
    public static final String USER_AGENT = "JobBoardIndexer/0.1 (+https://github.com/sb2k16/job-board-indexer)";
    /** robots.txt product token: the User-Agent's first word. */
    public static final String PRODUCT_TOKEN = "JobBoardIndexer";

    /** What the JDK client says on every request once its selector thread has died. */
    static final String DEAD_CLIENT = "selector manager closed";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JdkHttpTransport.class);

    private final java.util.function.Supplier<HttpClient> clients;
    private volatile HttpClient client;

    public JdkHttpTransport() {
        // HTTP/1.1: a polite crawler gains nothing from HTTP/2 multiplexing, and JDK 11's HTTP/2
        // client stalls on long-lived connections (Netflix on Eightfold timed out on Nov 14 and 16
        // while curl answered in half a second).
        this(() -> HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    JdkHttpTransport(final java.util.function.Supplier<HttpClient> clients) {
        this.clients = clients;
        this.client = clients.get();
    }

    /**
     * The JDK client runs all its I/O on one selector thread. If that thread dies (an
     * OutOfMemoryError there is caught and logged only at debug level), the client fails every later
     * request with "selector manager closed" and never recovers: on Nov 20 and 21 two crawler tasks
     * failed every board for a day and a half that way. A dead client is replaced once, by whichever
     * request sees it first, and that request is retried on the new one.
     */
    private HttpResponse<String> send(final HttpRequest request, final Duration timeout) throws IOException, InterruptedException {
        final HttpClient used = client;
        try {
            return sendWithin(used, request, timeout);
        } catch (final IOException e) {
            if (!isDeadClient(e)) {
                throw e;
            }
            synchronized (this) {
                if (client == used) {
                    LOG.error("HTTP client is dead ({}); replacing it", e.getMessage());
                    client = clients.get();
                }
            }
            return sendWithin(client, request, timeout);
        }
    }

    static boolean isDeadClient(final Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(DEAD_CLIENT)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Response get(final String url, final Duration timeout) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(io.github.sb2k16.jobboard.adapter.Html.uri(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8")
                .GET()
                .build();
        final HttpResponse<String> response = send(request, timeout);
        return new Response(response.statusCode(), response.body(),
                response.headers().firstValue("Retry-After").map(JdkHttpTransport::retryAfter).orElse(null));
    }

    @Override
    public Response post(final String url, final String jsonBody, final Duration timeout) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(io.github.sb2k16.jobboard.adapter.Html.uri(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        final HttpResponse<String> response = send(request, timeout);
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

    /**
     * Sends and reads the whole body within ten times the request timeout (5 minutes by default).
     * The JDK client's own timeout stops at the response headers, so a server that stalls mid-body
     * would block the calling crawler thread forever (seen on Nov 16: 18 hours). The deadline is
     * generous because large pages stream slowly (a 2.4 MB Zoho page takes 80 s). Past it the
     * exchange is cancelled and the failure is a retryable network error.
     */
    private static HttpResponse<String> sendWithin(final HttpClient client, final HttpRequest request, final Duration timeout)
            throws IOException, InterruptedException {
        final java.util.concurrent.CompletableFuture<HttpResponse<String>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(timeout.multipliedBy(BODY_DEADLINE_FACTOR).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (final java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new java.net.http.HttpTimeoutException("no complete response within " + timeout.multipliedBy(BODY_DEADLINE_FACTOR).toSeconds()
                    + " s from " + request.uri());
        } catch (final java.util.concurrent.ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException(cause);
        } catch (final InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }
}
