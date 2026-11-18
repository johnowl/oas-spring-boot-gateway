package com.johnowl.gateway.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The response to a denied request of an authenticated user (spec 024, "Response handling"). It reads the
 * deny reason of the exchange attribute {@link GatewaySecurityAttributes#DENY_REASON}. Each body is a fixed
 * problem body of {@link ProblemResponses}: it never names a permission, and no response has a
 * {@code WWW-Authenticate} header. The gateway does not use {@code BearerTokenServerAccessDeniedHandler},
 * because it reports {@code insufficient_scope}.
 */
public final class GatewayAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final ServerAuthenticationEntryPoint entryPoint;

    /** @param entryPoint the entry point of spec 022, for an ended session */
    public GatewayAccessDeniedHandler(ServerAuthenticationEntryPoint entryPoint) {
        this.entryPoint = entryPoint;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        DenyReason reason = exchange.getAttribute(GatewaySecurityAttributes.DENY_REASON);
        if (reason == null) {
            return ProblemResponses.write(exchange, HttpStatus.FORBIDDEN);
        }
        return switch (reason) {
            case NO_MATCH -> ProblemResponses.write(exchange, HttpStatus.NOT_FOUND);
            // The session ended (spec 023): the user must sign in again.
            case SESSION_INVALID, REAUTHENTICATION_REQUIRED -> entryPoint.commence(exchange,
                    new AuthenticationCredentialsNotFoundException("The session ended."));
            case CONSENT_REQUIRED -> ProblemResponses.writeConsentRequired(exchange);
            case TOKEN_GRANT_LIMIT -> ProblemResponses.write(exchange, HttpStatus.TOO_MANY_REQUESTS);
            case TOKEN_ENDPOINT_TIMEOUT -> ProblemResponses.write(exchange, HttpStatus.GATEWAY_TIMEOUT);
            case TOKEN_ENDPOINT_ERROR -> ProblemResponses.write(exchange, HttpStatus.BAD_GATEWAY);
            case TOKEN_ENDPOINT_THROTTLED -> ProblemResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE);
            case TOKEN_REQUEST_REJECTED, INVALID_AUDIENCE_TOKEN, WRONG_AUDIENCE, INTERNAL_ERROR ->
                    ProblemResponses.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR);
            case MISSING_PERMISSION, NOT_AUTHENTICATED -> ProblemResponses.write(exchange, HttpStatus.FORBIDDEN);
        };
    }
}
