package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.web.server.WebSession;
import org.springframework.web.server.session.DefaultWebSessionManager;
import org.springframework.web.server.session.WebSessionManager;

class SessionLifetimeWebFilterTest {

    private static final Instant LOGIN = Instant.parse("2026-11-13T08:00:00Z");

    private static final String CONTEXT = WebSessionServerSecurityContextRepository.DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME;

    private static final WebSessionManager SESSION_MANAGER = new DefaultWebSessionManager();

    @Test
    void startStoresTheLoginTimeAndTheIdleTimeout() {
        WebSession session = session();

        filterAt(LOGIN).start(session);

        assertThat(session.getAttributes()).containsEntry(SessionLifetimeWebFilter.LOGIN_TIME_ATTRIBUTE, LOGIN.toEpochMilli());
        assertThat(session.getMaxIdleTime()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void sessionWithinTheMaximumLifetimeIsValid() {
        WebSession session = authenticated(LOGIN.toEpochMilli());

        assertThat(filterAt(LOGIN.plus(Duration.ofHours(8))).isExpired(session)).isFalse();
    }

    @Test
    void sessionOlderThanTheMaximumLifetimeExpires() {
        WebSession session = authenticated(LOGIN.toEpochMilli());

        assertThat(filterAt(LOGIN.plus(Duration.ofHours(8)).plusMillis(1)).isExpired(session)).isTrue();
    }

    @Test
    void authenticatedSessionWithoutLoginTimeExpires() {
        WebSession session = authenticated(null);

        assertThat(filterAt(LOGIN).isExpired(session)).isTrue();
    }

    @Test
    void loginTimeOfAnotherTypeExpires() {
        WebSession session = authenticated("2026-11-13T08:00:00Z");

        assertThat(filterAt(LOGIN).isExpired(session)).isTrue();
    }

    @Test
    void sessionWithoutUserHasNoMaximumLifetime() {
        assertThat(filterAt(LOGIN).isExpired(session())).isFalse();
    }

    // QA-51-1: the rest of the chain gets a new session, so a saved request is not lost.
    @Test
    void filterInvalidatesAnExpiredSessionAndContinuesWithANewSession() {
        MockServerWebExchange exchange = MockServerWebExchange.builder(MockServerHttpRequest.get("/a"))
                .sessionManager(SESSION_MANAGER).build();
        WebSession session = exchange.getSession().block();
        session.getAttributes().put(CONTEXT, new SecurityContextImpl());
        List<WebSession> seen = new ArrayList<>();

        filterAt(LOGIN).filter(exchange, next -> next.getSession().doOnNext(seen::add).then()).block();

        assertThat(session.isExpired()).isTrue();
        assertThat(session.getAttributes()).isEmpty();
        assertThat(seen).singleElement().satisfies(newSession -> {
            assertThat(newSession.getId()).isNotEqualTo(session.getId());
            assertThat(newSession.getAttributes()).isEmpty();
            assertThat(newSession.isExpired()).isFalse();
        });
    }

    @Test
    void filterKeepsAValidSession() {
        MockServerWebExchange exchange = MockServerWebExchange.builder(MockServerHttpRequest.get("/a"))
                .sessionManager(SESSION_MANAGER).build();
        WebSession session = exchange.getSession().block();
        session.getAttributes().put(CONTEXT, new SecurityContextImpl());
        session.getAttributes().put(SessionLifetimeWebFilter.LOGIN_TIME_ATTRIBUTE, LOGIN.toEpochMilli());
        List<WebSession> seen = new ArrayList<>();

        filterAt(LOGIN.plusSeconds(60)).filter(exchange, next -> next.getSession().doOnNext(seen::add).then()).block();

        assertThat(seen).containsExactly(session);
        assertThat(session.isExpired()).isFalse();
    }

    private static SessionLifetimeWebFilter filterAt(Instant now) {
        return new SessionLifetimeWebFilter(Duration.ofMinutes(30), Duration.ofHours(8), Clock.fixed(now, ZoneOffset.UTC),
                SESSION_MANAGER);
    }

    private static WebSession authenticated(Object loginTime) {
        WebSession session = session();
        session.getAttributes().put(CONTEXT, new SecurityContextImpl());
        if (loginTime != null) {
            session.getAttributes().put(SessionLifetimeWebFilter.LOGIN_TIME_ATTRIBUTE, loginTime);
        }
        return session;
    }

    private static WebSession session() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/a")).getSession().block();
    }
}
