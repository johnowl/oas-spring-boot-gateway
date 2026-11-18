package com.johnowl.gateway.routing;

import com.johnowl.gateway.core.route.GatewayRoute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
import org.springframework.http.HttpHeaders;

/**
 * Converts a {@link GatewayRoute} from core into a Spring Cloud Gateway {@link RouteDefinition}.
 * The route never changes the request or response body. It removes the {@code Cookie} and the
 * {@code Authorization} header from every request to the backend. A route with an audience relays the
 * access token of its audience (spec 025): the filter {@value #TOKEN_RELAY} of the security package.
 *
 * <p>The path and the prefix come from the OAS file, so they use explicit arguments. The shortcut
 * text form ({@code "Path=/a,b"}) splits the value on commas into several arguments.
 */
public final class RouteDefinitionMapper {

    /** Route metadata key: the public path pattern of an OAS route. */
    public static final String OAS_PATH = "oas-path";

    /** Route metadata key: the uppercase methods of an OAS route. */
    public static final String OAS_METHODS = "oas-methods";

    /**
     * Route metadata key: the public path prefix of an OAS route, for example {@code /orders-api}. Only when
     * the route has a prefix. The backend response filter rewrites a backend {@code Location} with it (spec 025).
     */
    public static final String PUBLIC_PREFIX = "public-prefix";

    /**
     * Route metadata key: {@code x-gateway.upstream-base-path}, for example {@code /v1}. Only when set. The
     * backend response filter rewrites a backend {@code Location} with it (spec 025).
     */
    public static final String UPSTREAM_BASE_PATH = "upstream-base-path";

    /** The name of the token relay filter (spec 025). Its argument is the audience of the route. */
    public static final String TOKEN_RELAY = "AudienceTokenRelay";

    public RouteDefinition map(GatewayRoute route) {
        RouteDefinition definition = new RouteDefinition();
        definition.setId(route.id());
        definition.setUri(route.upstream());
        definition.setOrder(route.order());

        List<PredicateDefinition> predicates = new ArrayList<>();
        predicates.add(predicate("Path", "patterns", route.path()));
        // The shortcut form is safe here: the methods are fixed upper-case HTTP method names.
        predicates.add(new PredicateDefinition("Method=" + String.join(",", route.methods())));
        definition.setPredicates(predicates);

        List<FilterDefinition> filters = new ArrayList<>();
        // The session cookie and the CSRF cookie stay in the gateway (spec 022, spec 025).
        filters.add(filter("RemoveRequestHeader", "name", HttpHeaders.COOKIE));
        // No client value reaches the backend. The relay adds the token after this filter (spec 025).
        filters.add(filter("RemoveRequestHeader", "name", HttpHeaders.AUTHORIZATION));
        if (route.audience() != null) {
            // The relay sends no token for a public operation of the route.
            filters.add(filter(TOKEN_RELAY, "audience", route.audience()));
        }
        if (route.stripPrefix() > 0) {
            filters.add(filter("StripPrefix", "parts", Integer.toString(route.stripPrefix())));
        }
        if (route.prefixPath() != null) {
            filters.add(filter("PrefixPath", "prefix", route.prefixPath()));
        }
        definition.setFilters(filters);

        Map<String, Object> metadata = new LinkedHashMap<>();
        // Used by the indexed route lookup (spec 017). The values equal the Path and Method predicates.
        metadata.put(OAS_PATH, route.path());
        metadata.put(OAS_METHODS, List.copyOf(route.methods()));
        if (route.stripPrefix() > 0) {
            metadata.put(PUBLIC_PREFIX, publicPrefix(route.path(), route.stripPrefix()));
        }
        if (route.prefixPath() != null) {
            metadata.put(UPSTREAM_BASE_PATH, route.prefixPath());
        }
        if (route.responseTimeoutMillis() != null) {
            metadata.put(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, route.responseTimeoutMillis());
        }
        definition.setMetadata(metadata);
        return definition;
    }

    /** The first {@code segments} path segments, the part of the path that {@code StripPrefix} removes. */
    static String publicPrefix(String path, int segments) {
        int end = 0;
        for (int i = 0; i < segments; i++) {
            int next = path.indexOf('/', end + 1);
            end = next == -1 ? path.length() : next;
        }
        return path.substring(0, end);
    }

    private static PredicateDefinition predicate(String name, String argument, String value) {
        PredicateDefinition predicate = new PredicateDefinition();
        predicate.setName(name);
        predicate.addArg(argument, value);
        return predicate;
    }

    private static FilterDefinition filter(String name, String argument, String value) {
        FilterDefinition filter = new FilterDefinition();
        filter.setName(name);
        filter.addArg(argument, value);
        return filter;
    }
}
