package com.johnowl.gateway.core.text;

/**
 * The characters that a message or a log line shows as a Java escape. Text from the reviewed files can contain
 * them. A line break or a Unicode line separator could start a new log line, for example a GitHub workflow command
 * such as {@code ::error ...}. A bidirectional control character could show the text in another order than the
 * order of its characters ("Trojan Source", CVE-2021-42574).
 */
public final class UnsafeCharacters {

    private UnsafeCharacters() {
    }

    /**
     * Returns {@code true} for a C0 control character ({@code U+0000} to {@code U+001F}), {@code U+007F}, a C1
     * control character ({@code U+0080} to {@code U+009F}), the line separator {@code U+2028}, the paragraph
     * separator {@code U+2029}, and the bidirectional controls {@code U+061C}, {@code U+200E}, {@code U+200F},
     * {@code U+202A} to {@code U+202E}, and {@code U+2066} to {@code U+2069}.
     */
    public static boolean isUnsafe(char c) {
        return c < 0x20
                || (c >= 0x7f && c <= 0x9f)
                || c == 0x2028 || c == 0x2029
                || c == 0x061c || c == 0x200e || c == 0x200f
                || (c >= 0x202a && c <= 0x202e)
                || (c >= 0x2066 && c <= 0x2069);
    }
}
