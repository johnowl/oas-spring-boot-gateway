package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.RecordingMockBackend;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import reactor.core.scheduler.Schedulers;

/**
 * The maximum wait for the Redis lock of a session (spec 023, "Single-flight redemption"). The test
 * store has a maximum wait of 300 milliseconds, through the package-private constructor of
 * {@link RedisSessionTokenStore}, instead of 10 seconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "gateway.session.store=redis")
class RedisTokenLockWaitIT {

    private static final Duration MAX_WAIT = Duration.ofMillis(300);

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @LocalServerPort
    private int port;

    @Autowired
    private SessionRedemptions redemptions;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.register(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @AfterEach
    void reset() {
        idp.resetRefreshGrants();
    }

    @Test
    void aLockOfAnotherHolderMakesTheCallWaitAndThenGives504() {
        TestBrowser browser = new TestBrowser(port);
        browser.login(idp);
        backend.clear();
        idp.resetRefreshGrants();
        String session = browser.cookie(TestBrowser.SESSION);
        String lock = RedisSessionTokenStore.lockKey(session);
        // The test holds the lock with its own client, longer than the maximum wait.
        RedisTestContainer.admin().psetex(lock, 60_000, "held-by-the-test".getBytes(StandardCharsets.UTF_8));
        try {
            EntityExchangeResult<byte[]> result = browser.fetch(HttpMethod.GET, "/orders-api/orders/42");

            assertThat(result.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8))
                    .contains("\"detail\":\"Gateway timeout.\"");
            assertThat(idp.receivedRefreshGrants()).isZero();
            assertThat(backend.requests()).isEmpty();
            assertThat(redemptions.running()).isZero();
            // The gateway releases only its own lock value, and the session stays valid.
            assertThat(new String(RedisTestContainer.admin().get(lock), StandardCharsets.UTF_8)).isEqualTo("held-by-the-test");
            assertThat(RedisTestContainer.admin().exists(RedisSessionTokenStore.SESSION_KEY_PREFIX + session)).isOne();
        } finally {
            RedisTestContainer.admin().del(lock);
        }

        assertThat(browser.fetch(HttpMethod.GET, "/orders-api/orders/42").getStatus()).isEqualTo(HttpStatus.OK);
    }

    /** The store of the gateway, with a maximum wait for the lock of {@link #MAX_WAIT}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ShortLockWait {

        @Bean
        @Primary
        RedisSessionTokenStore shortLockWaitTokenStore(ReactiveRedisConnectionFactory connectionFactory,
                RedisSerializer<Object> springSessionDefaultRedisSerializer) {
            RedisSerializationContext<String, byte[]> context = RedisSerializationContext
                    .<String, byte[]>newSerializationContext(RedisSerializer.byteArray())
                    .key(RedisSerializer.string())
                    .hashKey(RedisSerializer.string())
                    .build();
            RedisSessionTokenStore store = new RedisSessionTokenStore(new ReactiveRedisTemplate<>(connectionFactory, context),
                    springSessionDefaultRedisSerializer, RedisSessionTokenStore.LOCK_LIFETIME,
                    RedisSessionTokenStore.LOCK_RETRY_INTERVAL, MAX_WAIT, Schedulers.parallel());
            store.loadScripts().block(Duration.ofSeconds(30));
            return store;
        }
    }
}
