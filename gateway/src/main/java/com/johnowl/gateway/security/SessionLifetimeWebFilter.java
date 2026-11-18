package com.johnowl.gateway.security;

import java.time.Clock;
import java.time.Duration;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebSession;
import org.springframework.web.server.session.WebSessionManager;
import reactor.core.publisher.Mono;

/**
 * The lifetime of a session after login (spec 022, "Session lifetime"). Spring Session has no
 * maximum lifetime, so this filter checks it.
 *
 * <ul>
 *   <li>At login, {@link #start(WebSession)} stores the login time and raises the maximum inactive
 *       interval from the pre-login timeout to the idle timeout.
 *   <li>For each request with an authenticated session, the filter invalidates the session if the
 *       login time is older than the maximum lifetime, or if the login time is missing. The request
 *       then continues as a request without a session: the rest of the chain gets a new, empty
 *       session from the {@link WebSessionManager}. A saved request or an authorization request
 *       goes into this new session, so a new login works.
 * </ul>
 */
final class SessionLifetimeWebFilter implements WebFilter {

    static final String LOGIN_TIME_ATTRIBUTE = "gateway.session.login-time";

    private static final String SECURITY_CONTEXT_ATTRIBUTE =
            WebSessionServerSecurityContextRepository.DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME;

    private final Duration idleTimeout;

    private final Duration maxLifetime;

    private final Clock clock;

    private final WebSessionManager sessionManager;

    SessionLifetimeWebFilter(Duration idleTimeout, Duration maxLifetime, Clock clock, WebSessionManager sessionManager) {
        this.idleTimeout = idleTimeout;
        this.maxLifetime = maxLifetime;
        this.clock = clock;
        this.sessionManager = sessionManager;
    }

    /** Starts the lifetime of a session at login. */
    void start(WebSession session) {
        session.getAttributes().put(LOGIN_TIME_ATTRIBUTE, clock.millis());
        session.setMaxIdleTime(idleTimeout);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getSession()
                .filter(this::isExpired)
                .flatMap(expired -> invalidate(expired).then(Mono.fromSupplier(() -> withNewSession(exchange))))
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    /**
     * The exchange with a new session. The store no longer has the old session, so the session
     * manager creates a new one. It also saves the new session before the response, and it sets the
     * session cookie only if the new session has attributes.
     */
    private ServerWebExchange withNewSession(ServerWebExchange exchange) {
        Mono<WebSession> newSession = sessionManager.getSession(exchange).cache();
        return new ServerWebExchangeDecorator(exchange) {
            @Override
            public Mono<WebSession> getSession() {
                return newSession;
            }
        };
    }

    /** {@code true} for an authenticated session without a login time, or with a login time older than the limit. */
    boolean isExpired(WebSession session) {
        if (!session.getAttributes().containsKey(SECURITY_CONTEXT_ATTRIBUTE)) {
            return false;
        }
        Object loginTime = session.getAttributes().get(LOGIN_TIME_ATTRIBUTE);
        if (!(loginTime instanceof Number millis)) {
            return true;
        }
        return clock.millis() - millis.longValue() > maxLifetime.toMillis();
    }

    private static Mono<Void> invalidate(WebSession session) {
        // Clear first: the security context repository reads the same session object later.
        session.getAttributes().clear();
        return session.invalidate();
    }
}
