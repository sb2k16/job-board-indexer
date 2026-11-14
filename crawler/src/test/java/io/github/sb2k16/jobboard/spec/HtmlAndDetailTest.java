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
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.JobRecord;
import io.github.sb2k16.jobboard.model.Tenant;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The html format, JSON-LD detail pages, POST and templated paging, until_missing, board parts. */
class HtmlAndDetailTest {

    private static SpecAdapter spec(final String yaml) throws Exception {
        final Path f = Files.createTempFile("spec", ".yaml");
        Files.writeString(f, yaml);
        return new SpecAdapter(SpecLoader.load(f));
    }

    private static BoardSnapshot run(final SpecAdapter a, final String board, final FakeFetcher f) {
        return a.fetchBoard(new PlatformConfig("p", null, null, null), new Tenant(board, null), f);
    }

    private static String posting(final String title, final String extra) {
        return "<html><script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"Organization\"},"
                + "{\"@type\":\"JobPosting\",\"title\":\"" + title + "\",\"description\":\"<p>Do &amp; things\n now</p>\","
                + "\"datePosted\":\"2026-11-01\",\"hiringOrganization\":{\"name\":\"Acme\"},\"identifier\":{\"value\":\"R-1\"},"
                + "\"employmentType\":[\"FULL_TIME\",\"CONTRACTOR\"]," + extra + "}]}</script></html>";
    }

    private static final String HTML_SPEC = "name: t\nformat: html\nlist:\n  url: \"https://{board}.example.com/jobs\"\n"
            + "  items: 'href=\"(/{board}/job/\\d+)\"'\n  marker: ['careers']\npagination:\n  style: next_url\n"
            + "  next: 'rel=\"next\" href=\"([^\"]+)\"'\nfields:\n  url: url\ndetail:\n  format: jsonld\n";

    @Test
    void htmlListWithPagingAndJsonLdDetails() throws Exception {
        final FakeFetcher f = new FakeFetcher()
                .on("https://acme.example.com/jobs", "careers <a href=\"/acme/job/1\"> <a href=\"/acme/job/1\"> <a href=\"/other/job/9\">"
                        + " <a rel=\"next\" href=\"/jobs?page=2&amp;x=1\">")
                .on("https://acme.example.com/jobs?page=2&x=1", "careers <a href=\"/acme/job/2\"> <a href=\"/acme/job/3\">")
                .on("https://acme.example.com/acme/job/1", posting("Welder",
                        "\"jobLocation\":[{\"address\":{\"addressLocality\":\"Austin\",\"addressRegion\":\"TX\",\"addressCountry\":\"US\"}},"
                                + "{\"address\":{\"addressLocality\":\"Boston\",\"addressRegion\":\"MA\",\"addressCountry\":{\"name\":\"US\"}}}]"))
                .on("https://acme.example.com/acme/job/2", posting("Remote Dev", "\"jobLocationType\":\"TELECOMMUTE\""))
                .on("https://acme.example.com/acme/job/3", "<html>This job is no longer available</html>"); // soft 404
        final BoardSnapshot s = run(spec(HTML_SPEC), "acme", f);
        final List<JobRecord> jobs = s.getJobs();
        assertThat(jobs, hasSize(2));
        assertThat(s.isComplete(), equalTo(true));
        final JobRecord a = jobs.get(0);
        assertThat(a.getUrl(), equalTo("https://acme.example.com/acme/job/1"));
        assertThat(a.getTitle(), equalTo("Welder"));
        assertThat(a.getCompany(), equalTo("Acme"));
        assertThat(a.getLocation(), equalTo("Austin, TX, US; Boston, MA, US"));
        assertThat(a.getCity(), equalTo("Austin"));
        assertThat(a.getRequisitionId(), equalTo("R-1"));
        assertThat(a.getEmploymentType(), equalTo("FULL_TIME, CONTRACTOR"));
        assertThat(a.getDescription(), containsString("Do & things"));
        assertThat(a.getPostedAt().toString(), equalTo("2026-11-01T00:00:00Z"));
        assertThat(jobs.get(1).getRemote(), equalTo(true));
    }

    @Test
    void everyDetailPageWithoutJobDataFailsTheBoard() throws Exception {
        final FakeFetcher f = new FakeFetcher()
                .on("https://acme.example.com/jobs", "careers <a href=\"/acme/job/1\">")
                .on("https://acme.example.com/acme/job/1", "<html>redesigned page</html>");
        final BoardFetchException e = assertThrows(BoardFetchException.class, () -> run(spec(HTML_SPEC), "acme", f));
        assertThat(e.getMessage(), containsString("no job data on any of 1 detail pages"));
    }

    @Test
    void aDetailPageGoneIsDroppedWithoutFailingTheBoard() throws Exception {
        final FakeFetcher f = new FakeFetcher()
                .on("https://acme.example.com/jobs", "careers <a href=\"/acme/job/1\"> <a href=\"/acme/job/2\">")
                .on("https://acme.example.com/acme/job/1", posting("Welder", "\"x\":1")); // job/2 answers 404
        final BoardSnapshot s = run(spec(HTML_SPEC), "acme", f);
        assertThat(s.getJobs(), hasSize(1));
        assertThat(s.isComplete(), equalTo(true));
    }

    @Test
    void postWithTheOffsetInTheBody() throws Exception {
        final SpecAdapter a = spec("name: t\nformat: json\nlist:\n  url: \"https://{board_1}/wday/cxs/{board_2}/{board_3}/jobs\"\n"
                + "  method: POST\n  body: '{\"limit\":2,\"offset\":{offset},\"searchText\":\"\"}'\n  items: jobPostings\n"
                + "  marker: [jobPostings]\n  total: total\npagination:\n  style: offset\n  page_size: 2\n"
                + "fields:\n  title: title\n  url: { template: \"https://{board_1}{externalPath}\" }\n");
        final String base = "https://acme.wd1.example.com/wday/cxs/acme/Site/jobs#";
        final FakeFetcher f = new FakeFetcher()
                .on(base + "{\"limit\":2,\"offset\":0,\"searchText\":\"\"}",
                        "{\"total\":3,\"jobPostings\":[{\"title\":\"A\",\"externalPath\":\"/job/a\"},{\"title\":\"B\",\"externalPath\":\"/job/b\"}]}")
                .on(base + "{\"limit\":2,\"offset\":2,\"searchText\":\"\"}",
                        "{\"total\":3,\"jobPostings\":[{\"title\":\"C\",\"externalPath\":\"/job/c\"}]}");
        final BoardSnapshot s = run(a, "acme.wd1.example.com|acme|Site", f);
        assertThat(s.getJobs(), hasSize(3));
        assertThat(s.isComplete(), equalTo(true));
        assertThat(s.getJobs().get(2).getUrl(), equalTo("https://acme.wd1.example.com/job/c"));
        assertThat(f.postBodies, hasSize(2));
    }

    @Test
    void untilMissingReadsEveryFileAndStopsAtThe404() throws Exception {
        final SpecAdapter a = spec("name: t\nformat: html\nlist:\n  url: \"https://x.example.com/sitemap-{page}.xml\"\n"
                + "  items: '<loc>(https://x\\.example\\.com/{board}/\\d+)</loc>'\n  marker: ['<urlset']\n"
                + "pagination:\n  style: page\n  until_missing: true\nfields:\n  url: url\n  title: { value: T }\n");
        final FakeFetcher f = new FakeFetcher()
                .on("https://x.example.com/sitemap-1.xml", "<urlset><loc>https://x.example.com/other/1</loc></urlset>")
                .on("https://x.example.com/sitemap-2.xml", "<urlset><loc>https://x.example.com/acme/2</loc></urlset>");
        final BoardSnapshot s = run(a, "acme", f);
        assertThat(s.getJobs(), hasSize(1));
        assertThat(s.isComplete(), equalTo(true));
        assertThat(f.requests, hasSize(3));
    }

    @Test
    void embeddedJsonIsExtractedBeforeParsing() throws Exception {
        final SpecAdapter a = spec("name: t\nformat: json\nlist:\n  url: \"https://x.example.com/{board}\"\n"
                + "  extract: 'POSITIONS = (\\[.*?\\]);'\n  items: $\n  marker: [$]\nfields:\n  title: name\n  url: link\n");
        final FakeFetcher f = new FakeFetcher().on("https://x.example.com/acme",
                "<script>var POSITIONS = [{\"name\":\"A\",\"link\":\"https://x/a\"}];\nvar Z = 1;</script>");
        assertThat(run(a, "acme", f).getJobs().get(0).getTitle(), equalTo("A"));
    }
}
