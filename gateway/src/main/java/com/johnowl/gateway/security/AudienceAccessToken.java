package com.johnowl.gateway.security;

/**
 * The access token that {@link TokenPermissionsResolver} validated for the current request: its signature,
 * its {@code aud}, and its {@code oid} passed. The relay of spec 025 sends exactly this token to the backend,
 * so the backend gets the token that the authorization manager checked, and the relay never calls the
 * authorized client manager or Entra again.
 *
 * @param audience the {@code aud} of the token: the client id of the backend app
 * @param value the token
 */
public record AudienceAccessToken(String audience, String value) {

    /** Without the token, so that a log line or an error message cannot contain it. */
    @Override
    public String toString() {
        return "AudienceAccessToken[audience=" + audience + "]";
    }
}
