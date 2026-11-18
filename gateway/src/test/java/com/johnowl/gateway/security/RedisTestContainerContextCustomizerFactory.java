package com.johnowl.gateway.security;

import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Gives the address of the Redis container ({@link RedisTestContainer}) to each Spring Boot test
 * context with {@code gateway.session.store=redis}. The gateway connects to Redis at startup in this
 * mode. Tests with the default store {@code memory}, and tests without an application context, do
 * not start the container, so they do not need Docker.
 */
public final class RedisTestContainerContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        return new RedisContextCustomizer();
    }

    /** All instances are equal, so the customizer does not prevent context caching. */
    private static final class RedisContextCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            String store = context.getEnvironment().getProperty(GatewayProperties.SESSION_STORE, "");
            if (!GatewayProperties.SessionStore.REDIS.name().equalsIgnoreCase(store)) {
                return;
            }
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("redisTestContainer", Map.of(
                            "spring.data.redis.host", RedisTestContainer.host(),
                            "spring.data.redis.port", RedisTestContainer.port())));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RedisContextCustomizer;
        }

        @Override
        public int hashCode() {
            return RedisContextCustomizer.class.hashCode();
        }
    }
}
