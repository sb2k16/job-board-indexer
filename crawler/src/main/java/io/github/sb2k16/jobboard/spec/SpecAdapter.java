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
import io.github.sb2k16.jobboard.PlatformConfig;
import io.github.sb2k16.jobboard.adapter.BoardFetchException;
import io.github.sb2k16.jobboard.adapter.Html;
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
    static final Map<String, FieldSpec> STANDARD_JSONLD_FIELDS = SpecLoader.standardJsonLdFields();

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
        // A board that is several values (Workday host, tenant and site; Oracle host and site number):
        // {board_raw} unencoded, and {board_1}, {board_2}... for the parts of "a|b|c".
        vars.put("board_raw", tenant.getBoard());
        final String[] parts = tenant.getBoard().split("\\|");
        for (int i = 0; i < parts.length; i++) {
            vars.put("board_" + (i + 1), parts[i]);
        }
        if (tenant.getCompany() != null) {
            vars.put("company", tenant.getCompany());
        }
        final Map<String, String> first = new HashMap<>(vars);
        first.put("offset", "0");
        first.put("page", "1");
        final String firstUrl = FieldMapper.render(spec.getList().getUrl(), first, null);
        if (firstUrl == null) {
            throw new IllegalStateException(spec.getName() + ": list.url has an unknown variable: " + spec.getList().getUrl());
        }
        return crawl(firstUrl, vars, platform.getName(), tenant, fetcher);
    }

    private BoardSnapshot crawl(final String firstUrl, final Map<String, String> vars, final String platformName,
                                final Tenant tenant, final HttpFetcher fetcher) {
        final PlatformSpec.PaginationSpec pg = spec.getPagination();
        final PlatformSpec.ListSpec list = spec.getList();
        final String style = pg.getStyle();
        final boolean positional = style.equals("offset") || style.equals("page");
        // Without a param, {offset} or {page} sits in the URL or POST body template itself.
        final boolean templated = positional && pg.getParam() == null;
        final Map<String, JobRecord> jobs = new LinkedHashMap<>();
        final Map<String, Page.Node> nodes = new HashMap<>();
        Integer total = null;
        boolean naturalEnd = false;
        int position = pg.getStart() != null ? pg.getStart() : (style.equals("page") ? 1 : 0);
        String url = templated ? renderAt(list.getUrl(), vars, position) : pagedUrl(firstUrl, position);
        final Integer maxResults = pg.getMaxResults();
        int read = 0;
        int lastItems = pg.getPageSize() != null ? pg.getPageSize() : 0;
        boolean windowEnd = false;
        boolean repeated = false;

        for (int pageNo = 0; pageNo < pg.getMaxPages(); pageNo++) {
            if (maxResults != null && pageNo > 0 && read + lastItems > maxResults) {
                windowEnd = true; // the next page would end past the platform's result window
                break;
            }
            final String where = spec.getName() + "/" + tenant.getBoard() + " page " + (pageNo + 1);
            final String body;
            try {
                body = "POST".equalsIgnoreCase(list.getMethod())
                        ? fetcher.post(url, renderAt(list.getBody() == null ? "{}" : list.getBody(), vars, position))
                        : fetcher.get(url);
            } catch (final BoardFetchException e) {
                if (pg.isUntilMissing() && pageNo > 0 && e.isBoardGone()) {
                    naturalEnd = true; // past the last file
                    break;
                }
                throw e;
            }
            if (pg.isEndOnEmpty() && pageNo > 0 && body.isBlank()) {
                naturalEnd = true;
                break;
            }
            final Page page = parse(body, url, spec.getFormat().name(), list.getExtract(), list.getExtractDecode(), where);
            for (final String marker : list.getMarker()) {
                if (!page.exists(regexVars(marker, vars))) {
                    throw new BoardFetchException(where + ": board marker '" + marker + "' missing", false);
                }
            }
            final List<Page.Node> items = page.items(regexVars(list.getItems(), vars));
            if (maxResults != null && pageNo > 0 && (items == null || items.isEmpty())
                    && read + lastItems >= maxResults) {
                windowEnd = true; // at the window's edge the platform answers with no list
                break;
            }
            if (items == null) {
                throw new BoardFetchException(where + ": items '" + list.getItems() + "' is not a list", false);
            }
            if (list.getTotal() != null && total == null) {
                total = readTotal(page, where);
            }
            read += items.size();
            if (!items.isEmpty()) {
                lastItems = items.size();
            }
            final int before = jobs.size();
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
                final String id = job.getPlatformJobId() != null ? job.getPlatformJobId() : "#" + jobs.size();
                if (jobs.putIfAbsent(id, job) == null) {
                    nodes.put(id, item);
                }
            }

            if (pageNo > 0 && !items.isEmpty() && jobs.size() == before && !pg.isUntilMissing()) {
                // A full page of jobs already seen: the platform ignores the offset or repeats its last
                // page (Avature's feed serves the same 20 at every jobOffset). Stop, and never call
                // that complete: the list was not read to a real end.
                repeated = true;
                break;
            }
            if (templated) {
                if (pg.isUntilMissing()) {
                    position += 1;
                    url = renderAt(list.getUrl(), vars, position);
                    continue;
                }
                if (items.isEmpty() || (pg.getPageSize() != null && items.size() < pg.getPageSize())
                        || (total != null && jobs.size() >= total)) {
                    naturalEnd = true;
                    break;
                }
                position += style.equals("offset") ? items.size() : 1;
                url = renderAt(list.getUrl(), vars, position);
                continue;
            }
            final String next = nextUrl(page, url, items.size(), jobs.size(), total);
            if (next == null) {
                naturalEnd = true;
                break;
            }
            url = next;
        }

        // A single list that holds exactly its cap is the platform's newest N, not the whole board (a
        // JobBoard.io RSS feed stops at 50): calling it complete made every older job look removed,
        // and the mass-closure guard held the board on every crawl (Nov 17 to 22).
        if (style.equals("none") && maxResults != null && read >= maxResults) {
            windowEnd = true;
        }
        // Complete means the list was read to its end and matches the platform's count. A job whose
        // detail page has gone since (expired mid-crawl) is dropped but does not make it partial.
        final boolean complete = naturalEnd && (total == null || total == jobs.size());
        final List<JobRecord> listed = spec.getDetail() == null ? new ArrayList<>(jobs.values())
                : withDetails(jobs, nodes, vars, tenant, fetcher);
        if (spec.getDetail() != null) {
            listed.removeIf(j -> j.getTitle() == null || j.getTitle().isBlank()); // a detail page without a title
        }
        return new BoardSnapshot(listed, total, complete && !windowEnd && !repeated, windowEnd ? maxResults : null);
    }

    private List<JobRecord> withDetails(final Map<String, JobRecord> jobs, final Map<String, Page.Node> nodes,
                                        final Map<String, String> vars, final Tenant tenant, final HttpFetcher fetcher) {
        final PlatformSpec.DetailSpec detail = spec.getDetail();
        final Map<String, FieldSpec> fields = detail.getFields() != null ? detail.getFields() : STANDARD_JSONLD_FIELDS;
        final List<JobRecord> out = new ArrayList<>();
        int noPosting = 0;
        for (final Map.Entry<String, JobRecord> e : jobs.entrySet()) {
            final JobRecord job = e.getValue();
            final Map<String, String> v = new HashMap<>(vars);
            v.put("url", job.getUrl() == null ? "" : job.getUrl());
            v.put("platform_job_id", e.getKey());
            if (detail.getUnless() != null && has(job, detail.getUnless())) {
                out.add(job); // the list already gave what the detail page is fetched for
                continue;
            }
            final String url = FieldMapper.render(detail.getUrl(), v, nodes.get(e.getKey()));
            if (url == null) {
                noPosting++;
                continue;
            }
            final String where = spec.getName() + "/" + tenant.getBoard() + " detail " + url;
            final String body;
            try {
                body = fetcher.get(url);
            } catch (final BoardFetchException ex) {
                if (ex.isBoardGone()) {
                    continue; // the job expired between the list and its detail page
                }
                throw ex;
            }
            final Page page;
            if (detail.getFormat().equals("jsonld")) {
                final JsonNode posting = JsonLd.normalized(body);
                if (posting == null) {
                    noPosting++; // a soft 404: the page answers but no longer holds the job
                    continue;
                }
                page = JsonPage.of(posting);
            } else {
                page = parse(body, url, detail.getFormat(), detail.getExtract(), detail.getExtractDecode(), where);
            }
            job.fillFrom(FieldMapper.map(fields, page::values, v));
            if (tenant.getCompany() != null) {
                job.setCompany(tenant.getCompany());
            }
            out.add(job);
        }
        if (out.isEmpty() && noPosting > 0) {
            // Every page lacks the job data: the platform changed, not every job closed at once.
            throw new BoardFetchException(spec.getName() + "/" + tenant.getBoard() + ": no job data on any of "
                    + noPosting + " detail pages", false);
        }
        return out;
    }

    /**
     * For html pages, whose paths are regexes: {board}, {board_raw} and {board_N} are replaced by
     * the quoted value; every other brace (a regex quantifier such as {36}) is left alone.
     */
    private String regexVars(final String regex, final Map<String, String> vars) {
        if (spec.getFormat() != PlatformSpec.Format.html || regex == null || !regex.contains("{board")) {
            return regex;
        }
        String out = regex;
        for (final Map.Entry<String, String> e : vars.entrySet()) {
            if (e.getKey().startsWith("board")) {
                out = out.replace("{" + e.getKey() + "}", java.util.regex.Pattern.quote(e.getKey().equals("board")
                        ? vars.get("board_raw") : e.getValue()));
            }
        }
        return out;
    }

    private static String renderAt(final String template, final Map<String, String> vars, final int position) {
        final Map<String, String> v = new HashMap<>(vars);
        v.put("offset", String.valueOf(position));
        v.put("page", String.valueOf(position));
        final String out = FieldMapper.render(template, v, null);
        if (out == null) {
            throw new IllegalStateException("template has an unknown variable: " + template);
        }
        return out;
    }

    static boolean has(final JobRecord job, final String field) {
        final Object v;
        switch (field) {
            case "description":
                v = job.getDescription();
                break;
            case "title":
                v = job.getTitle();
                break;
            case "location":
                v = job.getLocation();
                break;
            case "posted_at":
                v = job.getPostedAt();
                break;
            default:
                throw new IllegalArgumentException("detail.unless: unsupported field " + field);
        }
        return v != null && !v.toString().isBlank();
    }

    private Page parse(final String body, final String url, final String format, final String extract,
                       final String decode, final String where) {
        try {
            String text = body;
            if (extract != null) {
                text = FieldMapper.extract(extract, body, false);
                if (text == null) {
                    throw new BoardFetchException(where + ": embedded data not found", false);
                }
            }
            if ("html".equals(decode)) {
                text = Html.unescape(text);
            } else if ("js".equals(decode)) {
                text = Html.jsString(text);
            }
            switch (format) {
                case "xml":
                    return XmlPage.parse(text, spec.getNamespaces());
                case "html":
                    return new HtmlPage(text, url);
                default:
                    return JsonPage.parse(text);
            }
        } catch (final BoardFetchException e) {
            throw e;
        } catch (final Exception e) {
            throw new BoardFetchException(where + ": body is not " + format, e, false);
        }
    }

    private Integer readTotal(final Page page, final String where) {
        final List<String> values = page.values(spec.getList().getTotal());
        try {
            // "1,037" and "1 037" are counts too (HTML result labels).
            return values.isEmpty() ? null : (int) Double.parseDouble(values.get(0).trim().replaceAll("[,\\s\u00a0]", ""));
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
                return Html.uri(current).resolve(Html.uri(next.get(0))).toString();
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
