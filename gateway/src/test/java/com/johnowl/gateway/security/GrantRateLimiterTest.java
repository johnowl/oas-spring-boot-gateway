package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The grant limit of the instance (spec 023, "Grant limit for the instance"). */
class GrantRateLimiterTest {

    private final TestClock clock = new TestClock();

    @Test
    void aFullBucketAllowsOneSecondOfGrantsAndThenRefills() {
        GrantRateLimiter limiter = new GrantRateLimiter(50, clock);

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryAcquire()).as("grant " + (i + 1)).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();

        clock.advance(Duration.ofMillis(20));
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        clock.advance(Duration.ofSeconds(1));
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void theLimitMustBeAtLeastOne() {
        assertThatIllegalStateException().isThrownBy(() -> new GrantRateLimiter(0, clock))
                .withMessage("gateway.security.token-endpoint.grants-per-second must be at least 1, but is 0.");
    }
}
