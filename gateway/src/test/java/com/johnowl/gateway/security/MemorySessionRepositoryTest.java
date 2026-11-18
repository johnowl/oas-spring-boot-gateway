package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.session.MapSession;

class MemorySessionRepositoryTest {

    private static final String CONTEXT = WebSessionServerSecurityContextRepository.DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME;

    private static final Duration PRE_LOGIN = Duration.ofMinutes(5);

    @Test
    void newSessionHasTheDrbgIdAndThePreLoginTimeout() {
        MapSession session = repository(10).createSession().block();

        assertThat(session.getId()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(session.getMaxInactiveInterval()).isEqualTo(PRE_LOGIN);
    }

    @Test
    void savedSessionIsFoundAsACopy() {
        MemorySessionRepository repository = repository(10);
        MapSession session = preLogin(repository);
        session.setAttribute("a", "b");
        repository.save(session).block();

        MapSession found = repository.findById(session.getId()).block();

        assertThat(found).isNotSameAs(session);
        assertThat(found.<String>getAttribute("a")).isEqualTo("b");
    }

    @Test
    void newSessionIdReplacesTheOldIdAndUsesTheDrbgGenerator() {
        MemorySessionRepository repository = repository(1);
        MapSession session = preLogin(repository);
        MapSession found = repository.findById(session.getId()).block();

        String newId = found.changeSessionId();
        found.setAttribute(CONTEXT, new SecurityContextImpl());
        repository.save(found).block();

        assertThat(newId).hasSize(43).isNotEqualTo(session.getId());
        assertThat(repository.contains(session.getId())).isFalse();
        assertThat(repository.contains(newId)).isTrue();
        assertThat(repository.size()).isOne();
    }

    @Test
    void fullStoreRemovesTheOldestPreLoginSessionFirst() {
        MemorySessionRepository repository = repository(3);
        MapSession oldest = preLogin(repository);
        MapSession authenticated = authenticated(repository);
        MapSession younger = preLogin(repository);

        MapSession newest = preLogin(repository);

        assertThat(repository.contains(oldest.getId())).isFalse();
        assertThat(repository.contains(authenticated.getId())).isTrue();
        assertThat(repository.contains(younger.getId())).isTrue();
        assertThat(repository.contains(newest.getId())).isTrue();
        assertThat(repository.size()).isEqualTo(3);
    }

    @Test
    void preLoginSessionThatLoggedInIsNotRemovedForRoom() {
        MemorySessionRepository repository = repository(2);
        MapSession session = preLogin(repository);
        MapSession found = repository.findById(session.getId()).block();
        found.setAttribute(CONTEXT, new SecurityContextImpl());
        repository.save(found).block();
        MapSession other = preLogin(repository);

        MapSession newest = preLogin(repository);

        assertThat(repository.contains(session.getId())).isTrue();
        assertThat(repository.contains(other.getId())).isFalse();
        assertThat(repository.contains(newest.getId())).isTrue();
    }

    @Test
    void storeFullOfAuthenticatedSessionsRefusesANewSession() {
        MemorySessionRepository repository = repository(2);
        MapSession first = authenticated(repository);
        authenticated(repository);
        MapSession refused = repository.createSession().block();

        assertThatThrownBy(() -> repository.save(refused).block()).isInstanceOf(SessionStoreFullException.class);

        assertThat(repository.contains(refused.getId())).isFalse();
        // A known session still saves.
        MapSession found = repository.findById(first.getId()).block();
        found.setAttribute("a", "b");
        repository.save(found).block();
        assertThat(repository.findById(first.getId()).block().<String>getAttribute("a")).isEqualTo("b");
    }

    @Test
    void expiredSessionIsNotFoundAndIsRemoved() {
        MemorySessionRepository repository = repository(10);
        MapSession session = expired(repository);

        assertThat(repository.findById(session.getId()).blockOptional()).isEmpty();
        assertThat(repository.contains(session.getId())).isFalse();
    }

    @Test
    void cleanupRemovesExpiredSessionsAndKeepsTheOthers() {
        MemorySessionRepository repository = repository(10);
        MapSession expired = expired(repository);
        MapSession valid = preLogin(repository);

        repository.removeExpired();

        assertThat(repository.contains(expired.getId())).isFalse();
        assertThat(repository.contains(valid.getId())).isTrue();
    }

    @Test
    void deleteRemovesTheSession() {
        MemorySessionRepository repository = repository(10);
        MapSession session = preLogin(repository);

        repository.deleteById(session.getId()).block();

        assertThat(repository.contains(session.getId())).isFalse();
    }

    @Test
    void cleanupRunsOnItsOwnThreadUntilStop() {
        MemorySessionRepository repository = repository(10);

        repository.start();
        assertThat(repository.isRunning()).isTrue();
        repository.stop();

        assertThat(repository.isRunning()).isFalse();
    }

    @Test
    void maximumNumberOfSessionsMustBePositive() {
        assertThatThrownBy(() -> repository(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("gateway.session.memory.max-sessions must be at least 1");
    }

    @Test
    void aDeletionOfAnUnknownIdRemembersNothing() {
        MemorySessionRepository repository = repository(10);

        for (int i = 0; i < 1000; i++) {
            repository.deleteById("unknown-" + i).block();
        }

        assertThat(repository.deletedIds()).isZero();
    }

    @Test
    void aDeletedSessionIsNotSavedAgain() {
        MemorySessionRepository repository = repository(10);
        MapSession session = preLogin(repository);
        MapSession copy = repository.findById(session.getId()).block();

        repository.deleteById(session.getId()).block();
        repository.save(copy).block();

        assertThat(repository.contains(session.getId())).isFalse();
        assertThat(repository.deletedIds()).isOne();
    }

    @Test
    void theStoreRemembersAtMostTwiceTheMaximumNumberOfDeletedIds() {
        MemorySessionRepository repository = repository(2);

        for (int i = 0; i < 20; i++) {
            repository.deleteById(preLogin(repository).getId()).block();
        }

        assertThat(repository.deletedIds()).isLessThanOrEqualTo(4);
    }

    @Test
    void theStoreForgetsADeletedIdAfterTenMinutes() {
        AtomicLong nanos = new AtomicLong();
        MemorySessionRepository repository = new MemorySessionRepository(new DrbgSessionIdGenerator(), PRE_LOGIN, 10, nanos::get);
        repository.deleteById(preLogin(repository).getId()).block();

        nanos.addAndGet(MemorySessionRepository.DELETED_LIFETIME.toNanos() + 1);

        assertThat(repository.deletedIds()).isZero();
    }

    private static MemorySessionRepository repository(int maxSessions) {
        return new MemorySessionRepository(new DrbgSessionIdGenerator(), PRE_LOGIN, maxSessions);
    }

    private static MapSession preLogin(MemorySessionRepository repository) {
        MapSession session = repository.createSession().block();
        session.setAttribute("saved-request", "/a");
        repository.save(session).block();
        return session;
    }

    private static MapSession authenticated(MemorySessionRepository repository) {
        MapSession session = repository.createSession().block();
        session.setAttribute(CONTEXT, new SecurityContextImpl());
        repository.save(session).block();
        return session;
    }

    private static MapSession expired(MemorySessionRepository repository) {
        MapSession session = repository.createSession().block();
        session.setLastAccessedTime(Instant.now().minus(PRE_LOGIN).minusSeconds(1));
        repository.save(session).block();
        return session;
    }
}
