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
import io.github.sb2k16.jobboard.adapter.Html;
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.Tenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Engine behaviour that the shipped specs do not all exercise: every pagination style, limits, loader errors. */
class SpecEngineTest {
    @TempDir
    Path dir;

    private final Tenant acme = new Tenant("acme", "Acme");
    private final PlatformConfig platform = new PlatformConfig("p", null, null, List.of());

    private SpecAdapter spec(final String pagination) throws IOException {
        final Path file = dir.resolve("p.yaml");
        Files.writeString(file, "name: p\nformat: json\nbase_url: https://x.test\n"
                + "list: { url: \"{base_url}/{board}/jobs\", items: data, marker: [data] }\n"
                + pagination
                + "fields: { platform_job_id: id, title: t, url: { template: \"{base_url}/{board}/job/{id}\" } }\n");
        return new SpecAdapter(SpecLoader.load(file));
    }

    private static String page(final int from, final int to, final String extra) {
        final String items = java.util.stream.IntStream.range(from, to)
                .mapToObj(i -> "{\"id\":" + i + ",\"t\":\"Job " + i + "\"}").collect(Collectors.joining(","));
        return "{\"data\":[" + items + "]" + extra + "}";
    }

    @Test
    void offset_pagesUntilShortPage() throws IOException {
        final FakeFetcher f = new FakeFetcher().respond(url -> {
            final int off = Integer.parseInt(FakeFetcher.param(url, "offset"));
            return page(off, Math.min(5, off + 2), "");
        });
        final BoardSnapshot s = spec("pagination: { style: offset, param: offset, size_param: n, page_size: 2 }\n")
                .fetchBoard(platform, acme, f);
        assertThat(f.requests, equalTo(List.of(
                "https://x.test/acme/jobs?offset=0&n=2", "https://x.test/acme/jobs?n=2&offset=2", "https://x.test/acme/jobs?n=2&offset=4")));
        assertThat(s.getJobs().size(), equalTo(5));
        assertThat(s.isComplete(), equalTo(true));
        assertThat(s.getJobs().get(4).getUrl(), equalTo("https://x.test/acme/job/4"));
    }

    @Test
    void page_withoutPageSize_stopsOnEmptyPage() throws IOException {
        final FakeFetcher f = new FakeFetcher().respond(url -> {
            final int p = Integer.parseInt(FakeFetcher.param(url, "page"));
            return p <= 2 ? page((p - 1) * 3, p * 3, "") : page(0, 0, "");
        });
        final BoardSnapshot s = spec("pagination: { style: page, param: page }\n").fetchBoard(platform, acme, f);
        assertThat(f.requests.size(), equalTo(3));
        assertThat(s.getJobs().size(), equalTo(6));
        assertThat(s.isComplete(), equalTo(true));
    }

    @Test
    void cursor_followsUntilNoCursor() throws IOException {
        final FakeFetcher f = new FakeFetcher()
                .on("https://x.test/acme/jobs", page(0, 2, ",\"next\":\"abc==\""))
                .on("https://x.test/acme/jobs?c=abc%3D%3D", page(2, 3, ",\"next\":null"));
        final BoardSnapshot s = spec("pagination: { style: cursor, param: c, next: next }\n").fetchBoard(platform, acme, f);
        assertThat(s.getJobs().size(), equalTo(3));
        assertThat(s.isComplete(), equalTo(true));
    }

    @Test
    void nextUrl_resolvesRelativeLinks() throws IOException {
        final FakeFetcher f = new FakeFetcher()
                .on("https://x.test/acme/jobs", page(0, 2, ",\"links\":{\"next\":\"/acme/jobs?p=2\"}"))
                .on("https://x.test/acme/jobs?p=2", page(2, 4, ",\"links\":{}"));
        final BoardSnapshot s = spec("pagination: { style: next_url, next: links.next }\n").fetchBoard(platform, acme, f);
        assertThat(s.getJobs().size(), equalTo(4));
        assertThat(s.isComplete(), equalTo(true));
    }

    @Test
    void maxPages_reached_isIncompleteNotComplete() throws IOException {
        final FakeFetcher f = new FakeFetcher().respond(url -> {
            final int p = Integer.parseInt(FakeFetcher.param(url, "page"));
            return page(p * 10, p * 10 + 10, "");
        });
        final BoardSnapshot s = spec("pagination: { style: page, param: page, max_pages: 3 }\n").fetchBoard(platform, acme, f);
        assertThat(f.requests.size(), equalTo(3));
        assertThat(s.getJobs().size(), equalTo(30));
        assertThat(s.isComplete(), equalTo(false));
    }

    @Test
    void repeatedJobAcrossPages_isKeptOnce() throws IOException {
        // A posting added mid-crawl shifts the window; job 1 then appears on both pages.
        final FakeFetcher f = new FakeFetcher()
                .on("https://x.test/acme/jobs?page=1", page(0, 2, ""))
                .on("https://x.test/acme/jobs?page=2", page(1, 3, ""))
                .on("https://x.test/acme/jobs?page=3", page(0, 0, ""));
        final BoardSnapshot s = spec("pagination: { style: page, param: page }\n").fetchBoard(platform, acme, f);
        assertThat(s.getJobs().stream().map(j -> j.getPlatformJobId()).collect(Collectors.toList()), equalTo(List.of("0", "1", "2")));
    }

    @Test
    void markerMissingOnLaterPage_failsWholeBoard() throws IOException {
        final FakeFetcher f = new FakeFetcher()
                .on("https://x.test/acme/jobs?page=1", page(0, 2, ""))
                .on("https://x.test/acme/jobs?page=2", "{\"error\":\"rate limited\"}");
        final SpecAdapter a = spec("pagination: { style: page, param: page }\n");
        final BoardFetchException e = assertThrows(BoardFetchException.class, () -> a.fetchBoard(platform, acme, f));
        assertThat(e.getMessage(), containsString("page 2"));
    }

    @Test
    void totalReached_stopsWithoutExtraRequest() throws IOException {
        final Path file = dir.resolve("t.yaml");
        Files.writeString(file, "name: t\nformat: json\nbase_url: https://x.test\n"
                + "list: { url: \"{base_url}/{board}/jobs\", items: data, marker: [data, count], total: count }\n"
                + "pagination: { style: page, param: page }\n"
                + "fields: { platform_job_id: id, title: t, url: { value: \"https://x.test\" } }\n");
        final FakeFetcher f = new FakeFetcher()
                .on("https://x.test/acme/jobs?page=1", page(0, 2, ",\"count\":4"))
                .on("https://x.test/acme/jobs?page=2", page(2, 4, ",\"count\":4"));
        final BoardSnapshot s = new SpecAdapter(SpecLoader.load(file)).fetchBoard(platform, acme, f);
        assertThat(f.requests.size(), equalTo(2));
        assertThat(s.isComplete(), equalTo(true));
        assertThat(s.getReportedTotal(), equalTo(4));
    }

    @Test
    void templateWithMissingItemValue_yieldsNoUrl() {
        final Page.Node item = path -> List.of();
        assertThat(FieldMapper.render("{base_url}/job/{id}", java.util.Map.of("base_url", "b"), item), nullValue());
    }

    @Test
    void datetimeFormats() {
        assertThat(FieldMapper.instant("2026-11-05T02:20:02.702+00:00", "auto"), equalTo(Instant.parse("2026-11-05T02:20:02.702Z")));
        assertThat(FieldMapper.instant("2026-10-30 16:32:58 UTC", "auto"), equalTo(Instant.parse("2026-10-30T16:32:58Z")));
        assertThat(FieldMapper.instant("2026-11-01", "auto"), equalTo(Instant.parse("2026-11-01T00:00:00Z")));
        assertThat(FieldMapper.instant("1786469891368", "auto"), equalTo(Instant.ofEpochMilli(1786469891368L)));
        assertThat(FieldMapper.instant("1786469891", "auto"), equalTo(Instant.ofEpochSecond(1786469891L)));
        assertThat(FieldMapper.instant("Wed, 27 Feb 2019 00:00:00 EST", "rfc1123"), equalTo(Instant.parse("2019-02-27T05:00:00Z")));
        assertThat(FieldMapper.instant("Mon, 3 Aug 2026 09:00:00 PDT", "rfc1123"), equalTo(Instant.parse("2026-08-03T16:00:00Z")));
        assertThat(FieldMapper.instant("Tue, 10 Nov 2026 23:46:15 +0100", "rfc1123"), equalTo(Instant.parse("2026-11-10T22:46:15Z")));
        assertThat(FieldMapper.instant("Posted Today", "auto"), nullValue());
    }

    @Test
    void transforms() {
        assertThat(FieldMapper.transform("normalize_list", ",Portland,OR,97224"), equalTo("Portland, OR, 97224"));
        assertThat(FieldMapper.transform("normalize_list", ",,,"), equalTo(""));
        assertThat(Html.toText(Html.unescape("&lt;p&gt;A &amp;amp; B&lt;/p&gt;&lt;p&gt;C&#39;s&lt;/p&gt;")), equalTo("A & B\nC's"));
        assertThat(Html.unescape("&#x41;&#66;"), equalTo("AB"));
        assertThat(Html.toText(null), nullValue());
    }

    @Test
    void loader_listsEveryProblem() throws IOException {
        final Path file = dir.resolve("bad.yaml");
        Files.writeString(file, "name: bad\nformat: json\n"
                + "list: { url: \"{base_url}/x\", items: jobs }\n"
                + "pagination: { style: offset }\n"
                + "fields:\n  title: { path: t, transform: [shout] }\n  salary: pay\n  remote: { path: r }\n");
        final SpecLoader.InvalidSpecException e = assertThrows(SpecLoader.InvalidSpecException.class, () -> SpecLoader.load(file));
        for (final String expected : List.of("base_url is not set", "list.marker", "pagination.offset needs param",
                "fields.url is required", "transform 'shout'", "fields.salary is not a job record field", "fields.remote must have type boolean")) {
            assertThat(e.getMessage(), containsString(expected));
        }
    }

    @Test
    void loader_rejectsUnknownKeysAndNonClassA() throws IOException {
        final Path typo = dir.resolve("typo.yaml");
        Files.writeString(typo, "name: t\nformat: json\nlist: { url: u, items: x, marker: [x], totl: n }\nfields: { title: t, url: u }\n");
        assertThrows(SpecLoader.InvalidSpecException.class, () -> SpecLoader.load(typo));

        final Path classC = dir.resolve("c.yaml");
        Files.writeString(classC, "name: c\naccess_class: C\nformat: json\nlist: { url: u, items: x, marker: [x] }\nfields: { title: t, url: u }\n");
        final SpecLoader.InvalidSpecException e = assertThrows(SpecLoader.InvalidSpecException.class, () -> SpecLoader.load(classC));
        assertThat(e.getMessage(), containsString("only A"));
    }
}
