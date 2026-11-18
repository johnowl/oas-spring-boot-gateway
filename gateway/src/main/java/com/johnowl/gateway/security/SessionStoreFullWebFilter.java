package com.johnowl.gateway.security;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Answers {@code 503} when the in-memory session store refuses a new session (spec 022, "Session
 * store"). The session manager saves a session just before the response starts. A refused save
 * would otherwise end the connection without a response.
 *
 * <p>The filter runs first and starts the response itself, so a refused save reaches it. The
 * response then still has no status line on the wire, and the filter replaces it with the 503 problem
 * body: no redirect, and no session cookie.
 */
final class SessionStoreFullWebFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange)
                .then(Mono.defer(() -> exchange.getResponse().setComplete()))
                .onErrorResume(SessionStoreFullException.class, e -> refuse(exchange));
    }

    private static Mono<Void> refuse(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().remove(HttpHeaders.LOCATION);
        response.getCookies().remove(SessionCookieConfiguration.COOKIE_NAME);
        return ProblemResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
