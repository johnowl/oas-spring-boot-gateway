package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.Loopback;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * A client on a real connection that keeps cookies like a browser. It records the headers and the
 * body of every response, so a test can check that no token reaches the browser.
 */
final class TestBrowser {

    static final String SESSION = "__Host-SESSION";

    static final String XSRF = "__Host-XSRF-TOKEN";

    private final WebTestClient client;

    private final Map<String, String> cookies = new LinkedHashMap<>();

    private final List<String> responses = new ArrayList<>();

    TestBrowser(int port) {
        client = WebTestClient.bindToServer().baseUrl(Loopback.url(port))
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    /** An HTML navigation. */
    EntityExchangeResult<byte[]> navigate(String uri) {
        return send(HttpMethod.GET, uri, MediaType.TEXT_HTML, null);
    }

    /** A fetch request of the single-page application. */
    EntityExchangeResult<byte[]> fetch(HttpMethod method, String uri) {
        return send(method, uri, MediaType.APPLICATION_JSON, null);
    }

    /** A fetch request with the CSRF header. */
    EntityExchangeResult<byte[]> fetch(HttpMethod method, String uri, String csrfToken) {
        return send(method, uri, MediaType.APPLICATION_JSON, csrfToken);
    }

    /**
     * Starts the login at the gateway.
     *
     * @return the authorization request: the redirect from the gateway to the identity provider
     */
    URI startLogin(MockIdentityProvider idp) {
        EntityExchangeResult<byte[]> start = navigate("/oauth2/authorization/entra");
        assertThat(start.getStatus()).isEqualTo(HttpStatus.FOUND);
        URI location = start.getResponseHeaders().getLocation();
        assertThat(location.toString()).startsWith(idp.authorizationEndpoint() + "?");
        return location;
    }

    /** Signs in at the identity provider and calls the callback of the gateway with the code and the state. */
    EntityExchangeResult<byte[]> completeLogin(MockIdentityProvider idp, URI authorizationRequest) {
        return completeLogin(idp, authorizationRequest, MockIdentityProvider.USER_OID);
    }

    /** As {@link #completeLogin(MockIdentityProvider, URI)}, for the user with this object id. */
    EntityExchangeResult<byte[]> completeLogin(MockIdentityProvider idp, URI authorizationRequest, String oid) {
        String code = idp.signIn(authorizationRequest, oid);
        String state = query(authorizationRequest).getFirst("state");
        return navigate("/login/oauth2/code/entra?code=" + code + "&state=" + state);
    }

    /** The full login. */
    EntityExchangeResult<byte[]> login(MockIdentityProvider idp) {
        return login(idp, MockIdentityProvider.USER_OID);
    }

    /** The full login of the user with this object id. */
    EntityExchangeResult<byte[]> login(MockIdentityProvider idp, String oid) {
        EntityExchangeResult<byte[]> callback = completeLogin(idp, startLogin(idp), oid);
        assertThat(callback.getStatus()).isEqualTo(HttpStatus.FOUND);
        return callback;
    }

    /** The decoded query parameters of a URI. */
    static MultiValueMap<String, String> query(URI uri) {
        MultiValueMap<String, String> decoded = new LinkedMultiValueMap<>();
        UriComponentsBuilder.fromUri(uri).build(true).getQueryParams().forEach((name, values) -> values.forEach(value ->
                decoded.add(name, value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8))));
        return decoded;
    }

    /** The cookie value that the browser sends, or {@code null}. */
    String cookie(String name) {
        return cookies.get(name);
    }

    void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    /** The headers and the bodies of all responses. */
    List<String> responses() {
        return List.copyOf(responses);
    }

    private EntityExchangeResult<byte[]> send(HttpMethod method, String uri, MediaType accept, String csrfToken) {
        EntityExchangeResult<byte[]> result = client.method(method).uri(uri)
                .accept(accept)
                .cookies(jar -> cookies.forEach(jar::add))
                .headers(headers -> {
                    if (csrfToken != null) {
                        headers.set("X-XSRF-TOKEN", csrfToken);
                    }
                })
                .exchange()
                .expectBody().returnResult();
        byte[] body = result.getResponseBody();
        responses.add(result.getResponseHeaders() + "\n" + (body == null ? "" : new String(body, StandardCharsets.UTF_8)));
        for (List<ResponseCookie> values : result.getResponseCookies().values()) {
            ResponseCookie cookie = values.getLast();
            if (cookie.getMaxAge().isZero()) {
                cookies.remove(cookie.getName());
            } else {
                cookies.put(cookie.getName(), cookie.getValue());
            }
        }
        return result;
    }
}
