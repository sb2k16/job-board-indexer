/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import io.github.sb2k16.jobboard.PlatformConfig;
import io.github.sb2k16.jobboard.adapter.BoardFetchException;
import io.github.sb2k16.jobboard.adapter.HttpFetcher;
import io.github.sb2k16.jobboard.adapter.PlatformAdapter;
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.JobRecord;
import io.github.sb2k16.jobboard.model.Tenant;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs any class A platform from its spec: requests pages, checks the board marker on each,
 * maps items to job records and decides whether the snapshot is complete.
 *
 * <p>Complete means: paging ended on the platform's own stop signal (not on {@code max_pages}),
 * and, when the spec names a total, the number of distinct jobs equals it.
 */
public class SpecAdapter implements PlatformAdapter {
    private final PlatformSpec spec;

    public SpecAdapter(final PlatformSpec spec) {
        this.spec = spec;
    }

    public PlatformSpec getSpec() {
        return spec;
    }

    @Override
    public String name() {
        return spec.getName();
    }

    @Override
    public BoardSnapshot fetchBoard(final PlatformConfig platform, final Tenant tenant, final HttpFetcher fetcher) {
        final Map<String, String> vars = new HashMap<>();
        vars.put("base_url", platform.getBaseUrl() != null ? platform.getBaseUrl() : spec.getBaseUrl());
        vars.put("board", URLEncoder.encode(tenant.getBoard(), StandardCharsets.UTF_8).replace("+", "%20"));
        if (tenant.getCompany() != null) {
            vars.put("company", tenant.getCompany());
        }
        final String firstUrl = FieldMapper.render(spec.getList().getUrl(), vars, null);
        if (firstUrl == null) {
            throw new IllegalStateException(spec.getName() + ": list.url has an unknown variable: " + spec.getList().getUrl());
        }
        return crawl(firstUrl, vars, platform.getName(), tenant, fetcher);
    }

    private BoardSnapshot crawl(final String firstUrl, final Map<String, String> vars, final String platformName,
                                final Tenant tenant, final HttpFetcher fetcher) {
        final PlatformSpec.PaginationSpec pg = spec.getPagination();
        final String style = pg.getStyle();
        final Map<String, JobRecord> jobs = new LinkedHashMap<>();
        Integer total = null;
        boolean naturalEnd = false;
        int position = pg.getStart() != null ? pg.getStart() : (style.equals("page") ? 1 : 0);
        String url = pagedUrl(firstUrl, position);

        for (int pageNo = 0; pageNo < pg.getMaxPages(); pageNo++) {
            final String where = spec.getName() + "/" + tenant.getBoard() + " page " + (pageNo + 1);
            final Page page = parse(fetcher.get(url), where);
            for (final String marker : spec.getList().getMarker()) {
                if (!page.exists(marker)) {
                    throw new BoardFetchException(where + ": board marker '" + marker + "' missing", false);
                }
            }
            final List<Page.Node> items = page.items(spec.getList().getItems());
            if (items == null) {
                throw new BoardFetchException(where + ": items '" + spec.getList().getItems() + "' is not a list", false);
            }
            if (spec.getList().getTotal() != null && total == null) {
                total = readTotal(page, where);
            }
            for (final Page.Node item : items) {
                final JobRecord job = FieldMapper.map(spec.getFields(), item, vars)
                        .setPlatform(platformName)
                        .setBoardId(tenant.getBoard());
                if (tenant.getCompany() != null) {
                    job.setCompany(tenant.getCompany());
                }
                if (job.getPlatformJobId() == null) {
                    job.setPlatformJobId(job.getUrl());
                }
                // Pages can shift while we read them; keep the first copy of a repeated job.
                jobs.putIfAbsent(job.getPlatformJobId() != null ? job.getPlatformJobId() : "#" + jobs.size(), job);
            }

            final String next = nextUrl(page, url, items.size(), jobs.size(), total);
            if (next == null) {
                naturalEnd = true;
                break;
            }
            url = next;
        }

        final List<JobRecord> list = new ArrayList<>(jobs.values());
        final boolean complete = naturalEnd && (total == null || total == list.size());
        return new BoardSnapshot(list, total, complete);
    }

    private Page parse(final String body, final String where) {
        try {
            return spec.getFormat() == PlatformSpec.Format.xml
                    ? XmlPage.parse(body, spec.getNamespaces())
                    : JsonPage.parse(body);
        } catch (final Exception e) {
            throw new BoardFetchException(where + ": body is not " + spec.getFormat(), e, false);
        }
    }

    private Integer readTotal(final Page page, final String where) {
        final List<String> values = page.values(spec.getList().getTotal());
        try {
            return values.isEmpty() ? null : (int) Double.parseDouble(values.get(0).trim());
        } catch (final NumberFormatException e) {
            throw new BoardFetchException(where + ": total '" + values.get(0) + "' is not a number", false);
        }
    }

    /** The next page's URL, or null when this was the last page. */
    private String nextUrl(final Page page, final String current, final int pageItems, final int seen, final Integer total) {
        final PlatformSpec.PaginationSpec pg = spec.getPagination();
        switch (pg.getStyle()) {
            case "none":
                return null;
            case "offset":
            case "page": {
                if (pageItems == 0 || (pg.getPageSize() != null && pageItems < pg.getPageSize())
                        || (total != null && seen >= total)) {
                    return null;
                }
                final int step = pg.getStyle().equals("offset") ? pageItems : 1;
                final int position = Integer.parseInt(queryValue(current, pg.getParam())) + step;
                return withParam(stripParam(current, pg.getParam()), pg.getParam(), String.valueOf(position));
            }
            case "cursor": {
                final List<String> cursor = page.values(pg.getNext());
                if (cursor.isEmpty() || cursor.get(0).isBlank() || pageItems == 0) {
                    return null;
                }
                return withParam(stripParam(current, pg.getParam()), pg.getParam(), cursor.get(0));
            }
            case "next_url": {
                final List<String> next = page.values(pg.getNext());
                if (next.isEmpty() || next.get(0).isBlank() || pageItems == 0) {
                    return null;
                }
                return URI.create(current).resolve(next.get(0).trim()).toString();
            }
            default:
                throw new IllegalStateException("Unknown pagination style " + pg.getStyle());
        }
    }

    private String pagedUrl(final String url, final int position) {
        final PlatformSpec.PaginationSpec pg = spec.getPagination();
        if (!pg.getStyle().equals("offset") && !pg.getStyle().equals("page")) {
            return url;
        }
        String out = withParam(url, pg.getParam(), String.valueOf(position));
        if (pg.getSizeParam() != null && pg.getPageSize() != null) {
            out = withParam(out, pg.getSizeParam(), String.valueOf(pg.getPageSize()));
        }
        return out;
    }

    static String withParam(final String url, final String name, final String value) {
        return url + (url.contains("?") ? "&" : "?") + name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String stripParam(final String url, final String name) {
        final int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        final List<String> kept = new ArrayList<>();
        for (final String pair : url.substring(q + 1).split("&")) {
            if (!pair.startsWith(name + "=") && !pair.isEmpty()) {
                kept.add(pair);
            }
        }
        return url.substring(0, q) + (kept.isEmpty() ? "" : "?" + String.join("&", kept));
    }

    private static String queryValue(final String url, final String name) {
        final int q = url.indexOf('?');
        for (final String pair : url.substring(q + 1).split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        throw new IllegalStateException("No " + name + " in " + url);
    }
}
