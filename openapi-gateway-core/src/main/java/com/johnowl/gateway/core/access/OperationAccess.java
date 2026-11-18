package com.johnowl.gateway.core.access;

import java.util.Objects;

/**
 * The access rule of one operation.
 *
 * @param routeId the id of the {@code GatewayRoute} that serves the operation
 * @param publicPathPattern prefix and OAS path, for example {@code /orders-api/orders/{id}}
 * @param method uppercase HTTP method
 * @param rule the access rule
 */
public record OperationAccess(String routeId, String publicPathPattern, String method, AccessRule rule) {

    public OperationAccess {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(publicPathPattern, "publicPathPattern");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(rule, "rule");
    }
}
