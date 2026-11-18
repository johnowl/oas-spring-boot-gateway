package com.johnowl.gateway.security;

import java.time.Duration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import reactor.core.publisher.Mono;

/**
 * The token state of a session in the session store (spec 023, "Session store"). The provider reads
 * and writes it directly in the store, not in the copy of the session that a request loaded. Each
 * session store has one implementation: {@link MemorySessionTokenStore} and {@link RedisSessionTokenStore}.
 */
interface SessionTokenStore {

    /** A session can cause at most this number of refresh token grants in {@link #GRANT_WINDOW}. */
    int GRANT_LIMIT = 10;

    /** The window of the grant limit. */
    Duration GRANT_WINDOW = Duration.ofSeconds(60);

    /**
     * A stored authorized client.
     *
     * @param client the client
     * @param version the stored value that {@link #compareAndSet} compares
     */
    record Stored(OAuth2AuthorizedClient client, Object version) {
    }

    /** The stored client of the registration; empty without a client or without a session. */
    Mono<Stored> load(String sessionId, String registrationId);

    /**
     * Stores the client under its registration id.
     *
     * @return {@code false} if the session does not exist; the store then writes nothing
     */
    Mono<Boolean> save(String sessionId, OAuth2AuthorizedClient client);

    /** Removes the client of the registration, if the session exists. */
    Mono<Void> remove(String sessionId, String registrationId);

    /**
     * Stores the client only if the stored client of its registration is still {@code expected}.
     *
     * @return {@code true} if the store wrote the client
     */
    Mono<Boolean> compareAndSet(String sessionId, Stored expected, OAuth2AuthorizedClient client);

    /**
     * Removes the client of a registration only if the stored client is still {@code expected}.
     *
     * @return {@code true} if the store removed the client
     */
    Mono<Boolean> compareAndRemove(String sessionId, Stored expected);

    /**
     * Counts one refresh token grant of the session.
     *
     * @return {@code false} if the session already caused {@link #GRANT_LIMIT} grants in the window; the
     *     grant must not be sent
     */
    Mono<Boolean> countGrant(String sessionId);

    /** Deletes the session and all its authorized clients. */
    Mono<Void> deleteSession(String sessionId);

    /**
     * Runs the work while this instance holds the lock of the session across gateway instances. The
     * work runs to its end; the lock is then released.
     *
     * @return the result of the work, or a {@link LockTimeoutException} if the lock is not free in time
     */
    <T> Mono<T> withLock(String sessionId, Mono<T> work);

    /** The lock of a session was not free within the maximum wait. */
    final class LockTimeoutException extends RuntimeException {

        LockTimeoutException() {
            super("The token lock of the session was not free in time.", null, false, false);
        }
    }
}
