package com.johnowl.gateway.security;

/**
 * The registration ids of the per-audience client registrations (spec 023, "Client registrations for
 * each audience"). The id is an internal string; the gateway never sends it to Entra.
 */
public final class AudienceRegistrationIds {

    /** The prefix of each per-audience registration id. */
    public static final String PREFIX = EntraAuthorizationRequestResolver.REGISTRATION_ID + "-";

    private AudienceRegistrationIds() {
    }

    /**
     * The registration id of an audience.
     *
     * @param clientId the value of {@code x-gateway.audience}: the client id of the backend app
     * @return {@code entra-<client-id>}
     */
    public static String forAudience(String clientId) {
        return PREFIX + clientId;
    }
}
