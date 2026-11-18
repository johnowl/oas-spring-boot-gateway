package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.johnowl.gateway.core.access.AccessRule;
import com.johnowl.gateway.core.access.OperationAccess;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/** The per-audience registrations of spec 023 ("Client registrations for each audience"). */
class AudienceClientRegistrationsTest {

    private static final String ORDERS = "6f1c2e8a-0000-0000-0000-000000000000";

    private static final String PAYMENTS = "7a2d3f9b-0000-0000-0000-000000000000";

    private static final String BFF = "0b5f8c1d-0000-0000-0000-000000000000";

    @Test
    void createsOneRegistrationForEachAudienceOfAProtectedOperation() {
        AudienceClientRegistrations audiences = AudienceClientRegistrations.of(entra(BFF), List.of(
                access("GET", AccessRule.publicAccess()),
                access("GET", new AccessRule(false, ORDERS, Set.of("Orders.Read"), Set.of())),
                access("POST", new AccessRule(false, ORDERS, Set.of("Orders.Write"), Set.of())),
                access("GET", new AccessRule(false, PAYMENTS, Set.of(), Set.of()))));

        assertThat(audiences.registrationIds()).containsExactlyInAnyOrder("entra-" + ORDERS, "entra-" + PAYMENTS);
        assertThat(audiences.contains("entra-" + ORDERS)).isTrue();
        assertThat(audiences.contains("entra")).isFalse();
        assertThat(audiences.contains(null)).isFalse();
    }

    @Test
    void registrationCopiesTheClientOfEntraWithTheRefreshTokenGrantAndTheDefaultScope() {
        ClientRegistration entra = entra(BFF);

        ClientRegistration orders = AudienceClientRegistrations.of(entra, List.of(
                access("GET", new AccessRule(false, ORDERS, Set.of(), Set.of())))).registrations().iterator().next();

        assertThat(orders.getRegistrationId()).isEqualTo(AudienceRegistrationIds.forAudience(ORDERS))
                .isEqualTo("entra-6f1c2e8a-0000-0000-0000-000000000000");
        // Verification of spec 023: the builder of Spring Security 7 accepts the grant type refresh_token.
        assertThat(orders.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(orders.getScopes()).containsExactly(ORDERS + "/.default");
        assertThat(orders.getClientId()).isEqualTo(BFF);
        assertThat(orders.getClientSecret()).isEqualTo("secret");
        assertThat(orders.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(orders.getProviderDetails().getTokenUri()).isEqualTo(entra.getProviderDetails().getTokenUri());
        assertThat(orders.getRedirectUri()).isNull();
    }

    @Test
    void anOasFileWithoutProtectedOperationsGivesNoRegistration() {
        assertThat(AudienceClientRegistrations.of(entra(BFF), List.of(access("GET", AccessRule.publicAccess())))
                .registrations()).isEmpty();
    }

    @Test
    void theClientIdOfGatewayBffIsNotAnAudienceAlsoInUppercase() {
        List<OperationAccess> rules = List.of(access("GET", new AccessRule(false, BFF, Set.of(), Set.of())));

        assertThatIllegalStateException().isThrownBy(() -> AudienceClientRegistrations.of(entra(BFF.toUpperCase()), rules))
                .withMessage("x-gateway.audience '0b5f8c1d-0000-0000-0000-000000000000' is the client id of gateway-bff "
                        + "(registration 'entra'). The gateway does not request tokens for itself.");
        assertThatIllegalStateException().isThrownBy(() -> AudienceClientRegistrations.of(entra(BFF), rules));
    }

    static OperationAccess access(String method, AccessRule rule) {
        return new OperationAccess("route-" + method, "/orders-api/orders", method, rule);
    }

    static ClientRegistration entra(String clientId) {
        return ClientRegistration.withRegistrationId("entra")
                .clientId(clientId)
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://gateway.example.com/login/oauth2/code/entra")
                .scope("openid", "profile", "offline_access")
                .authorizationUri("https://login.example.com/tenant/oauth2/v2.0/authorize")
                .tokenUri("https://login.example.com/tenant/oauth2/v2.0/token")
                .jwkSetUri("https://login.example.com/tenant/discovery/v2.0/keys")
                .issuerUri("https://login.example.com/tenant/v2.0")
                .build();
    }
}
