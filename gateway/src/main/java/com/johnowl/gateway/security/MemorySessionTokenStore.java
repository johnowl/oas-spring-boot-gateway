package com.johnowl.gateway.security;

import java.time.Clock;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import reactor.core.publisher.Mono;

/**
 * The token state of the session store {@code memory} (spec 023, "Session store"): session attributes
 * of {@link MemorySessionRepository}, changed atomically. A session exists on one gateway instance
 * only, so the single-flight redemption of that instance is the lock; {@link #withLock} adds nothing.
 */
final class MemorySessionTokenStore implements SessionTokenStore {

    /** The grant counter of a session. It is removed with the session. */
    static final String GRANTS_ATTRIBUTE = MemorySessionRepository.TOKEN_STATE_PREFIX + "grants";

    /** The grants of one window. */
    record GrantWindow(long startMillis, int count) {
    }

    private final MemorySessionRepository sessions;

    private final Clock clock;

    MemorySessionTokenStore(MemorySessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public Mono<Stored> load(String sessionId, String registrationId) {
        return Mono.fromSupplier(() -> sessions.attribute(sessionId, SessionAuthorizedClientRepository.attribute(registrationId))
                        instanceof OAuth2AuthorizedClient client ? new Stored(client, client) : null);
    }

    @Override
    public Mono<Boolean> save(String sessionId, OAuth2AuthorizedClient client) {
        String name = SessionAuthorizedClientRepository.attribute(client.getClientRegistration().getRegistrationId());
        return Mono.fromSupplier(() -> sessions.updateAttribute(sessionId, name, stored -> client));
    }

    @Override
    public Mono<Void> remove(String sessionId, String registrationId) {
        String name = SessionAuthorizedClientRepository.attribute(registrationId);
        return Mono.fromRunnable(() -> sessions.updateAttribute(sessionId, name, stored -> null));
    }

    @Override
    public Mono<Boolean> compareAndSet(String sessionId, Stored expected, OAuth2AuthorizedClient client) {
        String name = SessionAuthorizedClientRepository.attribute(client.getClientRegistration().getRegistrationId());
        return Mono.fromSupplier(() -> {
            boolean[] written = new boolean[1];
            sessions.updateAttribute(sessionId, name, stored -> {
                written[0] = stored == expected.version();
                return written[0] ? client : stored;
            });
            return written[0];
        });
    }

    @Override
    public Mono<Boolean> compareAndRemove(String sessionId, Stored expected) {
        String name = SessionAuthorizedClientRepository.attribute(expected.client().getClientRegistration().getRegistrationId());
        return Mono.fromSupplier(() -> {
            boolean[] removed = new boolean[1];
            sessions.updateAttribute(sessionId, name, stored -> {
                removed[0] = stored == expected.version();
                return removed[0] ? null : stored;
            });
            return removed[0];
        });
    }

    @Override
    public Mono<Boolean> countGrant(String sessionId) {
        return Mono.fromSupplier(() -> {
            boolean[] allowed = {true};
            long now = clock.millis();
            sessions.updateAttribute(sessionId, GRANTS_ATTRIBUTE, stored -> {
                if (stored instanceof GrantWindow window && now - window.startMillis() < GRANT_WINDOW.toMillis()) {
                    allowed[0] = window.count() < GRANT_LIMIT;
                    return allowed[0] ? new GrantWindow(window.startMillis(), window.count() + 1) : window;
                }
                return new GrantWindow(now, 1);
            });
            return allowed[0];
        });
    }

    @Override
    public Mono<Void> deleteSession(String sessionId) {
        return sessions.deleteById(sessionId);
    }

    @Override
    public <T> Mono<T> withLock(String sessionId, Mono<T> work) {
        return work;
    }
}
