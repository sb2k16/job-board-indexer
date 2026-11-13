/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * How one job-record field is read from an item. A bare string in YAML is shorthand for
 * {@code path}. Exactly one source is used, in this order: {@code value} (a constant),
 * {@code template}, {@code concat} (every value of every path, joined), {@code path} (the first
 * path that yields a value). {@code transform} then runs in order, and {@code type} converts.
 */
public class FieldSpec {

    @JsonProperty("path")
    private List<String> path;

    @JsonProperty("concat")
    private List<String> concat;

    /** Variables: {base_url}, {board}, {company}, or any item path. */
    @JsonProperty("template")
    private String template;

    @JsonProperty("value")
    private String value;

    /** html_unescape, html_to_text, trim, normalize_list. */
    @JsonProperty("transform")
    private List<String> transform = List.of();

    /** string (default), datetime or boolean. */
    @JsonProperty("type")
    private String type = "string";

    /** datetime: auto (default), iso, epoch_millis, epoch_seconds, rfc1123. */
    @JsonProperty("format")
    private String format = "auto";

    /** Separator when a path yields several values; concat defaults to a blank line. */
    @JsonProperty("join")
    private String join;

    /**
     * Applied after the transforms: the first capturing group of the first match becomes the
     * value. No match yields no value, unless {@code keep_unmatched} is set.
     */
    @JsonProperty("regex")
    private String regex;

    @JsonProperty("keep_unmatched")
    private boolean keepUnmatched;

    /** boolean: values that mean true, compared case-insensitively. */
    @JsonProperty("true_values")
    private List<String> trueValues = List.of("true", "1", "yes");

    public FieldSpec() {
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public FieldSpec(final String path) {
        this.path = List.of(path);
    }

    public List<String> getPath() {
        return path;
    }

    public List<String> getConcat() {
        return concat;
    }

    public String getTemplate() {
        return template;
    }

    public String getValue() {
        return value;
    }

    public List<String> getTransform() {
        return transform;
    }

    public String getType() {
        return type;
    }

    public String getFormat() {
        return format;
    }

    public String getJoin() {
        return join;
    }

    public String getRegex() {
        return regex;
    }

    public boolean isKeepUnmatched() {
        return keepUnmatched;
    }

    public List<String> getTrueValues() {
        return trueValues;
    }
}
