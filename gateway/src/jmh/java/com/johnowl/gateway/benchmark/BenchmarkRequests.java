package com.johnowl.gateway.benchmark;

import com.johnowl.gateway.core.route.GatewayRoute;
import java.util.Comparator;
import java.util.List;

/** The request of each benchmark scenario, from the routes in gateway route order. */
record BenchmarkRequests(String method, String path, String expectedRouteId) {

    static final String NOT_FOUND_PATH = "/unknown-api/does/not/exist";

    /** Route order of the gateway: order, then registration order (the sorted route list). */
    static BenchmarkRequests of(String scenario, List<GatewayRoute> routes) {
        List<GatewayRoute> ordered = routes.stream().sorted(Comparator.comparingInt(GatewayRoute::order)).toList();
        return switch (scenario) {
            case "firstMatch" -> request(ordered.getFirst());
            case "lastMatch" -> request(ordered.getLast());
            case "notFound" -> new BenchmarkRequests("GET", NOT_FOUND_PATH, null);
            default -> throw new IllegalArgumentException(scenario);
        };
    }

    private static BenchmarkRequests request(GatewayRoute route) {
        String path = route.path().replaceAll("\\{[^}]+}", "x42");
        return new BenchmarkRequests(route.methods().iterator().next(), path, route.id());
    }
}
