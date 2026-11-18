package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.johnowl.gateway.routing.GatewaySecurityProperties;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/** {@link EntraAuthorizationRequestResolver}, {@link EntraLogoutSuccessHandler}, and the registration checks. */
class EntraLoginComponentsTest {

    private static final SessionLoginSettings SETTINGS = new SessionLoginSettings(
            PublicBaseUrl.parse("https://gateway.example.com", false),
            URI.create("https://gateway.example.com/?login=failed"),
            URI.create("https://gateway.example.com/"),
            new GatewaySecurityProperties.Session(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(8)));

    @Test
    void resolverAddsPkceStateNonceAndTheConfiguredRedirectUri() {
        EntraAuthorizationRequestResolver resolver = new EntraAuthorizationRequestResolver(registrations(entra().build()));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("http://internal-host:8080/oauth2/authorization/entra")
                .header("X-Forwarded-Host", "evil.example.com"));

        OAuth2AuthorizationRequest request = resolver.resolve(exchange).block();

        assertThat(request.getRedirectUri()).isEqualTo("https://gateway.example.com/login/oauth2/code/entra");
        assertThat(request.getState()).isNotBlank();
        assertThat(request.getAttributes()).containsKey("nonce").containsKey(PkceParameterNames.CODE_VERIFIER);
        assertThat(request.getAdditionalParameters())
                .containsEntry(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256")
                .containsKey(PkceParameterNames.CODE_CHALLENGE)
                .containsKey("nonce");
        assertThat(request.getScopes()).containsExactlyInAnyOrder("openid", "profile", "offline_access");
    }

    @Test
    void resolverIgnoresOtherRegistrations() {
        ClientRegistration audience = entra().registrationId("entra-6f1c2e8a-0000-0000-0000-000000000000").build();
        EntraAuthorizationRequestResolver resolver = new EntraAuthorizationRequestResolver(
                registrations(entra().build(), audience));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/oauth2/authorization/entra-6f1c2e8a-0000-0000-0000-000000000000"));

        assertThat(resolver.resolve(exchange).blockOptional()).isEmpty();
        assertThat(resolver.resolve(exchange, audience.getRegistrationId()).blockOptional()).isEmpty();
        assertThat(resolver.resolve(MockServerWebExchange.from(MockServerHttpRequest.get("/oauth2/authorization/unknown")))
                .blockOptional()).isEmpty();
    }

    @Test
    void logoutRedirectHasExactlyClientIdAndPostLogoutRedirectUri() {
        EntraLogoutSuccessHandler handler = new EntraLogoutSuccessHandler(
                URI.create("https://login.example.com/tenant/oauth2/v2.0/logout"), "client-id-1",
                URI.create("https://gateway.example.com/"));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/logout"));

        handler.onLogoutSuccess(new WebFilterExchange(exchange, e -> null), null).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        URI location = exchange.getResponse().getHeaders().getLocation();
        assertThat(location).hasToString("https://login.example.com/tenant/oauth2/v2.0/logout"
                + "?client_id=client-id-1&post_logout_redirect_uri=https%3A%2F%2Fgateway.example.com%2F");
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(location).build().getQueryParams();
        assertThat(query.keySet()).containsExactly("client_id", "post_logout_redirect_uri");
    }

    @Test
    void registrationNeedsAClientSecret() {
        InMemoryReactiveClientRegistrationRepository registrations = registrations(entra().clientSecret(null).build());

        assertThatThrownBy(() -> SessionSecurityConfiguration.entraRegistration(registrations, SETTINGS))
                .hasMessageContaining("client-secret is required");
    }

    @Test
    void registrationNeedsTheRedirectUriOfThePublicBaseUrl() {
        InMemoryReactiveClientRegistrationRepository registrations = registrations(
                entra().redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").build());

        assertThatThrownBy(() -> SessionSecurityConfiguration.entraRegistration(registrations, SETTINGS))
                .hasMessageContaining("redirect-uri must be 'https://gateway.example.com/login/oauth2/code/{registrationId}'");
    }

    @Test
    void registrationIsRequired() {
        InMemoryReactiveClientRegistrationRepository registrations = registrations(
                entra().registrationId("other").build());

        assertThatThrownBy(() -> SessionSecurityConfiguration.entraRegistration(registrations, SETTINGS))
                .hasMessageContaining("spring.security.oauth2.client.registration.entra is required");
    }

    @Test
    void registrationNeedsTheAuthorizationCodeGrant() {
        InMemoryReactiveClientRegistrationRepository registrations = registrations(
                entra().authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).build());

        assertThatThrownBy(() -> SessionSecurityConfiguration.entraRegistration(registrations, SETTINGS))
                .hasMessageContaining("must use the grant type authorization_code and the scope openid");
    }

    @Test
    void registrationNeedsTheOpenidScope() {
        InMemoryReactiveClientRegistrationRepository registrations = registrations(
                entra().scope("profile", "offline_access").build());

        assertThatThrownBy(() -> SessionSecurityConfiguration.entraRegistration(registrations, SETTINGS))
                .hasMessageContaining("must use the grant type authorization_code and the scope openid");
    }

    @Test
    void validRegistrationPassesTheChecks() {
        ClientRegistration entra = entra().build();

        assertThat(SessionSecurityConfiguration.entraRegistration(registrations(entra), SETTINGS)).isEqualTo(entra);
        assertThat(SessionSecurityConfiguration.endSessionEndpoint(entra))
                .hasToString("https://login.example.com/tenant/oauth2/v2.0/logout");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"https://login.example.com/logout?client_id=x", "/tenant/oauth2/v2.0/logout"})
    void providerNeedsAnAbsoluteEndSessionEndpointWithoutQuery(String endpoint) {
        Map<String, Object> metadata = new HashMap<>();
        if (endpoint != null) {
            metadata.put("end_session_endpoint", endpoint);
        }
        ClientRegistration entra = entra().providerConfigurationMetadata(metadata).build();

        assertThatThrownBy(() -> SessionSecurityConfiguration.endSessionEndpoint(entra))
                .hasMessageContaining("end_session_endpoint without a query");
    }

    private static InMemoryReactiveClientRegistrationRepository registrations(ClientRegistration... registrations) {
        return new InMemoryReactiveClientRegistrationRepository(List.of(registrations));
    }

    private static ClientRegistration.Builder entra() {
        return ClientRegistration.withRegistrationId("entra")
                .clientId("client-id-1")
                .clientSecret("test-only-dummy-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://gateway.example.com/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "offline_access")
                .authorizationUri("https://login.example.com/authorize")
                .tokenUri("https://login.example.com/token")
                .jwkSetUri("https://login.example.com/keys")
                .userNameAttributeName("oid")
                .providerConfigurationMetadata(Map.of(
                        "end_session_endpoint", "https://login.example.com/tenant/oauth2/v2.0/logout"));
    }
}
