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
 * platform's board marker, a parse failure, a robots.txt block or an open circuit.
 */
public class BoardFetchException extends RuntimeException {

    /** Why the fetch failed; decides how {@code board_state} records it. */
    public enum Kind {
        /** Non-2xx response; see {@link #getStatus()}. */
        HTTP_STATUS,
        /** Connection failure or timeout. */
        NETWORK,
        /** The body is not the platform's format, or the board marker is missing. */
        NOT_A_BOARD,
        /** robots.txt disallows the URL, or robots.txt itself is unavailable (5xx). */
        ROBOTS,
        /** The host's circuit breaker is open; no request was sent. */
        CIRCUIT_OPEN
    }

    private final boolean retryable;
    private final Kind kind;
    private final int status;

    public BoardFetchException(final String message, final boolean retryable) {
        this(message, null, retryable, Kind.NOT_A_BOARD, 0);
    }

    public BoardFetchException(final String message, final Throwable cause, final boolean retryable) {
        this(message, cause, retryable, Kind.NOT_A_BOARD, 0);
    }

    public BoardFetchException(final String message, final Throwable cause, final boolean retryable, final Kind kind, final int status) {
        super(message, cause);
        this.retryable = retryable;
        this.kind = kind;
        this.status = status;
    }

    public static BoardFetchException http(final String url, final int status) {
        return new BoardFetchException("GET " + url + " returned HTTP " + status, null,
                status == 429 || status >= 500, Kind.HTTP_STATUS, status);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Kind getKind() {
        return kind;
    }

    /** HTTP status for {@link Kind#HTTP_STATUS}, else 0. */
    public int getStatus() {
        return status;
    }

    /** A 404 or 410 on the board: a candidate for retirement (T2.5), never a closure on its own. */
    public boolean isBoardGone() {
        return kind == Kind.HTTP_STATUS && (status == 404 || status == 410);
    }
}
