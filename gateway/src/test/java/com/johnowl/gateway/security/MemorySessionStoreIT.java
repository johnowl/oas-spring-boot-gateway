package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.RecordingMockBackend;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * The session store {@code memory} with a limit of two sessions (spec 022, "Session store"). The
 * test needs no Redis and no Docker. Each test starts with an empty store.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.session.memory.max-sessions=2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MemorySessionStoreIT {

    private static final String PROTECTED = "/orders-api/orders/42";

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ReactiveSessionRepository<?> sessions;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.register(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @Test
    void gatewayRunsWithoutRedis() {
        assertThat(sessions).isInstanceOf(MemorySessionRepository.class);
        assertThat(context.getBeanNamesForType(RedisConnectionFactory.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ReactiveRedisConnectionFactory.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SessionRedisConfiguration.class)).isEmpty();
    }

    @Test
    void fullStoreRemovesTheOldestPreLoginSession() {
        TestBrowser first = navigating();
        TestBrowser second = navigating();

        TestBrowser third = navigating();

        MemorySessionRepository store = (MemorySessionRepository) sessions;
        assertThat(store.contains(first.cookie(TestBrowser.SESSION))).isFalse();
        assertThat(store.contains(second.cookie(TestBrowser.SESSION))).isTrue();
        assertThat(store.contains(third.cookie(TestBrowser.SESSION))).isTrue();
        // The removed session behaves as an expired session: a new login works.
        first.login(idp);
        assertThat(first.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void storeFullOfAuthenticatedSessionsRefusesNewSessionsWith503() {
        TestBrowser first = loggedIn();
        loggedIn();
        TestBrowser newcomer = new TestBrowser(port);

        EntityExchangeResult<byte[]> navigation = newcomer.navigate(PROTECTED);
        EntityExchangeResult<byte[]> loginStart = newcomer.navigate("/oauth2/authorization/entra");

        for (EntityExchangeResult<byte[]> refused : List.of(navigation, loginStart)) {
            assertThat(refused.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(refused.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(new String(refused.getResponseBody(), StandardCharsets.UTF_8)).isEqualTo(
                    "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"Try again later.\"}");
            assertThat(refused.getResponseHeaders().getLocation()).isNull();
            assertThat(refused.getResponseCookies()).doesNotContainKey(TestBrowser.SESSION);
        }
        // Requests that need no new session still work.
        assertThat(newcomer.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(newcomer.navigate("/orders-api/health").getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(first.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.OK);

        // A logout makes room.
        assertThat(first.fetch(HttpMethod.POST, "/logout", first.cookie(TestBrowser.XSRF)).getStatus())
                .isEqualTo(HttpStatus.FOUND);
        assertThat(newcomer.navigate(PROTECTED).getStatus()).isEqualTo(HttpStatus.FOUND);
    }

    private TestBrowser navigating() {
        TestBrowser browser = new TestBrowser(port);
        assertThat(browser.navigate(PROTECTED).getStatus()).isEqualTo(HttpStatus.FOUND);
        assertThat(browser.cookie(TestBrowser.SESSION)).isNotBlank();
        return browser;
    }

    private TestBrowser loggedIn() {
        TestBrowser browser = new TestBrowser(port);
        browser.login(idp);
        return browser;
    }
}
