package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache;

class SessionAuthenticationEntryPointTest {

    static final String UNAUTHORIZED_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Authentication required.\"}";

    static final String NOT_FOUND_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"Not found.\"}";

    private final WebSessionServerRequestCache requestCache = new WebSessionServerRequestCache();

    private final SessionAuthenticationEntryPoint entryPoint = new SessionAuthenticationEntryPoint(requestCache);

    @Test
    void htmlNavigationRedirectsToTheLogin() {
        MockServerWebExchange exchange = commence(get().accept(MediaType.TEXT_HTML, MediaType.ALL));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).isEqualTo(URI.create("/oauth2/authorization/entra"));
        assertThat(requestCache.getRedirectUri(exchange).block()).isEqualTo(URI.create("/orders-api/orders/42"));
    }

    @Test
    void fetchGetsTheProblemBodyWithoutRedirect() {
        MockServerWebExchange exchange = commence(get().accept(MediaType.APPLICATION_JSON));

        assertUnauthorized(exchange);
        assertThat(exchange.getSession().block().isStarted()).as("session started").isFalse();
    }

    @Test
    void anyMediaTypeIsNotNavigation() {
        assertUnauthorized(commence(get().accept(MediaType.ALL)));
        assertUnauthorized(commence(get()));
    }

    @Test
    void noMatchGetsNotFoundAlsoForHtml() {
        MockServerWebExchange exchange = MockServerWebExchange.from(get().accept(MediaType.TEXT_HTML));
        exchange.getAttributes().put(GatewaySecurityAttributes.DENY_REASON, DenyReason.NO_MATCH);

        entryPoint.commence(exchange, new InsufficientAuthenticationException("test")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(NOT_FOUND_BODY);
        assertThat(exchange.getSession().block().isStarted()).as("session started").isFalse();
    }

    @Test
    void otherDenyReasonsUseTheNormalSelection() {
        MockServerWebExchange exchange = MockServerWebExchange.from(get().accept(MediaType.APPLICATION_JSON));
        exchange.getAttributes().put(GatewaySecurityAttributes.DENY_REASON, DenyReason.SESSION_INVALID);

        entryPoint.commence(exchange, new InsufficientAuthenticationException("test")).block();

        assertUnauthorized(exchange);
    }

    private MockServerWebExchange commence(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        entryPoint.commence(exchange, new InsufficientAuthenticationException("test")).block();
        return exchange;
    }

    private static MockServerHttpRequest.BaseBuilder<?> get() {
        return MockServerHttpRequest.get("/orders-api/orders/42");
    }

    private static void assertUnauthorized(MockServerWebExchange exchange) {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getHeaders().containsHeader(HttpHeaders.LOCATION)).isFalse();
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(UNAUTHORIZED_BODY);
    }
}
