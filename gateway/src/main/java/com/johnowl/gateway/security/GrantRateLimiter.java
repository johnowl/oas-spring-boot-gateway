package com.johnowl.gateway.security;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The limit of refresh token grants for the whole gateway instance (spec 023, "Grant limit for the
 * instance"): a token bucket with {@code grantsPerSecond} tokens that refills in one second. A full bucket
 * allows a burst of {@code grantsPerSecond} grants.
 *
 * <p>The bucket is one {@link AtomicLong}: the time at which the bucket is full again (the generic cell
 * rate algorithm). No thread waits.
 */
final class GrantRateLimiter {

    private static final long SECOND_NANOS = 1_000_000_000L;

    private final long intervalNanos;

    private final Clock clock;

    /** The time at which the bucket is full again, in nanoseconds of the clock. */
    private final AtomicLong fullAt = new AtomicLong(Long.MIN_VALUE);

    /**
     * @param grantsPerSecond {@code gateway.security.token-endpoint.grants-per-second}
     */
    GrantRateLimiter(int grantsPerSecond, Clock clock) {
        if (grantsPerSecond < 1) {
            throw new IllegalStateException("gateway.security.token-endpoint.grants-per-second must be at least 1, but is "
                    + grantsPerSecond + ".");
        }
        this.intervalNanos = SECOND_NANOS / grantsPerSecond;
        this.clock = clock;
    }

    /**
     * Takes one token from the bucket.
     *
     * @return {@code false} if the bucket is empty; the grant must not be sent
     */
    boolean tryAcquire() {
        long now = nanos(clock.instant());
        while (true) {
            long current = fullAt.get();
            long next = Math.max(current, now) + intervalNanos;
            if (next - now > SECOND_NANOS) {
                return false;
            }
            if (fullAt.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    private static long nanos(Instant instant) {
        return instant.getEpochSecond() * SECOND_NANOS + instant.getNano();
    }
}
