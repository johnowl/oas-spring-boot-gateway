package com.johnowl.gateway.security;

import com.johnowl.gateway.routing.BackendResponseHeadersFilter;
import com.johnowl.gateway.routing.GatewayForwardedHeadersFilter;
import com.johnowl.gateway.routing.GatewaySecurityProperties;
import com.johnowl.gateway.routing.OpenApiRouteDefinitionLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Builds and checks the settings of the session login at startup (spec 022). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GatewayProperties.class, GatewaySecurityProperties.class})
public class SessionLoginConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SessionLoginConfiguration.class);

    /** The access rules of all operations (spec 024). Built once, at startup. */
    @Bean
    SecurityIndex securityIndex(OpenApiRouteDefinitionLocator locator, ObjectProvider<WebFluxProperties> webFluxProperties) {
        WebFluxProperties properties = webFluxProperties.getIfAvailable();
        return SecurityIndex.build(locator.accessRules(), properties == null ? null : properties.getBasePath());
    }

    /** The actuator base path, and the checks of the management port (spec 024, "Management port"). */
    @Bean
    ManagementEndpoints managementEndpoints(Environment environment) {
        return ManagementEndpoints.of(environment);
    }

    @Bean
    SessionLoginSettings sessionLoginSettings(GatewayProperties gateway, GatewaySecurityProperties security,
            SecurityIndex securityIndex, Environment environment) {
        boolean localProfile = environment.acceptsProfiles(Profiles.of("local"));
        if (localProfile) {
            log.warn("Profile 'local' is active. Do not use it in a deployed gateway.");
        }
        return SessionLoginSettings.of(gateway, security, localProfile, securityIndex);
    }

    /**
     * The forwarding headers to the backends come from {@code gateway.public-base-url} and from the
     * connection peer, never from the client (spec 025, "Forwarding headers").
     */
    @Bean
    GatewayForwardedHeadersFilter gatewayForwardedHeadersFilter(SessionLoginSettings settings) {
        return new GatewayForwardedHeadersFilter(settings.publicBaseUrl().uri());
    }

    /**
     * Backend responses never set cookies, CORS headers, or browser policies on the gateway site, and their
     * {@code Location} points at the gateway (spec 025, "Response headers").
     */
    @Bean
    BackendResponseHeadersFilter backendResponseHeadersFilter(SessionLoginSettings settings,
            ObjectProvider<WebFluxProperties> webFluxProperties) {
        WebFluxProperties properties = webFluxProperties.getIfAvailable();
        return new BackendResponseHeadersFilter(settings.publicBaseUrl().uri(),
                properties == null ? null : properties.getBasePath());
    }

    /** A shared cache does not keep a backend response of a user or with a gateway cookie (spec 025). */
    @Bean
    PrivateResponseCacheFilter privateResponseCacheFilter() {
        return new PrivateResponseCacheFilter();
    }
}
