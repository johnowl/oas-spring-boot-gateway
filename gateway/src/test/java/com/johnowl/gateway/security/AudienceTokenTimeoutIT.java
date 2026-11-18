package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.RecordingMockBackend;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/** The token endpoint timeout of spec 023, set to 200 milliseconds. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.security.token-endpoint.timeout=200ms")
class AudienceTokenTimeoutIT {

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @LocalServerPort
    private int port;

    @Autowired
    private MemorySessionRepository sessions;

    @Autowired
    private SessionRedemptions redemptions;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.register(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @AfterEach
    void reset() {
        idp.resetRefreshGrants();
        idp.delayKeySet(Duration.ZERO);
    }

    @Test
    void aSlowTokenEndpointGives504AndKeepsTheSession() {
        TestBrowser browser = new TestBrowser(port);
        browser.login(idp);
        backend.clear();
        idp.delayRefreshGrants(Duration.ofSeconds(1));

        EntityExchangeResult<byte[]> result = browser.fetch(HttpMethod.GET, "/orders-api/orders/42");

        assertThat(result.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8)).contains("\"detail\":\"Gateway timeout.\"");
        assertThat(backend.requests()).isEmpty();
        assertThat(sessions.contains(browser.cookie(TestBrowser.SESSION))).isTrue();
        assertThat(redemptions.running()).isZero();
    }

    @Test
    void aSlowKeySetGives504KeepsTheSessionAndStoresTheRotatedRefreshToken() {
        TestBrowser browser = new TestBrowser(port);
        browser.login(idp);
        backend.clear();
        // A new signing key: the decoder must load the key set again, and the key set answers after 1 second.
        idp.signAudienceTokensWithANewKey();
        idp.delayKeySet(Duration.ofSeconds(1));

        EntityExchangeResult<byte[]> result = browser.fetch(HttpMethod.GET, "/orders-api/orders/42");

        assertThat(result.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(backend.requests()).isEmpty();
        String session = browser.cookie(TestBrowser.SESSION);
        assertThat(sessions.contains(session)).isTrue();
        assertThat(redemptions.running()).isZero();
        OAuth2AuthorizedClient entra = (OAuth2AuthorizedClient) sessions.attribute(session,
                SessionAuthorizedClientRepository.attribute("entra"));
        assertThat(entra.getRefreshToken().getTokenValue()).isEqualTo(idp.refreshGrants().getFirst().refreshToken());
        assertThat(sessions.attribute(session, SessionAuthorizedClientRepository.attribute("entra-" + SessionTestSpecs.AUDIENCE)))
                .isNull();
    }
}
