/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a platform spec file and rejects one that cannot work, with every problem listed, so a
 * bad spec stops the pipeline at start-up rather than failing per board. The full schema and CI
 * validation against fixtures is T2.7.
 */
public final class SpecLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private SpecLoader() {
    }

    public static PlatformSpec load(final Path file) {
        final PlatformSpec spec;
        try {
            spec = YAML.readValue(file.toFile(), PlatformSpec.class);
        } catch (final IOException e) {
            throw new InvalidSpecException(file + ": " + e.getMessage(), e);
        }
        final List<String> problems = validate(spec);
        if (!problems.isEmpty()) {
            throw new InvalidSpecException(file + ": " + String.join("; ", problems));
        }
        return spec;
    }

    static List<String> validate(final PlatformSpec spec) {
        final List<String> problems = new ArrayList<>();
        if (isBlank(spec.getName())) {
            problems.add("name is required");
        }
        if (!"A".equals(spec.getAccessClass())) {
            problems.add("access_class " + spec.getAccessClass() + " is not run by the spec engine (only A)");
        }
        if (spec.getFormat() == null) {
            problems.add("format is required (json or xml)");
        }
        final PlatformSpec.ListSpec list = spec.getList();
        if (list == null) {
            problems.add("list is required");
        } else {
            if (isBlank(list.getUrl())) {
                problems.add("list.url is required");
            } else if (list.getUrl().contains("{base_url}") && isBlank(spec.getBaseUrl())) {
                problems.add("list.url uses {base_url} but base_url is not set");
            }
            if (isBlank(list.getItems())) {
                problems.add("list.items is required");
            }
            if (list.getMarker() == null || list.getMarker().isEmpty()) {
                problems.add("list.marker needs at least one path (a response without it is a failed fetch)");
            }
        }
        validatePagination(spec.getPagination(), problems);

        for (final String required : List.of("title", "url")) {
            if (!spec.getFields().containsKey(required)) {
                problems.add("fields." + required + " is required");
            }
        }
        for (final Map.Entry<String, FieldSpec> e : spec.getFields().entrySet()) {
            validateField(e.getKey(), e.getValue(), problems);
        }
        return problems;
    }

    private static void validatePagination(final PlatformSpec.PaginationSpec pg, final List<String> problems) {
        switch (pg.getStyle()) {
            case "none":
                break;
            case "offset":
            case "page":
                if (isBlank(pg.getParam())) {
                    problems.add("pagination." + pg.getStyle() + " needs param");
                }
                if (pg.getSizeParam() != null && pg.getPageSize() == null) {
                    problems.add("pagination.size_param needs page_size");
                }
                break;
            case "cursor":
                if (isBlank(pg.getParam()) || isBlank(pg.getNext())) {
                    problems.add("pagination.cursor needs param and next");
                }
                break;
            case "next_url":
                if (isBlank(pg.getNext())) {
                    problems.add("pagination.next_url needs next");
                }
                break;
            default:
                problems.add("pagination.style '" + pg.getStyle() + "' is not one of none, offset, page, cursor, next_url");
        }
        if (pg.getMaxPages() < 1) {
            problems.add("pagination.max_pages must be at least 1");
        }
    }

    private static void validateField(final String name, final FieldSpec f, final List<String> problems) {
        final String where = "fields." + name;
        if (!FieldMapper.SETTERS.containsKey(name)) {
            problems.add(where + " is not a job record field " + FieldMapper.SETTERS.keySet().stream().sorted().collect(java.util.stream.Collectors.toList()));
            return;
        }
        final int sources = (f.getPath() != null ? 1 : 0) + (f.getConcat() != null ? 1 : 0)
                + (f.getTemplate() != null ? 1 : 0) + (f.getValue() != null ? 1 : 0);
        if (sources != 1) {
            problems.add(where + " needs exactly one of path, concat, template, value");
        }
        if (!FieldMapper.TYPES.contains(f.getType())) {
            problems.add(where + ".type '" + f.getType() + "' is not one of " + FieldMapper.TYPES);
        }
        final String fixed = FieldMapper.FIELD_TYPES.getOrDefault(name, "string");
        if (FieldMapper.TYPES.contains(f.getType()) && !fixed.equals(f.getType())) {
            problems.add(where + " must have type " + fixed);
        }
        if (!FieldMapper.DATETIME_FORMATS.contains(f.getFormat())) {
            problems.add(where + ".format '" + f.getFormat() + "' is not one of " + FieldMapper.DATETIME_FORMATS);
        }
        for (final String t : f.getTransform()) {
            if (!FieldMapper.TRANSFORMS.contains(t)) {
                problems.add(where + ".transform '" + t + "' is not one of " + FieldMapper.TRANSFORMS);
            }
        }
    }

    private static boolean isBlank(final String s) {
        return s == null || s.isBlank();
    }

    /** A spec that cannot run. */
    public static class InvalidSpecException extends RuntimeException {
        public InvalidSpecException(final String message) {
            super(message);
        }

        public InvalidSpecException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
