package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.johnowl.gateway.routing.OpenApiRoutingConfiguration;
import com.johnowl.gateway.routing.TestAudienceAuthorization;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/** The registrations of spec 023 at startup: from the OAS files, and never for gateway-bff itself. */
class AudienceTokenStartupTest {

    private static final String REGISTRATION = "spring.security.oauth2.client.registration.entra.";

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    private final ApplicationContextRunner production = new ApplicationContextRunner()
            .withUserConfiguration(OpenApiRoutingConfiguration.class, SessionLoginConfiguration.class,
                    SessionMemoryConfiguration.class, AudienceTokenConfiguration.class, TestAudienceAuthorization.class,
                    AuthorizedClients.class)
            .withPropertyValues(SessionTestSpecs.write(9))
            .withPropertyValues("gateway.public-base-url=https://gateway.example.com")
            .withPropertyValues(
                    REGISTRATION + "provider=entra",
                    REGISTRATION + "client-id=" + MockIdentityProvider.CLIENT_ID,
                    REGISTRATION + "client-secret=" + MockIdentityProvider.CLIENT_SECRET,
                    REGISTRATION + "authorization-grant-type=authorization_code",
                    REGISTRATION + "redirect-uri=https://gateway.example.com/login/oauth2/code/{registrationId}",
                    REGISTRATION + "scope=openid,profile,offline_access",
                    "spring.security.oauth2.client.provider.entra.issuer-uri=" + idp.issuer());

    private final ApplicationContextRunner runner = production.withUserConfiguration(TestLocalIdentityProvider.class);

    @Test
    void createsTheRegistrationOfTheAudienceWithoutACatalog() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ReactiveClientRegistrationRepository registrations = context.getBean(ReactiveClientRegistrationRepository.class);
            ClientRegistration orders = registrations
                    .findByRegistrationId("entra-6f1c2e8a-0000-0000-0000-000000000000").block(Duration.ofSeconds(5));
            assertThat(orders).isNotNull();
            assertThat(orders.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.REFRESH_TOKEN);
            assertThat(orders.getScopes()).containsExactly(SessionTestSpecs.AUDIENCE + "/.default");
            assertThat(registrations.findByRegistrationId("entra").block(Duration.ofSeconds(5))).isNotNull();
            assertThat(context.getBean(AudienceClientRegistrations.class).registrationIds())
                    .containsExactly("entra-" + SessionTestSpecs.AUDIENCE);
            assertThat(context).hasSingleBean(ReactiveOAuth2AuthorizedClientManager.class)
                    .hasSingleBean(MemorySessionTokenStore.class);
        });
    }

    @Test
    void doesNotStartWhenAnAudienceIsTheClientIdOfGatewayBff() {
        // The property in uppercase: the comparison ignores case.
        runner.withPropertyValues(REGISTRATION + "client-id=" + SessionTestSpecs.AUDIENCE.toUpperCase()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("x-gateway.audience '6f1c2e8a-0000-0000-0000-000000000000' is the client id of gateway-bff "
                            + "(registration 'entra'). The gateway does not request tokens for itself.");
        });
    }

    @Test
    void theRegistrationIdPrefixIsReservedForTheAudiences() {
        runner.withPropertyValues(
                        "spring.security.oauth2.client.registration.entra-other.provider=entra",
                        "spring.security.oauth2.client.registration.entra-other.client-id=other",
                        "spring.security.oauth2.client.registration.entra-other.authorization-grant-type=client_credentials")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                            .hasMessageContaining("prefix 'entra-' is reserved");
                });
    }

    @Test
    void withoutTheTestBeanTheIssuerOfTheMockIdentityProviderStopsTheStartup() {
        production.run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("The issuer of the registration entra must have the form "
                            + "https://login.microsoftonline.com/<tenant-id>/v2.0")
                    .hasMessageEndingWith("but is '" + idp.issuer() + "'.");
        });
    }

    @Test
    void theDecoderLoadsTheKeySetAtStartup() {
        int before = idp.keySetRequests();

        runner.run(context -> assertThat(context).hasNotFailed());

        assertThat(idp.keySetRequests()).isEqualTo(before + 1);
    }

    @Test
    void anUnavailableKeySetDoesNotStopTheStartup() {
        idp.delayKeySet(Duration.ofSeconds(30));
        try {
            runner.run(context -> assertThat(context).hasNotFailed());
        } finally {
            idp.delayKeySet(Duration.ZERO);
        }
    }

    @Test
    void thePermissionCacheHasTheConfiguredMaximumSize() {
        runner.withPropertyValues("gateway.security.permission-cache.maximum-size=5")
                .run(context -> assertThat(context.getBean(TokenPermissionsResolver.class).maximumSize()).isEqualTo(5));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1_000_001})
    void aMaximumSizeOutsideTheBoundsStopsTheStartup(int maximumSize) {
        runner.withPropertyValues("gateway.security.permission-cache.maximum-size=" + maximumSize).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("gateway.security.permission-cache.maximum-size must be between 1 and 1000000, but is "
                            + maximumSize + ".");
        });
    }

    @Test
    void aProxyOfTheTestBeanStopsTheStartup() {
        LocalIdentityProvider proxy = (LocalIdentityProvider) Proxy.newProxyInstance(LocalIdentityProvider.class.getClassLoader(),
                new Class<?>[] {LocalIdentityProvider.class}, (target, method, arguments) -> null);

        assertThatIllegalStateException().isThrownBy(() -> AudienceTokenConfiguration.localIdentityProvider(List.of(proxy)))
                .withMessageEndingWith(" is not a valid bean of type LocalIdentityProvider. The bean must be a class in the "
                        + "package com.johnowl.gateway.security, and not a proxy.");
        assertThat(AudienceTokenConfiguration.localIdentityProvider(List.of(new TestLocalIdentityProvider.Marker()))).isTrue();
        assertThat(AudienceTokenConfiguration.localIdentityProvider(List.of())).isFalse();
    }

    /** The repository bean of the security configuration, without the security chain. */
    @Configuration(proxyBeanMethods = false)
    static class AuthorizedClients {

        @Bean
        ServerOAuth2AuthorizedClientRepository authorizedClientRepository(SessionTokenStore store) {
            return new SessionAuthorizedClientRepository(store);
        }
    }
}
