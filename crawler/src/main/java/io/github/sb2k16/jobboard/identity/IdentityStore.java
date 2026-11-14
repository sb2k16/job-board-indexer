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
import java.util.Map;
import java.util.Set;

/** Where postings and aliases are bound to job keys. Postgres in production, memory in tests. */
public interface IdentityStore extends AutoCloseable {

    /** Known postings of one board: posting id to job key. */
    Map<String, String> postings(String platform, String board, Collection<String> postingIds);

    /** Known aliases: alias to job key. */
    Map<String, String> aliases(Collection<String> aliases);

    /** For each key, the posting ids of this board already bound to it. */
    Map<String, Set<String>> postingsOnBoard(String platform, String board, Collection<String> jobKeys);

    /**
     * Binds new postings and aliases; existing bindings are kept (first writer wins). Returns the
     * key each new posting ended up with, which differs from the requested one only when another
     * pod bound the same posting first.
     */
    Map<String, String> bind(String platform, String board, Map<String, String> postingKeys, Map<String, String> aliasKeys);

    @Override
    default void close() {
    }
}
