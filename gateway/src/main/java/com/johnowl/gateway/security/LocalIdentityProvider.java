package com.johnowl.gateway.security;

/**
 * Test only: the issuer of the registration {@code entra} may be a local identity provider, for example
 * {@code http://127.0.0.1:<port>/tenant/v2.0}. Without a bean of this type, {@link AudienceTokenDecoder}
 * accepts only an Entra ID issuer (spec 023, "Permission decoding and cache", issuer pin).
 *
 * <p>No production code registers a bean of this type, and no configuration property turns it on. The
 * interface is package-private, so only a class of this package can implement it. The bean must not be a
 * proxy, otherwise the startup stops (see {@link AudienceTokenConfiguration}). The tests register it from
 * {@code src/test}.
 */
interface LocalIdentityProvider {
}
