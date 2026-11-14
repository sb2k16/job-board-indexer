/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.model;

import io.github.sb2k16.jobboard.location.CountryResolver;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One job as read from one board, before identity and hashing are applied.
 * Field names follow the document model in the design doc.
 */
public class JobRecord {
    private String title;
    private String company;
    private String description;
    private String location;
    private String city;
    private String region;
    private String country;
    private String requisitionId;
    private String employmentType;
    private Boolean remote;
    private Instant postedAt;
    private String url;
    private String applicationUrl;
    private String platform;
    private String boardId;
    private String platformJobId;

    public String getTitle() {
        return title;
    }
    public JobRecord setTitle(final String v) {
        title = v;
        return this;
    }

    public String getCompany() {
        return company;
    }
    public JobRecord setCompany(final String v) {
        company = v;
        return this;
    }

    public String getDescription() {
        return description;
    }
    public JobRecord setDescription(final String v) {
        description = v;
        return this;
    }

    public String getLocation() {
        return location;
    }
    public JobRecord setLocation(final String v) {
        location = v;
        return this;
    }

    public String getCity() {
        return city;
    }
    public JobRecord setCity(final String v) {
        city = v;
        return this;
    }

    public String getRegion() {
        return region;
    }
    public JobRecord setRegion(final String v) {
        region = v;
        return this;
    }

    public String getCountry() {
        return country;
    }
    public JobRecord setCountry(final String v) {
        country = v;
        return this;
    }

    public String getRequisitionId() {
        return requisitionId;
    }
    public JobRecord setRequisitionId(final String v) {
        requisitionId = v;
        return this;
    }

    public String getEmploymentType() {
        return employmentType;
    }
    public JobRecord setEmploymentType(final String v) {
        employmentType = v;
        return this;
    }

    public Boolean getRemote() {
        return remote;
    }
    public JobRecord setRemote(final Boolean v) {
        remote = v;
        return this;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
    public JobRecord setPostedAt(final Instant v) {
        postedAt = v;
        return this;
    }

    public String getUrl() {
        return url;
    }
    public JobRecord setUrl(final String v) {
        url = v;
        return this;
    }

    public String getApplicationUrl() {
        return applicationUrl;
    }
    public JobRecord setApplicationUrl(final String v) {
        applicationUrl = v;
        return this;
    }

    public String getPlatform() {
        return platform;
    }
    public JobRecord setPlatform(final String v) {
        platform = v;
        return this;
    }

    public String getBoardId() {
        return boardId;
    }
    public JobRecord setBoardId(final String v) {
        boardId = v;
        return this;
    }

    public String getPlatformJobId() {
        return platformJobId;
    }
    public JobRecord setPlatformJobId(final String v) {
        platformJobId = v;
        return this;
    }


    /**
     * Builds the event body the sink upserts. {@code jobKey} and {@code contentHash} come from
     * {@link io.github.sb2k16.jobboard.JobIdentity}.
     */
    /** Copies every field the other record has (a detail page over its list entry); identity fields stay. */
    public JobRecord fillFrom(final JobRecord other) {
        if (other.title != null) {
            title = other.title;
        }
        if (other.company != null) {
            company = other.company;
        }
        if (other.description != null) {
            description = other.description;
        }
        if (other.location != null) {
            location = other.location;
        }
        if (other.city != null) {
            city = other.city;
        }
        if (other.region != null) {
            region = other.region;
        }
        if (other.country != null) {
            country = other.country;
        }
        if (other.requisitionId != null) {
            requisitionId = other.requisitionId;
        }
        if (other.employmentType != null) {
            employmentType = other.employmentType;
        }
        if (other.remote != null) {
            remote = other.remote;
        }
        if (other.postedAt != null) {
            postedAt = other.postedAt;
        }
        if (other.applicationUrl != null) {
            applicationUrl = other.applicationUrl;
        }
        return this;
    }

    public Map<String, Object> toDocument(final String jobKey, final String contentHash, final Instant crawlStarted) {
        final Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("job_key", jobKey);
        doc.put("title", title);
        doc.put("company", company);
        doc.put("description", description);
        doc.put("location", location);
        doc.put("countries", CountryResolver.countries(country, location));
        putIfNotNull(doc, "remote", remote);
        putIfNotNull(doc, "employment_type", employmentType);
        putIfNotNull(doc, "requisition_id", requisitionId);
        putIfNotNull(doc, "posted_at", postedAt == null ? null : postedAt.toString());
        doc.put("content_hash", contentHash);
        doc.put("crawl_started", crawlStarted.toString());
        doc.put("last_checked", Instant.now().toString());
        final Map<String, Object> link = new LinkedHashMap<>();
        link.put("url", url);
        link.put("application_url", applicationUrl != null ? applicationUrl : url);
        link.put("platform", platform);
        link.put("board_id", boardId);
        link.put("posting_id", platformJobId != null ? platformJobId : url);
        link.put("last_seen", crawlStarted.toString());
        link.put("state", "live");
        // This source's own view of the job: kept per link, so the merged document can be rebuilt
        // and a change is detected per source (config/upsert.painless).
        link.put("content_hash", contentHash);
        putIfNotNull(link, "title", title);
        putIfNotNull(link, "location", location);
        putIfNotNull(link, "posted_at", postedAt == null ? null : postedAt.toString());
        putIfNotNull(link, "employment_type", employmentType);
        putIfNotNull(link, "remote", remote);
        doc.put("source_link", link);
        return doc;
    }

    private static void putIfNotNull(final Map<String, Object> doc, final String key, final Object value) {
        if (value != null) {
            doc.put(key, value);
        }
    }
}
