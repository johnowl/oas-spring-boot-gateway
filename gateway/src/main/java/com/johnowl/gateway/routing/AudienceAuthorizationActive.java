package com.johnowl.gateway.routing;

/**
 * Marker: the authorization manager of spec 024 checks the token {@code aud} and the permissions of
 * each protected operation. Without a bean of this type, an OAS file with a protected operation stops
 * the startup. See specs/024-authorization-manager.md, "Startup stop until the authorization manager
 * is active".
 *
 * <p>No production code registers a bean of this type before PR 2 of spec 024. No configuration
 * property registers it or turns off the stop. The bean must be a class in {@code com.johnowl.gateway} and
 * not a proxy, otherwise the startup stops (see {@link OpenApiRoutingConfiguration}).
 */
public interface AudienceAuthorizationActive {
}
