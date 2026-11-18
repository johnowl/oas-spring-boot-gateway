package org.springframework.cloud.gateway.handler;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Calls the protected {@code lookupRoute} method from the benchmark. Test code only. */
public final class RouteLookupAccess {

    private RouteLookupAccess() {
    }

    public static Mono<Route> lookupRoute(RoutePredicateHandlerMapping mapping, ServerWebExchange exchange) {
        return mapping.lookupRoute(exchange);
    }
}
