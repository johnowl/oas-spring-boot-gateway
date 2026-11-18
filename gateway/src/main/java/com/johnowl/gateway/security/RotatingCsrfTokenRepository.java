package com.johnowl.gateway.security;

import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Keeps the CSRF token in the cookie {@code __Host-XSRF-TOKEN}: readable by JavaScript, {@code Secure},
 * {@code Path=/}, and without {@code Domain}. The single-page application sends the value in the
 * header {@code X-XSRF-TOKEN}. A token is valid when it equals the cookie value of the request.
 *
 * <p>A request to clear the token ({@code saveToken(exchange, null)}) writes a new token instead.
 * Spring Security clears the token at logout, and the login success handler does the same. So the
 * browser gets a new token at login and at logout, and the old token no longer matches its cookie.
 */
final class RotatingCsrfTokenRepository implements ServerCsrfTokenRepository {

    static final String COOKIE_NAME = "__Host-XSRF-TOKEN";

    private final CookieServerCsrfTokenRepository delegate = CookieServerCsrfTokenRepository.withHttpOnlyFalse();

    RotatingCsrfTokenRepository() {
        delegate.setCookieName(COOKIE_NAME);
        delegate.setCookiePath("/");
        // Explicit, because TLS can end at a load balancer and the gateway then sees http.
        delegate.setCookieCustomizer(cookie -> cookie.secure(true));
    }

    @Override
    public Mono<CsrfToken> generateToken(ServerWebExchange exchange) {
        return delegate.generateToken(exchange);
    }

    @Override
    public Mono<Void> saveToken(ServerWebExchange exchange, CsrfToken token) {
        if (token != null) {
            return delegate.saveToken(exchange, token);
        }
        return delegate.generateToken(exchange).flatMap(newToken -> {
            // Only the new cookie goes to the browser, also if the response already has a cookie.
            exchange.getResponse().getCookies().remove(COOKIE_NAME);
            return delegate.saveToken(exchange, newToken);
        });
    }

    @Override
    public Mono<CsrfToken> loadToken(ServerWebExchange exchange) {
        return delegate.loadToken(exchange);
    }
}
