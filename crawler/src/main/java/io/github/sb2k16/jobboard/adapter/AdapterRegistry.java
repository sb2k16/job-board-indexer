/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.adapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Name to adapter lookup. */
public class AdapterRegistry {
    private final Map<String, PlatformAdapter> adapters = new HashMap<>();

    public AdapterRegistry(final List<PlatformAdapter> adapters) {
        for (final PlatformAdapter adapter : adapters) {
            if (this.adapters.put(adapter.name(), adapter) != null) {
                throw new IllegalArgumentException("Duplicate adapter name: " + adapter.name());
            }
        }
    }

    public static AdapterRegistry defaultRegistry() {
        return new AdapterRegistry(List.of(new GreenhouseAdapter()));
    }

    public Optional<PlatformAdapter> get(final String name) {
        return Optional.ofNullable(adapters.get(name));
    }
}
