package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import reactor.core.publisher.Mono;

/** The provider chain of spec 023: no refresh token provider, and the authorization code provider for entra only. */
class ProviderChainTest {

    private final ReactiveOAuth2AuthorizedClientProvider chain = AudienceTokenConfiguration.providerChain(context -> Mono.empty());

    @Test
    void anExpiredEntraTokenIsNotRefreshed() {
        Instant past = Instant.now().minusSeconds(3600);
        OAuth2AuthorizedClient entra = new OAuth2AuthorizedClient(AudienceClientRegistrationsTest.entra("bff"), "oid-1",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "graph", past.minusSeconds(60), past),
                new OAuth2RefreshToken("refresh", past));

        OAuth2AuthorizedClient result = chain.authorize(OAuth2AuthorizationContext.withAuthorizedClient(entra)
                .principal(new TestingAuthenticationToken("oid-1", null)).build()).block(Duration.ofSeconds(5));

        assertThat(result).isNull();
    }

    @Test
    void theAuthorizationCodeProviderIgnoresAPerAudienceRegistrationAlsoWithTheFallbackGrantType() {
        ClientRegistration fallback = ClientRegistration.withClientRegistration(AudienceClientRegistrationsTest.entra("bff"))
                .registrationId("entra-6f1c2e8a-0000-0000-0000-000000000000")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .build();

        // Without the wrapper, the provider throws ClientAuthorizationRequiredException and starts a login.
        OAuth2AuthorizedClient result = chain.authorize(OAuth2AuthorizationContext.withClientRegistration(fallback)
                .principal(new TestingAuthenticationToken("oid-1", null)).build()).block(Duration.ofSeconds(5));

        assertThat(result).isNull();
    }
}
