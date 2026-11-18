package com.johnowl.gateway.security;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Subscribes to the CSRF token of the request, so that the {@code __Host-XSRF-TOKEN} cookie is always
 * present. Spring Security writes the cookie only when a component subscribes to the token, and the
 * single-page application needs the cookie before its first state-changing request.
 */
final class CsrfCookieWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
        return token == null ? chain.filter(exchange) : token.then(chain.filter(exchange));
    }
}
