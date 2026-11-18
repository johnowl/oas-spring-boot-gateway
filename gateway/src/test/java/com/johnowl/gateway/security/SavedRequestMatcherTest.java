package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class SavedRequestMatcherTest {

    private final SavedRequestMatcher matcher = new SavedRequestMatcher();

    @Test
    void savesAnHtmlGet() {
        assertThat(matches(html("/orders-api/orders/42?view=full"))).isTrue();
    }

    @Test
    void doesNotSaveOtherRequests() {
        assertThat(matches(MockServerHttpRequest.get("/a").accept(MediaType.APPLICATION_JSON))).isFalse();
        assertThat(matches(MockServerHttpRequest.get("/a").accept(MediaType.ALL))).isFalse();
        assertThat(matches(MockServerHttpRequest.post("/a").accept(MediaType.TEXT_HTML))).isFalse();
    }

    @Test
    void savesAtMost1024BytesOfPathAndQuery() {
        String path = "/a?q=";
        assertThat(matches(html(path + "x".repeat(1024 - path.length())))).isTrue();
        assertThat(matches(html(path + "x".repeat(1025 - path.length())))).isFalse();
    }

    @Test
    void doesNotSaveAPathOfAnotherHost() {
        assertThat(matches(html("//evil.example.com/a"))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(names = {"NO_MATCH", "SESSION_INVALID", "REAUTHENTICATION_REQUIRED"})
    void doesNotSaveForTheExcludedDenyReasons(DenyReason reason) {
        MockServerWebExchange exchange = MockServerWebExchange.from(html("/orders-api/orders/42"));
        exchange.getAttributes().put(GatewaySecurityAttributes.DENY_REASON, reason);

        assertThat(matcher.matches(exchange).block().isMatch()).isFalse();
    }

    private boolean matches(MockServerHttpRequest.BaseBuilder<?> request) {
        return matcher.matches(MockServerWebExchange.from(request)).block().isMatch();
    }

    private static MockServerHttpRequest.BaseBuilder<?> html(String pathAndQuery) {
        // A full URI, so that "//host" stays a path, as in the request line "GET //host HTTP/1.1".
        return MockServerHttpRequest.method(HttpMethod.GET, URI.create("http://localhost" + pathAndQuery))
                .accept(MediaType.TEXT_HTML);
    }
}
