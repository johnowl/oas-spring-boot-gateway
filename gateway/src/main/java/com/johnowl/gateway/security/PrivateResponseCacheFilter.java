package com.johnowl.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Forces {@code Cache-Control: no-store} and {@code Pragma: no-cache} on a backend response when the request
 * is authenticated or the response sets a gateway cookie (spec 025, "Response headers"). A shared cache must
 * not keep the data of one user or a cookie of the gateway, also when the backend sends
 * {@code Cache-Control: public}.
 *
 * <p>Spring Security writes its cache headers only when the response has none, so a backend value would
 * stay. This filter sets the headers just before the response is committed, after the gateway has copied
 * the backend headers and after the session and CSRF cookies are added.
 */
public final class PrivateResponseCacheFilter implements GlobalFilter, Ordered {

    /** After {@code RouteOperationFilter} and {@code IdentityHeaderRemovalFilter}. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .map(principal -> principal instanceof Authentication authentication
                        && TRUST_RESOLVER.isAuthenticated(authentication))
                .defaultIfEmpty(false)
                .flatMap(authenticated -> {
                    ServerHttpResponse response = exchange.getResponse();
                    response.beforeCommit(() -> {
                        if (authenticated || !response.getCookies().isEmpty()) {
                            response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
                            response.getHeaders().set(HttpHeaders.PRAGMA, "no-cache");
                        }
                        return Mono.empty();
                    });
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
