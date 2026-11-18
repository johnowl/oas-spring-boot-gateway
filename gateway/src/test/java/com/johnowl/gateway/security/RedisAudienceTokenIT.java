package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.johnowl.gateway.FreePort;
import com.johnowl.gateway.GatewayApplication;
import com.johnowl.gateway.Loopback;
import com.johnowl.gateway.OwnNettyResources;
import com.johnowl.gateway.routing.RecordingMockBackend;
import com.johnowl.gateway.security.MockIdentityProvider.RefreshAnswer;
import com.johnowl.gateway.security.MockIdentityProvider.RefreshGrant;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The per-audience tokens of spec 023 with the session store {@code redis} and the Redis ACL user of
 * spec 022, through the authorization manager of spec 024.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "gateway.session.store=redis")
class RedisAudienceTokenIT {

    private static final String ORDERS = SessionTestSpecs.AUDIENCE;

    private static final String PAYMENTS = SessionTestSpecs.PAYMENTS_AUDIENCE;

    private static final String ORDER = "/orders-api/orders/42";

    private static final String PAYMENT = "/payments-api/payments/7";

    private static final String ENTRA_FIELD = "sessionAttr:" + SessionAuthorizedClientRepository.attribute("entra");

    private static final String ORDERS_FIELD = "sessionAttr:" + SessionAuthorizedClientRepository.attribute("entra-" + ORDERS);

    private static final String PAYMENTS_FIELD = "sessionAttr:" + SessionAuthorizedClientRepository.attribute("entra-" + PAYMENTS);

    private static final String SECOND_USER = "00000000-0000-0000-0000-0000000000b2";

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    private final SessionJsonSerializer serializer = new SessionJsonSerializer(getClass().getClassLoader());

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private SessionRedemptions redemptions;

    @Autowired
    private RedisSerializer<Object> springSessionDefaultRedisSerializer;

    private TestBrowser browser;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.registerWithPayments(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @BeforeEach
    void login() {
        idp.resetRefreshGrants();
        browser = new TestBrowser(port);
        browser.login(idp);
        backend.clear();
        RedisTestContainer.admin().aclLogReset();
    }

    @AfterEach
    void reset() {
        idp.resetRefreshGrants();
    }

    @Test
    void theAudienceClientInRedisHasNoRefreshTokenAndNoLockRemains() {
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);

        assertThat(idp.refreshGrants()).hasSize(1);
        assertThat(stored(ORDERS_FIELD).getRefreshToken()).isNull();
        assertThat(stored(ENTRA_FIELD).getRefreshToken().getTokenValue()).isEqualTo(idp.refreshGrants().getFirst().refreshToken());
        assertThat(new String(RedisTestContainer.admin().hget(sessionKey(), ORDERS_FIELD), StandardCharsets.UTF_8))
                .doesNotContain(MockIdentityProvider.CLIENT_SECRET);
        assertThat(RedisTestContainer.admin().exists(RedisSessionTokenStore.lockKey(sessionId()))).isZero();
        assertThat(redemptions.running()).isZero();
        assertThat(RedisTestContainer.admin().aclLog()).as("ACL LOG").isEmpty();
    }

    @Test
    void aVersion1TokenIsNotStoredButTheRotatedRefreshTokenIs() {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.token(MockIdentityProvider.AudienceTokenDefect.VERSION_1));

        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(RedisTestContainer.admin().hget(sessionKey(), ORDERS_FIELD)).isNull();
        assertThat(stored(ENTRA_FIELD).getRefreshToken().getTokenValue()).isEqualTo(idp.refreshGrants().getFirst().refreshToken());
        assertThat(RedisTestContainer.admin().exists(sessionKey())).isOne();
    }

    // Step 7 accepts the token; the permission resolver rejects it and removes it under the lock of the session.
    @Test
    void aStoredTokenWithAMalformedRolesClaimIsRemoved() {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.token(MockIdentityProvider.AudienceTokenDefect.ROLES_STRING));

        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(idp.refreshGrants()).hasSize(1);
        assertThat(storedOrNull(ORDERS_FIELD)).isNull();
        assertThat(RedisTestContainer.admin().exists(RedisSessionTokenStore.lockKey(sessionId()))).isZero();
        assertThat(RedisTestContainer.admin().exists(sessionKey())).isOne();
    }

    @Test
    void noLockRemainsAfterAServerErrorOfTheTokenEndpoint() {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(503, Map.of()));

        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);

        assertThat(RedisTestContainer.admin().exists(RedisSessionTokenStore.lockKey(sessionId()))).isZero();
        assertThat(RedisTestContainer.admin().exists(sessionKey())).isOne();
        assertThat(redemptions.running()).isZero();
    }

    @Test
    void noLockRemainsAfterInvalidGrant() {
        String session = sessionId();
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, Map.of("error", "invalid_grant")));

        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(RedisTestContainer.admin().exists(RedisSessionTokenStore.lockKey(session))).isZero();
        assertThat(RedisTestContainer.admin().exists(RedisSessionTokenStore.SESSION_KEY_PREFIX + session)).isZero();
        assertThat(redemptions.running()).isZero();
    }

    // QA-60-3: an HTML navigation also ends the session in Redis, and goes to the login.
    @Test
    void invalidGrantOfAnHtmlNavigationRedirectsToTheLoginAndRemovesTheSessionKey() {
        String session = sessionId();
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, Map.of("error", "invalid_grant")));

        EntityExchangeResult<byte[]> result = browser.navigate(ORDER);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.FOUND);
        assertThat(result.getResponseHeaders().getLocation()).hasToString(SessionAuthenticationEntryPoint.LOGIN_PATH);
        assertThat(RedisTestContainer.admin().exists(RedisSessionTokenStore.SESSION_KEY_PREFIX + session)).isZero();
        assertThat(RedisTestContainer.admin().exists(RedisSessionTokenStore.lockKey(session))).isZero();
    }

    @Test
    void aLoginOfAnotherUserInTheSameSessionRemovesTheTokensOfTheFirstUser() {
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(stored(ORDERS_FIELD).getPrincipalName()).isEqualTo(MockIdentityProvider.USER_OID);

        browser.login(idp, SECOND_USER);

        assertThat(storedOrNull(ORDERS_FIELD)).isNull();
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(idp.refreshGrants()).hasSize(2);
        assertThat(idp.refreshGrants().get(1).form().get("refresh_token")).isEqualTo(idp.issued().getLast().refreshToken());
        assertThat(stored(ORDERS_FIELD).getPrincipalName()).isEqualTo(SECOND_USER);
    }

    @Test
    void theGrantUsesTheTokenUriOfTheConfiguredRegistrationNotOfTheSession() {
        AtomicInteger otherRequests = new AtomicInteger();
        DisposableServer other = HttpServer.create().host(Loopback.ADDRESS).port(0)
                .handle((request, response) -> {
                    otherRequests.incrementAndGet();
                    return response.status(500).send();
                })
                .bindNow();
        try {
            String entra = new String(RedisTestContainer.admin().hget(sessionKey(), ENTRA_FIELD), StandardCharsets.UTF_8);
            assertThat(entra).contains(idp.tokenEndpoint());
            RedisTestContainer.admin().hset(sessionKey(), Map.of(ENTRA_FIELD, entra.replace(idp.tokenEndpoint(),
                    "http://127.0.0.1:" + other.port() + "/token").getBytes(StandardCharsets.UTF_8)));

            assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);

            assertThat(idp.refreshGrants()).hasSize(1);
            assertThat(otherRequests).hasValue(0);
        } finally {
            other.disposeNow();
        }
    }

    @Test
    void aRedemptionDoesNotOverwriteAnEntraClientThatChangedInTheMeantime() {
        idp.delayRefreshGrants(Duration.ofMillis(500));
        Mono<Integer> call = call(port, ORDER).cache();
        call.subscribe();
        await().atMost(Duration.ofSeconds(5)).until(() -> idp.receivedRefreshGrants() == 1);

        byte[] newer = serializer.serialize(MemorySessionTokenStoreTest.entra("refresh-written-by-another-instance"));
        RedisTestContainer.admin().hset(sessionKey(), Map.of(ENTRA_FIELD, newer));

        assertThat(call.block(Duration.ofSeconds(10))).isEqualTo(200);
        assertThat(stored(ENTRA_FIELD).getRefreshToken().getTokenValue()).isEqualTo("refresh-written-by-another-instance");
    }

    @Test
    void anInvalidatedSessionIsNotWrittenBackByAParallelRequest() {
        idp.delayRefreshGrants(Duration.ofMillis(300));
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, Map.of("error", "invalid_grant")));
        // The public page answers after 1 second (RecordingMockBackend), so this request saves the session last.
        Mono<Integer> slow = call(port, "/?slow=1").cache();
        slow.subscribe();

        assertThat(call(port, ORDER).block(Duration.ofSeconds(10))).isEqualTo(401);
        slow.onErrorResume(e -> Mono.empty()).block(Duration.ofSeconds(10));

        assertThat(RedisTestContainer.admin().exists(sessionKey())).isZero();
    }

    // A parallel request ended the session before this request read it: 401, no grant, and no defect.
    @Test
    void aRequestOfASessionThatAParallelRequestEndedGets401() {
        assertThat(RedisTestContainer.admin().del(sessionKey())).isOne();

        assertThat(call(port, ORDER).block(Duration.ofSeconds(10))).isEqualTo(401);

        assertThat(idp.receivedRefreshGrants()).isZero();
        assertThat(RedisTestContainer.admin().exists(sessionKey())).isZero();
    }

    // CI run 43712577572: two parallel requests of one session read the session in Redis at the same time, and
    // the read of one waited for the lock of the Jackson deserializer cache on the event loop of Redis. BlockHound
    // then ended that request with 500. The test holds the lock of the serializer of the gateway.
    @Test
    void aSessionReadThatWaitsForTheLockOfTheDeserializerCacheGetsTheResponseOfTheSpec() throws Exception {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, Map.of("error", "invalid_grant")));
        ReentrantLock lock = SessionJsonSerializerTest.deserializerCacheLock((SessionJsonSerializer) springSessionDefaultRedisSerializer);
        CompletableFuture<Integer> status;

        lock.lock();
        try {
            status = call(port, ORDER).toFuture();
            await().atMost(Duration.ofSeconds(10)).until(() -> lock.hasQueuedThreads() || status.isDone());
            assertThat(status).as("the session read waits for the lock").isNotDone();
        } finally {
            lock.unlock();
        }

        assertThat(status.get(10, TimeUnit.SECONDS)).isEqualTo(401);
        assertThat(RedisTestContainer.admin().exists(sessionKey())).isZero();
    }

    @Test
    void aSecondInstanceWaitsForTheLockAndUsesTheTokenOfTheFirstInstance() {
        idp.delayRefreshGrants(Duration.ofSeconds(1));
        try (ConfigurableApplicationContext second = secondInstance()) {
            int secondPort = ((WebServerApplicationContext) second).getWebServer().getPort();
            Mono<Integer> first = call(port, ORDER).cache();
            first.subscribe();
            await().atMost(Duration.ofSeconds(5)).until(() -> idp.receivedRefreshGrants() == 1);

            Integer secondStatus = call(secondPort, ORDER).block(Duration.ofSeconds(15));

            assertThat(first.block(Duration.ofSeconds(10))).isEqualTo(200);
            assertThat(secondStatus).isEqualTo(200);
            assertThat(idp.refreshGrants()).hasSize(1);
            assertThat(backend.requests()).hasSize(2);
        }
        assertThat(RedisTestContainer.admin().aclLog()).as("ACL LOG").isEmpty();
    }

    @Test
    void parallelFirstCallsForTwoAudiencesCauseTwoGrantsOneAfterTheOther() {
        // The delay makes an overlap visible. The test does not wait for it.
        idp.delayRefreshGrants(Duration.ofMillis(300));

        List<Integer> statuses = Flux.merge(call(port, ORDER), call(port, PAYMENT)).collectList().block(Duration.ofSeconds(10));

        assertThat(statuses).containsOnly(200);
        List<RefreshGrant> grants = idp.refreshGrants();
        assertThat(grants).hasSize(2);
        assertThat(grants.get(1).received()).isAfterOrEqualTo(grants.get(0).answered());
        assertThat(grants.get(1).form().get("refresh_token")).isEqualTo(grants.get(0).refreshToken());
        assertThat(stored(ORDERS_FIELD).getAccessToken()).isNotNull();
        assertThat(stored(PAYMENTS_FIELD).getAccessToken()).isNotNull();
        assertThat(stored(ENTRA_FIELD).getRefreshToken().getTokenValue()).isEqualTo(grants.get(1).refreshToken());
    }

    @Test
    void aDisconnectedClientDoesNotStopTheRedemption() {
        idp.holdRefreshGrants(true);
        Disposable client = call(port, ORDER).subscribe();
        await().atMost(Duration.ofSeconds(5)).until(() -> idp.receivedRefreshGrants() == 1);

        // The client disconnects while the token endpoint holds the grant. Then the grant completes.
        client.dispose();
        idp.releaseRefreshGrant(0);

        await().atMost(Duration.ofSeconds(5)).until(() -> redemptions.running() == 0 && idp.refreshGrants().size() == 1);
        String rotated = idp.refreshGrants().getFirst().refreshToken();
        await().atMost(Duration.ofSeconds(5)).until(() -> storedOrNull(ORDERS_FIELD) != null
                && rotated.equals(stored(ENTRA_FIELD).getRefreshToken().getTokenValue()));
        assertThat(idp.receivedRefreshGrants()).isOne();
        assertThat(RedisTestContainer.admin().exists(RedisSessionTokenStore.lockKey(sessionId()))).isZero();
    }

    // The lock of instance A ends early (here: the test deletes it), and instance B sends a grant with the same
    // refresh token. The first compare-and-set wins. The other grant read the same value, which is no longer the
    // stored value, so it does not write its older refresh token.
    @Test
    void aGrantAfterALostLockDoesNotWriteAnOlderRefreshToken() {
        try (ConfigurableApplicationContext second = secondInstance()) {
            int secondPort = ((WebServerApplicationContext) second).getWebServer().getPort();
            String login = stored(ENTRA_FIELD).getRefreshToken().getTokenValue();
            idp.holdRefreshGrants(true);
            Mono<Integer> first = call(port, ORDER).cache();
            first.subscribe();
            await().atMost(Duration.ofSeconds(5)).until(() -> idp.receivedRefreshGrants() == 1);
            assertThat(RedisTestContainer.admin().del(RedisSessionTokenStore.lockKey(sessionId()))).isOne();

            Mono<Integer> later = call(secondPort, PAYMENT).cache();
            later.subscribe();
            await().atMost(Duration.ofSeconds(5)).until(() -> idp.receivedRefreshGrants() == 2);
            // The grant of instance B answers first, and its compare-and-set writes its refresh token.
            idp.releaseRefreshGrant(1);
            assertThat(later.block(Duration.ofSeconds(10))).isEqualTo(200);
            idp.releaseRefreshGrant(0);
            assertThat(first.block(Duration.ofSeconds(10))).isEqualTo(200);

            RefreshGrant grantOfA = idp.refreshGrants(ORDERS).getFirst();
            RefreshGrant grantOfB = idp.refreshGrants(PAYMENTS).getFirst();
            assertThat(grantOfA.form().get("refresh_token")).isEqualTo(login);
            assertThat(grantOfB.form().get("refresh_token")).isEqualTo(login);
            assertThat(stored(ENTRA_FIELD).getRefreshToken().getTokenValue())
                    .isEqualTo(grantOfB.refreshToken())
                    .isNotEqualTo(grantOfA.refreshToken());
            assertThat(stored(ORDERS_FIELD).getAccessToken()).isNotNull();
            assertThat(stored(PAYMENTS_FIELD).getAccessToken()).isNotNull();
            assertThat(RedisTestContainer.admin().exists(RedisSessionTokenStore.lockKey(sessionId()))).isZero();
        }
        assertThat(RedisTestContainer.admin().aclLog()).as("ACL LOG").isEmpty();
    }

    /** A second gateway instance on the same Redis, with the same OAS files. */
    private ConfigurableApplicationContext secondInstance() {
        return new SpringApplicationBuilder(GatewayApplication.class,
                OwnNettyResources.class,
                TestLocalIdentityProvider.class).run(
                "--server.port=0",
                "--management.server.port=" + FreePort.next(),
                Loopback.serverAddressArguments()[0],
                Loopback.serverAddressArguments()[1],
                "--gateway.session.store=redis",
                "--gateway.openapi.specs=" + environment.getProperty("gateway.openapi.specs"),
                "--spring.data.redis.host=" + environment.getProperty("spring.data.redis.host"),
                "--spring.data.redis.port=" + environment.getProperty("spring.data.redis.port"),
                "--spring.data.redis.username=" + RedisTestContainer.USER,
                "--spring.data.redis.password=" + RedisTestContainer.PASSWORD);
    }

    private String sessionId() {
        return browser.cookie(TestBrowser.SESSION);
    }

    private String sessionKey() {
        return RedisSessionTokenStore.SESSION_KEY_PREFIX + sessionId();
    }

    private OAuth2AuthorizedClient stored(String field) {
        return (OAuth2AuthorizedClient) serializer.deserialize(RedisTestContainer.admin().hget(sessionKey(), field));
    }

    /** The client of the field, or {@code null} without a value. Spring Session writes an empty value for a removed attribute. */
    private OAuth2AuthorizedClient storedOrNull(String field) {
        byte[] value = RedisTestContainer.admin().hget(sessionKey(), field);
        return value == null || value.length == 0 ? null : stored(field);
    }

    private Mono<Integer> call(int gatewayPort, String path) {
        return WebClient.create(Loopback.url(gatewayPort)).get().uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .cookie(TestBrowser.SESSION, sessionId())
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()));
    }
}
