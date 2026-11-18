package com.johnowl.gateway.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A clock that a test moves forward. {@link SessionLifetimeWebFilter} reads it for the maximum
 * session lifetime, so a test can pass this limit without waiting. The session store keeps the real
 * time for the pre-login and idle timeouts.
 */
final class TestClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    /** Adds the clock to the application context of a test. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        TestClock testClock() {
            return new TestClock();
        }
    }
}
