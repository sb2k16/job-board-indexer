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
public final class Html {
    private static final Pattern BLOCK_TAGS = Pattern.compile("(?i)<\\s*(br|/p|/li|/h[1-6]|/div|/tr)\\s*/?>");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    /** A tag cut off at the end of a truncated field, such as {@code </str}. */
    private static final Pattern TRAILING_PARTIAL_TAG = Pattern.compile("<[^>]*$");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n+");

    private Html() {
    }

    public static String unescape(final String value) {
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

    public static String toText(final String html) {
        if (html == null) {
            return null;
        }
        String text = BLOCK_TAGS.matcher(html).replaceAll("\n");
        text = TAGS.matcher(text).replaceAll("");
        text = TRAILING_PARTIAL_TAG.matcher(text).replaceAll("");
        text = unescape(text);
        text = SPACES.matcher(text).replaceAll(" ");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.trim();
    }

    /**
     * Decodes the body of a JavaScript string literal (without its quotes) in one pass: \xHH,
     * backslash-u plus four hex digits, \n \r \t \b \f \v \0, and a backslash before any other character stands for that
     * character (so \\ is one backslash and \/ \- \' are /, -, '). Zoho Recruit embeds job
     * records as JSON.parse('...').
     */
    public static String jsString(final String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s;
        }
        final StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                b.append(c);
                continue;
            }
            final char n = s.charAt(++i);
            switch (n) {
                case 'x':
                    if (isHex(s, i + 1, 2)) {
                        b.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                        i += 2;
                    } else {
                        b.append(n);
                    }
                    break;
                case 'u':
                    if (isHex(s, i + 1, 4)) {
                        b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    } else {
                        b.append(n);
                    }
                    break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'v': b.append((char) 0x0B); break;
                case '0': b.append('\0'); break;
                default: b.append(n);
            }
        }
        return b.toString();
    }

    private static boolean isHex(final String s, final int from, final int len) {
        if (from + len > s.length()) {
            return false;
        }
        for (int i = from; i < from + len; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
