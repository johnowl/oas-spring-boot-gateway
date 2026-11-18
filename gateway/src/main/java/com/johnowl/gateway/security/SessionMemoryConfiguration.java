package com.johnowl.gateway.security;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.annotation.web.server.EnableSpringWebSession;

/**
 * The session store {@code memory}, the default (spec 022, "Session store"). Spring Session keeps the
 * sessions of this gateway instance in {@link MemorySessionRepository}. The gateway opens no Redis
 * connection: {@link RedisAutoConfigurationFilter} turns off the Redis auto-configurations.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = GatewayProperties.SESSION_STORE, havingValue = "memory", matchIfMissing = true)
@EnableSpringWebSession
public class SessionMemoryConfiguration {

    @Bean
    MemorySessionRepository sessionRepository(GatewayProperties gateway, SessionLoginSettings settings) {
        return new MemorySessionRepository(new DrbgSessionIdGenerator(), settings.session().preLoginTimeout(),
                gateway.session().memory().maxSessions());
    }

    /** The token state of spec 023 in the same store. */
    @Bean
    MemorySessionTokenStore sessionTokenStore(MemorySessionRepository sessionRepository, ObjectProvider<Clock> clock) {
        return new MemorySessionTokenStore(sessionRepository, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    SessionStoreFullWebFilter sessionStoreFullWebFilter() {
        return new SessionStoreFullWebFilter();
    }
}
