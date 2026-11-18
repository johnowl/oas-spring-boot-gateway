package com.johnowl.gateway.security;

/**
 * An audience token failed the validation of {@link AudienceTokenDecoder}. The message is only the
 * name of the failed check, never the token.
 */
public final class InvalidAudienceTokenException extends RuntimeException {

    private final String check;

    InvalidAudienceTokenException(String check) {
        super("The audience token failed the check '" + check + "'.", null, false, false);
        this.check = check;
    }

    /** The name of the failed check, for example {@code iss} or {@code signature}. */
    public String check() {
        return check;
    }
}
