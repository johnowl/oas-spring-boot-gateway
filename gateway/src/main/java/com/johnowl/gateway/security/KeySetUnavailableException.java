package com.johnowl.gateway.security;

/**
 * The JSON Web Key Set of Entra ID did not load: a timeout, a connection error, or an error status
 * (spec 023, "Token endpoint errors", rows 5 and 6). The token itself was not checked.
 */
public final class KeySetUnavailableException extends RuntimeException {

    private final boolean timeout;

    KeySetUnavailableException(boolean timeout) {
        super(timeout ? "The JSON Web Key Set did not load in time." : "The JSON Web Key Set did not load.", null, false, false);
        this.timeout = timeout;
    }

    /** {@code true} if the connection or the response took too long. */
    public boolean timeout() {
        return timeout;
    }
}
