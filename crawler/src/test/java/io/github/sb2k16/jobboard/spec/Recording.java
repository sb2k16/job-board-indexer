/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sb2k16.jobboard.adapter.BoardFetchException;
import io.github.sb2k16.jobboard.adapter.HttpFetcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A recorded crawl of one live board: every request the spec made and what came back, stored as
 * gzipped JSON under test resources fixtures/recorded/&lt;platform&gt;.json.gz. Recording reads one
 * list page and the first few detail pages; later requests are recorded as 404, which the engine
 * treats as a job that expired between the list and its page. Replay serves exactly those
 * responses and fails on any request that was not recorded, so a spec that changes what it asks
 * for has to be re-recorded.
 */
public final class Recording {
    private static final ObjectMapper JSON = new ObjectMapper();

    public String platform;
    public String board;
    public String company;
    public String recorded;
    public final Map<String, Integer> status = new LinkedHashMap<>();
    public final Map<String, String> body = new LinkedHashMap<>();
    public final List<String> order = new ArrayList<>();

    static String key(final String url, final String postBody) {
        return postBody == null ? url : url + "#" + postBody;
    }

    /** Wraps a live fetcher; the first {@code budget} requests go through, the rest answer 404. */
    public HttpFetcher recorder(final HttpFetcher live, final int budget) {
        return new HttpFetcher() {
            @Override
            public String get(final String url) {
                return take(key(url, null), () -> live.get(url), url);
            }

            @Override
            public String post(final String url, final String jsonBody) {
                return take(key(url, jsonBody), () -> live.post(url, jsonBody), url);
            }

            private String take(final String k, final java.util.function.Supplier<String> call, final String url) {
                order.add(k);
                if (order.size() > budget) {
                    status.put(k, 404);
                    throw BoardFetchException.http(url, 404);
                }
                try {
                    final String b = call.get();
                    status.put(k, 200);
                    body.put(k, b);
                    return b;
                } catch (final BoardFetchException e) {
                    status.put(k, e.isBoardGone() ? 404 : 500);
                    throw e;
                }
            }
        };
    }

    /** Serves the recorded responses; an unrecorded request is an error. */
    public FakeReplay replayer() {
        return new FakeReplay();
    }

    public final class FakeReplay implements HttpFetcher {
        public final List<String> unrecorded = new ArrayList<>();

        @Override
        public String get(final String url) {
            return serve(key(url, null), url);
        }

        @Override
        public String post(final String url, final String jsonBody) {
            return serve(key(url, jsonBody), url);
        }

        private String serve(final String k, final String url) {
            final Integer s = status.get(k);
            if (s == null) {
                unrecorded.add(k);
                throw new IllegalStateException("request not in the recording: " + k);
            }
            if (s == 404) {
                throw BoardFetchException.http(url, 404);
            }
            if (s != 200) {
                throw BoardFetchException.http(url, s);
            }
            return body.get(k);
        }
    }

    public void save(final Path file) throws IOException {
        final ObjectNode root = JSON.createObjectNode();
        root.put("platform", platform).put("board", board).put("company", company).put("recorded", recorded);
        final ArrayNode rs = root.putArray("responses");
        for (final Map.Entry<String, Integer> e : status.entrySet()) {
            final ObjectNode r = rs.addObject().put("request", e.getKey()).put("status", e.getValue());
            if (body.containsKey(e.getKey())) {
                r.put("body", body.get(e.getKey()));
            }
        }
        Files.createDirectories(file.getParent());
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            JSON.writerWithDefaultPrettyPrinter().writeValue(out, root);
        }
    }

    public static Recording load(final InputStream gz) throws IOException {
        final JsonNode root;
        try (InputStream in = new GZIPInputStream(gz)) {
            root = JSON.readTree(in);
        }
        final Recording r = new Recording();
        r.platform = root.path("platform").asText();
        r.board = root.path("board").asText();
        r.company = root.path("company").isNull() ? null : root.path("company").asText(null);
        r.recorded = root.path("recorded").asText();
        for (final JsonNode x : root.path("responses")) {
            final String k = x.path("request").asText();
            r.status.put(k, x.path("status").asInt());
            if (x.has("body")) {
                r.body.put(k, x.path("body").asText());
            }
        }
        return r;
    }

    /** The spec with paging capped, so a recording holds one list page (or one per until_missing file). */
    public static SpecAdapter capped(final SpecAdapter adapter, final int maxPages) {
        try {
            final Field f = PlatformSpec.PaginationSpec.class.getDeclaredField("maxPages");
            f.setAccessible(true);
            f.setInt(adapter.getSpec().getPagination(), maxPages);
            return adapter;
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
