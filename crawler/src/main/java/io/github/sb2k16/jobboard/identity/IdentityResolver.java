/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.identity;

import io.github.sb2k16.jobboard.JobIdentity;
import io.github.sb2k16.jobboard.model.JobRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which job document each posting of a board snapshot belongs to.
 *
 * <ol>
 *   <li>A posting (platform, board, posting id) seen before keeps its job key for good, whatever
 *       was edited since.</li>
 *   <li>A new posting joins an existing job when one of its aliases is taken: company +
 *       requisition id (the reliable cross-platform signal in Phase 0), or company + title +
 *       canonical location. Both rules apply; neither depends on how the other board is keyed.</li>
 *   <li>Two postings of the same board are never one job: a board that lists two ids lists two
 *       openings, even when title and city match.</li>
 *   <li>Otherwise the posting starts a new job, keyed by its strongest alias (so the same job
 *       found by two pods at once still lands on one key), or by the posting itself when that
 *       alias already names another posting of this board.</li>
 * </ol>
 */
public final class IdentityResolver {

    private final IdentityStore store;

    public IdentityResolver(final IdentityStore store) {
        this.store = store;
    }

    /** The posting id: the platform's job id, else the job URL. */
    public static String postingId(final JobRecord job) {
        if (job.getPlatformJobId() != null && !job.getPlatformJobId().isBlank()) {
            return job.getPlatformJobId();
        }
        return job.getUrl();
    }

    /** Aliases in order of strength. */
    static List<String> aliases(final JobRecord job, final boolean useRequisitionIds) {
        final List<String> out = new ArrayList<>(2);
        final String company = JobIdentity.normalize(job.getCompany());
        if (useRequisitionIds && JobIdentity.isPlausibleRequisitionId(job.getRequisitionId()) && !company.isEmpty()) {
            out.add(JobIdentity.sha256("r|" + company + "|" + JobIdentity.normalize(job.getRequisitionId())));
        }
        out.add(JobIdentity.sha256("t|" + company + "|" + JobIdentity.normalize(job.getTitle()) + "|" + JobIdentity.locationToken(job)));
        return out;
    }

    static String postingKey(final String platform, final String board, final String postingId) {
        return JobIdentity.sha256("p|" + platform + "|" + board + "|" + postingId);
    }

    /** Job key per posting id, for every job in the snapshot. Postings without an id are skipped. */
    public Map<String, String> resolve(final String platform, final String board, final List<JobRecord> jobs) {
        final boolean useReq = JobIdentity.boardHasUsableRequisitionIds(jobs);
        final Map<String, JobRecord> byPosting = new LinkedHashMap<>();
        for (final JobRecord job : jobs) {
            final String id = postingId(job);
            if (id != null) {
                byPosting.putIfAbsent(id, job);
            }
        }
        final Map<String, String> result = new HashMap<>(store.postings(platform, board, byPosting.keySet()));

        final Map<String, List<String>> aliasesOf = new LinkedHashMap<>();
        final Set<String> allAliases = new LinkedHashSet<>();
        for (final Map.Entry<String, JobRecord> e : byPosting.entrySet()) {
            final List<String> a = aliases(e.getValue(), useReq);
            aliasesOf.put(e.getKey(), a);
            allAliases.addAll(a);
        }
        final Map<String, String> knownAliases = store.aliases(allAliases);

        // Keys this board already uses, and by which postings: candidates and existing bindings.
        final Set<String> candidateKeys = new HashSet<>(knownAliases.values());
        candidateKeys.addAll(allAliases);
        final Map<String, Set<String>> usedOnBoard = new HashMap<>();
        store.postingsOnBoard(platform, board, candidateKeys).forEach((k, v) -> usedOnBoard.put(k, new HashSet<>(v)));
        result.forEach((posting, key) -> usedOnBoard.computeIfAbsent(key, k -> new HashSet<>()).add(posting));

        final Map<String, String> newPostings = new LinkedHashMap<>();
        final Map<String, String> newAliases = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> e : aliasesOf.entrySet()) {
            final String posting = e.getKey();
            String key = result.get(posting);
            if (key == null) {
                for (final String alias : e.getValue()) {
                    final String existing = knownAliases.getOrDefault(alias, newAliases.get(alias));
                    if (existing != null && freeFor(usedOnBoard, existing, posting)) {
                        key = existing;
                        break;
                    }
                }
                if (key == null) {
                    final String strongest = e.getValue().get(0);
                    key = freeFor(usedOnBoard, strongest, posting) && !knownAliases.containsKey(strongest)
                            ? strongest : postingKey(platform, board, posting);
                }
                newPostings.put(posting, key);
                usedOnBoard.computeIfAbsent(key, k -> new HashSet<>()).add(posting);
                result.put(posting, key);
            }
            for (final String alias : e.getValue()) {
                if (!knownAliases.containsKey(alias)) {
                    newAliases.putIfAbsent(alias, key);
                }
            }
        }
        if (!newPostings.isEmpty() || !newAliases.isEmpty()) {
            result.putAll(store.bind(platform, board, newPostings, newAliases));
        }
        return result;
    }

    private static boolean freeFor(final Map<String, Set<String>> usedOnBoard, final String key, final String posting) {
        final Set<String> users = usedOnBoard.get(key);
        return users == null || users.isEmpty() || (users.size() == 1 && users.contains(posting));
    }
}
