package com.johnowl.gateway.security;

/**
 * Why the gateway denies a request. The authorization manager of spec 024 and the provider of spec 023
 * store the reason in the exchange attribute {@link GatewaySecurityAttributes#DENY_REASON}. The entry
 * point and the request cache of spec 022 read these values.
 */
public enum DenyReason {

    /** No access rule matches the method and the path. The response is {@code 404}. */
    NO_MATCH,

    /** The operation is not public, and the request has no authenticated user. The entry point answers. */
    NOT_AUTHENTICATED,

    /** The session is no longer valid, for example after {@code invalid_grant} (spec 023). */
    SESSION_INVALID,

    /** The user must sign in again (spec 023). */
    REAUTHENTICATION_REQUIRED,

    /** Entra has no consent for the audience (spec 023, "Token endpoint errors", row 1). The response is {@code 403}. */
    CONSENT_REQUIRED,

    /** The token endpoint rejected the request with another {@code 4xx} error (spec 023, row 4). The response is {@code 500}. */
    TOKEN_REQUEST_REJECTED,

    /** The session reached the grant limit (spec 023). The response is {@code 429}. */
    TOKEN_GRANT_LIMIT,

    /** No token response within the timeout, or no session lock in time (spec 023, row 5). The response is {@code 504}. */
    TOKEN_ENDPOINT_TIMEOUT,

    /** A connection error or a {@code 5xx} status of the token endpoint (spec 023, row 6). The response is {@code 502}. */
    TOKEN_ENDPOINT_ERROR,

    /**
     * Entra throttles the token requests ({@code 429} or {@code temporarily_unavailable}), or the gateway
     * pauses its grants after such a response (spec 023, "Throttling"). The response is {@code 503}.
     */
    TOKEN_ENDPOINT_THROTTLED,

    /** The new audience token failed the validation (spec 023). The response is {@code 500}. */
    INVALID_AUDIENCE_TOKEN,

    /** The {@code aud} of the audience token is not the audience of the access rule. The response is {@code 500}. */
    WRONG_AUDIENCE,

    /** The permissions of the audience token do not satisfy the access rule. The response is {@code 403}. */
    MISSING_PERMISSION,

    /**
     * Another error during the decision of the authorization manager, for example a defect in the resolver of
     * spec 023. The response is {@code 500}.
     */
    INTERNAL_ERROR
}
