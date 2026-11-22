/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.closure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sb2k16.jobboard.ClosureConfig;
import io.github.sb2k16.jobboard.adapter.BoardFetchException;
import io.github.sb2k16.jobboard.adapter.HttpFetcher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

class JobVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final String OLD = "2026-11-10T00:00:00Z";
    private static final String RECENT = "2026-11-22T06:00:00Z";

    /** Pages by URL: a String is a 200 body, an Integer an HTTP status, anything else a network error. */
    static final class Pages implements HttpFetcher {
        final Map<String, Object> pages = new HashMap<>();
        final List<String> requests = new ArrayList<>();

        Pages on(final String url, final Object page) {
            pages.put(url, page);
            return this;
        }

        @Override
        public String get(final String url) {
            requests.add(url);
            final Object p = pages.get(url);
            if (p instanceof String) {
                return (String) p;
            }
            if (p instanceof Integer) {
                throw new BoardFetchException("GET " + url + " returned HTTP " + p, null, false,
                        BoardFetchException.Kind.HTTP_STATUS, (Integer) p);
            }
            throw new BoardFetchException("GET " + url + " failed: connection reset", true);
        }
    }

    static final class Index implements IndexClient {
        final List<Map<String, Object>> docs = new ArrayList<>();
        int searchedSize;

        @Override
        public void refresh() {
        }

        @Override
        public long count(final Map<String, Object> query) {
            return 0;
        }

        @Override
        public List<Map<String, Object>> search(final Map<String, Object> query, final int size, final List<String> fields,
                                                final List<Map<String, Object>> sort) {
            searchedSize = size;
            return docs;
        }

        Index job(final String key, final String title, final Map<String, Object>... links) {
            docs.add(Map.of("job_key", key, "title", title, "source_links", List.of(links)));
            return this;
        }
    }

    private static Map<String, Object> link(final String url, final String board, final String lastSeen) {
        return Map.of("url", url, "platform", "workday", "board_id", board, "state", "live", "last_seen", lastSeen);
    }

    private static ClosureConfig config(final String json) throws Exception {
        return new ObjectMapper().findAndRegisterModules().readValue(json, ClosureConfig.class);
    }

    /** Each write the verifier sends to the buffer, in order. */
    private final List<List<Map<String, Object>>> writes = new ArrayList<>();
    private final Commands out = writes::add;

    private static JobVerifier verifier(final Index index) throws Exception {
        return new JobVerifier(config("{\"opensearch_url\":\"http://x\",\"verify_max_per_crawl\":50}"), index);
    }

    @SuppressWarnings("unchecked")
    private static List<String> urls(final List<Map<String, Object>> write) {
        final List<String> out = new ArrayList<>();
        write.forEach(c -> out.addAll((List<String>) c.get("urls")));
        return out;
    }

    private static String verdict(final List<Map<String, Object>> write) {
        return (String) write.get(0).get("verdict");
    }

    @Test
    void notFoundAndGoneWordingClose_titleOnThePageConfirms_errorsChangeNothing() throws Exception {
        final Index index = new Index()
                .job("a", "Staff Nurse", link("https://h.test/a", "cvs", OLD))
                .job("b", "Pharmacist", link("https://h.test/b", "cvs", OLD))
                .job("c", "Store Manager", link("https://h.test/c", "cvs", OLD))
                .job("d", "Data Analyst", link("https://h.test/d", "cvs", OLD));
        final Pages pages = new Pages()
                .on("https://h.test/a", 404)
                .on("https://h.test/b", "<h1>Sorry, this job is no longer available.</h1>")
                .on("https://h.test/c", "<title>Store Manager - CVS</title><p>Apply now</p>")
                .on("https://h.test/d", "network");

        final JobVerifier.Result r = verifier(index).verify("workday", "cvs", pages, NOW, out);

        assertThat(r.getGone(), equalTo(2));
        assertThat(r.getAlive(), equalTo(1));
        assertThat(r.getUnknown(), equalTo(1));
        assertThat(writes, hasSize(2));
        assertThat(verdict(writes.get(0)), equalTo("gone"));
        assertThat(urls(writes.get(0)), containsInAnyOrder("https://h.test/a", "https://h.test/b"));
        assertThat(verdict(writes.get(1)), equalTo("alive"));
        assertThat(urls(writes.get(1)), contains("https://h.test/c"));
    }

    @Test
    void onlyThisBoardsLiveLinkUnseenSinceTheCutoffIsChecked() throws Exception {
        // source_links is not nested: the index can match on fields of different links.
        final Index index = new Index()
                .job("recent", "A", link("https://h.test/recent", "cvs", RECENT))
                .job("other", "B", link("https://h.test/other", "other-board", OLD))
                .job("old", "C", link("https://h.test/other2", "other-board", OLD), link("https://h.test/old", "cvs", OLD));
        final Pages pages = new Pages().on("https://h.test/old", 410);

        verifier(index).verify("workday", "cvs", pages, NOW, out);

        assertThat(pages.requests, contains("https://h.test/old"));
    }

    @Test
    void manyPagesReadingAsGone_closeNothing_butConfirmationsStillApply() throws Exception {
        final Index index = new Index();
        final Pages pages = new Pages();
        for (int i = 0; i < 12; i++) {
            index.job("g" + i, "Job " + i, link("https://h.test/g" + i, "cvs", OLD));
            pages.on("https://h.test/g" + i, "<p>Applications are now closed for this vacancy</p>");
        }
        index.job("ok", "Night Pharmacist", link("https://h.test/ok", "cvs", OLD));
        pages.on("https://h.test/ok", "<h1>Night Pharmacist</h1>");

        final JobVerifier.Result r = verifier(index).verify("workday", "cvs", pages, NOW, out);

        assertThat(r.isHeld(), equalTo(true));
        assertThat(writes, hasSize(1));
        assertThat(verdict(writes.get(0)), equalTo("alive"));
    }

    @Test
    void stopsAfterFiveUnreadablePagesInARow() throws Exception {
        final Index index = new Index();
        for (int i = 0; i < 10; i++) {
            index.job("e" + i, "Job " + i, link("https://h.test/e" + i, "cvs", OLD));
        }
        final Pages pages = new Pages();

        final JobVerifier.Result r = verifier(index).verify("workday", "cvs", pages, NOW, out);

        assertThat(pages.requests, hasSize(JobVerifier.STOP_AFTER_ERRORS));
        assertThat(r.getUnknown(), equalTo(JobVerifier.STOP_AFTER_ERRORS));
        assertThat(writes, empty());
    }

    @Test
    void offWhenTheBudgetIsZero() throws Exception {
        final Index index = new Index().job("a", "A", link("https://h.test/a", "cvs", OLD));
        final JobVerifier v = new JobVerifier(config("{\"opensearch_url\":\"http://x\",\"verify_max_per_crawl\":0}"), index);
        final Pages pages = new Pages();

        v.verify("workday", "cvs", pages, NOW, out);

        assertThat(pages.requests, empty());
    }

    @Test
    void judge_readsGoneWordingAndTitle_conservatively() {
        assertThat(JobVerifier.judge("This position has been filled.", "Nurse"), equalTo(JobVerifier.Verdict.GONE));
        assertThat(JobVerifier.judge("Job not found", "Nurse"), equalTo(JobVerifier.Verdict.GONE));
        assertThat(JobVerifier.judge("<h1>Senior  Nurse &amp; Midwife</h1>", "Senior Nurse & Midwife"),
                equalTo(JobVerifier.Verdict.ALIVE));
        // A deadline is not a closure, and a page without the title proves nothing.
        assertThat(JobVerifier.judge("<h1>Nurse Lead</h1> Applications close on 31 December", "Nurse Lead"),
                equalTo(JobVerifier.Verdict.ALIVE));
        assertThat(JobVerifier.judge("<div id=app></div>", "Nurse Lead"), equalTo(JobVerifier.Verdict.UNKNOWN));
    }
}
