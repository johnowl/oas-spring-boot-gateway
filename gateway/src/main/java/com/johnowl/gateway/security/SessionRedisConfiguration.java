package com.johnowl.gateway.security;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.session.autoconfigure.SessionTimeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.oauth2.client.oidc.authentication.ReactiveOidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoderFactory;
import org.springframework.session.MapSession;
import org.springframework.session.SessionIdGenerator;
import org.springframework.session.config.ReactiveSessionRepositoryCustomizer;
import org.springframework.session.data.redis.ReactiveRedisSessionRepository;
import org.springframework.session.data.redis.RedisSessionMapper;
import org.springframework.session.data.redis.config.ConfigureReactiveRedisAction;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The session store {@code redis} (spec 022, "Session store"): Spring Session with Redis, only when
 * {@code gateway.session.store} is {@code redis}. Spring Boot configures the non-indexed reactive
 * repository ({@link ReactiveRedisSessionRepository}). It needs no keyspace notifications, so it
 * needs no {@code CONFIG} command. The Redis user of the gateway cannot run {@code CONFIG}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = GatewayProperties.SESSION_STORE, havingValue = "redis")
public class SessionRedisConfiguration {

    /**
     * Opens the shared Redis connection at startup, on the main thread. Without this, the first
     * request opens it and waits for the connection on a Reactor thread (found by BlockHound). The
     * gateway therefore does not start if Redis is not reachable.
     */
    @Bean
    static BeanPostProcessor eagerRedisConnection() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof LettuceConnectionFactory factory) {
                    factory.setEagerInitialization(true);
                }
                return bean;
            }
        };
    }

    /** Session ids without I/O on the event loop. */
    @Bean
    SessionIdGenerator sessionIdGenerator() {
        return new DrbgSessionIdGenerator();
    }

    /** Never configure keyspace notifications, also if someone selects the indexed repository. */
    @Bean
    ConfigureReactiveRedisAction configureReactiveRedisAction() {
        return ConfigureReactiveRedisAction.NO_OP;
    }

    /** JSON with a type allowlist, not Java serialization. The bean name is the one that Spring Session uses. */
    @Bean
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new SessionJsonSerializer(SessionRedisConfiguration.class.getClassLoader());
    }

    /** Decodes the ID token with claims that the session serializer reads back. */
    @Bean
    ReactiveJwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        ReactiveOidcIdTokenDecoderFactory factory = new ReactiveOidcIdTokenDecoderFactory();
        factory.setClaimTypeConverterFactory(registration -> new SessionSafeClaims());
        return factory;
    }

    /**
     * The token state of spec 023 in the same Redis. The scripts are loaded once at startup, on the main
     * thread, so the first request does not wait for them.
     */
    @Bean
    RedisSessionTokenStore sessionTokenStore(ReactiveRedisConnectionFactory connectionFactory,
            RedisSerializer<Object> springSessionDefaultRedisSerializer) {
        RedisSerializationContext<String, byte[]> context = RedisSerializationContext
                .<String, byte[]>newSerializationContext(RedisSerializer.byteArray())
                .key(RedisSerializer.string())
                .hashKey(RedisSerializer.string())
                .build();
        RedisSessionTokenStore store = new RedisSessionTokenStore(new ReactiveRedisTemplate<>(connectionFactory, context),
                springSessionDefaultRedisSerializer, RedisSessionTokenStore.LOCK_LIFETIME,
                RedisSessionTokenStore.LOCK_RETRY_INTERVAL, RedisSessionTokenStore.LOCK_MAX_WAIT, Schedulers.parallel());
        store.loadScripts().block(Duration.ofSeconds(30));
        return store;
    }

    /** A new session has the pre-login timeout. The login raises it to the idle timeout. */
    @Bean
    SessionTimeout sessionTimeout(SessionLoginSettings settings) {
        return () -> settings.session().preLoginTimeout();
    }

    /**
     * A session with a value that does not read, or without the session metadata, is treated as
     * missing. The repository then deletes it, and the request continues without a session.
     */
    @Bean
    ReactiveSessionRepositoryCustomizer<ReactiveRedisSessionRepository> unreadableSessionIsMissing() {
        RedisSessionMapper mapper = new RedisSessionMapper();
        return repository -> repository.setRedisSessionMapper((id, values) -> readSession(mapper, id, values));
    }

    /**
     * Reads a session, or nothing if a value does not read. The mapper throws
     * {@link IllegalStateException} for missing metadata and {@link ClassCastException} for metadata
     * of another type, for example a creation time that is a string.
     */
    static Mono<MapSession> readSession(RedisSessionMapper mapper, String id, Map<String, Object> values) {
        if (values.containsValue(SessionJsonSerializer.UNREADABLE)) {
            return Mono.empty();
        }
        return Mono.fromSupplier(() -> mapper.apply(id, values))
                .onErrorResume(RuntimeException.class, e -> Mono.empty());
    }
}
