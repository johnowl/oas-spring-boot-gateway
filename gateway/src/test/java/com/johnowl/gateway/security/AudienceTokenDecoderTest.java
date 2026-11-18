package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.johnowl.gateway.security.MockIdentityProvider.AudienceTokenDefect;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** The decoder of the audience tokens (spec 023, "Permission decoding and cache", issuer pin). */
class AudienceTokenDecoderTest {

    private static final String ORDERS = "6f1c2e8a-0000-0000-0000-000000000000";

    private static final String TENANT = "8a0d4f6e-1b2c-4d3e-9f80-123456789abc";

    private static final String ENTRA_ISSUER = "https://login.microsoftonline.com/" + TENANT + "/v2.0";

    private static final String ENTRA_KEYS = "https://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys";

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    private final AudienceTokenDecoder decoder = local(webClient(Duration.ofSeconds(5)));

    @AfterEach
    void reset() {
        idp.delayKeySet(Duration.ZERO);
    }

    @Test
    void acceptsAVersion2TokenOfTheTenant() {
        String token = idp.audienceToken(ORDERS, List.of("Orders.Read"), Instant.now().plusSeconds(3600), null);

        Jwt jwt = decoder.decode(token).block(Duration.ofSeconds(10));

        assertThat(jwt.getAudience()).containsExactly(ORDERS);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("Orders.Read");
        assertThat(decoder.issuer()).isEqualTo(idp.issuer());
        assertThat(decoder.tenantId()).isEqualTo(MockIdentityProvider.TENANT_ID);
        assertThat(decoder.decode(token, ORDERS).block(Duration.ofSeconds(10))).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({
        "VERSION_1, iss",
        "OTHER_TENANT, iss",
        "OTHER_TID, tid",
        "VERSION_CLAIM, ver",
        "BAD_SIGNATURE, signature",
        "EXPIRED, exp",
        "NO_EXPIRY, exp",
        "NOT_YET_VALID, nbf",
    })
    void rejectsATokenAndNamesTheFailedCheck(AudienceTokenDefect defect, String check) {
        String token = idp.audienceToken(ORDERS, List.of("Orders.Read"), Instant.now().plusSeconds(3600), defect);

        StepVerifier.create(decoder.decode(token))
                .expectErrorSatisfies(e -> assertFailedCheck(e, check, token))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void aNewTokenMustHaveTheAudienceOfTheRegistration() {
        String token = idp.audienceToken(ORDERS, List.of("Orders.Read"), Instant.now().plusSeconds(3600), null);

        StepVerifier.create(decoder.decode(token, "9b2d3f4a-0000-0000-0000-000000000000"))
                .expectErrorSatisfies(e -> assertFailedCheck(e, "aud", token))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void aNewTokenMustBeIssuedToGatewayBff() {
        String token = idp.audienceToken(ORDERS, List.of("Orders.Read"), Instant.now().plusSeconds(3600),
                AudienceTokenDefect.OTHER_CLIENT);

        StepVerifier.create(decoder.decode(token, ORDERS))
                .expectErrorSatisfies(e -> assertFailedCheck(e, "azp", token))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void loadKeysFetchesTheKeySetOnceAndTheValidationUsesTheCache() {
        AudienceTokenDecoder fresh = local(webClient(Duration.ofSeconds(5)));
        int before = idp.keySetRequests();

        fresh.loadKeys().block(Duration.ofSeconds(10));
        fresh.decode(idp.audienceToken(ORDERS, List.of(), Instant.now().plusSeconds(3600), null)).block(Duration.ofSeconds(10));

        assertThat(idp.keySetRequests()).isEqualTo(before + 1);
    }

    @Test
    void aSlowKeySetIsATimeoutNotAnInvalidToken() {
        AudienceTokenDecoder fresh = local(webClient(Duration.ofMillis(200)));
        idp.delayKeySet(Duration.ofSeconds(2));
        String token = idp.audienceToken(ORDERS, List.of(), Instant.now().plusSeconds(3600), null);

        StepVerifier.create(fresh.decode(token))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(KeySetUnavailableException.class,
                        unavailable -> assertThat(unavailable.timeout()).isTrue()))
                .verify(Duration.ofSeconds(5));
        StepVerifier.create(fresh.loadKeys()).expectError(KeySetUnavailableException.class).verify(Duration.ofSeconds(5));
    }

    @Test
    void anEntraIssuerOfTheTenantIsAccepted() {
        for (String host : AudienceTokenDecoder.ENTRA_HOSTS) {
            assertThatNoException().isThrownBy(() -> AudienceTokenDecoder.of(
                    entra("https://" + host + "/" + TENANT + "/v2.0", "https://" + host + "/" + TENANT + "/discovery/v2.0/keys"),
                    webClient(Duration.ofSeconds(5))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://sts.windows.net/" + TENANT + "/",
        "http://login.microsoftonline.com/" + TENANT + "/v2.0",
        "https://login.microsoftonline.com.evil.example/" + TENANT + "/v2.0",
        "https://login.microsoftonline.com:8443/" + TENANT + "/v2.0",
        "https://user@login.microsoftonline.com/" + TENANT + "/v2.0",
        "https://evil.example/" + TENANT + "/v2.0",
        "https://login.microsoftonline.com/8A0D4F6E-1B2C-4D3E-9F80-123456789ABC/v2.0",
        "https://login.microsoftonline.com/common/v2.0",
        "https://login.microsoftonline.com/x/" + TENANT + "/v2.0",
    })
    void theIssuerOfEntraMustBeAVersion2IssuerOfEntraId(String issuer) {
        assertThatIllegalStateException().isThrownBy(() -> AudienceTokenDecoder.of(entra(issuer, ENTRA_KEYS),
                        webClient(Duration.ofSeconds(5))))
                .withMessage("The issuer of the registration entra must have the form https://login.microsoftonline.com/"
                        + "<tenant-id>/v2.0, with the host login.microsoftonline.com, login.microsoftonline.us, "
                        + "login.partner.microsoftonline.cn and the tenant id as a GUID in lowercase, but is '" + issuer + "'.");
    }

    @Test
    void theMockIssuerIsRejectedWithoutTheTestBean() {
        assertThatIllegalStateException().isThrownBy(() -> AudienceTokenDecoder.of(
                entra(idp.issuer(), idp.jwkSetUri()), webClient(Duration.ofSeconds(5))))
                .withMessageContaining("but is '" + idp.issuer() + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://evil.example/" + TENANT + "/discovery/v2.0/keys",
        "http://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys",
        "https://login.microsoftonline.com/" + TENANT + "/v2.0/keys",
        "https://login.microsoftonline.com/other/discovery/v2.0/keys",
        "https://login.microsoftonline.com/" + TENANT + "/discovery/../../evil/keys",
        "https://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys?x=1",
    })
    void theKeySetMustBeOnTheHostOfTheIssuerUnderTheDiscoveryPathOfTheTenant(String keys) {
        assertThatIllegalStateException().isThrownBy(() -> AudienceTokenDecoder.of(entra(ENTRA_ISSUER, keys),
                        webClient(Duration.ofSeconds(5))))
                .withMessage("The jwks_uri of the registration entra must start with 'https://login.microsoftonline.com/"
                        + TENANT + "/discovery/', but is '" + keys + "'.");
    }

    private AudienceTokenDecoder local(WebClient webClient) {
        return AudienceTokenDecoder.ofLocalIdentityProvider(entra(idp.issuer(), idp.jwkSetUri()), webClient);
    }

    private static WebClient webClient(Duration timeout) {
        return IdentityProviderWebClient.create(timeout, ObservationRegistry.NOOP);
    }

    private static void assertFailedCheck(Throwable e, String check, String token) {
        assertThat(e).isInstanceOf(InvalidAudienceTokenException.class);
        assertThat(((InvalidAudienceTokenException) e).check()).isEqualTo(check);
        assertThat(e.getMessage()).doesNotContain(token);
    }

    private static ClientRegistration entra(String issuer, String keys) {
        return ClientRegistration.withClientRegistration(AudienceClientRegistrationsTest.entra(MockIdentityProvider.CLIENT_ID))
                .issuerUri(issuer)
                .jwkSetUri(keys)
                .build();
    }
}
