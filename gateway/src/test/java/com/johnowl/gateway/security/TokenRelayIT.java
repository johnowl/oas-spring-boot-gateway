package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.RecordingMockBackend;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The token relay of spec 025 with a real login against the mock identity provider: the mock backend records
 * the headers, and the test sends raw HTTP with a forged {@code Authorization} header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenRelayIT {

    private static final String ORDERS = SessionTestSpecs.AUDIENCE;

    private static final String PAYMENTS = SessionTestSpecs.PAYMENTS_AUDIENCE;

    private static final String ORDER = "/orders-api/orders/42";

    private static final String PAYMENT = "/payments-api/payments/7";

    private static final String FORGED = "Authorization: Bearer forged";

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @LocalServerPort
    private int port;

    @Autowired
    private MemorySessionRepository sessions;

    private TestBrowser browser;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.registerWithPaymentsAndItems(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @BeforeEach
    void login() {
        idp.resetRefreshGrants();
        browser = new TestBrowser(port);
        browser.login(idp);
        backend.clear();
    }

    @AfterEach
    void reset() {
        idp.resetRefreshGrants();
    }

    @Test
    void eachRouteGetsTheTokenOfItsOwnAudienceAndNeverTheClientValue() throws Exception {
        assertThat(send(ORDER, FORGED)).startsWith("HTTP/1.1 200");
        assertThat(send(PAYMENT, FORGED)).startsWith("HTTP/1.1 200");

        List<String> relayed = relayedTokens();
        assertThat(relayed).hasSize(2);
        assertThat(claims(relayed.get(0)).getAudience()).containsExactly(ORDERS);
        assertThat(claims(relayed.get(1)).getAudience()).containsExactly(PAYMENTS);
        assertThat(relayed.get(0)).isEqualTo(stored("entra-" + ORDERS).getAccessToken().getTokenValue());
        assertThat(relayed.get(1)).isEqualTo(stored("entra-" + PAYMENTS).getAccessToken().getTokenValue());
        assertThat(claims(relayed.get(0)).getStringClaim("oid")).isEqualTo(MockIdentityProvider.USER_OID);
        assertThat(allHeaderValues()).noneMatch(value -> value.contains("forged"));
    }

    @Test
    void aPublicOperationGetsNoAuthorization() throws IOException {
        assertThat(send("/orders-api/health", FORGED)).startsWith("HTTP/1.1 200");
        assertThat(HeaderHygieneIT.send(port, "/orders-api/health", List.of(FORGED))).startsWith("HTTP/1.1 200");

        assertThat(backend.headers()).hasSize(2)
                .allSatisfy(headers -> assertThat(headers.containsHeader(HttpHeaders.AUTHORIZATION)).isFalse());
    }

    // QA-62-2: one route, a public GET and a protected DELETE on the same path.
    @Test
    void aPublicAndAProtectedOperationOfOneRoute() {
        String item = "/orders-api/items/7";

        assertThat(browser.fetch(HttpMethod.GET, item).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(browser.fetch(HttpMethod.DELETE, item, browser.cookie(TestBrowser.XSRF)).getStatus())
                .isEqualTo(HttpStatus.OK);

        assertThat(backend.requests()).containsExactly("GET /items/7", "DELETE /items/7");
        assertThat(backend.headers().get(0).containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
        assertThat(backend.headers().get(1).get(HttpHeaders.AUTHORIZATION))
                .containsExactly("Bearer " + stored("entra-" + ORDERS).getAccessToken().getTokenValue());
    }

    // SEC-62-1, QA-62-1: the Connection header of the client cannot remove the relayed token or the gateway
    // X-Forwarded-For. The gateway removes the whole Connection header (spec 025, "Connection header").
    @Test
    void theConnectionHeaderOfTheClientCannotRemoveTheRelayedToken() throws Exception {
        assertThat(send(ORDER, "Connection: Authorization")).startsWith("HTTP/1.1 200");
        assertThat(send(ORDER, "Connection: X-Forwarded-For", FORGED)).startsWith("HTTP/1.1 200");
        assertThat(send(ORDER, "Connection: close, Authorization, X-Forwarded-For")).startsWith("HTTP/1.1 200");

        String token = stored("entra-" + ORDERS).getAccessToken().getTokenValue();
        assertThat(relayedTokens()).hasSize(3).containsOnly(token);
        assertThat(backend.headers()).allSatisfy(headers -> {
            assertThat(headers.get("X-Forwarded-For")).containsExactly("127.0.0.1");
            assertThat(headers.getConnection()).doesNotContain("Authorization", "X-Forwarded-For");
        });
    }

    @Test
    void theBackendNeverReceivesTheIdTokenTheRefreshTokenOrTheLoginAccessToken() throws IOException {
        assertThat(send(ORDER)).startsWith("HTTP/1.1 200");
        assertThat(send(PAYMENT)).startsWith("HTTP/1.1 200");
        assertThat(send("/orders-api/health")).startsWith("HTTP/1.1 200");

        MockIdentityProvider.Tokens login = idp.issued().getLast();
        List<String> secrets = new ArrayList<>(List.of(login.idToken(), login.accessToken(), login.refreshToken(),
                stored("entra").getRefreshToken().getTokenValue()));
        idp.refreshGrants().stream().map(MockIdentityProvider.RefreshGrant::refreshToken)
                .filter(token -> token != null).forEach(secrets::add);
        assertThat(backend.headers()).hasSize(3);
        assertThat(allHeaderValues()).allSatisfy(value -> assertThat(secrets).noneMatch(value::contains));
    }

    @Test
    void theRelayCausesNoCallToTheTokenEndpoint() throws IOException {
        assertThat(send(ORDER)).startsWith("HTTP/1.1 200");
        assertThat(idp.refreshGrants()).hasSize(1);

        assertThat(send(ORDER)).startsWith("HTTP/1.1 200");

        assertThat(idp.refreshGrants()).hasSize(1);
        assertThat(relayedTokens()).hasSize(2).containsOnly(stored("entra-" + ORDERS).getAccessToken().getTokenValue());
    }

    // A token in the clock skew window is renewed by the authorization manager on each call. The relay sends
    // the renewed token and does not renew it a second time.
    @Test
    void inTheSkewWindowEachCallCausesOneGrantAndRelaysItsToken() throws IOException {
        idp.audienceTokenLifetime(Duration.ofSeconds(30));   // less than the clock skew of 60 seconds

        assertThat(send(ORDER)).startsWith("HTTP/1.1 200");
        assertThat(send(ORDER)).startsWith("HTTP/1.1 200");

        assertThat(idp.refreshGrants()).hasSize(2);
        List<String> relayed = relayedTokens();
        assertThat(relayed).hasSize(2).doesNotHaveDuplicates();
        assertThat(relayed.getLast()).isEqualTo(stored("entra-" + ORDERS).getAccessToken().getTokenValue());
    }

    private String send(String path, String... headers) throws IOException {
        List<String> lines = new ArrayList<>(List.of("Cookie: " + TestBrowser.SESSION + "=" + browser.cookie(TestBrowser.SESSION)));
        lines.addAll(List.of(headers));
        return HeaderHygieneIT.send(port, path, lines);
    }

    /** The bearer token of each backend request. */
    private static List<String> relayedTokens() {
        return backend.headers().stream().map(headers -> {
            assertThat(headers.get(HttpHeaders.AUTHORIZATION)).singleElement().asString().startsWith("Bearer ");
            return headers.getFirst(HttpHeaders.AUTHORIZATION).substring("Bearer ".length());
        }).toList();
    }

    private static List<String> allHeaderValues() {
        return backend.headers().stream().flatMap(headers -> headers.headerSet().stream())
                .flatMap(header -> header.getValue().stream()).toList();
    }

    private static JWTClaimsSet claims(String token) throws ParseException {
        return SignedJWT.parse(token).getJWTClaimsSet();
    }

    private OAuth2AuthorizedClient stored(String registrationId) {
        return (OAuth2AuthorizedClient) sessions.attribute(browser.cookie(TestBrowser.SESSION),
                SessionAuthorizedClientRepository.attribute(registrationId));
    }
}
