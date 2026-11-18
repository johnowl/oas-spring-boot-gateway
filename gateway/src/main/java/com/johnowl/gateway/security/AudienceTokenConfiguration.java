package com.johnowl.gateway.security;

import com.johnowl.gateway.routing.GatewaySecurityProperties;
import com.johnowl.gateway.routing.OpenApiRouteDefinitionLocator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizationCodeReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.DelegatingReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.util.ClassUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The per-audience tokens of spec 023: the client registrations of the audiences, the decoder of the
 * audience tokens, the provider, and the authorized client manager. The session store configuration
 * gives the {@link SessionTokenStore}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class AudienceTokenConfiguration {

    private static final String ENTRA = EntraAuthorizationRequestResolver.REGISTRATION_ID;

    private static final Logger log = LoggerFactory.getLogger(AudienceTokenConfiguration.class);

    /**
     * The registrations from the properties ({@code entra}) and the generated per-audience
     * registrations. They are not in YAML. This bean replaces the repository of Spring Boot, which
     * knows only the registrations from the properties.
     */
    @Bean
    ReactiveClientRegistrationRepository clientRegistrationRepository(OAuth2ClientProperties properties,
            OpenApiRouteDefinitionLocator locator) {
        // Reads the OpenID Connect metadata of each issuer once, at startup, on the main thread.
        Map<String, ClientRegistration> configured = new OAuth2ClientPropertiesMapper(properties).asClientRegistrations();
        for (String id : configured.keySet()) {
            if (id.startsWith(AudienceRegistrationIds.PREFIX)) {
                throw new IllegalStateException("spring.security.oauth2.client.registration." + id + ": the prefix '"
                        + AudienceRegistrationIds.PREFIX + "' is reserved for the per-audience registrations of the gateway.");
            }
        }
        ClientRegistration entra = configured.get(ENTRA);
        if (entra == null) {
            throw new IllegalStateException("spring.security.oauth2.client.registration." + ENTRA + " is required.");
        }
        List<ClientRegistration> all = new ArrayList<>(configured.values());
        all.addAll(AudienceClientRegistrations.of(entra, locator.accessRules()).registrations());
        return new InMemoryReactiveClientRegistrationRepository(all);
    }

    /**
     * The per-audience registrations. The same registrations as in the repository: both come from the
     * registration {@code entra} and the access rules, without I/O.
     */
    @Bean
    AudienceClientRegistrations audienceClientRegistrations(ReactiveClientRegistrationRepository registrations,
            OpenApiRouteDefinitionLocator locator) {
        return AudienceClientRegistrations.of(entra(registrations), locator.accessRules());
    }

    /**
     * Validates each audience token before the gateway stores it. The decoder loads the JSON Web Key Set
     * at startup, on the main thread. If the key set does not load, the gateway starts and loads it at the
     * first validation. The calls for the key set have the timeout of the token endpoint.
     */
    @Bean
    AudienceTokenDecoder audienceTokenDecoder(ReactiveClientRegistrationRepository registrations,
            GatewaySecurityProperties security, ObjectProvider<LocalIdentityProvider> localIdentityProvider,
            ObjectProvider<ObservationRegistry> observations) {
        Duration timeout = security.tokenEndpoint().timeout();
        WebClient webClient = IdentityProviderWebClient.create(timeout,
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
        AudienceTokenDecoder decoder = localIdentityProvider(localIdentityProvider.stream().toList())
                ? AudienceTokenDecoder.ofLocalIdentityProvider(entra(registrations), webClient)
                : AudienceTokenDecoder.of(entra(registrations), webClient);
        decoder.loadKeys()
                .timeout(timeout.multipliedBy(3))
                .onErrorResume(e -> e instanceof KeySetUnavailableException || e instanceof TimeoutException, e -> {
                    log.warn("The JSON Web Key Set of the registration entra did not load at startup ({}). "
                            + "The gateway loads it at the first token validation.", e.getClass().getSimpleName());
                    return Mono.empty();
                })
                .block();
        return decoder;
    }

    /**
     * Accepts a {@link LocalIdentityProvider} bean only if it is a class of this package and not a proxy.
     * Only the tests register one.
     */
    static boolean localIdentityProvider(List<LocalIdentityProvider> markers) {
        for (LocalIdentityProvider marker : markers) {
            Class<?> type = marker.getClass();
            boolean proxy = Proxy.isProxyClass(type) || ClassUtils.getUserClass(type) != type;
            if (proxy || !type.getPackageName().equals(LocalIdentityProvider.class.getPackageName())) {
                throw new IllegalStateException(type.getName() + " is not a valid bean of type "
                        + LocalIdentityProvider.class.getSimpleName() + ". The bean must be a class in the package "
                        + LocalIdentityProvider.class.getPackageName() + ", and not a proxy.");
            }
        }
        return !markers.isEmpty();
    }

    @Bean
    SessionRedemptions sessionRedemptions() {
        return new SessionRedemptions();
    }

    @Bean
    AudienceRefreshTokenAuthorizedClientProvider audienceRefreshTokenAuthorizedClientProvider(
            AudienceClientRegistrations audiences, ReactiveClientRegistrationRepository registrations,
            SessionTokenStore store, SessionRedemptions redemptions, AudienceTokenDecoder decoder,
            GatewaySecurityProperties security, ObjectProvider<ObservationRegistry> observations,
            ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
        // The tests give a Clock bean to reach the clock skew without waiting.
        Clock time = clock.getIfAvailable(Clock::systemUTC);
        TokenEndpointClient tokenEndpoint = TokenEndpointClient.create(security.tokenEndpoint().timeout(),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP), time);
        return new AudienceRefreshTokenAuthorizedClientProvider(audiences, registrations, store, redemptions,
                tokenEndpoint, decoder, security.tokenClockSkew(), time, meters.getIfAvailable(() -> Metrics.globalRegistry),
                new GrantRateLimiter(security.tokenEndpoint().grantsPerSecond(), time));
    }

    /**
     * The provider chain of spec 023: first the per-audience provider, then the authorization code
     * provider for {@code entra} only. No refresh token provider: the refresh token of {@code entra} is
     * used only inside the single-flight redemption and the lock of the session.
     */
    @Bean
    ReactiveOAuth2AuthorizedClientManager authorizedClientManager(ReactiveClientRegistrationRepository registrations,
            ServerOAuth2AuthorizedClientRepository authorizedClients,
            AudienceRefreshTokenAuthorizedClientProvider audienceProvider) {
        DefaultReactiveOAuth2AuthorizedClientManager manager =
                new DefaultReactiveOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(providerChain(audienceProvider));
        // The provider needs the exchange for the session. Request parameters never set a scope.
        manager.setContextAttributesMapper(request -> Mono.justOrEmpty(
                        request.<ServerWebExchange>getAttribute(ServerWebExchange.class.getName()))
                .map(exchange -> Map.<String, Object>of(ServerWebExchange.class.getName(), exchange))
                .defaultIfEmpty(Map.of()));
        return manager;
    }

    /**
     * The permissions of the audience tokens, for the authorization manager of spec 024. The decoder of the
     * provider validates the tokens, with the timeout of the token endpoint; a Caffeine cache keeps the
     * result until the token {@code exp}. The session token store removes a stored token that fails the
     * validation.
     */
    @Bean
    TokenPermissionsResolver tokenPermissionsResolver(ReactiveOAuth2AuthorizedClientManager authorizedClientManager,
            AudienceTokenDecoder decoder, SessionTokenStore store, GatewaySecurityProperties security) {
        return new TokenPermissionsResolver(authorizedClientManager, decoder, store,
                security.permissionCache().maximumSize(), security.tokenEndpoint().timeout());
    }

    static ReactiveOAuth2AuthorizedClientProvider providerChain(ReactiveOAuth2AuthorizedClientProvider audienceProvider) {
        return new DelegatingReactiveOAuth2AuthorizedClientProvider(audienceProvider,
                new EntraOnlyAuthorizedClientProvider(new AuthorizationCodeReactiveOAuth2AuthorizedClientProvider()));
    }

    /** The repository is in memory, so the call does not wait for I/O. It runs at startup, on the main thread. */
    private static ClientRegistration entra(ReactiveClientRegistrationRepository registrations) {
        return registrations.findByRegistrationId(ENTRA).blockOptional()
                .orElseThrow(() -> new IllegalStateException("spring.security.oauth2.client.registration." + ENTRA + " is required."));
    }
}
