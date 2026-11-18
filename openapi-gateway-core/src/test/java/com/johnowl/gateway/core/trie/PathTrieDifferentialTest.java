package com.johnowl.gateway.core.trie;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Compares {@link PathTrie} with a linear matcher that uses the route order of the gateway: literal
 * patterns first, then patterns with variables, each in registration order. The generator skips
 * two cases: duplicates (insert rejects them) and two patterns with variables and a common method
 * that can match the same request (an open question of spec 015).
 */
class PathTrieDifferentialTest {

    private static final long SEED = 20261112L;
    private static final List<String> LITERALS = List.of("a", "b", "c");
    private static final List<String> REQUEST_SEGMENTS = List.of("a", "b", "c", "d", "");
    private static final List<String> METHODS = List.of("GET", "POST", "DELETE");

    @Test
    void trieAndLinearMatcherAgree() {
        Random random = new Random(SEED);
        int requests = 0;
        for (int set = 0; set < 100; set++) {
            List<Route> routes = randomRoutes(random);
            PathTrie<Route> trie = new PathTrie<>();
            routes.forEach(route -> trie.insert(route.pattern(), Set.of(route.method()), route));
            for (int i = 0; i < 200; i++, requests++) {
                List<String> segments = randomSegments(random, REQUEST_SEGMENTS, 0, 4);
                String method = METHODS.get(random.nextInt(METHODS.size()));
                assertThat(trie.match(segments, method))
                        .as("seed %d, set %d, request %s %s, routes %s", SEED, set, method, segments, routes)
                        .isEqualTo(linear(routes, segments, method));
            }
        }
        assertThat(requests).isGreaterThanOrEqualTo(10_000);
    }

    @Test
    void concurrentMatchesGetTheExpectedResults() throws Exception {
        PathTrie<String> trie = new PathTrie<>();
        trie.insert("/users/me", Set.of("GET"), "me");
        trie.insert("/users/{id}", Set.of("GET", "DELETE"), "id");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                results.add(executor.submit(() -> {
                    for (int i = 0; i < 10_000; i++) {
                        if (!trie.match(List.of("users", "me"), "GET").equals(Optional.of("me"))
                                || !trie.match(List.of("users", "me"), "DELETE").equals(Optional.of("id"))) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<Route> randomRoutes(Random random) {
        List<Route> routes = new ArrayList<>();
        for (int attempt = 0; attempt < 30; attempt++) {
            List<String> segments = new ArrayList<>();
            int length = 1 + random.nextInt(3);
            for (int i = 0; i < length; i++) {
                segments.add(random.nextInt(3) == 0 ? "{v" + i + "}" : LITERALS.get(random.nextInt(LITERALS.size())));
            }
            Route candidate = new Route("/" + String.join("/", segments), METHODS.get(random.nextInt(METHODS.size())), routes.size());
            if (routes.stream().noneMatch(existing -> conflicts(existing, candidate))) {
                routes.add(candidate);
            }
        }
        return routes;
    }

    private static boolean conflicts(Route a, Route b) {
        if (!a.method().equals(b.method())) {
            return false;
        }
        List<String> x = PathTrie.segments(a.pattern());
        List<String> y = PathTrie.segments(b.pattern());
        if (x.size() != y.size()) {
            return false;
        }
        boolean same = true;
        for (int i = 0; i < x.size(); i++) {
            boolean xv = x.get(i).startsWith("{");
            boolean yv = y.get(i).startsWith("{");
            if (xv != yv || (!xv && !x.get(i).equals(y.get(i)))) {
                same = false;
            }
            if (!xv && !yv && !x.get(i).equals(y.get(i))) {
                return false; // different literals: never match the same request
            }
        }
        // Same normalized pattern (duplicate), or two templates that overlap.
        return same || (a.hasVariable() && b.hasVariable());
    }

    private static Optional<Route> linear(List<Route> routes, List<String> segments, String method) {
        return routes.stream()
                .sorted(Comparator.comparingInt(Route::order).thenComparingInt(Route::index))
                .filter(route -> route.method().equals(method) && route.matches(segments))
                .findFirst();
    }

    private static List<String> randomSegments(Random random, List<String> alphabet, int min, int max) {
        int length = min + random.nextInt(max - min + 1);
        List<String> segments = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            segments.add(alphabet.get(random.nextInt(alphabet.size())));
        }
        return segments;
    }

    private record Route(String pattern, String method, int index) {

        boolean hasVariable() {
            return pattern.contains("{");
        }

        int order() {
            return hasVariable() ? 100 : 0;
        }

        boolean matches(List<String> request) {
            List<String> pattern = PathTrie.segments(this.pattern);
            if (pattern.size() != request.size()) {
                return false;
            }
            for (int i = 0; i < pattern.size(); i++) {
                boolean variable = pattern.get(i).startsWith("{");
                // A Spring PathPattern variable does not match an empty segment.
                if (variable ? request.get(i).isEmpty() : !pattern.get(i).equals(request.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }
}
