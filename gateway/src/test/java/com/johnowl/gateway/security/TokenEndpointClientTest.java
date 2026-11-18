package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.johnowl.gateway.Loopback;
import com.johnowl.gateway.routing.GatewaySecurityProperties;
import com.johnowl.gateway.security.MockIdentityProvider.RefreshAnswer;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** The calls to the token endpoint and the rows of spec 023, "Token endpoint errors". */
class TokenEndpointClientTest {

    private static final String ORDERS = "6f1c2e8a-0000-0000-0000-000000000000";

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    private final TestClock clock = new TestClock();

    /** The client of production, with its own WebClient. */
    private final TokenEndpointClient client = TokenEndpointClient.create(Duration.ofSeconds(5), ObservationRegistry.NOOP, clock);

    @BeforeEach
    @AfterEach
    void reset() {
        idp.resetRefreshGrants();
    }

    @Test
    void sendsTheRefreshTokenGrantWithTheDefaultScopeAndOfflineAccess() {
        String refreshToken = idp.newRefreshToken();

        OAuth2AccessTokenResponse response = client.send(request(idp.tokenEndpoint(), refreshToken)).block(WAIT);

        assertThat(response.getAccessToken().getTokenValue()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotNull();
        assertThat(idp.refreshGrants()).singleElement().satisfies(grant -> {
            assertThat(grant.form()).containsEntry("grant_type", "refresh_token")
                    .containsEntry("refresh_token", refreshToken)
                    .containsEntry("scope", ORDERS + "/.default offline_access");
            assertThat(grant.audience()).isEqualTo(ORDERS);
        });
    }

    @Test
    void theExactConsentBodyOfEntraIsConsentRequired() {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, MockIdentityProvider.CONSENT_REQUIRED_BODY));

        AudienceTokenException e = failure();

        assertThat(e.reason()).isEqualTo(DenyReason.CONSENT_REQUIRED);
        assertThat(e.tokenError()).isEqualTo(new TokenError("entra-" + ORDERS, 400, "invalid_grant", List.of(65001L), null));
        assertThat(e.getMessage()).doesNotContain("AADSTS65001: The user");
    }

    @Test
    void consentRequiredWithoutErrorCodesIsConsentRequired() {
        assertThat(reason(Map.of("error", "invalid_grant", "suberror", "consent_required"))).isEqualTo(DenyReason.CONSENT_REQUIRED);
    }

    @Test
    void theConsentRowComesBeforeTheOtherRows() {
        assertThat(reason(Map.of("error", "invalid_grant", "error_codes", List.of(65001)))).isEqualTo(DenyReason.CONSENT_REQUIRED);
        assertThat(reason(Map.of("error", "interaction_required", "error_codes", List.of(50076, 65001))))
                .isEqualTo(DenyReason.CONSENT_REQUIRED);
        assertThat(reason(Map.of("error", "interaction_required", "error_codes", List.of(50076))))
                .isEqualTo(DenyReason.REAUTHENTICATION_REQUIRED);
        assertThat(reason(Map.of("error", "invalid_grant", "error_codes", List.of(700082)))).isEqualTo(DenyReason.SESSION_INVALID);
        assertThat(reason(Map.of("error", "invalid_grant", "suberror", "bad_token"))).isEqualTo(DenyReason.SESSION_INVALID);
    }

    @Test
    void anUnknownAudienceIsRejected() {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, MockIdentityProvider.INVALID_RESOURCE_BODY));

        AudienceTokenException e = failure();

        assertThat(e.reason()).isEqualTo(DenyReason.TOKEN_REQUEST_REJECTED);
        assertThat(e.tokenError().errorCodes()).containsExactly(500011L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_scope", "invalid_client", "unauthorized_client"})
    void anotherClientErrorIsRejected(String error) {
        assertThat(reason(Map.of("error", error, "error_description", "secret detail"))).isEqualTo(DenyReason.TOKEN_REQUEST_REJECTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void aServerErrorIsATokenEndpointError(int status) {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(status, Map.of("error", "server_error")));

        AudienceTokenException e = failure();

        assertThat(e.reason()).isEqualTo(DenyReason.TOKEN_ENDPOINT_ERROR);
        assertThat(e.tokenError().status()).isEqualTo(status);
        assertThat(client.paused()).isFalse();
    }

    @Test
    void aClientErrorThatIsNotJsonIsRejected() {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.raw(400, Map.of("Content-Type", "text/html"), "<html>Bad request</html>"));

        AudienceTokenException e = failure();

        assertThat(e.reason()).isEqualTo(DenyReason.TOKEN_REQUEST_REJECTED);
        assertThat(e.tokenError()).isEqualTo(new TokenError("entra-" + ORDERS, 400, null, List.of(), null));
    }

    @Test
    void aSuccessWithoutATokenResponseIsATokenEndpointError() {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.raw(200, Map.of("Content-Type", "application/json"), "{}"));

        assertThat(failure().reason()).isEqualTo(DenyReason.TOKEN_ENDPOINT_ERROR);
    }

    @Test
    void aRedirectIsATokenEndpointErrorAndIsNotFollowed() {
        // The redirect points to the token endpoint itself: a followed redirect would be a second grant.
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.raw(302, Map.of("Location", idp.tokenEndpoint()), ""));

        AudienceTokenException e = failure();

        assertThat(e.reason()).isEqualTo(DenyReason.TOKEN_ENDPOINT_ERROR);
        assertThat(e.tokenError().status()).isEqualTo(302);
        assertThat(idp.refreshGrants()).hasSize(1);
    }

    @Test
    void tooManyRequestsPausesAllGrantsUntilRetryAfter() {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.raw(429, Map.of("Retry-After", "2"), ""));

        AudienceTokenException e = failure();

        assertThat(e.reason()).isEqualTo(DenyReason.TOKEN_ENDPOINT_THROTTLED);
        assertThat(e.tokenError().status()).isEqualTo(429);
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.token(null));
        AudienceTokenException paused = failure();
        assertThat(paused.reason()).isEqualTo(DenyReason.TOKEN_ENDPOINT_THROTTLED);
        assertThat(paused.tokenError().status()).isZero();
        assertThat(idp.refreshGrants()).hasSize(1);

        clock.advance(Duration.ofSeconds(2));

        assertThat(client.paused()).isFalse();
        assertThat(client.send(request(idp.tokenEndpoint(), idp.newRefreshToken())).block(WAIT)).isNotNull();
        assertThat(idp.refreshGrants()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 503})
    void temporarilyUnavailableIsThrottledWithTheDefaultPause(int status) {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(status, Map.of("error", "temporarily_unavailable")));

        assertThat(failure().reason()).isEqualTo(DenyReason.TOKEN_ENDPOINT_THROTTLED);

        clock.advance(Duration.ofSeconds(4));
        assertThat(client.paused()).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(client.paused()).isFalse();
    }

    @Test
    void thePauseOfRetryAfterIsCappedAndHasADefault() {
        assertThat(TokenEndpointClient.pauseSeconds("2", clock)).isEqualTo(2);
        assertThat(TokenEndpointClient.pauseSeconds("3600", clock)).isEqualTo(60);
        assertThat(TokenEndpointClient.pauseSeconds("-5", clock)).isZero();
        assertThat(TokenEndpointClient.pauseSeconds(null, clock)).isEqualTo(5);
        assertThat(TokenEndpointClient.pauseSeconds("soon", clock)).isEqualTo(5);
        String inTenSeconds = DateTimeFormatter.RFC_1123_DATE_TIME.format(clock.instant().plusSeconds(10).atZone(ZoneOffset.UTC));
        assertThat(TokenEndpointClient.pauseSeconds(inTenSeconds, clock)).isBetween(9L, 10L);
        String inTwoHours = DateTimeFormatter.RFC_1123_DATE_TIME.format(clock.instant().plusSeconds(7200).atZone(ZoneOffset.UTC));
        assertThat(TokenEndpointClient.pauseSeconds(inTwoHours, clock)).isEqualTo(60);
    }

    @Test
    void aRefusedConnectionIsATokenEndpointError() throws Exception {
        int port;
        // A closed port on the loopback address: a port of the wildcard address can be in use on 127.0.0.1 (Loopback).
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(Loopback.ADDRESS))) {
            port = socket.getLocalPort();
        }

        StepVerifier.create(client.send(request(Loopback.url(port) + "/token", "refresh")))
                .expectErrorSatisfies(e -> {
                    assertThat(((AudienceTokenException) e).reason()).isEqualTo(DenyReason.TOKEN_ENDPOINT_ERROR);
                    assertThat(((AudienceTokenException) e).tokenError().status()).isZero();
                })
                .verify(WAIT);
    }

    @Test
    void noResponseWithinTheTimeoutIsATimeout() {
        idp.delayRefreshGrants(Duration.ofSeconds(1));
        TokenEndpointClient shortTimeout = TokenEndpointClient.create(Duration.ofMillis(200), ObservationRegistry.NOOP, clock);

        StepVerifier.create(shortTimeout.send(request(idp.tokenEndpoint(), idp.newRefreshToken())))
                .expectErrorSatisfies(e -> assertThat(((AudienceTokenException) e).reason())
                        .isEqualTo(DenyReason.TOKEN_ENDPOINT_TIMEOUT))
                .verify(WAIT);
    }

    @Test
    void theLogFieldsHaveASafeErrorValueAndAtMostFiveCodes() {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, Map.of("error", "Invalid Scope\nINJECTED",
                "error_codes", List.of(1, 2, 3, 4, 5, 6, 7))));

        TokenError error = failure().tokenError();

        assertThat(error.error()).isEqualTo(TokenError.INVALID);
        assertThat(error.errorCodes()).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void theDefaultTimeoutIsFiveSeconds() {
        GatewaySecurityProperties properties = new Binder(new MapConfigurationPropertySource(Map.of("gateway.security.login.failure-path", "/")))
                .bindOrCreate("gateway.security", GatewaySecurityProperties.class);

        assertThat(properties.tokenEndpoint().timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.tokenClockSkew()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.tokenEndpoint().grantsPerSecond()).isEqualTo(50);
    }

    @Test
    void theTimeoutMustBePositive() {
        assertThatIllegalStateException().isThrownBy(() -> TokenEndpointClient.create(Duration.ZERO, ObservationRegistry.NOOP, clock));
        assertThatIllegalStateException().isThrownBy(() -> new TokenEndpointClient(Duration.ZERO, WebClient.create(), clock));
    }

    private DenyReason reason(Map<String, ?> body) {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, body));
        return failure().reason();
    }

    private AudienceTokenException failure() {
        AudienceTokenException[] failure = new AudienceTokenException[1];
        StepVerifier.create(client.send(request(idp.tokenEndpoint(), idp.newRefreshToken())))
                .expectErrorSatisfies(e -> failure[0] = (AudienceTokenException) e)
                .verify(WAIT);
        return failure[0];
    }

    private static OAuth2RefreshTokenGrantRequest request(String tokenUri, String refreshToken) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("entra-" + ORDERS)
                .clientId(MockIdentityProvider.CLIENT_ID)
                .clientSecret(MockIdentityProvider.CLIENT_SECRET)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope(ORDERS + "/.default")
                .tokenUri(tokenUri)
                .build();
        Instant now = Instant.now();
        return new OAuth2RefreshTokenGrantRequest(registration,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "graph", now, now.plusSeconds(3600)),
                new OAuth2RefreshToken(refreshToken, now), new LinkedHashSet<>(List.of(ORDERS + "/.default", "offline_access")));
    }
}
