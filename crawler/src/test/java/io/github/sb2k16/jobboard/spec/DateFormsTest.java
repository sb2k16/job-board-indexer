/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/** Date shapes seen on live platforms (2026-11-14). */
class DateFormsTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "2026-04-22T00:00:00.000+0000|2026-04-22T00:00:00Z",   // Phenom
        "2026-04-08 00:00:00|2026-04-08T00:00:00Z",           // ApplicantPro, iSolvedHire
        "2026-10-17 06:43:52|2026-10-17T06:43:52Z",           // Recooty
        "2026-11-12T17:33:30+00:00|2026-11-12T17:33:30Z",
        "2026-11-01|2026-11-01T00:00:00Z",
    })
    void parses(final String raw, final String expected) {
        final FieldSpec spec = new FieldSpec("d");
        final Object v = FieldMapper.map(Map.of("posted_at", withType(spec)), p -> java.util.List.of(raw), Map.of()).getPostedAt();
        assertThat(String.valueOf(v), equalTo(expected));
    }

    private static FieldSpec withType(final FieldSpec f) {
        try {
            final java.lang.reflect.Field t = FieldSpec.class.getDeclaredField("type");
            t.setAccessible(true);
            t.set(f, "datetime");
            return f;
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
