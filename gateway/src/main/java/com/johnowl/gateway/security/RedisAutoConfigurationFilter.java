package com.johnowl.gateway.security;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Turns off the Redis auto-configurations of Spring Boot, unless {@code gateway.session.store} is
 * {@code redis} (spec 022, "Session store"). The Redis libraries are always on the class path. With
 * the store {@code memory}, the gateway then has no Redis connection factory, no Redis session
 * repository, and no Redis health check, and it starts without Redis.
 */
public final class RedisAutoConfigurationFilter implements AutoConfigurationImportFilter, EnvironmentAware {

    private static final String[] REDIS_PACKAGES = {
        "org.springframework.boot.data.redis.",
        "org.springframework.boot.session.data.redis.",
    };

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata) {
        boolean redis = GatewayProperties.SessionStore.REDIS.name()
                .equalsIgnoreCase(environment.getProperty(GatewayProperties.SESSION_STORE, "").trim());
        boolean[] matches = new boolean[autoConfigurationClasses.length];
        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            matches[i] = redis || !isRedis(autoConfigurationClasses[i]);
        }
        return matches;
    }

    private static boolean isRedis(String className) {
        if (className == null) {
            return false;
        }
        for (String prefix : REDIS_PACKAGES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
