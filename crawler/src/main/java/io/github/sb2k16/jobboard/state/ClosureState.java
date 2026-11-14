/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.state;

import java.time.Instant;

/** A board's closure bookkeeping (db/V3): retirement streak, guard pause and operator override. */
public class ClosureState {
    public static final ClosureState NONE = new ClosureState(0, null, null, null);

    private final int goneStreak;
    private final Instant pausedAt;
    private final Instant overrideUntil;
    private final Instant retiredAt;

    public ClosureState(final int goneStreak, final Instant pausedAt, final Instant overrideUntil, final Instant retiredAt) {
        this.goneStreak = goneStreak;
        this.pausedAt = pausedAt;
        this.overrideUntil = overrideUntil;
        this.retiredAt = retiredAt;
    }

    /** Consecutive crawls that confirmed the board is gone (404/410). */
    public int getGoneStreak() {
        return goneStreak;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    /** An operator released the mass-closure guard until this time. */
    public boolean isOverridden(final Instant now) {
        return overrideUntil != null && overrideUntil.isAfter(now);
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }
}
