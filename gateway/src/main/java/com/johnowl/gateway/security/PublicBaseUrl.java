package com.johnowl.gateway.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * The public URL of the gateway ({@code gateway.public-base-url}). The gateway builds its redirect
 * URIs from it, never from the {@code Host}, {@code Forwarded} or {@code X-Forwarded-*} headers.
 *
 * <p>Rules: an absolute {@code https} URL with a host, and without user information, path, query
 * or fragment. With the Spring profile {@code local}, the scheme {@code http} is also accepted for
 * the hosts {@code localhost}, {@code 127.0.0.1} and {@code [::1]} (exact, lowercase). The value is
 * parsed as a URI, so {@code http://localhost.evil.example} does not pass as a local host.
 */
public final class PublicBaseUrl {

    private static final String PROPERTY = "gateway.public-base-url";

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final URI uri;

    private PublicBaseUrl(URI uri) {
        this.uri = uri;
    }

    /**
     * Parses and checks the value.
     *
     * @param localProfile {@code true} if the Spring profile {@code local} is active
     * @throws IllegalStateException if the value breaks a rule
     */
    public static PublicBaseUrl parse(String value, boolean localProfile) {
        if (value == null || value.isBlank()) {
            throw invalid(value, "is required");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw invalid(value, "is not a valid URI");
        }
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null) {
            throw invalid(value, "must be an absolute URL with a host, for example https://gateway.example.com");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid(value, "must not contain user information");
        }
        if (!uri.getRawPath().isEmpty()) {
            throw invalid(value, "must not contain a path and must not end with /");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid(value, "must not contain a query or a fragment");
        }
        String authority = uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        if (!authority.equals(uri.getRawAuthority())) {
            throw invalid(value, "must have an authority of the form host or host:port");
        }
        if ("http".equals(uri.getScheme())) {
            if (!localProfile) {
                throw invalid(value, "must use https. The scheme http is allowed only with the Spring profile 'local'");
            }
            if (!LOCAL_HOSTS.contains(uri.getHost())) {
                throw invalid(value, "must use https. With http, the host must be localhost, 127.0.0.1 or [::1]");
            }
        } else if (!"https".equals(uri.getScheme())) {
            throw invalid(value, "must use https");
        }
        return new PublicBaseUrl(uri);
    }

    /** The URL, for example {@code https://gateway.example.com}. */
    public URI uri() {
        return uri;
    }

    /**
     * The absolute URI of a path on the gateway.
     *
     * @param pathAndQuery a path that starts with {@code /}, with an optional query
     */
    public URI resolve(String pathAndQuery) {
        return URI.create(uri + pathAndQuery);
    }

    @Override
    public String toString() {
        return uri.toString();
    }

    private static IllegalStateException invalid(String value, String reason) {
        return new IllegalStateException(PROPERTY + " '" + value + "' " + reason + ".");
    }
}
