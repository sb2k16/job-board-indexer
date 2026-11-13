/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.state;

/**
 * How one board fetch ended (design: "Failure is not closure"). Only {@link #COMPLETE}, once the
 * sink has confirmed every job, adds to {@code snapshot_history}. Conditional requests (304,
 * "unchanged") are not implemented yet.
 */
public enum Outcome {
    /** Every job the platform reported was read; becomes a snapshot after the sink confirms. */
    COMPLETE,
    /** Read, but fewer jobs than the platform reported, or paging hit max_pages. */
    PARTIAL,
    /** 429, 5xx, timeout, network error, open circuit, missing board marker, or a sink failure. */
    FAILED,
    /** 404 or 410 on the board itself: a retirement candidate (T2.5), never a closure on its own. */
    GONE,
    /** robots.txt disallows the board's URL. */
    BLOCKED
}
