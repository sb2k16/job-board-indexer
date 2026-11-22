/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.closure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the sweeper and the verifier send their decisions: the crawler writes them to the pipeline's
 * buffer as events, and the sink applies them with config/upsert.painless (its {@code _op} branch),
 * so the index is written only by the sink. The sweeper and verifier read the index to decide; they
 * never write to it.
 */
@FunctionalInterface
public interface Commands {

    void write(List<Map<String, Object>> commands);

    /** Live links of the board last seen before the cutoff go; with no cutoff (a retired board), all of them. */
    static List<Map<String, Object>> close(final List<String> jobKeys, final String platform, final String board,
                                           final Instant cutoff, final Instant now) {
        final List<Map<String, Object>> out = new ArrayList<>(jobKeys.size());
        for (final String key : jobKeys) {
            final Map<String, Object> c = new LinkedHashMap<>();
            c.put("job_key", key);
            c.put("_op", "close");
            c.put("platform", platform);
            c.put("board_id", board);
            c.put("cutoff", cutoff == null ? null : cutoff.toString());
            c.put("now", now.toString());
            out.add(c);
        }
        return out;
    }

    /** One job's checked link: it goes ("gone") or is seen now ("alive"). */
    static Map<String, Object> verify(final String jobKey, final String platform, final String board, final String url,
                                      final String verdict, final Instant now) {
        final Map<String, Object> c = new LinkedHashMap<>();
        c.put("job_key", jobKey);
        c.put("_op", "verify");
        c.put("platform", platform);
        c.put("board_id", board);
        c.put("urls", List.of(url));
        c.put("verdict", verdict);
        c.put("now", now.toString());
        return c;
    }
}
