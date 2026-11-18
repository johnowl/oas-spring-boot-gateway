package com.johnowl.gateway.security;

/**
 * A token redemption of spec 023 failed. The deny reason gives the response (spec 024); the token error
 * gives the log fields. The message contains no token and no text from Entra.
 */
public final class AudienceTokenException extends RuntimeException {

    private final DenyReason reason;

    private final TokenError tokenError;

    AudienceTokenException(DenyReason reason, TokenError tokenError) {
        super(reason + " for " + tokenError.registrationId(), null, false, false);
        this.reason = reason;
        this.tokenError = tokenError;
    }

    public DenyReason reason() {
        return reason;
    }

    public TokenError tokenError() {
        return tokenError;
    }
}
