package com.johnowl.gateway.security;

import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.DelegatingServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.WebSessionServerLogoutHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.header.StaticServerHttpHeadersWriter;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.session.WebSessionManager;

/**
 * The security chain of the session login (spec 022): OIDC login with Entra ID (registration
 * {@code entra}), the entry point, CSRF protection, and logout. All tokens stay in the session.
 *
 * <p>The authorization manager of spec 024 decides each request. A request without an access rule
 * already got {@code 404} from {@link UndocumentedOperationWebFilter}, before this chain.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SessionSecurityConfiguration {

    private static final String REDIRECT_URI_TEMPLATE = "/login/oauth2/code/{registrationId}";

    static final String STRICT_TRANSPORT_SECURITY = "Strict-Transport-Security";

    /** One year. */
    static final String STRICT_TRANSPORT_SECURITY_VALUE = "max-age=31536000";

    /**
     * The actuator runs on its own port (spec 002) and needs no session. Its requests come from the
     * management context, so this chain takes all of them before the session chain. It permits only
     * the paths under the actuator base path, and denies every other path with {@code 404}. The
     * actuator exposes only {@code health}; it answers {@code 404} for every other endpoint.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityWebFilterChain managementSecurityChain(ServerHttpSecurity http, ManagementEndpoints management,
            GatewayAudienceAuthorization audienceAuthorization) {
        SecurityWebFilterChain chain = http
                .securityMatcher(exchange -> ManagementEndpoints.isManagementRequest(exchange)
                        ? ServerWebExchangeMatcher.MatchResult.match()
                        : ServerWebExchangeMatcher.MatchResult.notMatch())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(management.patterns()).permitAll()
                        .anyExchange().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((exchange, denied) -> ProblemResponses.write(exchange, HttpStatus.NOT_FOUND))
                        .accessDeniedHandler((exchange, denied) -> ProblemResponses.write(exchange, HttpStatus.NOT_FOUND)))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .build();
        audienceAuthorization.exempts(chain);
        return chain;
    }

    @Bean
    SecurityWebFilterChain sessionSecurityChain(ServerHttpSecurity http,
            ReactiveClientRegistrationRepository registrations,
            SessionAuthorizedClientRepository authorizedClients,
            AudienceClientRegistrations audiences,
            GatewayAuthorizationManager authorizationManager,
            GatewayAudienceAuthorization audienceAuthorization,
            SessionLoginSettings settings,
            WebSessionManager sessionManager,
            ObjectProvider<Clock> clock) {
        ClientRegistration entra = entraRegistration(registrations, settings);
        WebSessionServerRequestCache requestCache = new WebSessionServerRequestCache();
        SessionAuthenticationEntryPoint entryPoint = new SessionAuthenticationEntryPoint(requestCache);
        requestCache.setSaveRequestMatcher(new SavedRequestMatcher());
        RotatingCsrfTokenRepository csrfTokens = new RotatingCsrfTokenRepository();
        RedirectServerAuthenticationSuccessHandler redirectToSavedRequest = new RedirectServerAuthenticationSuccessHandler();
        redirectToSavedRequest.setRequestCache(requestCache);
        ServerAuthenticationSuccessHandler newCsrfToken =
                (exchange, authentication) -> csrfTokens.saveToken(exchange.getExchange(), null);
        // The tests give a Clock bean to check the maximum session lifetime without waiting.
        SessionLifetimeWebFilter sessionLifetime = new SessionLifetimeWebFilter(settings.session().idleTimeout(),
                settings.session().maxLifetime(), clock.getIfAvailable(Clock::systemUTC), sessionManager);
        // A per-audience client of an earlier login in the same session belongs to another user (spec 023).
        ServerAuthenticationSuccessHandler removeAudienceClients = (exchange, authentication) -> exchange.getExchange()
                .getSession().flatMap(session -> authorizedClients.removeAudienceClients(session, audiences.registrationIds()));
        ServerAuthenticationSuccessHandler startSessionLifetime =
                (exchange, authentication) -> exchange.getExchange().getSession().doOnNext(sessionLifetime::start).then();
        SecurityWebFilterChain chain = http
                .authorizeExchange(exchanges -> exchanges.anyExchange().access(authorizationManager))
                .oauth2Login(login -> login
                        .authorizationRequestResolver(new EntraAuthorizationRequestResolver(registrations))
                        .authorizedClientRepository(authorizedClients)
                        .authenticationSuccessHandler(
                                new DelegatingServerAuthenticationSuccessHandler(
                                        removeAudienceClients, newCsrfToken, startSessionLifetime, redirectToSavedRequest))
                        .authenticationFailureHandler(
                                new RedirectServerAuthenticationFailureHandler(settings.loginFailureUri().toString())))
                .requestCache(cache -> cache.requestCache(requestCache))
                // An explicit entry point also turns off the default login page of Spring Security.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new GatewayAccessDeniedHandler(entryPoint)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler())
                        .accessDeniedHandler((exchange, denied) -> ProblemResponses.write(exchange, HttpStatus.FORBIDDEN)))
                .headers(headers -> strictTransportSecurity(headers, settings.publicBaseUrl()))
                .addFilterAfter(new CsrfCookieWebFilter(), SecurityWebFiltersOrder.CSRF)
                .addFilterBefore(sessionLifetime, SecurityWebFiltersOrder.REACTOR_CONTEXT)
                .logout(logout -> logout
                        .logoutHandler(handlers -> handlers.add(new WebSessionServerLogoutHandler()))
                        .logoutSuccessHandler(new EntraLogoutSuccessHandler(
                                endSessionEndpoint(entra), entra.getClientId(), settings.postLogoutRedirectUri())))
                .build();
        // The production marker of spec 024 ends the startup stop only for a chain that uses the manager.
        audienceAuthorization.authorizes(chain, authorizationManager);
        return chain;
    }

    /**
     * The gateway sets {@code Strict-Transport-Security: max-age=31536000} on each response when
     * {@code gateway.public-base-url} uses {@code https} (spec 025, "Response headers"). The default writer
     * of Spring Security checks the scheme of the request instead. TLS can end at a load balancer, so the
     * gateway can see {@code http}. The value has no {@code includeSubDomains} and no {@code preload}: the
     * gateway does not know the other hosts of its domain.
     */
    static void strictTransportSecurity(ServerHttpSecurity.HeaderSpec headers, PublicBaseUrl publicBaseUrl) {
        headers.hsts(ServerHttpSecurity.HeaderSpec.HstsSpec::disable);
        if ("https".equals(publicBaseUrl.uri().getScheme())) {
            headers.writer(StaticServerHttpHeadersWriter.builder()
                    .header(STRICT_TRANSPORT_SECURITY, STRICT_TRANSPORT_SECURITY_VALUE)
                    .build());
        }
    }

    /** Keeps each authorized client in its own session attribute (spec 023, "Storage of authorized clients"). */
    @Bean
    SessionAuthorizedClientRepository authorizedClientRepository(SessionTokenStore store) {
        return new SessionAuthorizedClientRepository(store);
    }

    /**
     * Takes the identity from the ID token only. The gateway does not call the user info endpoint:
     * for Entra ID it is in Microsoft Graph, and the gateway ignores the Graph access token.
     */
    @Bean
    ReactiveOAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        OidcReactiveOAuth2UserService userService = new OidcReactiveOAuth2UserService();
        userService.setRetrieveUserInfo(request -> false);
        return userService;
    }

    /**
     * Checks the registration {@code entra} at startup. The registration repository is in memory, so
     * the call does not wait for I/O; it runs once, on the main thread.
     */
    static ClientRegistration entraRegistration(ReactiveClientRegistrationRepository registrations,
            SessionLoginSettings settings) {
        String property = "spring.security.oauth2.client.registration." + EntraAuthorizationRequestResolver.REGISTRATION_ID;
        ClientRegistration entra = registrations.findByRegistrationId(EntraAuthorizationRequestResolver.REGISTRATION_ID)
                .blockOptional()
                .orElseThrow(() -> new IllegalStateException(property + " is required."));
        if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(entra.getAuthorizationGrantType())
                || !entra.getScopes().contains(OidcScopes.OPENID)) {
            throw new IllegalStateException(property + " must use the grant type authorization_code and the scope openid.");
        }
        if (!StringUtils.hasText(entra.getClientSecret())) {
            throw new IllegalStateException(property + ".client-secret is required (GATEWAY_BFF_CLIENT_SECRET).");
        }
        // The redirect URI comes from gateway.public-base-url, never from request headers.
        String expected = settings.publicBaseUrl() + REDIRECT_URI_TEMPLATE;
        String expanded = expected.replace("{registrationId}", EntraAuthorizationRequestResolver.REGISTRATION_ID);
        if (!expected.equals(entra.getRedirectUri()) && !expanded.equals(entra.getRedirectUri())) {
            throw new IllegalStateException(property + ".redirect-uri must be '" + expected + "', but is '"
                    + entra.getRedirectUri() + "'.");
        }
        return entra;
    }

    /** The logout endpoint of the provider. The gateway does not start without it. */
    static URI endSessionEndpoint(ClientRegistration entra) {
        Object value = entra.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
        URI endpoint = value == null ? null : URI.create(value.toString());
        if (endpoint == null || !endpoint.isAbsolute() || endpoint.getRawQuery() != null) {
            throw new IllegalStateException("The provider of the registration entra must have an "
                    + "end_session_endpoint without a query in its OpenID Connect metadata.");
        }
        return endpoint;
    }
}
