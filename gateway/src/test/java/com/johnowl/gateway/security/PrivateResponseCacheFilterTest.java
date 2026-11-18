package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** SEC-61-2: a backend {@code Cache-Control} does not replace {@code no-store}. */
class PrivateResponseCacheFilterTest {

    private final PrivateResponseCacheFilter filter = new PrivateResponseCacheFilter();

    @Test
    void anAuthenticatedRequestGetsNoStore() {
        TestingAuthenticationToken user = new TestingAuthenticationToken("alice", null, "ROLE_USER");

        HttpHeaders headers = respond(exchange().mutate().principal(Mono.just(user)).build(), false);

        assertThat(headers.get(HttpHeaders.CACHE_CONTROL)).containsExactly("no-store");
        assertThat(headers.get(HttpHeaders.PRAGMA)).containsExactly("no-cache");
    }

    @Test
    void aResponseWithAGatewayCookieGetsNoStore() {
        HttpHeaders headers = respond(exchange(), true);

        assertThat(headers.get(HttpHeaders.CACHE_CONTROL)).containsExactly("no-store");
        assertThat(headers.get(HttpHeaders.PRAGMA)).containsExactly("no-cache");
    }

    @Test
    void anAnonymousRequestWithoutAGatewayCookieKeepsTheBackendValue() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        Principal other = () -> "not-an-authentication";

        for (ServerWebExchange exchange : List.of(exchange(), exchange().mutate().principal(Mono.just(anonymous)).build(),
                exchange().mutate().principal(Mono.just(other)).build())) {
            HttpHeaders headers = respond(exchange, false);

            assertThat(headers.get(HttpHeaders.CACHE_CONTROL)).containsExactly("public, max-age=3600");
            assertThat(headers.containsHeader(HttpHeaders.PRAGMA)).isFalse();
        }
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/orders-api/orders/42"));
    }

    /** Runs the filter with a backend that sends {@code Cache-Control: public}, and commits the response. */
    private HttpHeaders respond(ServerWebExchange exchange, boolean gatewayCookie) {
        filter.filter(exchange, forwarded -> {
            forwarded.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "public, max-age=3600");
            if (gatewayCookie) {
                forwarded.getResponse().addCookie(ResponseCookie.from("__Host-XSRF-TOKEN", "t").build());
            }
            return forwarded.getResponse().setComplete();
        }).block();
        return exchange.getResponse().getHeaders();
    }
}
