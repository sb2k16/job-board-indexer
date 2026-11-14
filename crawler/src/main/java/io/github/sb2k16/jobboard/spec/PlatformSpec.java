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

    /** html: list.items is a regex over the page; each match is an item (links to detail pages). */
    public enum Format { json, xml, html }

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

    /** The platform's whole budget across all pods; each pod takes its share (T2.4). */
    /** Optional: one more request per job, whose fields fill and override the list's. */
    @JsonProperty("detail")
    private DetailSpec detail;

    public DetailSpec getDetail() {
        return detail;
    }

    @JsonProperty("rate_limit")
    private RateLimitSpec rateLimit = new RateLimitSpec();

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

    public RateLimitSpec getRateLimit() {
        return rateLimit;
    }

    /**
     * Platform-wide request budget. The budget is per platform, not per host: tenants on their own
     * subdomains still share it. Defaults apply when the spec leaves a value out.
     */
    public static class RateLimitSpec {
        public static final double DEFAULT_REQUESTS_PER_SECOND = 2.0;
        public static final int DEFAULT_MAX_CONCURRENCY = 2;

        @JsonProperty("requests_per_second")
        private double requestsPerSecond = DEFAULT_REQUESTS_PER_SECOND;

        @JsonProperty("max_concurrency")
        private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;

        public double getRequestsPerSecond() {
            return requestsPerSecond;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }
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

        /** GET (default) or POST. */
        @JsonProperty("method")
        private String method = "GET";

        /** POST body template (JSON); same variables as url, plus {offset} and {page}. */
        @JsonProperty("body")
        private String body;

        /**
         * Regex applied to the response before parsing; its first group is the document. For job
         * data embedded in an HTML page (a script variable, Next.js __NEXT_DATA__).
         */
        @JsonProperty("extract")
        private String extract;

        public String getMethod() {
            return method;
        }

        public String getBody() {
            return body;
        }

        public String getExtract() {
            return extract;
        }

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
        /**
         * page/offset with the position in the URL template: keep going until a page is missing
         * (404), whatever it holds. For a fixed set of files where any one may hold none of this
         * board's jobs (Hireology's cross-tenant sitemaps).
         */
        @JsonProperty("until_missing")
        private boolean untilMissing;

        public boolean isUntilMissing() {
            return untilMissing;
        }

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

    /**
     * The per-job request. format jsonld reads the schema.org JobPosting in the page's
     * application/ld+json scripts; with no fields given, the standard JobPosting mapping applies
     * (spec/jsonld-fields.yaml). A job whose detail page is gone (404/410, or no JobPosting) is
     * dropped; if every detail page lacks it, the board fails rather than listing nothing.
     */
    public static class DetailSpec {
        /** Template; variables as list.url plus any item path, e.g. {url} or {id}. */
        @JsonProperty("url")
        private String url = "{url}";

        @JsonProperty("format")
        private String format = "jsonld";

        @JsonProperty("extract")
        private String extract;

        /** Detail fields; for jsonld, omitted means the standard JobPosting mapping. */
        @JsonProperty("fields")
        private Map<String, FieldSpec> fields;

        public String getUrl() {
            return url;
        }

        public String getFormat() {
            return format;
        }

        public String getExtract() {
            return extract;
        }

        public Map<String, FieldSpec> getFields() {
            return fields;
        }
    }
}
