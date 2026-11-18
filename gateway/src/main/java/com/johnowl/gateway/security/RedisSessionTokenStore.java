package com.johnowl.gateway.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * The token state of the session store {@code redis} (spec 023, "Session store"). The authorized
 * clients are fields of the session hash of Spring Session, with the JSON of {@link SessionJsonSerializer}.
 *
 * <ul>
 *   <li>A write changes one field, and only if the session key exists: a script compares and writes in
 *       one atomic step. The gateway never creates a session key again after a deletion.
 *   <li>The compare-and-set of the refresh token compares the stored bytes with the bytes of step 5.
 *       The compare-and-delete of an invalid audience token is the same script with the empty value of a
 *       removed attribute.
 *   <li>The lock of a session is {@code SET <key> <value> NX PX <lifetime>}. The gateway retries every
 *       100 milliseconds without blocking a thread, for at most 10 seconds. A script deletes the key only
 *       if it still has the value of this acquisition.
 *   <li>The grant counter is a key with a lifetime of 60 seconds from its first grant. A grant over the
 *       limit does not change it.
 *   <li>When the maximum wait for the lock ends, the gateway also runs the release: a {@code SET} that was
 *       on its way can still take the lock.
 * </ul>
 *
 * <p>The key names have the SHA-256 hash of the session id, never the session id. All keys are in the
 * Spring Session namespace, so the Redis ACL of spec 022 covers them. The gateway loads each script at
 * startup ({@code SCRIPT LOAD}) and calls it with {@code EVALSHA}; after {@code NOSCRIPT} it loads it again.
 * It never sends {@code EVAL}.
 */
final class RedisSessionTokenStore implements SessionTokenStore {

    /** The lifetime of the lock of a session. Longer than the token endpoint timeout of 5 seconds. */
    static final Duration LOCK_LIFETIME = Duration.ofSeconds(10);

    static final Duration LOCK_RETRY_INTERVAL = Duration.ofMillis(100);

    static final Duration LOCK_MAX_WAIT = Duration.ofSeconds(10);

    static final String SESSION_KEY_PREFIX = "spring:session:sessions:";

    static final String LOCK_KEY_PREFIX = "spring:session:token-locks:";

    static final String GRANTS_KEY_PREFIX = "spring:session:token-grants:";

    /** The prefix of an attribute field in the session hash of Spring Session. */
    private static final String ATTRIBUTE_FIELD_PREFIX = "sessionAttr:";

    /** Writes a field only if the session key exists. */
    static final Script SAVE = new Script("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
              return 1
            end
            return 0""");

    /** Writes a field only if its stored value is still the expected value. */
    static final Script COMPARE_AND_SET = new Script("""
            if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then
              redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
              return 1
            end
            return 0""");

    /**
     * Counts a grant and returns 1, or returns 0 without a count if the window already has the limit
     * ({@code ARGV[2]}) of grants. The first grant of a window sets the lifetime of the window.
     */
    static final Script COUNT_GRANT = new Script("""
            if tonumber(redis.call('GET', KEYS[1]) or '0') >= tonumber(ARGV[2]) then
              return 0
            end
            if redis.call('INCR', KEYS[1]) == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return 1""");

    /** Deletes the lock only if it still has the value of this acquisition. */
    static final Script RELEASE = new Script("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0""");

    private static final List<Script> SCRIPTS = List.of(SAVE, COMPARE_AND_SET, COUNT_GRANT, RELEASE);

    /** An empty field value: the value of Spring Session for a removed attribute. */
    private static final byte[] REMOVED = new byte[0];

    private final ReactiveRedisTemplate<String, byte[]> redis;

    private final RedisSerializer<Object> serializer;

    private final Duration lockLifetime;

    private final Duration lockRetryInterval;

    private final Duration lockMaxWait;

    private final Scheduler scheduler;

    /** The first part of each lock value: random for each instance. */
    private final String instance = UUID.randomUUID().toString();

    private final AtomicLong acquisitions = new AtomicLong();

    /**
     * Tests give other lock values and a scheduler with virtual time. Production uses the fixed values.
     */
    RedisSessionTokenStore(ReactiveRedisTemplate<String, byte[]> redis, RedisSerializer<Object> serializer,
            Duration lockLifetime, Duration lockRetryInterval, Duration lockMaxWait, Scheduler scheduler) {
        this.redis = redis;
        this.serializer = serializer;
        this.lockLifetime = lockLifetime;
        this.lockRetryInterval = lockRetryInterval;
        this.lockMaxWait = lockMaxWait;
        this.scheduler = scheduler;
    }

    /** Loads all scripts ({@code SCRIPT LOAD}). The gateway calls it once at startup. */
    Mono<Void> loadScripts() {
        return Flux.fromIterable(SCRIPTS).concatMap(this::load).then();
    }

    @Override
    public Mono<Stored> load(String sessionId, String registrationId) {
        return redis.<String, byte[]>opsForHash().get(sessionKey(sessionId), field(registrationId))
                .mapNotNull(bytes -> serializer.deserialize(bytes) instanceof OAuth2AuthorizedClient client
                        ? new Stored(client, bytes) : null);
    }

    @Override
    public Mono<Boolean> save(String sessionId, OAuth2AuthorizedClient client) {
        String field = field(client.getClientRegistration().getRegistrationId());
        return run(SAVE, sessionKey(sessionId), bytes(field), serializer.serialize(client)).map(result -> result == 1);
    }

    @Override
    public Mono<Void> remove(String sessionId, String registrationId) {
        return run(SAVE, sessionKey(sessionId), bytes(field(registrationId)), REMOVED).then();
    }

    @Override
    public Mono<Boolean> compareAndSet(String sessionId, Stored expected, OAuth2AuthorizedClient client) {
        if (!(expected.version() instanceof byte[] stored)) {
            return Mono.just(false);
        }
        String field = field(client.getClientRegistration().getRegistrationId());
        return run(COMPARE_AND_SET, sessionKey(sessionId), bytes(field), stored, serializer.serialize(client))
                .map(result -> result == 1);
    }

    @Override
    public Mono<Boolean> compareAndRemove(String sessionId, Stored expected) {
        if (!(expected.version() instanceof byte[] stored)) {
            return Mono.just(false);
        }
        String field = field(expected.client().getClientRegistration().getRegistrationId());
        return run(COMPARE_AND_SET, sessionKey(sessionId), bytes(field), stored, REMOVED).map(result -> result == 1);
    }

    @Override
    public Mono<Boolean> countGrant(String sessionId) {
        return run(COUNT_GRANT, GRANTS_KEY_PREFIX + hash(sessionId), bytes(Long.toString(GRANT_WINDOW.toMillis())),
                bytes(Integer.toString(GRANT_LIMIT)))
                .map(counted -> counted == 1);
    }

    @Override
    public Mono<Void> deleteSession(String sessionId) {
        return redis.delete(sessionKey(sessionId)).then();
    }

    @Override
    public <T> Mono<T> withLock(String sessionId, Mono<T> work) {
        String key = lockKey(sessionId);
        return Mono.usingWhen(acquire(key), value -> work,
                value -> release(key, value),
                (value, error) -> release(key, value),
                value -> release(key, value));
    }

    /** The lock key of a session. It has the hash of the session id, not the id. */
    static String lockKey(String sessionId) {
        return LOCK_KEY_PREFIX + hash(sessionId);
    }

    private Mono<String> acquire(String key) {
        String value = instance + ":" + acquisitions.incrementAndGet();
        return Mono.defer(() -> redis.opsForValue().setIfAbsent(key, bytes(value), lockLifetime))
                .filter(Boolean::booleanValue)
                .repeatWhenEmpty(attempts -> attempts.concatMap(attempt -> Mono.delay(lockRetryInterval, scheduler)))
                .timeout(lockMaxWait, scheduler)
                // A SET that was on its way when the wait ended can still take the lock: release it.
                .onErrorResume(TimeoutException.class, e -> release(key, value).then(Mono.error(new LockTimeoutException())))
                .thenReturn(value);
    }

    private Mono<Void> release(String key, String value) {
        return run(RELEASE, key, bytes(value)).then();
    }

    /** Runs a script with {@code EVALSHA}. After {@code NOSCRIPT}, loads it and runs it again. */
    private Mono<Long> run(Script script, String key, byte[]... args) {
        Mono<Long> evalSha = Mono.defer(() -> {
            ByteBuffer[] keysAndArgs = new ByteBuffer[args.length + 1];
            keysAndArgs[0] = ByteBuffer.wrap(bytes(key));
            for (int i = 0; i < args.length; i++) {
                keysAndArgs[i + 1] = ByteBuffer.wrap(args[i]);
            }
            return redis.execute(connection -> connection.scriptingCommands()
                    .<Long>evalSha(script.sha(), ReturnType.INTEGER, 1, keysAndArgs)).next();
        });
        return evalSha.onErrorResume(RedisSessionTokenStore::isNoScript, e -> load(script).then(evalSha));
    }

    private Mono<String> load(Script script) {
        return redis.execute(connection -> connection.scriptingCommands()
                .scriptLoad(ByteBuffer.wrap(bytes(script.source())))).next();
    }

    private static boolean isNoScript(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("NOSCRIPT")) {
                return true;
            }
        }
        return false;
    }

    private static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private static String field(String registrationId) {
        return ATTRIBUTE_FIELD_PREFIX + SessionAuthorizedClientRepository.attribute(registrationId);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** The SHA-256 hash of the session id, hex. */
    static String hash(String sessionId) {
        return HexFormat.of().formatHex(digest("SHA-256", bytes(sessionId)));
    }

    private static byte[] digest(String algorithm, byte[] value) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A Lua script and its SHA-1 hash, the name for {@code EVALSHA}. */
    record Script(String source, String sha) {

        Script(String source) {
            this(source, HexFormat.of().formatHex(digest("SHA-1", bytes(source))));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Script script && script.sha.equals(sha);
        }

        @Override
        public int hashCode() {
            return sha.hashCode();
        }

        @Override
        public String toString() {
            return "Script[" + sha + "]";
        }
    }

    /** {@code true} if both arrays have the same bytes. For the tests. */
    static boolean sameBytes(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
