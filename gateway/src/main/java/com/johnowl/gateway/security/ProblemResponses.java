package com.johnowl.gateway.security;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The fixed problem bodies ({@code application/problem+json}, RFC 9457) of the security chain. Each
 * body has a generic {@code detail}. It never contains the reason of the decision, the session, or
 * gateway internals.
 */
public final class ProblemResponses {

    /** The problem {@code type} of missing consent (spec 023), so that the single-page application can show a specific message. */
    public static final String CONSENT_REQUIRED_TYPE = "urn:oas-spring-boot-gateway:problem:consent-required";

    private ProblemResponses() {
    }

    /**
     * Writes the generic problem body of the status.
     *
     * @param status {@code 401}, {@code 403}, {@code 404}, {@code 429}, {@code 500}, {@code 502},
     *     {@code 503} or {@code 504}
     */
    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status) {
        String body = switch (status) {
            case UNAUTHORIZED -> body(status, "Unauthorized", "Authentication required.");
            case FORBIDDEN -> body(status, "Forbidden", "Access denied.");
            case NOT_FOUND -> body(status, "Not Found", "Not found.");
            case TOO_MANY_REQUESTS -> body(status, "Too Many Requests", "Too many requests.");
            case INTERNAL_SERVER_ERROR -> body(status, "Internal Server Error", "Internal error.");
            case BAD_GATEWAY -> body(status, "Bad Gateway", "Bad gateway.");
            case SERVICE_UNAVAILABLE -> body(status, "Service Unavailable", "Try again later.");
            case GATEWAY_TIMEOUT -> body(status, "Gateway Timeout", "Gateway timeout.");
            default -> throw new IllegalArgumentException("No generic problem body for " + status);
        };
        return write(exchange, status, body);
    }

    /** Writes the {@code 403} body of missing consent (spec 023), with its own problem {@code type}. */
    public static Mono<Void> writeConsentRequired(ServerWebExchange exchange) {
        return write(exchange, HttpStatus.FORBIDDEN, "{\"type\":\"" + CONSENT_REQUIRED_TYPE
                + "\",\"title\":\"Consent Required\",\"status\":403,\"detail\":\"Consent required.\"}");
    }

    private static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String body) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return response.writeWith(Mono.fromSupplier(
                () -> response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    private static String body(HttpStatus status, String title, String detail) {
        return "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\"}";
    }
}
