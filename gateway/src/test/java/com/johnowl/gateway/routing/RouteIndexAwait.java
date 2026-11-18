package com.johnowl.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

/** Waits for the route index that the gateway builds after the first route load (spec 017). */
final class RouteIndexAwait {

    private RouteIndexAwait() {
    }

    /** Waits up to 10 seconds. Call it from the test thread, never from an event loop thread. */
    static void awaitIndex(IndexedRoutePredicateHandlerMapping mapping) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!mapping.isIndexed() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(mapping.isIndexed()).as("route index built at startup").isTrue();
    }
}
