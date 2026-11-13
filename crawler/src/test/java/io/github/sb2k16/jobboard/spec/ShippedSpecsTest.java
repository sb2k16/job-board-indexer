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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.sb2k16.jobboard.PlatformConfig;
import io.github.sb2k16.jobboard.adapter.BoardFetchException;
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.JobRecord;
import io.github.sb2k16.jobboard.model.Tenant;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T2.2 acceptance: class A platforms run from the spec files in config/specs alone, against
 * fixtures cut from real Phase 0 responses. No platform-specific code is involved.
 */
class ShippedSpecsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static Path specsDir() {
        return Path.of(System.getProperty("jbi.specs.dir", "../config/specs"));
    }

    static SpecAdapter adapter(final String platform) {
        return new SpecAdapter(SpecLoader.load(specsDir().resolve(platform + ".yaml")));
    }

    private static PlatformConfig platform(final String name) {
        return new PlatformConfig(name, null, null, List.of());
    }

    @Test
    void everyShippedSpecLoads() {
        final File[] files = specsDir().toFile().listFiles((d, n) -> n.endsWith(".yaml"));
        assertThat(files, notNullValue());
        assertThat(files.length >= 3, equalTo(true));
        for (final File f : files) {
            final PlatformSpec spec = SpecLoader.load(f.toPath());
            assertThat(f.getName(), equalTo(spec.getName() + ".yaml"));
        }
    }

    // ---- Greenhouse: JSON, single response, reported total ------------------------------------

    private final Tenant stripe = new Tenant("stripe", "Stripe");

    @Test
    void greenhouse_realStripeSample_mapsFieldsAndIsComplete() {
        final FakeFetcher fetcher = new FakeFetcher().on(
                "https://boards-api.greenhouse.io/v1/boards/stripe/jobs?content=true", FakeFetcher.resource("greenhouse-stripe-3.json"));
        final BoardSnapshot snapshot = adapter("greenhouse").fetchBoard(platform("greenhouse"), stripe, fetcher);
        assertThat(snapshot.isComplete(), equalTo(true));
        assertThat(snapshot.getJobs().size(), equalTo(3));
        final JobRecord first = snapshot.getJobs().get(0);
        assertThat(first.getTitle(), equalTo("Abuse Investigator"));
        assertThat(first.getCompany(), equalTo("Stripe"));
        assertThat(first.getLocation(), equalTo("Dublin"));
        assertThat(first.getUrl(), equalTo("https://stripe.com/jobs/search?gh_jid=8172487"));
        assertThat(first.getPlatformJobId(), equalTo("8172487"));
        assertThat(first.getPlatform(), equalTo("greenhouse"));
        assertThat(first.getBoardId(), equalTo("stripe"));
        assertThat(first.getPostedAt().toString(), equalTo("2026-09-03T17:30:34Z"));
        assertThat(first.getDescription(), containsString("Who we are"));
        assertThat(first.getDescription(), not(containsString("<")));
        assertThat(first.getDescription(), not(containsString("&lt;")));
    }

    private BoardSnapshot greenhouse(final String body, final Tenant tenant) {
        final FakeFetcher fetcher = new FakeFetcher().respond(url -> body);
        return adapter("greenhouse").fetchBoard(platform("greenhouse"), tenant, fetcher);
    }

    @Test
    void greenhouse_countBelowTotal_isIncomplete() {
        final BoardSnapshot snapshot = greenhouse("{\"jobs\":[{\"id\":1,\"title\":\"x\",\"absolute_url\":\"u\"}],\"meta\":{\"total\":2}}", stripe);
        assertThat(snapshot.isComplete(), equalTo(false));
        assertThat(snapshot.getReportedTotal(), equalTo(2));
    }

    @Test
    void greenhouse_emptyBoardWithMarker_isCompleteZero() {
        final BoardSnapshot snapshot = greenhouse("{\"jobs\":[],\"meta\":{\"total\":0}}", stripe);
        assertThat(snapshot.isComplete(), equalTo(true));
        assertThat(snapshot.getJobs().size(), equalTo(0));
    }

    @Test
    void greenhouse_missingMarker_throwsInsteadOfZeroJobs() {
        assertThrows(BoardFetchException.class, () -> greenhouse("{\"error\":\"not found\"}", stripe));
        assertThrows(BoardFetchException.class, () -> greenhouse("{\"jobs\":[]}", stripe));
        assertThrows(BoardFetchException.class, () -> greenhouse("<html>maintenance</html>", stripe));
    }

    @Test
    void greenhouse_noTenantCompany_usesPlatformCompanyName() {
        final BoardSnapshot snapshot = greenhouse(
                "{\"jobs\":[{\"id\":1,\"title\":\"x\",\"company_name\":\"Figma\"}],\"meta\":{\"total\":1}}", new Tenant("figma", null));
        assertThat(snapshot.getJobs().get(0).getCompany(), equalTo("Figma"));
    }

    @Test
    void greenhouse_baseUrlOverride_fromPipelineConfig() {
        final FakeFetcher fetcher = new FakeFetcher().on("http://fixture:8080/v1/boards/stripe/jobs?content=true", "{\"jobs\":[],\"meta\":{\"total\":0}}");
        final PlatformConfig config = new PlatformConfig("greenhouse", null, "http://fixture:8080", List.of(stripe));
        assertThat(adapter("greenhouse").fetchBoard(config, stripe, fetcher).isComplete(), equalTo(true));
    }

    // ---- Lever: JSON array body, offset pagination ---------------------------------------------

    /** Serves the fixture like the Lever API: slices the array by skip and limit. */
    private static FakeFetcher leverServer(final ArrayNode all) {
        return new FakeFetcher().respond(url -> {
            if (!url.startsWith("https://api.lever.co/v0/postings/palantir?mode=json")) {
                return null;
            }
            final int skip = Integer.parseInt(FakeFetcher.param(url, "skip"));
            final int limit = Integer.parseInt(FakeFetcher.param(url, "limit"));
            final ArrayNode page = JSON.createArrayNode();
            for (int i = skip; i < Math.min(all.size(), skip + limit); i++) {
                page.add(all.get(i));
            }
            return page.toString();
        });
    }

    private static ArrayNode leverFixture() throws Exception {
        return (ArrayNode) JSON.readTree(FakeFetcher.resource("lever-palantir-5.json"));
    }

    @Test
    void lever_realPalantirSample_mapsFieldsAndIsComplete() throws Exception {
        final FakeFetcher fetcher = leverServer(leverFixture());
        final BoardSnapshot snapshot = adapter("lever").fetchBoard(platform("lever"), new Tenant("palantir", "Palantir"), fetcher);

        assertThat(fetcher.requests, equalTo(List.of("https://api.lever.co/v0/postings/palantir?mode=json&skip=0&limit=100")));
        assertThat(snapshot.isComplete(), equalTo(true));
        assertThat(snapshot.getJobs().size(), equalTo(5));
        final JobRecord first = snapshot.getJobs().get(0);
        assertThat(first.getPlatformJobId(), equalTo("6ed76ce8-4156-4b60-b120-403538bd66cd"));
        assertThat(first.getTitle(), equalTo("Administrative Business Partner"));
        assertThat(first.getCompany(), equalTo("Palantir"));
        assertThat(first.getLocation(), equalTo("Singapore, Singapore"));
        assertThat(first.getCountry(), equalTo("SG"));
        assertThat(first.getEmploymentType(), equalTo("Full-time"));
        assertThat(first.getRemote(), equalTo(false));
        assertThat(first.getPostedAt(), equalTo(Instant.ofEpochMilli(1786469891368L)));
        assertThat(first.getUrl(), equalTo("https://jobs.lever.co/palantir/6ed76ce8-4156-4b60-b120-403538bd66cd"));
        assertThat(first.getApplicationUrl(), equalTo("https://jobs.lever.co/palantir/6ed76ce8-4156-4b60-b120-403538bd66cd/apply"));
        assertThat(first.getDescription(), startsWith("A World-Changing Company"));
        assertThat(first.getDescription(), containsString("Provide administrative support"));   // from lists[].content
        assertThat(first.getDescription(), not(containsString("<")));
        assertThat(snapshot.getJobs().get(1).getRemote(), equalTo(true));
    }

    @Test
    void lever_boardLargerThanOnePage_followsSkipUntilShortPage() throws Exception {
        final ArrayNode five = leverFixture();
        final ArrayNode big = JSON.createArrayNode();
        for (int i = 0; i < 230; i++) {
            final JsonNode copy = five.get(i % 5).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) copy).put("id", "job-" + i);
            big.add(copy);
        }
        final FakeFetcher fetcher = leverServer(big);
        final BoardSnapshot snapshot = adapter("lever").fetchBoard(platform("lever"), new Tenant("palantir", "Palantir"), fetcher);
        assertThat(fetcher.requests.stream().map(u -> FakeFetcher.param(u, "skip")).collect(Collectors.toList()),
                equalTo(List.of("0", "100", "200")));
        assertThat(snapshot.getJobs().size(), equalTo(230));
        assertThat(snapshot.isComplete(), equalTo(true));
    }

    @Test
    void lever_errorObjectInsteadOfArray_isFailedFetch() {
        final FakeFetcher fetcher = new FakeFetcher().respond(url -> "{\"ok\":false,\"error\":\"Document not found\"}");
        assertThrows(BoardFetchException.class,
                () -> adapter("lever").fetchBoard(platform("lever"), new Tenant("palantir", "Palantir"), fetcher));
    }

    // ---- HireBridge: XML (RSS with g: namespace), single response ------------------------------

    private BoardSnapshot hirebridge(final String body, final Tenant tenant) {
        final FakeFetcher fetcher = new FakeFetcher().on("https://rss.hirebridge.com/6284.rss", body);
        return adapter("hirebridge").fetchBoard(platform("hirebridge"), tenant, fetcher);
    }

    @Test
    void hirebridge_realQuovisFeed_mapsFieldsAndIsComplete() {
        final BoardSnapshot snapshot = hirebridge(FakeFetcher.resource("hirebridge-6284.rss"), new Tenant("6284", null));
        assertThat(snapshot.isComplete(), equalTo(true));
        assertThat(snapshot.getJobs().size(), equalTo(10));

        final JobRecord general = snapshot.getJobs().get(0);
        assertThat(general.getPlatformJobId(), equalTo("69532"));
        assertThat(general.getLocation(), nullValue());                       // ",,," is no location
        assertThat(general.getPostedAt().toString(), equalTo("2019-03-26T05:00:00Z")); // last pubDate, EST = -05:00

        final JobRecord second = snapshot.getJobs().get(1);
        assertThat(second.getTitle(), equalTo("Talent Acquisition Consultant - Remote, WI"));
        assertThat(second.getCompany(), equalTo("Quovis, Inc."));
        assertThat(second.getLocation(), equalTo("Remote, WI, 53226"));
        assertThat(second.getEmploymentType(), equalTo("Full Time"));
        assertThat(second.getUrl(), equalTo("http://recruit.hirebridge.com/v3/Jobs/JobDetails.aspx?jid=593666&cid=6284&locvalue=1181"));
        assertThat(second.getPostedAt().toString(), equalTo("2022-08-23T05:00:00Z"));
        assertThat(second.getDescription(), containsString("Position Title: Talent Acquisition Consultant"));
        assertThat(second.getDescription(), not(containsString("<p")));

        // The feed build time (first pubDate, same on every item) must never be read as posted date.
        assertThat(snapshot.getJobs().stream().map(j -> j.getPostedAt().toString()).collect(Collectors.toList()),
                everyItem(not(equalTo("2026-11-11T04:46:15Z"))));
    }

    @Test
    void hirebridge_tenantCompanyWinsOverFeedEmployer() {
        final BoardSnapshot snapshot = hirebridge(FakeFetcher.resource("hirebridge-6284.rss"), new Tenant("6284", "Quovis"));
        assertThat(snapshot.getJobs().get(1).getCompany(), equalTo("Quovis"));
    }

    @Test
    void hirebridge_channelWithoutItems_isCompleteZero() {
        final BoardSnapshot snapshot = hirebridge(
                "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>x</title></channel></rss>", new Tenant("6284", null));
        assertThat(snapshot.isComplete(), equalTo(true));
        assertThat(snapshot.getJobs().size(), equalTo(0));
    }

    @Test
    void hirebridge_htmlErrorPage_isFailedFetch() {
        assertThrows(BoardFetchException.class, () -> hirebridge(
                "<!DOCTYPE html><html><head><title>404</title></head><body>gone</body></html>", new Tenant("6284", null)));
        assertThrows(BoardFetchException.class, () -> hirebridge(
                "The resource you are looking for has been removed", new Tenant("6284", null)));
        // Well-formed XML that is not a feed: no channel, so no board.
        assertThrows(BoardFetchException.class, () -> hirebridge("<error>busy</error>", new Tenant("6284", null)));
    }

    @Test
    void xml_externalEntity_isNeverResolved() throws Exception {
        final File secret = File.createTempFile("jbi-secret", ".txt");
        secret.deleteOnExit();
        java.nio.file.Files.writeString(secret.toPath(), "TOP-SECRET");
        final String body = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY x SYSTEM \"" + secret.toURI() + "\">]>"
                + "<rss xmlns:g=\"http://base.google.com/ns/1.0\"><channel><item><title>&x;</title><link>u</link></item></channel></rss>";
        try {
            final BoardSnapshot snapshot = hirebridge(body, new Tenant("6284", null));
            assertThat(Arrays.toString(snapshot.getJobs().stream().map(JobRecord::getTitle).toArray()), not(containsString("TOP-SECRET")));
        } catch (final BoardFetchException rejected) {
            // Rejecting the document outright is also acceptable.
        }
    }
}
