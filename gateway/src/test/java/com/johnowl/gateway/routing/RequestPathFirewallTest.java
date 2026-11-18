package com.johnowl.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequestPathFirewallTest {

    private final RequestPathFirewall firewall = new RequestPathFirewall();

    @ParameterizedTest
    @ValueSource(strings = {
        "/orders/..",
        "/orders/../admin",
        "/orders/.",
        "/./orders",
        "/orders/%2e%2e",
        "/orders/%2E%2E/admin",
        "/orders/.%2e",
        "/orders/%2e",
        "/orders/..;jsessionid=1",
        "/orders/..%3Bx",
        "/orders/..%2F..%2Fadmin%2Fsecrets",
        "/orders/a%2fb",
        "/orders/a%5Cb",
        "/orders/a%5cb",
        "/orders/a\\b",
        "/orders/%zz",
        "/orders/%2",
        // SEC-59-1: matrix parameters and encoded unreserved characters.
        "/orders/a;b",
        "/users/me;x=1",
        "/users/me%3Bx=1",
        "/users/me%3bx=1",
        "/orders/a%3Bb",
        "/users;x/me",
        "/users/m%65",
        "/users/M%45",
        "/%6Frders/1",
        "/orders/%34%32",
        "/orders/a%2Db",
        "/orders/a%5fb",
        "/orders/a%7Eb",
        "/orders/report%2Ev2",
    })
    void rejectsAmbiguousPaths(String rawPath) {
        assertThat(RequestPathFirewall.isRejected(rawPath)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/",
        "/orders",
        "/orders/42",
        "/orders/",
        "/files/report.v2",
        "/files/...",
        "/a/.well-known",
        "/a/..b",
        "/orders/a%20b",
        "/orders/%25",
        "/orders/caf%C3%A9",
        "/orders/a%40b",
        "/orders/100%25",
    })
    void acceptsOtherPaths(String rawPath) {
        assertThat(RequestPathFirewall.isRejected(rawPath)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/orders/..", "/orders/a%2Fb", "/users/me;x=1", "/users/me%3Bx=1", "/users/m%65"})
    void returnsBadRequestWithoutCallingTheChain(String rawPath) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(rawPath)).build());

        StepVerifier.create(firewall.filter(exchange, ignored -> Mono.error(new AssertionError("chain called"))))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/orders/42", "/a/.well-known"})
    void passesOtherPathsToTheChain(String rawPath) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(rawPath)).build());

        StepVerifier.create(firewall.filter(exchange, ignored -> Mono.empty())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
