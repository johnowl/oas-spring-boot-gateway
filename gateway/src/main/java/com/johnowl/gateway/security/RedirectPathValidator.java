package com.johnowl.gateway.security;

import java.util.List;
import java.util.function.Predicate;

/**
 * Checks the login failure path and the post-logout redirect path at startup (spec 022, "Rules for
 * redirect paths"). The gateway adds {@code gateway.public-base-url} before the path, so a valid
 * path can never send the browser to another host.
 */
final class RedirectPathValidator {

    private static final List<String> SPRING_SECURITY_PATHS = List.of("/login", "/logout");

    private static final List<String> SPRING_SECURITY_PREFIXES = List.of("/oauth2/", "/login/", "/logout/");

    private RedirectPathValidator() {
    }

    /**
     * @param property the property name, for the error message
     * @param value a path with an optional query, for example {@code /?login=failed}
     * @param nonPublicGetPath {@code true} for a path that matches a non-public {@code GET} operation
     * @throws IllegalStateException if the value breaks a rule
     */
    static void validate(String property, String value, Predicate<String> nonPublicGetPath) {
        if (value == null || value.isEmpty()) {
            throw invalid(property, value, "is required");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c >= 0x7f) {
                throw invalid(property, value, "must contain only printable ASCII characters, without a space");
            }
            // ';' starts a matrix parameter: Spring removes it before matching, so /login;x is /login.
            if (c == '\\' || c == '%' || c == '#' || c == ';') {
                throw invalid(property, value, "must not contain '\\', '%', '#' or ';'");
            }
        }
        int query = value.indexOf('?');
        String path = query < 0 ? value : value.substring(0, query);
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw invalid(property, value, "must start with exactly one '/'");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw invalid(property, value, "must not contain a '.' or '..' segment");
            }
        }
        if (SPRING_SECURITY_PATHS.contains(path) || SPRING_SECURITY_PREFIXES.stream().anyMatch(path::startsWith)) {
            throw invalid(property, value, "must not be a login, logout or OAuth2 path of Spring Security");
        }
        if (nonPublicGetPath.test(path)) {
            throw invalid(property, value, "must not match a non-public operation; the target must work without a session");
        }
    }

    private static IllegalStateException invalid(String property, String value, String reason) {
        return new IllegalStateException(property + " '" + value + "' " + reason + ".");
    }
}
