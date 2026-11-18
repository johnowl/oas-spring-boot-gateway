package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.johnowl.gateway.core.access.AccessRule;
import com.johnowl.gateway.core.access.OperationAccess;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RouteOperationFilterTest {

    private static final OperationAccess ME =
            new OperationAccess("users-me", "/users/me", "GET", AccessRule.publicAccess());

    private final RouteOperationFilter filter = new RouteOperationFilter();

    private final AtomicBoolean forwarded = new AtomicBoolean();

    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(true);
        return Mono.empty();
    };

    @Test
    void routeOfTheAccessRulePasses() {
        MockServerWebExchange exchange = exchange(route("users-me"), ME);

        filter.filter(exchange, chain).block();

        assertThat(forwarded).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // SEC-59-3: the route index chose another operation than the security index.
    @Test
    void otherRouteGets404() {
        MockServerWebExchange exchange = exchange(route("users-id"), ME);

        filter.filter(exchange, chain).block();

        assertNotFound(exchange);
    }

    @Test
    void routeWithoutAccessRuleGets404() {
        MockServerWebExchange exchange = exchange(route("users-me"), null);

        filter.filter(exchange, chain).block();

        assertNotFound(exchange);
    }

    @Test
    void runsFirstAmongTheGlobalFilters() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    private void assertNotFound(MockServerWebExchange exchange) {
        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(SessionAuthenticationEntryPointTest.NOT_FOUND_BODY);
    }

    private static MockServerWebExchange exchange(Route route, OperationAccess operation) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/users/me"));
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        if (operation != null) {
            exchange.getAttributes().put(GatewaySecurityAttributes.OPERATION_ACCESS, operation);
        }
        return exchange;
    }

    private static Route route(String id) {
        return Route.async().id(id).uri("http://localhost:9").predicate(exchange -> true).build();
    }
}
