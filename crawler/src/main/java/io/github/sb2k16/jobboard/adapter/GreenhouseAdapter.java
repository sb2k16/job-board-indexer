/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sb2k16.jobboard.PlatformConfig;
import io.github.sb2k16.jobboard.model.BoardSnapshot;
import io.github.sb2k16.jobboard.model.JobRecord;
import io.github.sb2k16.jobboard.model.Tenant;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Greenhouse Job Board API: one GET returns every job with its content.
 * Board marker: a JSON object with a {@code jobs} array and {@code meta.total}.
 * Completeness: the number of parsed jobs equals {@code meta.total}.
 */
public class GreenhouseAdapter implements PlatformAdapter {
    public static final String NAME = "greenhouse";
    static final String DEFAULT_BASE_URL = "https://boards-api.greenhouse.io";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public BoardSnapshot fetchBoard(final PlatformConfig platform, final Tenant tenant, final HttpFetcher fetcher) {
        final String base = platform.getBaseUrl() != null ? platform.getBaseUrl() : DEFAULT_BASE_URL;
        final String url = base + "/v1/boards/" + URLEncoder.encode(tenant.getBoard(), StandardCharsets.UTF_8)
                + "/jobs?content=true";
        return parse(fetcher.get(url), platform.getName(), tenant);
    }

    BoardSnapshot parse(final String body, final String platformName, final Tenant tenant) {
        final JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (final JsonProcessingException e) {
            throw new BoardFetchException("greenhouse/" + tenant.getBoard() + ": body is not JSON", e, false);
        }
        if (root == null || !root.path("jobs").isArray() || !root.path("meta").path("total").isNumber()) {
            throw new BoardFetchException("greenhouse/" + tenant.getBoard() + ": board marker (jobs, meta.total) missing", false);
        }
        final List<JobRecord> jobs = new ArrayList<>();
        for (final JsonNode j : root.get("jobs")) {
            final String company = tenant.getCompany() != null ? tenant.getCompany() : text(j, "company_name");
            jobs.add(new JobRecord()
                    .setPlatform(platformName)
                    .setBoardId(tenant.getBoard())
                    .setPlatformJobId(text(j, "id"))
                    .setTitle(text(j, "title"))
                    .setCompany(company)
                    .setLocation(j.path("location").path("name").isTextual() ? j.path("location").path("name").asText() : null)
                    .setDescription(Html.toText(Html.unescape(text(j, "content"))))
                    .setRequisitionId(text(j, "requisition_id"))
                    .setPostedAt(instant(text(j, "first_published")))
                    .setUrl(text(j, "absolute_url")));
        }
        final int total = root.get("meta").get("total").asInt();
        return new BoardSnapshot(jobs, total, jobs.size() == total);
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static java.time.Instant instant(final String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (final DateTimeParseException e) {
            return null;
        }
    }
}
