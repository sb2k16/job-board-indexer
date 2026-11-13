/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.fetch;

import java.time.Duration;

/** Time source and sleep, injectable so limits and backoff are tested without waiting. */
public interface Ticker {
    Ticker SYSTEM = new Ticker() {
        @Override
        public long nanos() {
            return System.nanoTime();
        }

        @Override
        public void sleep(final Duration d) throws InterruptedException {
            if (!d.isNegative() && !d.isZero()) {
                Thread.sleep(d.toMillis(), (int) (d.toNanos() % 1_000_000));
            }
        }
    };

    long nanos();

    void sleep(Duration d) throws InterruptedException;
}
