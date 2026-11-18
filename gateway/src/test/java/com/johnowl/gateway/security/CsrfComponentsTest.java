package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.DefaultCsrfToken;
import reactor.core.publisher.Flux;

/** {@link HeaderOnlyCsrfTokenRequestHandler} and {@link RotatingCsrfTokenRepository}. */
class CsrfComponentsTest {

    private static final CsrfToken EXPECTED = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value");

    private final HeaderOnlyCsrfTokenRequestHandler handler = new HeaderOnlyCsrfTokenRequestHandler();

    private final RotatingCsrfTokenRepository repository = new RotatingCsrfTokenRepository();

    @Test
    void handlerReadsTheHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/a").header("X-XSRF-TOKEN", "token-value"));

        assertThat(handler.resolveCsrfTokenValue(exchange, EXPECTED).block()).isEqualTo("token-value");
    }

    @Test
    void handlerIgnoresTheFormFieldAndDoesNotReadTheBody() {
        // A body that fails when the handler reads it.
        Flux<DataBuffer> body = Flux.defer(() -> Flux.error(new AssertionError("The body must not be read")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/a?_csrf=token-value")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body));

        assertThat(handler.resolveCsrfTokenValue(exchange, EXPECTED).blockOptional()).isEmpty();
    }

    @Test
    void handlerIgnoresAFormBody() {
        DataBuffer form = DefaultDataBufferFactory.sharedInstance.wrap("_csrf=token-value".getBytes(StandardCharsets.UTF_8));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/a")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(Flux.just(form)));

        assertThat(handler.resolveCsrfTokenValue(exchange, EXPECTED).blockOptional()).isEmpty();
    }

    @Test
    void cookieIsAHostCookieThatJavaScriptCanRead() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a"));

        repository.saveToken(exchange, repository.generateToken(exchange).block()).block();

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("__Host-XSRF-TOKEN");
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(repository.generateToken(exchange).block().getHeaderName()).isEqualTo("X-XSRF-TOKEN");
    }

    @Test
    void clearingTheTokenWritesOneNewToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/logout").cookie(new HttpCookie("__Host-XSRF-TOKEN", "old")));
        repository.saveToken(exchange, new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "first")).block();

        repository.saveToken(exchange, null).block();

        assertThat(exchange.getResponse().getCookies().get("__Host-XSRF-TOKEN"))
                .singleElement()
                .satisfies(cookie -> {
                    assertThat(cookie.getValue()).isNotBlank().isNotEqualTo("old").isNotEqualTo("first");
                    assertThat(cookie.getMaxAge().isNegative()).as("session cookie").isTrue();
                });
    }
}
