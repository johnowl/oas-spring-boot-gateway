package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.access.AccessRule;
import com.johnowl.gateway.core.access.OperationAccess;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

class UndocumentedOperationWebFilterTest {

    private static final OperationAccess GET_ORDER = new OperationAccess("orders:/orders/{id}",
            "/orders-api/orders/{id}", "GET", new AccessRule(false, "aud", Set.of("Orders.Read"), Set.of()));

    private final UndocumentedOperationWebFilter filter =
            new UndocumentedOperationWebFilter(SecurityIndex.build(List.of(GET_ORDER)), ManagementEndpoints.DEFAULT,
                    new AuthorizationDenials(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

    @Test
    void documentedOperationPassesWithItsAccessRule() {
        SessionCountingExchange exchange = exchange(HttpMethod.GET, "/orders-api/orders/42");
        AtomicReference<ServerWebExchange> next = new AtomicReference<>();

        filter.filter(exchange, chain(next)).block();

        assertThat(next.get()).isNotNull();
        assertThat(exchange.<OperationAccess>getAttribute(GatewaySecurityAttributes.OPERATION_ACCESS)).isEqualTo(GET_ORDER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /admin/secrets", "PUT /orders-api/orders/42", "OPTIONS /orders-api/orders/42",
        "GET /orders-api/orders/42/", "GET /login", "GET /actuator/health", "GET /"})
    void undocumentedOperationGets404WithoutTheSession(String request) {
        String[] methodAndPath = request.split(" ");
        SessionCountingExchange exchange = exchange(HttpMethod.valueOf(methodAndPath[0]), methodAndPath[1]);
        AtomicReference<ServerWebExchange> next = new AtomicReference<>();

        filter.filter(exchange, chain(next)).block();

        assertThat(next.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(SessionAuthenticationEntryPointTest.NOT_FOUND_BODY);
        assertThat(exchange.sessionCalls.get()).isZero();
        assertThat(exchange.<Object>getAttribute(GatewaySecurityAttributes.OPERATION_ACCESS)).isNull();
    }

    @Test
    void corsPreflightGets404() {
        MockServerWebExchange mock = MockServerWebExchange.from(MockServerHttpRequest
                .options("/orders-api/orders/42")
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "DELETE"));
        AtomicReference<ServerWebExchange> next = new AtomicReference<>();

        filter.filter(mock, chain(next)).block();

        assertThat(next.get()).isNull();
        assertThat(mock.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /oauth2/authorization/entra", "GET /login/oauth2/code/entra", "POST /logout"})
    void endpointsOfTheSessionChainPass(String request) {
        String[] methodAndPath = request.split(" ");
        SessionCountingExchange exchange = exchange(HttpMethod.valueOf(methodAndPath[0]), methodAndPath[1]);
        AtomicReference<ServerWebExchange> next = new AtomicReference<>();

        filter.filter(exchange, chain(next)).block();

        assertThat(next.get()).isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.<Object>getAttribute(GatewaySecurityAttributes.OPERATION_ACCESS)).isNull();
    }

    // QA-59-1: only the method and the registration id that the session chain handles pass.
    @ParameterizedTest
    @ValueSource(strings = {"GET /logout", "PUT /logout", "DELETE /logout", "POST /oauth2/authorization/entra",
        "POST /oauth2/authorization/unknown", "GET /oauth2/authorization/unknown",
        "GET /oauth2/authorization/entra-6f1c2e8a-0000-0000-0000-000000000000", "POST /login/oauth2/code/entra",
        "GET /login/oauth2/code/unknown", "GET /oauth2/authorization/entra/x", "GET /logout/x"})
    void otherRequestsToTheSessionChainPathsGet404(String request) {
        String[] methodAndPath = request.split(" ");
        SessionCountingExchange exchange = exchange(HttpMethod.valueOf(methodAndPath[0]), methodAndPath[1]);
        AtomicReference<ServerWebExchange> next = new AtomicReference<>();

        filter.filter(exchange, chain(next)).block();

        assertThat(next.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(SessionAuthenticationEntryPointTest.NOT_FOUND_BODY);
        assertThat(exchange.sessionCalls.get()).isZero();
    }

    // SEC-59-2: in the management context, only the paths under the actuator base path pass.
    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/health", "/actuator/health/liveness"})
    void actuatorPathsOfTheManagementContextPass(String path) {
        SessionCountingExchange exchange = exchange(HttpMethod.GET, path).inManagementContext();
        AtomicReference<ServerWebExchange> next = new AtomicReference<>();

        filter.filter(exchange, chain(next)).block();

        assertThat(next.get()).isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/admin/secrets", "/actuatorx", "/orders-api/orders/42"})
    void otherPathsOfTheManagementContextGet404(String path) {
        SessionCountingExchange exchange = exchange(HttpMethod.GET, path).inManagementContext();
        AtomicReference<ServerWebExchange> next = new AtomicReference<>();

        filter.filter(exchange, chain(next)).block();

        assertThat(next.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(SessionAuthenticationEntryPointTest.NOT_FOUND_BODY);
    }

    private static WebFilterChain chain(AtomicReference<ServerWebExchange> next) {
        return exchange -> {
            next.set(exchange);
            return Mono.empty();
        };
    }

    private static SessionCountingExchange exchange(HttpMethod method, String path) {
        return new SessionCountingExchange(MockServerWebExchange.from(MockServerHttpRequest.method(method, URI.create(path))));
    }

    /** Counts the calls of {@link #getSession()} and {@link #getPrincipal()}. */
    private static final class SessionCountingExchange extends ServerWebExchangeDecorator {

        private final AtomicInteger sessionCalls = new AtomicInteger();

        private final MockServerWebExchange mock;

        private ApplicationContext applicationContext;

        SessionCountingExchange(MockServerWebExchange delegate) {
            super(delegate);
            this.mock = delegate;
        }

        /** The exchange as the management context of Spring Boot (server namespace {@code management}) sees it. */
        SessionCountingExchange inManagementContext() {
            ReactiveWebServerApplicationContext context = new ReactiveWebServerApplicationContext();
            context.setServerNamespace("management");
            this.applicationContext = context;
            return this;
        }

        @Override
        public ApplicationContext getApplicationContext() {
            return applicationContext;
        }

        @Override
        public Mono<WebSession> getSession() {
            sessionCalls.incrementAndGet();
            return super.getSession();
        }

        @Override
        public <T extends Principal> Mono<T> getPrincipal() {
            sessionCalls.incrementAndGet();
            return super.getPrincipal();
        }

        @Override
        public MockServerHttpResponse getResponse() {
            return mock.getResponse();
        }
    }
}
