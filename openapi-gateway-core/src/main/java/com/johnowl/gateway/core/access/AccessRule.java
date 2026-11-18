package com.johnowl.gateway.core.access;

import java.util.Set;

/**
 * The precompiled authorization requirement of one operation.
 *
 * @param isPublic {@code true} only for an explicit {@code security: []}
 * @param audience the client id of the backend app registration; {@code null} for a public rule
 * @param allOf the token must contain every permission
 * @param anyOf the token must contain at least one permission; no condition if empty
 */
public record AccessRule(boolean isPublic, String audience, Set<String> allOf, Set<String> anyOf) {

    public AccessRule {
        allOf = Set.copyOf(allOf);
        anyOf = Set.copyOf(anyOf);
        if (isPublic && (audience != null || !allOf.isEmpty() || !anyOf.isEmpty())) {
            throw new IllegalArgumentException("A public rule has no audience and no permissions");
        }
    }

    /** A rule for an operation with {@code security: []}. */
    public static AccessRule publicAccess() {
        return new AccessRule(true, null, Set.of(), Set.of());
    }

    /**
     * Returns {@code true} if the permissions satisfy the rule. The caller checks the audience of
     * the token first.
     */
    public boolean allows(Set<String> permissions) {
        if (isPublic) {
            return true;
        }
        if (!permissions.containsAll(allOf)) {
            return false;
        }
        return anyOf.isEmpty() || anyOf.stream().anyMatch(permissions::contains);
    }
}
