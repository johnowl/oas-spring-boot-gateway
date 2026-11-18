package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.text.UnsafeCharacters;

/**
 * Makes text from OAS files safe for a terminal or a CI log. File names, paths, and messages come from
 * the files under review. A line break in them could start a new log line, for example a GitHub
 * workflow command such as {@code ::error ...}.
 */
final class ConsoleText {

    private ConsoleText() {
    }

    /**
     * Replaces each character of {@link UnsafeCharacters} (control characters, Unicode line separators, and
     * bidirectional controls) with a Java escape: a line feed becomes {@code \n}, other characters become
     * {@code \}{@code uXXXX}. Other text stays as it is.
     */
    static String printable(Object value) {
        String text = String.valueOf(value);
        StringBuilder result = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String escape = escape(c);
            if (escape != null && result == null) {
                result = new StringBuilder(text.length() + 8).append(text, 0, i);
            }
            if (result != null) {
                if (escape != null) {
                    result.append(escape);
                } else {
                    result.append(c);
                }
            }
        }
        return result == null ? text : result.toString();
    }

    /**
     * Makes a value from Microsoft Graph or from the files of a PR safe for a line on stderr. It applies
     * {@link #printable}, and then the GitHub runner sees no workflow command in the value: the second {@code #} of
     * each {@code ##[} and the first {@code :} of a {@code ::} at the start (also after spaces) become Java escapes.
     * A caller still puts a fixed text before the value, so that no line starts with it.
     */
    static String untrusted(Object value) {
        String text = printable(value).replace("##[", "#\\u0023[");
        int start = text.length() - text.stripLeading().length();
        return text.startsWith("::", start) ? text.substring(0, start) + "\\u003A" + text.substring(start + 1) : text;
    }

    private static String escape(char c) {
        return switch (c) {
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> UnsafeCharacters.isUnsafe(c) ? String.format("\\u%04X", (int) c) : null;
        };
    }
}
