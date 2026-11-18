package com.johnowl.gateway.core.text;

/**
 * Shows text from the reviewed files in a message or a log line. Each character of {@link UnsafeCharacters} becomes
 * a Java escape, for example {@code \u0009}, so that the text stays on one line and shows its characters in their
 * order.
 */
public final class PrintableText {

    /** The maximum number of characters of a copied value in a message. */
    public static final int MAX_LENGTH = 200;

    private PrintableText() {
    }

    /** Each unsafe character as a Java escape. The result has the full text. */
    public static String escape(String text) {
        StringBuilder result = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (UnsafeCharacters.isUnsafe(c) && result == null) {
                result = new StringBuilder(text.length() + 8).append(text, 0, i);
            }
            if (result != null) {
                result.append(UnsafeCharacters.isUnsafe(c) ? String.format("\\u%04X", (int) c) : String.valueOf(c));
            }
        }
        return result == null ? text : result.toString();
    }

    /**
     * Each unsafe character as a Java escape. The result has at most {@value #MAX_LENGTH} characters, followed by
     * {@code ...} if the value is longer. The cut is never inside an escape or a surrogate pair.
     */
    public static String printable(String text) {
        StringBuilder result = new StringBuilder(Math.min(text.length(), MAX_LENGTH) + 3);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int next = Character.isHighSurrogate(c) && i + 1 < text.length() ? i + 2 : i + 1;
            String part = UnsafeCharacters.isUnsafe(c) ? String.format("\\u%04X", (int) c) : text.substring(i, next);
            if (result.length() + part.length() > MAX_LENGTH) {
                return result.append("...").toString();
            }
            result.append(part);
            i = next;
        }
        return result.toString();
    }
}
