package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * The token state of the store redis (spec 023), with the Redis ACL user of spec 022 and short lock
 * values: lock, compare-and-set, write only to an existing session, grant counter, and NOSCRIPT.
 */
class RedisSessionTokenStoreIT {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private static LettuceConnectionFactory connections;

    private static RedisSessionTokenStore store;

    private final SessionJsonSerializer serializer = new SessionJsonSerializer(getClass().getClassLoader());

    private String sessionId;

    private String sessionKey;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(RedisTestContainer.host(), RedisTestContainer.port());
        configuration.setUsername(RedisTestContainer.USER);
        configuration.setPassword(RedisTestContainer.PASSWORD);
        connections = new LettuceConnectionFactory(configuration);
        connections.afterPropertiesSet();
        connections.start();
        RedisSerializationContext<String, byte[]> context = RedisSerializationContext
                .<String, byte[]>newSerializationContext(RedisSerializer.byteArray())
                .key(RedisSerializer.string()).hashKey(RedisSerializer.string()).build();
        store = new RedisSessionTokenStore(new ReactiveRedisTemplate<>(connections, context),
                new SessionJsonSerializer(RedisSessionTokenStoreIT.class.getClassLoader()),
                Duration.ofMillis(500), Duration.ofMillis(20), Duration.ofMillis(300), Schedulers.parallel());
        store.loadScripts().block(WAIT);
    }

    @AfterAll
    static void disconnect() {
        connections.destroy();
    }

    @BeforeEach
    void session() {
        RedisTestContainer.admin().aclLogReset();
        sessionId = UUID.randomUUID().toString();
        sessionKey = RedisSessionTokenStore.SESSION_KEY_PREFIX + sessionId;
        RedisTestContainer.admin().hset(sessionKey, Map.of("creationTime", "1".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void writesOnlyToAnExistingSession() {
        assertThat(store.save(sessionId, MemorySessionTokenStoreTest.entra("refresh-1")).block(WAIT)).isTrue();
        assertThat(store.load(sessionId, "entra").block(WAIT).client().getRefreshToken().getTokenValue()).isEqualTo("refresh-1");

        RedisTestContainer.admin().del(sessionKey);

        assertThat(store.save(sessionId, MemorySessionTokenStoreTest.entra("refresh-2")).block(WAIT)).isFalse();
        assertThat(RedisTestContainer.admin().exists(sessionKey)).isZero();
        assertThat(RedisTestContainer.admin().aclLog()).as("ACL LOG").isEmpty();
    }

    @Test
    void compareAndSetDoesNotReplaceANewerValue() {
        store.save(sessionId, MemorySessionTokenStoreTest.entra("refresh-1")).block(WAIT);
        SessionTokenStore.Stored read = store.load(sessionId, "entra").block(WAIT);
        store.save(sessionId, MemorySessionTokenStoreTest.entra("refresh-newer")).block(WAIT);

        assertThat(store.compareAndSet(sessionId, read, MemorySessionTokenStoreTest.entra("refresh-older")).block(WAIT)).isFalse();
        assertThat(refreshToken()).isEqualTo("refresh-newer");

        SessionTokenStore.Stored newest = store.load(sessionId, "entra").block(WAIT);
        assertThat(store.compareAndSet(sessionId, newest, MemorySessionTokenStoreTest.entra("refresh-3")).block(WAIT)).isTrue();
        assertThat(refreshToken()).isEqualTo("refresh-3");
    }

    @Test
    void compareAndRemoveRemovesOnlyTheExpectedValue() {
        store.save(sessionId, MemorySessionTokenStoreTest.entra("refresh-1")).block(WAIT);
        SessionTokenStore.Stored read = store.load(sessionId, "entra").block(WAIT);
        store.save(sessionId, MemorySessionTokenStoreTest.entra("refresh-newer")).block(WAIT);

        assertThat(store.compareAndRemove(sessionId, read).block(WAIT)).isFalse();
        assertThat(refreshToken()).isEqualTo("refresh-newer");

        SessionTokenStore.Stored newest = store.load(sessionId, "entra").block(WAIT);
        assertThat(store.compareAndRemove(sessionId, newest).block(WAIT)).isTrue();
        assertThat(store.load(sessionId, "entra").blockOptional(WAIT)).isEmpty();
        // The empty value of a removed attribute, as Spring Session writes it.
        assertThat(RedisTestContainer.admin().hget(sessionKey, "sessionAttr:gateway.oauth2.client.entra")).isEmpty();
        assertThat(RedisTestContainer.admin().aclLog()).as("ACL LOG").isEmpty();
    }

    @Test
    void theLockKeyHasTheHashOfTheSessionIdAndIsReleasedAfterTheWork() {
        String key = RedisSessionTokenStore.lockKey(sessionId);
        assertThat(key).startsWith("spring:session:token-locks:").doesNotContain(sessionId);

        // The check uses the blocking admin client: not on the I/O thread of Redis.
        String inside = store.withLock(sessionId, Mono.fromSupplier(() -> RedisTestContainer.admin().exists(key) == 1 ? "held" : "free")
                        .subscribeOn(Schedulers.boundedElastic()))
                .block(WAIT);

        assertThat(inside).isEqualTo("held");
        assertThat(RedisTestContainer.admin().exists(key)).isZero();
        StepVerifier.create(store.withLock(sessionId, Mono.error(new IllegalStateException("work failed"))))
                .expectErrorMessage("work failed").verify(WAIT);
        assertThat(RedisTestContainer.admin().exists(key)).isZero();
    }

    @Test
    void aLockOfAnotherHolderMakesTheWorkWaitAndThenTimeOut() {
        String key = RedisSessionTokenStore.lockKey(sessionId);
        RedisTestContainer.admin().set(key, "other-holder".getBytes(StandardCharsets.UTF_8));

        StepVerifier.create(store.withLock(sessionId, Mono.just("work")))
                .expectError(SessionTokenStore.LockTimeoutException.class)
                .verify(WAIT);

        // The release deletes only its own value.
        assertThat(new String(RedisTestContainer.admin().get(key), StandardCharsets.UTF_8)).isEqualTo("other-holder");
        RedisTestContainer.admin().del(key);
    }

    @Test
    void anExpiredLockIsFreeAgain() {
        RedisSessionTokenStore patient = store(Duration.ofMillis(500), Duration.ofSeconds(1));
        String key = RedisSessionTokenStore.lockKey(sessionId);
        RedisTestContainer.admin().psetex(key, 100, "other-holder".getBytes(StandardCharsets.UTF_8));

        assertThat(patient.withLock(sessionId, Mono.just("work")).block(WAIT)).isEqualTo("work");
    }

    @Test
    void aLockThatTheGatewayTakesAfterTheMaximumWaitIsReleased() {
        // A long lifetime: without the release, the key would stay for 10 seconds.
        RedisSessionTokenStore impatient = store(Duration.ofSeconds(10), Duration.ofMillis(100));
        String key = RedisSessionTokenStore.lockKey(sessionId);
        // Redis runs the SET of the gateway only after the maximum wait ended.
        RedisTestContainer.admin().clientPause(400);

        StepVerifier.create(impatient.withLock(sessionId, Mono.just("work")))
                .expectError(SessionTokenStore.LockTimeoutException.class)
                .verify(WAIT);

        await().atMost(Duration.ofSeconds(3)).until(() -> RedisTestContainer.admin().exists(key) == 0);
    }

    @Test
    void theEleventhGrantInTheWindowIsRefused() {
        for (int i = 0; i < SessionTokenStore.GRANT_LIMIT; i++) {
            assertThat(store.countGrant(sessionId).block(WAIT)).isTrue();
        }
        assertThat(store.countGrant(sessionId).block(WAIT)).isFalse();

        String key = RedisSessionTokenStore.GRANTS_KEY_PREFIX + RedisSessionTokenStore.hash(sessionId);
        assertThat(RedisTestContainer.admin().pttl(key)).isBetween(1L, 60_000L);
        // A refused grant does not count, as with the store memory.
        assertThat(store.countGrant(sessionId).block(WAIT)).isFalse();
        assertThat(new String(RedisTestContainer.admin().get(key), StandardCharsets.UTF_8))
                .isEqualTo(Integer.toString(SessionTokenStore.GRANT_LIMIT));
    }

    @Test
    void loadsTheScriptsAgainAfterNoScript() {
        RedisTestContainer.admin().scriptFlush();

        assertThat(store.save(sessionId, MemorySessionTokenStoreTest.entra("refresh-1")).block(WAIT)).isTrue();
        assertThat(RedisTestContainer.admin().aclLog()).as("ACL LOG").isEmpty();
    }

    private static RedisSessionTokenStore store(Duration lockLifetime, Duration lockMaxWait) {
        RedisSerializationContext<String, byte[]> context = RedisSerializationContext
                .<String, byte[]>newSerializationContext(RedisSerializer.byteArray())
                .key(RedisSerializer.string()).hashKey(RedisSerializer.string()).build();
        return new RedisSessionTokenStore(new ReactiveRedisTemplate<>(connections, context),
                new SessionJsonSerializer(RedisSessionTokenStoreIT.class.getClassLoader()),
                lockLifetime, Duration.ofMillis(20), lockMaxWait, Schedulers.parallel());
    }

    private String refreshToken() {
        OAuth2AuthorizedClient client = (OAuth2AuthorizedClient) serializer.deserialize(
                RedisTestContainer.admin().hget(sessionKey, "sessionAttr:gateway.oauth2.client.entra"));
        return client.getRefreshToken().getTokenValue();
    }
}
