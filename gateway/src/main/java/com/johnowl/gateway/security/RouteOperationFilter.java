package com.johnowl.gateway.security;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.johnowl.gateway.core.access.OperationAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers {@code 404} when the route that the gateway chose is not the route of the access rule that
 * the security chain checked (spec 024, "Only OAS routes"). The security index and the route index are
 * built separately, so a defect or a route from another source could make them select different
 * operations. Then the request would reach a backend under the access rule of another operation.
 *
 * <p>The filter runs first among the global filters, before any filter sends the request to a backend.
 * A request without an access rule never reaches routing ({@link UndocumentedOperationWebFilter}), so a
 * missing rule here is also a mismatch. The filter reads only exchange attributes.
 */
public final class RouteOperationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RouteOperationFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        OperationAccess operation = exchange.getAttribute(GatewaySecurityAttributes.OPERATION_ACCESS);
        if (route != null && operation != null && route.getId().equals(operation.routeId())) {
            return chain.filter(exchange);
        }
        // A defect, not a client error: the request line alone cannot cause it.
        log.error("The route {} is not the route {} of the access rule. The gateway answers 404.",
                route == null ? "-" : route.getId(), operation == null ? "-" : operation.routeId());
        return ProblemResponses.write(exchange, HttpStatus.NOT_FOUND);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
