package com.johnowl.gateway.security;

/** Names of the exchange attributes of the security chain. A client cannot set exchange attributes. */
public final class GatewaySecurityAttributes {

    /**
     * The {@link com.johnowl.gateway.core.access.OperationAccess} of the request. {@link UndocumentedOperationWebFilter}
     * sets it, so that the authorization manager does not search the security index again.
     */
    public static final String OPERATION_ACCESS = GatewaySecurityAttributes.class.getName() + ".OPERATION_ACCESS";

    /** The {@link com.johnowl.gateway.core.access.AccessRule} of the request. The authorization manager sets it. */
    public static final String ACCESS_RULE = GatewaySecurityAttributes.class.getName() + ".ACCESS_RULE";

    /** The {@link DenyReason} of a denied request. Spec 024 sets it. */
    public static final String DENY_REASON = GatewaySecurityAttributes.class.getName() + ".DENY_REASON";

    /**
     * The {@link TokenError} of a failed token redemption (spec 023). The denial log of spec 024 writes
     * its fields; the provider of spec 023 does not log.
     */
    public static final String TOKEN_ERROR = GatewaySecurityAttributes.class.getName() + ".TOKEN_ERROR";

    /**
     * The {@link AudienceAccessToken} that {@link TokenPermissionsResolver} validated for this request. The token
     * relay of spec 025 reads it. Only the exchange holds it; the attribute never reaches a log or a response.
     */
    public static final String AUDIENCE_TOKEN = GatewaySecurityAttributes.class.getName() + ".AUDIENCE_TOKEN";

    private GatewaySecurityAttributes() {
    }
}
