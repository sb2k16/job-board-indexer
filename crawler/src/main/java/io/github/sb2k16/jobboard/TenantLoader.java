/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard;

import io.github.sb2k16.jobboard.model.Tenant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tenants come inline from the pipeline config and/or from a CSV file with a header row
 * {@code board,company}. The file is re-read on every refresh, so it can change without a restart.
 */
class TenantLoader {

    List<Tenant> load(final PlatformConfig platform) {
        final List<Tenant> tenants = new ArrayList<>(platform.getTenants());
        if (platform.getTenantsFile() != null) {
            tenants.addAll(readCsv(Path.of(platform.getTenantsFile())));
        }
        return tenants;
    }

    List<Tenant> readCsv(final Path file) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read tenants file " + file, e);
        }
        final List<Tenant> tenants = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            final String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final int comma = line.indexOf(',');
            if (comma < 0) {
                tenants.add(new Tenant(line, null));
            } else {
                final String company = unquote(line.substring(comma + 1).trim());
                tenants.add(new Tenant(line.substring(0, comma).trim(), company.isEmpty() ? null : company));
            }
        }
        return tenants;
    }

    private static String unquote(final String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value;
    }
}
