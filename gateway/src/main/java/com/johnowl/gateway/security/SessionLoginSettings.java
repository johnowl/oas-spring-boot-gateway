package com.johnowl.gateway.security;

import com.johnowl.gateway.routing.GatewaySecurityProperties;
import java.net.URI;
import java.time.Duration;

/**
 * The checked settings of the session login (spec 022). The gateway builds them once at startup,
 * and does not start if a value breaks a rule.
 *
 * @param publicBaseUrl the public URL of the gateway
 * @param loginFailureUri the absolute redirect target after a login failure
 * @param postLogoutRedirectUri the absolute URI where Entra ID sends the browser after logout
 * @param session the session lifetime
 */
public record SessionLoginSettings(
        PublicBaseUrl publicBaseUrl,
        URI loginFailureUri,
        URI postLogoutRedirectUri,
        GatewaySecurityProperties.Session session) {

    /**
     * Checks the settings.
     *
     * @param localProfile {@code true} if the Spring profile {@code local} is active
     * @param securityIndex the access rules, to check that the redirect paths need no session
     * @throws IllegalStateException if a value breaks a rule
     */
    static SessionLoginSettings of(GatewayProperties gateway, GatewaySecurityProperties security,
            boolean localProfile, SecurityIndex securityIndex) {
        PublicBaseUrl baseUrl = PublicBaseUrl.parse(gateway.publicBaseUrl(), localProfile);
        String failurePath = security.login().failurePath();
        String postLogoutPath = security.logout().postLogoutRedirectPath();
        RedirectPathValidator.validate("gateway.security.login.failure-path", failurePath,
                securityIndex::isNonPublicGet);
        RedirectPathValidator.validate("gateway.security.logout.post-logout-redirect-path", postLogoutPath,
                securityIndex::isNonPublicGet);
        GatewaySecurityProperties.Session session = security.session();
        requirePositive("gateway.security.session.pre-login-timeout", session.preLoginTimeout());
        requirePositive("gateway.security.session.idle-timeout", session.idleTimeout());
        requirePositive("gateway.security.session.max-lifetime", session.maxLifetime());
        return new SessionLoginSettings(baseUrl, baseUrl.resolve(failurePath), baseUrl.resolve(postLogoutPath), session);
    }

    private static void requirePositive(String property, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalStateException(property + " must be a positive duration, but is " + value + ".");
        }
    }
}
