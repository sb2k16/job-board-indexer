/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One platform described as data (design: "Platform spec, one per platform"). A class A platform
 * is fully described by its spec: where the list lives, how it pages, what proves the response
 * is a real board, and how each job maps to the job record. See {@code config/specs/README.md}.
 */
public class PlatformSpec {

    public enum Format { json, xml }

    @JsonProperty("name")
    private String name;

    @JsonProperty("access_class")
    private String accessClass = "A";

    @JsonProperty("format")
    private Format format;

    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("list")
    private ListSpec list;

    @JsonProperty("pagination")
    private PaginationSpec pagination = new PaginationSpec();

    /** XML namespace prefixes usable in XPath expressions. */
    @JsonProperty("namespaces")
    private Map<String, String> namespaces = new LinkedHashMap<>();

    @JsonProperty("fields")
    private Map<String, FieldSpec> fields = new LinkedHashMap<>();

    /** Read by the politeness layer (T2.4); carried here so the spec format does not change later. */
    @JsonProperty("rate_limit")
    private Map<String, Object> rateLimit;

    public String getName() {
        return name;
    }

    public String getAccessClass() {
        return accessClass;
    }

    public Format getFormat() {
        return format;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public ListSpec getList() {
        return list;
    }

    public PaginationSpec getPagination() {
        return pagination;
    }

    public Map<String, String> getNamespaces() {
        return namespaces;
    }

    public Map<String, FieldSpec> getFields() {
        return fields;
    }

    public Map<String, Object> getRateLimit() {
        return rateLimit;
    }

    /** The listing request and what a trustworthy response looks like. */
    public static class ListSpec {
        /** URL template; variables {base_url}, {board} (URL-encoded) and {company}. */
        @JsonProperty("url")
        private String url;

        /** JSON path or XPath to the job items; "$" is the JSON root. */
        @JsonProperty("items")
        private String items;

        /** Paths that must be present, or the response is a failed fetch (board marker). */
        @JsonProperty("marker")
        private List<String> marker = List.of();

        /** Path to the platform's job count; when set, the snapshot is complete only if it matches. */
        @JsonProperty("total")
        private String total;

        public String getUrl() {
            return url;
        }

        public String getItems() {
            return items;
        }

        public List<String> getMarker() {
            return marker;
        }

        public String getTotal() {
            return total;
        }
    }

    /**
     * Styles: {@code none} (one request), {@code offset} (param = offset, size_param = limit),
     * {@code page} (param = page number), {@code cursor} (next = path to the cursor, param = its
     * query name) and {@code next_url} (next = path to the next page's URL).
     */
    public static class PaginationSpec {
        @JsonProperty("style")
        private String style = "none";

        @JsonProperty("param")
        private String param;

        @JsonProperty("size_param")
        private String sizeParam;

        @JsonProperty("page_size")
        private Integer pageSize;

        @JsonProperty("start")
        private Integer start;

        @JsonProperty("next")
        private String next;

        /** Safety stop; a board that hits it is an incomplete snapshot, never a complete one. */
        @JsonProperty("max_pages")
        private int maxPages = 500;

        public String getStyle() {
            return style;
        }

        public String getParam() {
            return param;
        }

        public String getSizeParam() {
            return sizeParam;
        }

        public Integer getPageSize() {
            return pageSize;
        }

        public Integer getStart() {
            return start;
        }

        public String getNext() {
            return next;
        }

        public int getMaxPages() {
            return maxPages;
        }
    }
}
