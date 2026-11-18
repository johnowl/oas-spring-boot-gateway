package com.johnowl.gateway.core.validation;

/** Helpers for JSON pointers (RFC 6901). */
public final class JsonPointers {

    private JsonPointers() {
    }

    /** Escapes one reference token: {@code ~} becomes {@code ~0} and {@code /} becomes {@code ~1}. */
    public static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
