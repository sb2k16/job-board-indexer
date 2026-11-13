/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.state;

/** board_state could not be read or written. */
public class BoardStateException extends RuntimeException {
    public BoardStateException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
