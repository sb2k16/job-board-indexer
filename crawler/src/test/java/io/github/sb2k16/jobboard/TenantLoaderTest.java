/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.sb2k16.jobboard.model.Tenant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantLoaderTest {

    @Test
    void readCsv_parsesHeaderQuotesCommentsAndMissingCompany(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("t.csv");
        Files.writeString(file, "board,company\nstripe,Stripe\n# comment\n\nacme,\"Acme, Inc.\"\nbare\n");
        final List<Tenant> tenants = new TenantLoader().readCsv(file);
        assertThat(tenants.size(), equalTo(3));
        assertThat(tenants.get(1).getCompany(), equalTo("Acme, Inc."));
        assertThat(tenants.get(2).getBoard(), equalTo("bare"));
        assertThat(tenants.get(2).getCompany(), nullValue());
    }

    @Test
    void readCsv_missingFile_throws(@TempDir final Path dir) {
        assertThrows(UncheckedIOException.class, () -> new TenantLoader().readCsv(dir.resolve("nope.csv")));
    }
}
