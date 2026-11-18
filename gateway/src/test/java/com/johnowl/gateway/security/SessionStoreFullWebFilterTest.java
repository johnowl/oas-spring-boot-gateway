package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class SessionStoreFullWebFilterTest {

    private final SessionStoreFullWebFilter filter = new SessionStoreFullWebFilter();

    @Test
    void refusedSessionGivesThe503ProblemBodyWithoutRedirectAndSessionCookie() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));

        filter.filter(exchange, next -> Mono.fromRunnable(() -> {
            // As the entry point and the session manager do it for a new pre-login session.
            next.getResponse().setStatusCode(HttpStatus.FOUND);
            next.getResponse().getHeaders().setLocation(URI.create("/oauth2/authorization/entra"));
            next.getResponse().beforeCommit(() -> {
                next.getResponse().addCookie(ResponseCookie.from(SessionCookieConfiguration.COOKIE_NAME, "id").build());
                return Mono.error(new SessionStoreFullException());
            });
        })).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getLocation()).isNull();
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getCookies()).doesNotContainKey(SessionCookieConfiguration.COOKIE_NAME);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(
                "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"Try again later.\"}");
    }

    @Test
    void otherResponsesAreUnchanged() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));

        filter.filter(exchange, next -> Mono.fromRunnable(() -> {
            next.getResponse().setStatusCode(HttpStatus.FOUND);
            next.getResponse().getHeaders().setLocation(URI.create("/oauth2/authorization/entra"));
        })).block();

        assertThat(exchange.getResponse().isCommitted()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString("/oauth2/authorization/entra");
    }
}
