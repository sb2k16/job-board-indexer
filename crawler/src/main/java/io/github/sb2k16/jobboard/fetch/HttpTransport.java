/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.fetch;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/** One HTTP GET. Separated from policy so the policy can be tested without a network. */
public interface HttpTransport {

    Response get(String url, Duration timeout) throws IOException, InterruptedException;

    default Response post(final String url, final String jsonBody, final Duration timeout) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("POST is not supported by " + getClass().getSimpleName());
    }

    /** Status, body and the one header the policy reads. */
    final class Response {
        private final int status;
        private final String body;
        private final Duration retryAfter;

        public Response(final int status, final String body, final Duration retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }

        public int getStatus() {
            return status;
        }

        public String getBody() {
            return body;
        }

        /** Parsed Retry-After (delta-seconds form), if present. */
        public Optional<Duration> getRetryAfter() {
            return Optional.ofNullable(retryAfter);
        }
    }
}
