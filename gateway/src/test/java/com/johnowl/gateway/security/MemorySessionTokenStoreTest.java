package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.session.MapSession;

/** The token state of the store memory (spec 023, "Session store"). */
class MemorySessionTokenStoreTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final MemorySessionRepository sessions =
            new MemorySessionRepository(new DrbgSessionIdGenerator(), Duration.ofMinutes(5), 10);

    private final TestClock clock = new TestClock();

    private final MemorySessionTokenStore store = new MemorySessionTokenStore(sessions, clock);

    @Test
    void aSaveOfAnOlderRequestCopyKeepsTheStoredTokenState() {
        MapSession stored = session();
        MapSession olderCopy = sessions.findById(stored.getId()).block(WAIT);
        OAuth2AuthorizedClient first = entra("refresh-1");
        assertThat(store.save(stored.getId(), first).block(WAIT)).isTrue();
        SessionTokenStore.Stored read = store.load(stored.getId(), "entra").block(WAIT);

        assertThat(store.compareAndSet(stored.getId(), read, entra("refresh-2")).block(WAIT)).isTrue();
        olderCopy.setAttribute("other", "value");
        sessions.save(olderCopy).block(WAIT);

        assertThat(store.load(stored.getId(), "entra").block(WAIT).client().getRefreshToken().getTokenValue())
                .isEqualTo("refresh-2");
        assertThat(sessions.attribute(stored.getId(), "other")).isEqualTo("value");
    }

    @Test
    void compareAndSetDoesNotReplaceANewerValue() {
        MapSession session = session();
        store.save(session.getId(), entra("refresh-1")).block(WAIT);
        SessionTokenStore.Stored read = store.load(session.getId(), "entra").block(WAIT);
        store.save(session.getId(), entra("refresh-newer")).block(WAIT);

        assertThat(store.compareAndSet(session.getId(), read, entra("refresh-older")).block(WAIT)).isFalse();

        assertThat(store.load(session.getId(), "entra").block(WAIT).client().getRefreshToken().getTokenValue())
                .isEqualTo("refresh-newer");
    }

    @Test
    void compareAndRemoveRemovesOnlyTheExpectedValue() {
        MapSession session = session();
        store.save(session.getId(), entra("refresh-1")).block(WAIT);
        SessionTokenStore.Stored read = store.load(session.getId(), "entra").block(WAIT);
        store.save(session.getId(), entra("refresh-newer")).block(WAIT);

        assertThat(store.compareAndRemove(session.getId(), read).block(WAIT)).isFalse();
        assertThat(store.load(session.getId(), "entra").block(WAIT).client().getRefreshToken().getTokenValue())
                .isEqualTo("refresh-newer");

        SessionTokenStore.Stored newest = store.load(session.getId(), "entra").block(WAIT);
        assertThat(store.compareAndRemove(session.getId(), newest).block(WAIT)).isTrue();
        assertThat(store.load(session.getId(), "entra").blockOptional(WAIT)).isEmpty();
    }

    @Test
    void aDeletedSessionIsNotWrittenBack() {
        MapSession session = session();
        MapSession copy = sessions.findById(session.getId()).block(WAIT);

        store.deleteSession(session.getId()).block(WAIT);
        sessions.save(copy).block(WAIT);

        assertThat(sessions.contains(session.getId())).isFalse();
        assertThat(store.save(session.getId(), entra("refresh-1")).block(WAIT)).isFalse();
    }

    @Test
    void tenGrantsPassInTheWindowAndTheEleventhDoesNot() {
        MapSession session = session();
        for (int i = 0; i < SessionTokenStore.GRANT_LIMIT; i++) {
            assertThat(store.countGrant(session.getId()).block(WAIT)).as("grant " + (i + 1)).isTrue();
        }
        assertThat(store.countGrant(session.getId()).block(WAIT)).isFalse();

        clock.advance(SessionTokenStore.GRANT_WINDOW);

        assertThat(store.countGrant(session.getId()).block(WAIT)).isTrue();
    }

    private MapSession session() {
        MapSession session = sessions.createSession().block(WAIT);
        session.setAttribute("x", "y");
        sessions.save(session).block(WAIT);
        return session;
    }

    static OAuth2AuthorizedClient entra(String refreshToken) {
        ClientRegistration registration = AudienceClientRegistrationsTest.entra("bff");
        Instant now = Instant.now();
        return new OAuth2AuthorizedClient(registration, "oid-1",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "graph", now, now.plusSeconds(3600)),
                new OAuth2RefreshToken(refreshToken, now));
    }
}
