/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.adapter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal HTML to text for descriptions. */
final class Html {
    private static final Pattern BLOCK_TAGS = Pattern.compile("(?i)<\\s*(br|/p|/li|/h[1-6]|/div|/tr)\\s*/?>");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n+");

    private Html() {
    }

    static String unescape(final String value) {
        if (value == null) {
            return null;
        }
        final Matcher m = NUMERIC_ENTITY.matcher(value);
        final StringBuffer sb = new StringBuffer();
        while (m.find()) {
            final String code = m.group(1);
            final int cp = code.startsWith("x") || code.startsWith("X")
                    ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString()
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    static String toText(final String html) {
        if (html == null) {
            return null;
        }
        String text = BLOCK_TAGS.matcher(html).replaceAll("\n");
        text = TAGS.matcher(text).replaceAll("");
        text = unescape(text);
        text = SPACES.matcher(text).replaceAll(" ");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.trim();
    }
}
