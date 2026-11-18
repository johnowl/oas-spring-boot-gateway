package com.johnowl.gateway.core.route;

import java.net.URI;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One gateway route built from an OAS file.
 *
 * @param id unique id, for example {@code orders:/orders/{id}}
 * @param upstream the backend address, {@code x-gateway.upstream}
 * @param path the public path pattern: {@code x-gateway.prefix} and the OAS path
 * @param methods uppercase HTTP methods, sorted
 * @param stripPrefix the number of segments of {@code x-gateway.prefix}; 0 without a prefix
 * @param prefixPath {@code x-gateway.upstream-base-path}; {@code null} if not set
 * @param order 0 for literal paths, 100 for paths with variables
 * @param responseTimeoutMillis {@code x-gateway-response-timeout}; {@code null} if not set
 * @param audience {@code x-gateway.audience}; {@code null} if not set
 */
public record GatewayRoute(
        String id,
        URI upstream,
        String path,
        Set<String> methods,
        int stripPrefix,
        String prefixPath,
        int order,
        Integer responseTimeoutMillis,
        String audience) {

    public static final int LITERAL_ORDER = 0;
    public static final int TEMPLATE_ORDER = 100;

    public GatewayRoute {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(path, "path");
        methods = Collections.unmodifiableSortedSet(new TreeSet<>(methods));
    }
}
