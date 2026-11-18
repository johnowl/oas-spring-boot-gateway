package com.johnowl.gateway.core.access;

import java.util.Objects;

/**
 * One change between two versions of the OAS files that loosens the access to an operation. See
 * specs/034-access-change-review.md, "Loosening changes".
 *
 * @param kind the change
 * @param method uppercase HTTP method
 * @param publicPath prefix and OAS path of the operation in the head, for example {@code /orders-api/orders/{id}}
 * @param file the head file of the operation
 * @param pointer the JSON pointer of the operation in the head file
 * @param permission the removed or added permission; {@code null} for the other kinds
 * @param message the change in one line, for example {@code Orders.Delete removed from all-of}
 */
public record AccessChange(
        Kind kind, String method, String publicPath, String file, String pointer, String permission, String message) {

    /** The loosening changes, in the order of the checks. */
    public enum Kind {
        BECAME_PUBLIC("became-public"),
        AUDIENCE_CHANGED("audience-changed"),
        AUTHENTICATED_ONLY_REPLACED_PERMISSIONS("authenticated-only-replaced-permissions"),
        PERMISSION_REMOVED_FROM_ALL_OF("permission-removed-from-all-of"),
        PERMISSION_ADDED_TO_ANY_OF("permission-added-to-any-of"),
        ANY_OF_REMOVED("any-of-removed"),
        NEW_PUBLIC_OPERATION("new-public-operation"),
        SHADOWS_PROTECTED_TEMPLATE("shadows-protected-template"),
        LITERAL_REMOVED_TO_TEMPLATE("literal-removed-to-template"),
        HEAD_WEAKER_THAN_GET("head-weaker-than-get");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        /** The change id of the output, for example {@code became-public}. */
        public String id() {
            return id;
        }
    }

    public AccessChange {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(publicPath, "publicPath");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(message, "message");
    }
}
