package com.johnowl.gateway.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * The permissions of a user for one backend app (spec 023, "Permission decoding and cache"). They come
 * only from the access token of the audience, never from the ID token.
 *
 * @param audience the {@code aud} of the access token: the client id of the backend app. Spec 024
 *     compares it with {@code x-gateway.audience}.
 * @param permissions the values of the {@code roles} claim; empty without the claim
 * @param expiresAt the {@code exp} of the access token
 */
public record TokenPermissions(String audience, Set<String> permissions, Instant expiresAt) {

    public TokenPermissions {
        Objects.requireNonNull(audience, "audience");
        permissions = Set.copyOf(permissions);
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
