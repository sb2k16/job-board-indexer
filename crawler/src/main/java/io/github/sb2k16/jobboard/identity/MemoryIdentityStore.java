/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.identity;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-process identity store: tests, and runs without a database (one pod only). */
public class MemoryIdentityStore implements IdentityStore {
    private final Map<String, String> postings = new HashMap<>();
    private final Map<String, String> aliases = new HashMap<>();

    private static String pk(final String platform, final String board, final String posting) {
        return platform + "\u0001" + board + "\u0001" + posting;
    }

    @Override
    public synchronized Map<String, String> postings(final String platform, final String board, final Collection<String> ids) {
        final Map<String, String> out = new HashMap<>();
        for (final String id : ids) {
            final String key = postings.get(pk(platform, board, id));
            if (key != null) {
                out.put(id, key);
            }
        }
        return out;
    }

    @Override
    public synchronized Map<String, String> aliases(final Collection<String> wanted) {
        final Map<String, String> out = new HashMap<>();
        for (final String a : wanted) {
            if (aliases.containsKey(a)) {
                out.put(a, aliases.get(a));
            }
        }
        return out;
    }

    @Override
    public synchronized Map<String, Set<String>> postingsOnBoard(final String platform, final String board, final Collection<String> keys) {
        final Set<String> wanted = new HashSet<>(keys);
        final String prefix = platform + "\u0001" + board + "\u0001";
        final Map<String, Set<String>> out = new HashMap<>();
        postings.forEach((k, v) -> {
            if (k.startsWith(prefix) && wanted.contains(v)) {
                out.computeIfAbsent(v, x -> new HashSet<>()).add(k.substring(prefix.length()));
            }
        });
        return out;
    }

    @Override
    public synchronized Map<String, String> bind(final String platform, final String board, final Map<String, String> postingKeys,
                                                 final Map<String, String> aliasKeys) {
        final Map<String, String> out = new HashMap<>();
        postingKeys.forEach((id, key) -> out.put(id, postings.computeIfAbsent(pk(platform, board, id), k -> key)));
        aliasKeys.forEach(aliases::putIfAbsent);
        return out;
    }
}
