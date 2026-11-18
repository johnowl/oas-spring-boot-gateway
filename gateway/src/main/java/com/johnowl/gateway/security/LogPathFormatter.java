package com.johnowl.gateway.security;

import com.johnowl.gateway.core.text.UnsafeCharacters;

/**
 * Formats the request path for the denial log (spec 024, "Denial logs and metrics"). All denial log
 * lines use it:
 *
 * <ul>
 *   <li>The caller gives the raw path, still percent-encoded ({@code request.getURI().getRawPath()}), so
 *       two paths that decode to the same path stay different in the log.
 *   <li>Each control character, Unicode line separator, and bidirectional control ({@link UnsafeCharacters})
 *       becomes a Java escape, as in spec 008: a line feed becomes {@code \n}, other characters become
 *       {@code \}{@code uXXXX}. A path never starts a new log line.
 *   <li>The result has at most {@value #MAX_LENGTH} characters, followed by {@code ...} if the path is longer.
 * </ul>
 */
public final class LogPathFormatter {

    /** The maximum number of characters of the path in a log line. */
    public static final int MAX_LENGTH = 512;

    private LogPathFormatter() {
    }

    public static String format(String rawPath) {
        if (rawPath == null) {
            return "-";
        }
        StringBuilder result = new StringBuilder(Math.min(rawPath.length(), MAX_LENGTH) + 3);
        for (int i = 0; i < rawPath.length(); i++) {
            String escape = escape(rawPath.charAt(i));
            if (escape == null) {
                result.append(rawPath.charAt(i));
            } else {
                result.append(escape);
            }
            if (result.length() >= MAX_LENGTH) {
                boolean more = result.length() > MAX_LENGTH || i < rawPath.length() - 1;
                result.setLength(MAX_LENGTH);
                return more ? result.append("...").toString() : result.toString();
            }
        }
        return result.toString();
    }

    /** The same escapes as the CLI of spec 008 ({@code ConsoleText}). */
    private static String escape(char c) {
        return switch (c) {
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> UnsafeCharacters.isUnsafe(c) ? String.format("\\u%04X", (int) c) : null;
        };
    }
}
