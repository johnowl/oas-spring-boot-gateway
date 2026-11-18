package com.johnowl.gateway.security;

/**
 * The in-memory session store has no room for a new session: it holds the maximum number of
 * sessions, and all of them have an authenticated user. {@link SessionStoreFullWebFilter} answers
 * {@code 503}.
 */
final class SessionStoreFullException extends RuntimeException {

    SessionStoreFullException() {
        // No stack trace: the gateway expects this exception, and it does not show a programming error.
        super("The in-memory session store is full of authenticated sessions.", null, false, false);
    }
}
