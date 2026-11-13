/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * JSON paths are dot-separated: {@code meta.total}, {@code location.name}, {@code lists.*.content}
 * ({@code *} or {@code [*]} fans out over an array or object), {@code offices.0.name} (array index).
 * {@code $} or an empty path is the node itself.
 */
final class JsonPage implements Page {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;

    private JsonPage(final JsonNode root) {
        this.root = root;
    }

    static JsonPage parse(final String body) throws JsonProcessingException {
        final JsonNode root = MAPPER.readTree(body);
        if (root == null || root.isMissingNode()) {
            throw new JsonProcessingException("empty body") { };
        }
        return new JsonPage(root);
    }

    @Override
    public boolean exists(final String path) {
        return resolve(root, path).stream().anyMatch(n -> !n.isNull());
    }

    @Override
    public List<Node> items(final String path) {
        final List<JsonNode> found = resolve(root, path);
        if (found.size() != 1 || !found.get(0).isArray()) {
            return null;
        }
        final List<Node> items = new ArrayList<>();
        for (final JsonNode item : found.get(0)) {
            items.add(p -> values(item, p));
        }
        return items;
    }

    @Override
    public List<String> values(final String path) {
        return values(root, path);
    }

    private static List<String> values(final JsonNode from, final String path) {
        final List<String> out = new ArrayList<>();
        for (final JsonNode node : resolve(from, path)) {
            if (node.isArray()) {
                node.forEach(e -> addScalar(e, out));
            } else {
                addScalar(node, out);
            }
        }
        return out;
    }

    private static void addScalar(final JsonNode node, final List<String> out) {
        if (node.isValueNode() && !node.isNull()) {
            out.add(node.asText());
        }
    }

    static List<JsonNode> resolve(final JsonNode from, final String path) {
        List<JsonNode> current = new ArrayList<>(List.of(from));
        final String normalized = path == null ? "" : path.trim().replace("[*]", ".*").replaceAll("\\[(\\d+)]", ".$1");
        if (normalized.isEmpty() || normalized.equals("$")) {
            return current;
        }
        final String body = normalized.startsWith("$.") ? normalized.substring(2) : normalized;
        for (final String segment : body.split("\\.")) {
            if (segment.isEmpty()) {
                continue;
            }
            final List<JsonNode> next = new ArrayList<>();
            for (final JsonNode node : current) {
                if (segment.equals("*")) {
                    final Iterator<JsonNode> it = node.elements();
                    it.forEachRemaining(next::add);
                } else if (node.isArray() && segment.chars().allMatch(Character::isDigit)) {
                    final JsonNode child = node.get(Integer.parseInt(segment));
                    if (child != null) {
                        next.add(child);
                    }
                } else if (node.isObject()) {
                    final JsonNode child = node.get(segment);
                    if (child != null) {
                        next.add(child);
                    }
                }
            }
            current = next;
        }
        return current;
    }
}
