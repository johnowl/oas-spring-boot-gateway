package com.johnowl.gateway.security;

import com.johnowl.gateway.routing.AudienceAuthorizationActive;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The authorization of spec 024: the filter for undocumented operations, the authorization manager of the
 * session chain, its denial log, the production bean of {@link AudienceAuthorizationActive}, the global
 * filter that compares the chosen route with the access rule, and the token relay of spec 025. The filter and the manager use the
 * {@link SecurityIndex} of {@link SessionLoginConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

    /**
     * The manager checks the token {@code aud} and the permissions, so the startup stop of spec 024 does not
     * apply. A static method: the route locator asks for this bean before the other beans of this class exist.
     * The bean checks at the end of the startup that the security chains use the manager.
     */
    @Bean
    static GatewayAudienceAuthorization gatewayAudienceAuthorization() {
        return new GatewayAudienceAuthorization();
    }

    @Bean
    AuthorizationDenials authorizationDenials(ObjectProvider<MeterRegistry> meters) {
        return new AuthorizationDenials(meters.getIfAvailable(() -> Metrics.globalRegistry));
    }

    @Bean
    UndocumentedOperationWebFilter undocumentedOperationWebFilter(SecurityIndex securityIndex,
            ManagementEndpoints managementEndpoints, AuthorizationDenials denials) {
        return new UndocumentedOperationWebFilter(securityIndex, managementEndpoints, denials);
    }

    /** Compares the chosen route with the route of the access rule (spec 024, "Only OAS routes"). */
    @Bean
    RouteOperationFilter routeOperationFilter() {
        return new RouteOperationFilter();
    }

    /** Relays the access token of the route audience to the backend (spec 025, "Filters on each route"). */
    @Bean
    AudienceTokenRelayGatewayFilterFactory audienceTokenRelayGatewayFilterFactory() {
        return new AudienceTokenRelayGatewayFilterFactory();
    }

    @Bean
    GatewayAuthorizationManager gatewayAuthorizationManager(SecurityIndex securityIndex,
            TokenPermissionsResolver tokenPermissionsResolver, AuthorizationDenials denials) {
        return new GatewayAuthorizationManager(securityIndex, tokenPermissionsResolver, denials);
    }
}
