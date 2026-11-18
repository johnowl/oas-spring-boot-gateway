package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** Every problem body has a fixed, generic detail (specs 022 and 023). */
class ProblemResponsesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "401 | {\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Authentication required.\"}",
        "429 | {\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Too many requests.\"}",
        "500 | {\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500,\"detail\":\"Internal error.\"}",
        "502 | {\"type\":\"about:blank\",\"title\":\"Bad Gateway\",\"status\":502,\"detail\":\"Bad gateway.\"}",
        "504 | {\"type\":\"about:blank\",\"title\":\"Gateway Timeout\",\"status\":504,\"detail\":\"Gateway timeout.\"}",
    })
    void writesTheGenericBody(int status, String body) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));

        ProblemResponses.write(exchange, HttpStatus.valueOf(status)).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.valueOf(status));
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5))).isEqualTo(body);
    }

    @Test
    void consentRequiredHasItsOwnType() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));

        ProblemResponses.writeConsentRequired(exchange).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5))).isEqualTo(
                "{\"type\":\"urn:oas-spring-boot-gateway:problem:consent-required\",\"title\":\"Consent Required\","
                        + "\"status\":403,\"detail\":\"Consent required.\"}");
    }
}
