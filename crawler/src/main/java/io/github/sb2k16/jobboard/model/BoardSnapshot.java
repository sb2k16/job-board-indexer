/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.model;

import java.util.List;

/**
 * Everything one fetch of one board returned. {@code complete} is true only when the
 * platform's completeness signal agrees with what was parsed (design: a response without the
 * board marker is a failed fetch, never zero jobs).
 */
public class BoardSnapshot {
    private final List<JobRecord> jobs;
    private final Integer reportedTotal;
    private final boolean complete;
    private final Integer resultLimit;

    public BoardSnapshot(final List<JobRecord> jobs, final Integer reportedTotal, final boolean complete) {
        this(jobs, reportedTotal, complete, null);
    }

    /** {@code resultLimit}: set when paging stopped at the platform's maximum result count. */
    public BoardSnapshot(final List<JobRecord> jobs, final Integer reportedTotal, final boolean complete,
                         final Integer resultLimit) {
        this.jobs = jobs;
        this.reportedTotal = reportedTotal;
        this.complete = complete;
        this.resultLimit = resultLimit;
    }

    public Integer getResultLimit() {
        return resultLimit;
    }

    public List<JobRecord> getJobs() {
        return jobs;
    }

    public Integer getReportedTotal() {
        return reportedTotal;
    }

    public boolean isComplete() {
        return complete;
    }
}
