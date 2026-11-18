package com.johnowl.gateway.routing;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Security settings of the session login (spec 022), of the per-audience tokens (spec 023), and of the
 * request header removal (spec 025).
 *
 * @param session the session lifetime (spec 022)
 * @param login the login settings (spec 022)
 * @param logout the logout settings (spec 022)
 * @param tokenClockSkew the gateway renews an audience token this time before its {@code exp} (spec 023)
 * @param tokenEndpoint the calls to the token endpoint (spec 023)
 * @param permissionCache the cache of the decoded permissions (spec 023)
 * @param removeRequestHeaders the request headers that the gateway removes in addition to the built-in
 *     list (spec 025)
 */
@ConfigurationProperties("gateway.security")
public record GatewaySecurityProperties(
        @DefaultValue Session session,
        @DefaultValue Login login,
        @DefaultValue Logout logout,
        @DefaultValue("60s") Duration tokenClockSkew,
        @DefaultValue TokenEndpoint tokenEndpoint,
        @DefaultValue PermissionCache permissionCache,
        @DefaultValue RemoveRequestHeaders removeRequestHeaders) {

    /**
     * @param preLoginTimeout maximum inactive interval of a session without an authenticated user
     * @param idleTimeout maximum inactive interval of a session with an authenticated user
     * @param maxLifetime maximum time from login to the end of the session
     */
    public record Session(
            @DefaultValue("5m") Duration preLoginTimeout,
            @DefaultValue("30m") Duration idleTimeout,
            @DefaultValue("8h") Duration maxLifetime) {
    }

    /** @param failurePath the redirect target after a login failure, for example {@code /?login=failed} */
    public record Login(@DefaultValue("/?login=failed") String failurePath) {
    }

    /** @param postLogoutRedirectPath the path of the post-logout redirect URI */
    public record Logout(@DefaultValue("/") String postLogoutRedirectPath) {
    }

    /**
     * @param timeout the maximum time for one call to the token endpoint, and the connect timeout and the
     *     response timeout of the JSON Web Key Set (spec 023)
     * @param grantsPerSecond the refresh token grants that this gateway instance sends at most in one
     *     second, for all sessions (spec 023, "Grant limit for the instance")
     */
    public record TokenEndpoint(@DefaultValue("5s") Duration timeout, @DefaultValue("50") int grantsPerSecond) {
    }

    /**
     * @param maximumSize the maximum number of entries in the permission cache (spec 023). At least {@code 1},
     *     at most {@code 1000000}.
     */
    public record PermissionCache(@DefaultValue("10000") int maximumSize) {
    }

    /**
     * The configured values add to the built-in list of {@link IdentityHeaderRemovalFilter}. They cannot
     * remove a built-in entry.
     *
     * @param names exact header names to remove, in any letter case
     * @param prefixes header name prefixes to remove, in any letter case
     */
    public record RemoveRequestHeaders(@DefaultValue List<String> names, @DefaultValue List<String> prefixes) {
    }
}
