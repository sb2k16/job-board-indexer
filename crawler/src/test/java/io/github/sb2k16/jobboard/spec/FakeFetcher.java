/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import io.github.sb2k16.jobboard.adapter.BoardFetchException;
import io.github.sb2k16.jobboard.adapter.HttpFetcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Serves canned bodies by URL and records every request, in order. */
class FakeFetcher implements HttpFetcher {
    final List<String> requests = new ArrayList<>();
    private final Map<String, String> bodies = new LinkedHashMap<>();
    private Function<String, String> responder = url -> null;

    FakeFetcher on(final String url, final String body) {
        bodies.put(url, body);
        return this;
    }

    FakeFetcher respond(final Function<String, String> responder) {
        this.responder = responder;
        return this;
    }

    @Override
    public String get(final String url) {
        requests.add(url);
        String body = bodies.get(url);
        if (body == null) {
            body = responder.apply(url);
        }
        if (body == null) {
            throw BoardFetchException.http(url, 404);
        }
        return body;
    }

    final List<String> postBodies = new ArrayList<>();

    @Override
    public String post(final String url, final String jsonBody) {
        postBodies.add(jsonBody);
        return get(url + "#" + jsonBody);
    }

    static String resource(final String name) {
        try (InputStream in = FakeFetcher.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException | NullPointerException e) {
            throw new IllegalStateException("missing fixture " + name, e);
        }
    }

    /** The value of one query parameter, or null. */
    static String param(final String url, final String name) {
        final int q = url.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (final String pair : url.substring(q + 1).split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }
}
