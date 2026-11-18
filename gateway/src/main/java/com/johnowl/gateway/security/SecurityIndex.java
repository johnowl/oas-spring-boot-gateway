package com.johnowl.gateway.security;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_PATH_CONTAINER_ATTR;

import com.johnowl.gateway.core.access.OperationAccess;
import com.johnowl.gateway.core.trie.PathTrie;
import com.johnowl.gateway.routing.IndexedRoutePredicateHandlerMapping;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.server.PathContainer;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

/**
 * The access rules of all operations, by method and path (spec 024, "Security index"). Built once at
 * startup from the access rules of the OAS files (spec 018); no request changes it. A lookup costs
 * O(number of path segments). It uses the same path segments as the indexed route lookup (spec 017),
 * so a request matches an access rule only if it also matches the route of that operation.
 *
 * <p>Not final, so that a test can count the lookups.
 */
public class SecurityIndex {

    private final PathTrie<OperationAccess> operations = new PathTrie<>();

    /**
     * @param basePath {@code spring.webflux.base-path}, or {@code null}. The routes add it before each
     *     pattern, so the index does the same.
     */
    protected SecurityIndex(Collection<OperationAccess> operations, String basePath) {
        String prefix = StringUtils.hasText(basePath) ? basePath : "";
        for (OperationAccess operation : operations) {
            this.operations.insert(prefix + operation.publicPathPattern(), Set.of(operation.method()), operation);
        }
    }

    /** Builds the index for a gateway without a base path. Startup only. */
    public static SecurityIndex build(Collection<OperationAccess> operations) {
        return build(operations, null);
    }

    /**
     * Builds the index. Startup only.
     *
     * @param basePath {@code spring.webflux.base-path}, or {@code null}
     */
    public static SecurityIndex build(Collection<OperationAccess> operations, String basePath) {
        return new SecurityIndex(operations, basePath);
    }

    /**
     * The access rule of the method and the path of the request, or empty. Per request.
     *
     * <p>The path is the raw path of the request, parsed once and kept in the exchange attribute of the
     * {@code Path} predicate and the route index: the three lookups read the same decoded segments.
     */
    public Optional<OperationAccess> find(ServerWebExchange exchange) {
        PathContainer path = (PathContainer) exchange.getAttributes().computeIfAbsent(
                GATEWAY_PREDICATE_PATH_CONTAINER_ATTR,
                key -> PathContainer.parsePath(exchange.getRequest().getURI().getRawPath()));
        return find(path, exchange.getRequest().getMethod().name());
    }

    /**
     * {@code true} if a {@code GET} of the path matches an operation that is not public.
     *
     * @param path a path without percent-encoding, for example {@code /signed-out}
     */
    public boolean isNonPublicGet(String path) {
        return find(PathContainer.parsePath(path), "GET").filter(operation -> !operation.rule().isPublic()).isPresent();
    }

    private Optional<OperationAccess> find(PathContainer path, String method) {
        List<String> segments = IndexedRoutePredicateHandlerMapping.segments(path);
        return segments == null ? Optional.empty() : operations.match(segments, method);
    }
}
