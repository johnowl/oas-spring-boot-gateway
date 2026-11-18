package com.johnowl.gateway.security;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces tokens and secrets in a log line with {@value #MASK} (spec 022, "Log guard"). It is the last
 * line of defense: the gateway does not log a token, and the log guard pins the loggers of the
 * libraries that can write one. The masking replaces:
 *
 * <ul>
 *   <li>the value after {@code Bearer} or {@code Basic}, from 8 characters;
 *   <li>the value of {@code access_token}, {@code refresh_token}, {@code id_token}, {@code client_secret}
 *       and {@code code_verifier}, after {@code =} or {@code :}, also with JSON quotes;
 *   <li>the value of {@code code=}, for example in the query of the login callback;
 *   <li>each JSON Web Token: {@code eyJ}, base64url characters, a dot, and base64url characters.
 * </ul>
 */
final class LogMasking {

    static final String MASK = "***";

    /** The characters that end a value: white space, a separator of a query, a list, or JSON, and quotes. */
    private static final String VALUE = "[^\\s&\"'\\\\,;)}\\]]+";

    private static final Pattern SECRETS = Pattern.compile(
            "(?<scheme>\\b(?:Bearer|Basic)\\s+)[A-Za-z0-9._~+/-]{8,}=*"
                    + "|(?<key>(?<![A-Za-z0-9_])(?:access_token|refresh_token|id_token|client_secret|code_verifier)"
                    + "\\\\?\"?\\s*[=:]\\s*\\\\?\"?)" + VALUE
                    + "|(?<code>(?<![A-Za-z0-9_])code=)" + VALUE
                    + "|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]*)?");

    private LogMasking() {
    }

    /** The text with each token and secret replaced by {@value #MASK}. */
    static String mask(String text) {
        Matcher matcher = SECRETS.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder masked = new StringBuilder(text.length());
        do {
            String kept = matcher.group("scheme") != null ? matcher.group("scheme")
                    : matcher.group("key") != null ? matcher.group("key")
                    : matcher.group("code") != null ? matcher.group("code") : "";
            matcher.appendReplacement(masked, Matcher.quoteReplacement(kept + MASK));
        } while (matcher.find());
        return matcher.appendTail(masked).toString();
    }

    /**
     * The encoded log line with each token and secret replaced. ISO-8859-1 maps each byte to one character,
     * so the bytes outside a match stay the same in each charset that extends ASCII, for example UTF-8.
     */
    static byte[] mask(byte[] line) {
        String text = new String(line, StandardCharsets.ISO_8859_1);
        String masked = mask(text);
        return masked == text ? line : masked.getBytes(StandardCharsets.ISO_8859_1);
    }
}
