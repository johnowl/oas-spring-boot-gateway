package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.GatewayApplication;
import com.johnowl.gateway.routing.RecordingMockBackend;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * Short session timeouts on a real server (spec 022, "Session lifetime"), with the session store
 * {@code memory}. {@link RedisSessionTimeoutsIT} runs the same tests with Redis.
 *
 * <p>The session store uses the real time for the pre-login and idle timeouts. The maximum session
 * lifetime uses {@link TestClock}, so these tests do not wait for it.
 */
@SpringBootTest(classes = {GatewayApplication.class, TestClock.Configuration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "gateway.security.session.pre-login-timeout=2s",
            "gateway.security.session.idle-timeout=3s",
            "gateway.security.session.max-lifetime=5s",
        })
class SessionTimeoutsIT {

    private static final String PROTECTED = "/orders-api/orders/42";

    private static final Duration PAST_THE_MAXIMUM_LIFETIME = Duration.ofSeconds(6);

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @LocalServerPort
    private int port;

    @Autowired
    private TestClock clock;

    @Autowired
    private ReactiveSessionRepository<?> sessions;

    private TestBrowser browser;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.register(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @BeforeEach
    void newBrowser() {
        browser = new TestBrowser(port);
    }

    TestBrowser browser() {
        return browser;
    }

    @Test
    void callbackAfterThePreLoginTimeoutFails() throws InterruptedException {
        URI authorizationRequest = browser.startLogin(idp);
        assertThat(stored(browser.cookie(TestBrowser.SESSION))).isTrue();

        Thread.sleep(2500);
        EntityExchangeResult<byte[]> callback = browser.completeLogin(idp, authorizationRequest);

        assertThat(callback.getStatus()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getResponseHeaders().getLocation()).hasToString("https://gateway.example.com/?login=failed");
        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sessionWithoutRequestsForTheIdleTimeoutExpires() throws InterruptedException {
        browser.login(idp);

        // Longer than the pre-login timeout and shorter than the idle timeout.
        Thread.sleep(2500);
        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.OK);

        Thread.sleep(3500);
        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void activeSessionEndsAfterTheMaximumLifetime() {
        browser.login(idp);
        String session = browser.cookie(TestBrowser.SESSION);

        clock.advance(Duration.ofSeconds(4));
        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.OK);
        clock.advance(Duration.ofMillis(1500));
        assertThat(stored(session)).as("session before the request").isTrue();

        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(stored(session)).as("session after the request").isFalse();
    }

    // QA-51-1: after the gateway ends a session, the request behaves as a request without a session.
    @Test
    void navigationWithAnEndedSessionIsSavedForTheNextLogin() {
        browser.login(idp);
        String oldSession = browser.cookie(TestBrowser.SESSION);
        clock.advance(PAST_THE_MAXIMUM_LIFETIME);

        EntityExchangeResult<byte[]> navigation = browser.navigate(PROTECTED + "?view=full");

        assertThat(navigation.getStatus()).isEqualTo(HttpStatus.FOUND);
        assertThat(navigation.getResponseHeaders().getLocation()).hasToString("/oauth2/authorization/entra");
        assertThat(browser.cookie(TestBrowser.SESSION)).isNotBlank().isNotEqualTo(oldSession);
        assertThat(stored(oldSession)).isFalse();
        assertThat(browser.login(idp).getResponseHeaders().getLocation()).hasToString(PROTECTED + "?view=full");
        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void loginWithAnEndedSessionWorks() {
        browser.login(idp);
        clock.advance(PAST_THE_MAXIMUM_LIFETIME);

        EntityExchangeResult<byte[]> callback = browser.login(idp);

        assertThat(callback.getResponseHeaders().getLocation()).hasToString("/");
        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void fetchWithAnEndedSessionCreatesNoSession() {
        browser.login(idp);
        String oldSession = browser.cookie(TestBrowser.SESSION);
        clock.advance(PAST_THE_MAXIMUM_LIFETIME);

        EntityExchangeResult<byte[]> fetch = browser.fetch(HttpMethod.GET, PROTECTED);

        assertThat(fetch.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(fetch.getResponseCookies().get(TestBrowser.SESSION)).singleElement()
                .satisfies(cookie -> assertThat(cookie.getMaxAge()).isZero());
        assertThat(stored(oldSession)).isFalse();
    }

    /** {@code true} if the session store has a session with this id that has not expired. */
    private boolean stored(String sessionId) {
        assertThat(sessionId).isNotBlank();
        return sessions.findById(sessionId).blockOptional(Duration.ofSeconds(5)).isPresent();
    }
}
