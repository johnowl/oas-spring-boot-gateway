package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

/** The table of spec 024, "Response handling". */
class GatewayAccessDeniedHandlerTest {

    static final String FORBIDDEN_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Access denied.\"}";

    static final String INTERNAL_ERROR_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500,\"detail\":\"Internal error.\"}";

    static final String CONSENT_BODY = "{\"type\":\"urn:oas-spring-boot-gateway:problem:consent-required\","
            + "\"title\":\"Consent Required\",\"status\":403,\"detail\":\"Consent required.\"}";

    private static final Map<DenyReason, String> BODIES = Map.ofEntries(
            Map.entry(DenyReason.NO_MATCH, SessionAuthenticationEntryPointTest.NOT_FOUND_BODY),
            Map.entry(DenyReason.NOT_AUTHENTICATED, FORBIDDEN_BODY),
            Map.entry(DenyReason.MISSING_PERMISSION, FORBIDDEN_BODY),
            Map.entry(DenyReason.CONSENT_REQUIRED, CONSENT_BODY),
            Map.entry(DenyReason.SESSION_INVALID, SessionAuthenticationEntryPointTest.UNAUTHORIZED_BODY),
            Map.entry(DenyReason.REAUTHENTICATION_REQUIRED, SessionAuthenticationEntryPointTest.UNAUTHORIZED_BODY),
            Map.entry(DenyReason.TOKEN_GRANT_LIMIT,
                    "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Too many requests.\"}"),
            Map.entry(DenyReason.TOKEN_REQUEST_REJECTED, INTERNAL_ERROR_BODY),
            Map.entry(DenyReason.INVALID_AUDIENCE_TOKEN, INTERNAL_ERROR_BODY),
            Map.entry(DenyReason.WRONG_AUDIENCE, INTERNAL_ERROR_BODY),
            Map.entry(DenyReason.INTERNAL_ERROR, INTERNAL_ERROR_BODY),
            Map.entry(DenyReason.TOKEN_ENDPOINT_TIMEOUT,
                    "{\"type\":\"about:blank\",\"title\":\"Gateway Timeout\",\"status\":504,\"detail\":\"Gateway timeout.\"}"),
            Map.entry(DenyReason.TOKEN_ENDPOINT_ERROR,
                    "{\"type\":\"about:blank\",\"title\":\"Bad Gateway\",\"status\":502,\"detail\":\"Bad gateway.\"}"),
            Map.entry(DenyReason.TOKEN_ENDPOINT_THROTTLED,
                    "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"Try again later.\"}"));

    private final GatewayAccessDeniedHandler handler =
            new GatewayAccessDeniedHandler(new SessionAuthenticationEntryPoint(NoOpServerRequestCache.getInstance()));

    @ParameterizedTest
    @EnumSource(DenyReason.class)
    void eachDenyReasonGetsItsFixedProblemBody(DenyReason reason) {
        MockServerWebExchange exchange = handle(reason, MediaType.APPLICATION_JSON);

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).isEqualTo(BODIES.get(reason));
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(status(body));
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(body).doesNotContain("insufficient_scope").doesNotContain("Orders.");
    }

    @ParameterizedTest
    @EnumSource(value = DenyReason.class, names = {"SESSION_INVALID", "REAUTHENTICATION_REQUIRED"})
    void anEndedSessionRedirectsAnHtmlNavigationToTheLogin(DenyReason reason) {
        MockServerWebExchange exchange = handle(reason, MediaType.TEXT_HTML);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString(SessionAuthenticationEntryPoint.LOGIN_PATH);
    }

    @Test
    void withoutDenyReasonTheResponseIsTheGeneric403() {
        MockServerWebExchange exchange = handle(null, MediaType.APPLICATION_JSON);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(FORBIDDEN_BODY);
    }

    private static int status(String body) {
        return Integer.parseInt(body.replaceAll(".*\"status\":(\\d+).*", "$1"));
    }

    private MockServerWebExchange handle(DenyReason reason, MediaType accept) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders-api/orders/42").accept(accept));
        if (reason != null) {
            exchange.getAttributes().put(GatewaySecurityAttributes.DENY_REASON, reason);
        }
        handler.handle(exchange, new AccessDeniedException("Access Denied")).block();
        return exchange;
    }
}
