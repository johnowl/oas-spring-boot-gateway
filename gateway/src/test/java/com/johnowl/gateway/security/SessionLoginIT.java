package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.RecordingMockBackend;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.util.MultiValueMap;

/**
 * The login flow on a real server with the mock identity provider (spec 022). The test plays the
 * browser and the sign-in page of Entra ID. It uses the default session store {@code memory};
 * {@link RedisSessionLoginIT} runs the same tests with Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionLoginIT {

    private static final String PROTECTED = "/orders-api/orders/42?view=full";

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @LocalServerPort
    private int port;

    private TestBrowser browser;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.register(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @BeforeEach
    void newBrowser() {
        browser = new TestBrowser(port);
        backend.clear();
    }

    private URI startLogin() {
        return browser.startLogin(idp);
    }

    private static MultiValueMap<String, String> query(URI uri) {
        return TestBrowser.query(uri);
    }

    @Test
    void authorizationRedirectHasPkceStateNonceAndScopes() {
        MultiValueMap<String, String> query = query(startLogin());

        assertThat(query.getFirst("response_type")).isEqualTo("code");
        assertThat(query.getFirst("client_id")).isEqualTo(MockIdentityProvider.CLIENT_ID);
        assertThat(query.getFirst("code_challenge")).isNotBlank();
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(query.getFirst("state")).isNotBlank();
        assertThat(query.getFirst("nonce")).isNotBlank();
        assertThat(query.getFirst("scope").split(" ")).containsExactlyInAnyOrder("openid", "profile", "offline_access");
        assertThat(query.getFirst("redirect_uri")).isEqualTo("https://gateway.example.com/login/oauth2/code/entra");
    }

    // A raw connection, so that the forged Host header really reaches the gateway.
    @Test
    void redirectUriIgnoresForgedHostAndForwardingHeaders() throws IOException {
        String response = RawHttp.send(port, """
                GET /oauth2/authorization/entra HTTP/1.1\r
                Host: evil.example.com\r
                Forwarded: for=203.0.113.9;host=evil.example.com;proto=http\r
                X-Forwarded-Host: evil.example.com\r
                X-Forwarded-Proto: http\r
                X-Forwarded-Port: 80\r
                X-Forwarded-Prefix: /evil\r
                Connection: close\r
                \r
                """);

        assertThat(response).startsWith("HTTP/1.1 302");
        String location = RawHttp.header(response, "Location");
        assertThat(query(URI.create(location)).getFirst("redirect_uri"))
                .isEqualTo("https://gateway.example.com/login/oauth2/code/entra");
    }

    @Test
    void loginCreatesANewSessionAndRedirectsToTheSavedRequest() {
        EntityExchangeResult<byte[]> navigation = browser.navigate(PROTECTED);
        assertThat(navigation.getStatus()).isEqualTo(HttpStatus.FOUND);
        assertThat(navigation.getResponseHeaders().getLocation()).hasToString("/oauth2/authorization/entra");
        String preLoginSession = browser.cookie(TestBrowser.SESSION);
        String preLoginCsrf = browser.cookie(TestBrowser.XSRF);
        assertThat(preLoginSession).isNotBlank();
        assertThat(preLoginCsrf).isNotBlank();

        EntityExchangeResult<byte[]> callback = browser.completeLogin(idp, startLogin());

        assertThat(callback.getStatus()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getResponseHeaders().getLocation()).hasToString(PROTECTED);
        ResponseCookie session = callback.getResponseCookies().getFirst(TestBrowser.SESSION);
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.isSecure()).isTrue();
        assertThat(session.getPath()).isEqualTo("/");
        assertThat(session.getSameSite()).isEqualTo("Lax");
        assertThat(session.getDomain()).isNull();
        assertThat(session.getValue()).isNotEqualTo(preLoginSession);
        assertThat(callback.getResponseCookies().get(TestBrowser.XSRF)).singleElement()
                .satisfies(cookie -> assertThat(cookie.getValue()).isNotBlank().isNotEqualTo(preLoginCsrf));

        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(backend.requests()).containsExactly("GET /orders/42?view=full");
        assertThat(browser.fetch(HttpMethod.GET, "/orders-api/orders/42").getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tokenRequestUsesPkceTheClientSecretAndThePublicRedirectUri() {
        browser.completeLogin(idp, startLogin());

        // The mock provider answers only if the client secret and the PKCE code verifier are correct.
        MockIdentityProvider.Tokens tokens = idp.issued().getLast();
        assertThat(tokens.form()).containsEntry("grant_type", "authorization_code")
                .containsEntry("redirect_uri", "https://gateway.example.com/login/oauth2/code/entra")
                .containsKey("code_verifier");
        assertThat(browser.fetch(HttpMethod.GET, "/orders-api/orders/42").getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void noTokenReachesTheBrowser() {
        browser.navigate(PROTECTED);
        browser.completeLogin(idp, startLogin());
        browser.navigate(PROTECTED);
        browser.fetch(HttpMethod.POST, "/logout", browser.cookie(TestBrowser.XSRF));

        MockIdentityProvider.Tokens tokens = idp.issued().getLast();
        assertThat(browser.responses()).isNotEmpty().allSatisfy(response -> assertThat(response)
                .doesNotContain(tokens.idToken())
                .doesNotContain(tokens.idToken().substring(0, tokens.idToken().indexOf('.')))
                .doesNotContain(tokens.accessToken())
                .doesNotContain(tokens.refreshToken()));
    }

    @Test
    void wrongStateRedirectsToTheLoginFailurePath() {
        String code = idp.signIn(startLogin());

        EntityExchangeResult<byte[]> callback = browser.navigate(
                "/login/oauth2/code/entra?code=" + code + "&state=wrong-state");

        assertThat(callback.getStatus()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getResponseHeaders().getLocation()).hasToString("https://gateway.example.com/?login=failed");
        assertThat(browser.fetch(HttpMethod.GET, "/orders-api/orders/42").getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void errorFromEntraRedirectsToTheLoginFailurePath() {
        String state = query(startLogin()).getFirst("state");

        EntityExchangeResult<byte[]> callback = browser.navigate(
                "/login/oauth2/code/entra?error=access_denied&state=" + state);

        assertThat(callback.getResponseHeaders().getLocation()).hasToString("https://gateway.example.com/?login=failed");
        assertThat(browser.fetch(HttpMethod.GET, "/orders-api/orders/42").getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @EnumSource(MockIdentityProvider.IdTokenDefect.class)
    void invalidIdTokenRedirectsToTheLoginFailurePath(MockIdentityProvider.IdTokenDefect defect) {
        URI authorizationRequest = startLogin();
        idp.defectInNextIdToken(defect);

        EntityExchangeResult<byte[]> callback = browser.completeLogin(idp, authorizationRequest);

        assertThat(callback.getStatus()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getResponseHeaders().getLocation()).hasToString("https://gateway.example.com/?login=failed");
        assertThat(browser.fetch(HttpMethod.GET, "/orders-api/orders/42").getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(backend.requests()).isEmpty();
    }

    // SEC-50-1: until spec 025, the gateway already keeps its cookies away from backends.
    @Test
    void backendReceivesNoCookieAndSetsNoCookie() {
        browser.setCookie("other", "client-value");

        EntityExchangeResult<byte[]> response = browser.fetch(HttpMethod.GET, "/orders-api/health?set-cookie=1");

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(backend.cookieHeaders()).singleElement().isEqualTo(RecordingMockBackend.NO_COOKIE);
        assertThat(response.getResponseCookies().keySet()).containsExactly(TestBrowser.XSRF);
        assertThat(response.getResponseHeaders().get("Set-Cookie")).singleElement().asString()
                .startsWith(TestBrowser.XSRF + "=");
        assertThat(response.getResponseHeaders().containsHeader("Set-Cookie2")).isFalse();
    }

    @Test
    void backendReceivesNoSessionCookieAfterLogin() {
        browser.completeLogin(idp, startLogin());
        assertThat(browser.cookie(TestBrowser.SESSION)).isNotBlank();
        backend.clear();

        EntityExchangeResult<byte[]> response = browser.fetch(HttpMethod.GET, "/orders-api/orders/42?set-cookie=1");

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(backend.cookieHeaders()).singleElement().isEqualTo(RecordingMockBackend.NO_COOKIE);
        assertThat(response.getResponseCookies()).doesNotContainKey(RecordingMockBackend.BACKEND_COOKIE);
        assertThat(response.getResponseHeaders().containsHeader("Set-Cookie2")).isFalse();
        assertThat(browser.fetch(HttpMethod.GET, "/orders-api/orders/42").getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void csrfTokenOfBeforeTheLoginNoLongerWorks() {
        browser.fetch(HttpMethod.GET, "/orders-api/health");
        String beforeLogin = browser.cookie(TestBrowser.XSRF);
        assertThat(beforeLogin).isNotBlank();
        browser.completeLogin(idp, startLogin());
        String afterLogin = browser.cookie(TestBrowser.XSRF);
        backend.clear();

        assertThat(browser.fetch(HttpMethod.POST, "/orders-api/orders", beforeLogin).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(browser.fetch(HttpMethod.POST, "/orders-api/orders", afterLogin).getStatus())
                .isEqualTo(HttpStatus.OK);
        assertThat(backend.requests()).containsExactly("POST /orders");
    }

    @Test
    void logoutEndsTheSessionAndWritesANewCsrfToken() {
        browser.completeLogin(idp, startLogin());
        String session = browser.cookie(TestBrowser.SESSION);
        String csrf = browser.cookie(TestBrowser.XSRF);

        EntityExchangeResult<byte[]> logout = browser.fetch(HttpMethod.POST, "/logout", csrf);

        assertThat(logout.getStatus()).isEqualTo(HttpStatus.FOUND);
        URI location = logout.getResponseHeaders().getLocation();
        assertThat(location.toString()).startsWith(idp.logoutEndpoint() + "?");
        MultiValueMap<String, String> query = query(location);
        assertThat(query.keySet()).containsExactly("client_id", "post_logout_redirect_uri");
        assertThat(query.getFirst("post_logout_redirect_uri")).isEqualTo("https://gateway.example.com/");
        assertThat(logout.getResponseCookies().get(TestBrowser.XSRF)).singleElement()
                .satisfies(cookie -> assertThat(cookie.getValue()).isNotBlank().isNotEqualTo(csrf));
        assertThat(logout.getResponseCookies().getFirst(TestBrowser.SESSION).getMaxAge()).isZero();

        // The old session cookie no longer works.
        browser.setCookie(TestBrowser.SESSION, session);
        assertThat(browser.fetch(HttpMethod.GET, "/orders-api/orders/42").getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
