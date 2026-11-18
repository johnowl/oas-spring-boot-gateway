package com.johnowl.gateway.security;

import com.johnowl.gateway.core.access.OperationAccess;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.web.server.ServerWebExchange;

/**
 * The denial log and the denial metrics (spec 024, "Denial logs and metrics"). Each denial writes exactly
 * one line, with the deny reason, the method, the path ({@link LogPathFormatter}), and the principal name
 * ({@code oid}, or {@code -} without a user). For a token error of spec 023, the line also has the fields of
 * {@link GatewaySecurityAttributes#TOKEN_ERROR}. A line never contains a token.
 *
 * <p>Each deny reason except {@link DenyReason#WRONG_AUDIENCE} increments {@value #DENIED_METRIC} with the tag
 * {@code reason}. {@code WRONG_AUDIENCE} increments only {@value #AUDIENCE_MISMATCH_METRIC} with the tag
 * {@code route}.
 */
public final class AuthorizationDenials {

    public static final String DENIED_METRIC = "gateway.authorization.denied";

    public static final String AUDIENCE_MISMATCH_METRIC = "gateway.authorization.audience.mismatch";

    private static final Logger log = LoggerFactory.getLogger(AuthorizationDenials.class);

    private final MeterRegistry meters;

    public AuthorizationDenials(MeterRegistry meters) {
        this.meters = meters;
    }

    /** The log level of a deny reason. */
    static Level level(DenyReason reason) {
        return switch (reason) {
            case NO_MATCH, NOT_AUTHENTICATED -> Level.DEBUG;
            case MISSING_PERMISSION, SESSION_INVALID, CONSENT_REQUIRED, REAUTHENTICATION_REQUIRED -> Level.INFO;
            case TOKEN_GRANT_LIMIT, TOKEN_ENDPOINT_TIMEOUT, TOKEN_ENDPOINT_ERROR, TOKEN_ENDPOINT_THROTTLED -> Level.WARN;
            case TOKEN_REQUEST_REJECTED, INVALID_AUDIENCE_TOKEN, WRONG_AUDIENCE, INTERNAL_ERROR -> Level.ERROR;
        };
    }

    /**
     * Logs and counts a denial.
     *
     * @param operation the operation of the request, or {@code null} for {@link DenyReason#NO_MATCH}
     * @param principal the principal name of the user, or {@code null} without a user
     */
    public void denied(ServerWebExchange exchange, DenyReason reason, OperationAccess operation, String principal) {
        meters.counter(DENIED_METRIC, "reason", reason.name()).increment();
        Level level = level(reason);
        if (log.isEnabledForLevel(level)) {
            log.atLevel(level).log("Access denied: reason={} {}", reason, request(exchange, operation, principal)
                    + tokenError(exchange.getAttribute(GatewaySecurityAttributes.TOKEN_ERROR)));
        }
    }

    /**
     * Logs and counts an {@link DenyReason#INTERNAL_ERROR}. The line has the class of the error, not its message,
     * because a message can contain a token.
     */
    public void internalError(ServerWebExchange exchange, OperationAccess operation, String principal, Throwable error) {
        meters.counter(DENIED_METRIC, "reason", DenyReason.INTERNAL_ERROR.name()).increment();
        log.error("Access denied: reason={} {} exception={}", DenyReason.INTERNAL_ERROR,
                request(exchange, operation, principal), error.getClass().getName());
    }

    /**
     * Logs and counts a token whose {@code aud} is not the audience of the access rule. The line has the route
     * id, the expected audience, and the token audience, never the token.
     */
    public void wrongAudience(ServerWebExchange exchange, OperationAccess operation, String principal, String tokenAudience) {
        meters.counter(AUDIENCE_MISMATCH_METRIC, "route", operation.routeId()).increment();
        log.error("Access denied: reason={} {} expectedAudience={} tokenAudience={}", DenyReason.WRONG_AUDIENCE,
                request(exchange, operation, principal), operation.rule().audience(), tokenAudience);
    }

    private static String request(ServerWebExchange exchange, OperationAccess operation, String principal) {
        String text = "method=" + exchange.getRequest().getMethod().name()
                + " path=" + LogPathFormatter.format(exchange.getRequest().getURI().getRawPath())
                + " principal=" + (principal == null ? "-" : principal);
        return operation == null ? text : text + " route=" + operation.routeId();
    }

    private static String tokenError(TokenError error) {
        if (error == null) {
            return "";
        }
        return " registration=" + error.registrationId() + " status=" + error.status()
                + " error=" + (error.error() == null ? "-" : error.error())
                + " errorCodes=" + error.errorCodes()
                + (error.failedCheck() == null ? "" : " check=" + error.failedCheck());
    }
}
