/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.adapter;

/**
 * A board fetch that must not be read as "zero jobs": a non-2xx status, a body without the
 * platform's board marker, or a parse failure.
 */
public class BoardFetchException extends RuntimeException {
    private final boolean retryable;

    public BoardFetchException(final String message, final boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public BoardFetchException(final String message, final Throwable cause, final boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
