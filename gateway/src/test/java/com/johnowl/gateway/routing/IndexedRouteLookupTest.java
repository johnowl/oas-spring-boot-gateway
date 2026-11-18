package com.johnowl.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.event.RefreshRoutesResultEvent;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class IndexedRouteLookupTest {

    private final List<Route> routes = new CopyOnWriteArrayList<>();
    private final AtomicInteger routeReads = new AtomicInteger();
    // Sorted like the CachingRouteLocator of Spring Cloud Gateway.
    private final RouteLocator locator = () -> {
        routeReads.incrementAndGet();
        return Flux.fromIterable(routes).sort(AnnotationAwareOrderComparator.INSTANCE);
    };
    private final IndexedRoutePredicateHandlerMapping mapping = mapping(locator, null);

    /** Set to {@code false} in a test that expects a fallback to the linear lookup. */
    private boolean expectNoFallback = true;

    @AfterEach
    void noFallbackToTheLinearLookup() {
        if (expectNoFallback) {
            assertThat(mapping.fallbackCount()).as("fallbacks to the linear lookup").isZero();
        }
    }

    @Test
    void buildKeepsRouteOrderOfUnindexedRoutes() {
        Route a = plain("a", "/a");
        Route b = plain("b", "/b");
        RouteIndex index = RouteIndex.build(List.of(a, oas("x", "/x", "GET"), b));

        assertThat(index.unindexed()).extracting(RouteIndex.Entry::route).containsExactly(a, b);
        assertThat(index.unindexed()).extracting(RouteIndex.Entry::position).containsExactly(0, 2);
        assertThat(index.size()).isEqualTo(3);
        assertThat(index.trie().match(List.of("x"), "GET")).hasValueSatisfying(entry ->
                assertThat(entry.position()).isEqualTo(1));
    }

    @Test
    void buildFailsForTwoRoutesWithTheSamePatternAndMethod() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                RouteIndex.build(List.of(oas("x", "/x/{id}", "GET"), oas("y", "/x/{key}", "GET"))));
    }

    @Test
    void usesTheLinearLookupBeforeTheFirstIndex() {
        routes.add(oas("orders", "/orders/{id}", "GET"));

        assertThat(mapping.index()).isNull();
        assertThat(lookup("GET", "/orders/1")).isEqualTo("orders");
    }

    @Test
    void findsOasRoutesThroughTheTrie() {
        routes.add(oas("me", "/users/me", "GET"));
        routes.add(oas("user", "/users/{id}", "GET", "DELETE"));
        mapping.rebuild().block();

        assertThat(lookup("GET", "/users/me")).isEqualTo("me");
        assertThat(lookup("DELETE", "/users/me")).isEqualTo("user");
        assertThat(lookup("GET", "/users/42")).isEqualTo("user");
        assertThat(lookup("POST", "/users/42")).isNull();
        assertThat(lookup("GET", "/users/42/")).isNull();
        assertThat(lookup("GET", "/unknown")).isNull();
    }

    // SEC-59-1: the only difference from the linear lookup. A backend that ignores case serves /users/me,
    // so the route of /users/{id} must not take /users/ME. The security index denies it in the same way.
    @Test
    void caseVariantOfALiteralGetsNoOasRoute() {
        routes.add(oas("me", "/users/me", "GET"));
        routes.add(oas("user", "/users/{id}", "GET"));
        mapping.rebuild().block();

        assertThat(lookup("GET", "/users/ME")).isNull();
        assertThat(linear("GET", "/users/ME")).isEqualTo("user");
    }

    @Test
    void comparesDecodedSegmentsLikePathPattern() {
        routes.add(oas("orders", "/orders/{id}", "GET"));
        mapping.rebuild().block();

        assertThat(lookup("GET", "/%6Frders/1")).isEqualTo("orders");
        assertThat(lookup("GET", "/%6Frders/1")).isEqualTo(linear("GET", "/%6Frders/1"));
    }

    // PathContainer.parsePath drops the empty segment, so the trie would see [orders, 1]. PathPattern
    // does not match the empty segment, so no OAS route matches, and the lookup must not fall back.
    @ParameterizedTest
    @ValueSource(strings = {"/orders//1", "//orders/1", "/orders/1//", "//"})
    void pathWithAdjacentSeparatorsGetsNoOasRoute(String path) {
        routes.add(oas("orders", "/orders/{id}", "GET"));
        mapping.rebuild().block();

        assertThat(lookup("GET", path)).isNull();
        assertThat(linear("GET", path)).isNull();
    }

    // The Path predicate matches the raw path, which contains the context path. The trie must use the
    // same path, or it selects a candidate whose predicate is false.
    @Test
    void usesTheSamePathAsThePathPredicateWithAContextPath() {
        routes.add(oas("orders", "/orders/{id}", "GET"));
        routes.add(oas("edge-orders", "/edge/orders/{id}", "GET"));
        mapping.rebuild().block();

        assertThat(id(mapping.lookupRoute(withContextPath("/edge", "/edge/orders/1")).block()))
                .isEqualTo("edge-orders");
        assertThat(id(mapping.linearLookup(withContextPath("/edge", "/edge/orders/1")).block()))
                .isEqualTo("edge-orders");
        assertThat(id(mapping.lookupRoute(withContextPath("/edge", "/edge/1")).block())).isNull();
        assertThat(id(mapping.linearLookup(withContextPath("/edge", "/edge/1")).block())).isNull();
    }

    @Test
    void usesTheSamePathAsThePathPredicateWithAForwardedPrefix() {
        routes.add(oas("orders", "/orders/{id}", "GET"));
        mapping.rebuild().block();

        // ForwardedHeaderTransformer adds X-Forwarded-Prefix to the path and sets it as context path.
        assertThat(id(mapping.lookupRoute(forwarded("/proxy", "/orders/1")).block())).isNull();
        assertThat(id(mapping.linearLookup(forwarded("/proxy", "/orders/1")).block())).isNull();
        assertThat(id(mapping.lookupRoute(forwarded("/orders", "/1")).block())).isEqualTo("orders");
        assertThat(id(mapping.linearLookup(forwarded("/orders", "/1")).block())).isEqualTo("orders");
    }

    @Test
    void addsTheBasePathLikeThePathPredicate() {
        WebFluxProperties properties = new WebFluxProperties();
        properties.setBasePath("/base");
        routes.add(oas("orders", "/orders/{id}", properties, "GET"));
        IndexedRoutePredicateHandlerMapping withBasePath = mapping(locator, properties.getBasePath());
        withBasePath.rebuild().block();
        assertThat(id(withBasePath.lookupRoute(withContextPath("/base", "/base/orders/1")).block()))
                .isEqualTo("orders");
        assertThat(id(withBasePath.linearLookup(withContextPath("/base", "/base/orders/1")).block()))
                .isEqualTo("orders");
        assertThat(withBasePath.fallbackCount()).isZero();
    }

    @Test
    void countsTheFallbackToTheLinearLookup() {
        expectNoFallback = false;
        // The metadata says /orders/{id}, but the predicate matches only /orders/1: the index is wrong.
        routes.add(withPredicateOf(oas("orders", "/orders/{id}", "GET"), "/orders/1"));
        routes.add(oas("other", "/other", "GET"));
        mapping.rebuild().block();

        assertThat(lookup("GET", "/orders/1")).isEqualTo("orders");
        assertThat(lookup("GET", "/orders/2")).isNull();
        assertThat(lookup("GET", "/orders/3")).isNull();

        assertThat(mapping.fallbackCount()).isEqualTo(2);
    }

    @Test
    void storesTheUriTemplateVariablesForFilters() {
        routes.add(oas("orders", "/orders/{id}", "GET"));
        mapping.rebuild().block();
        ServerWebExchange exchange = exchange("GET", "/orders/42");

        Route route = mapping.lookupRoute(exchange).block();

        assertThat(route).isNotNull();
        assertThat(ServerWebExchangeUtils.getUriTemplateVariables(exchange)).containsEntry("id", "42");
    }

    @Test
    void unindexedRouteBeforeTheCandidateWins() {
        // Route order: plain routes (order 0) come before OAS template routes (order 100).
        routes.add(oas("oas", "/orders/{id}", "GET"));
        routes.add(plain("hand-written", "/orders/special"));
        mapping.rebuild().block();

        assertThat(lookup("GET", "/orders/special")).isEqualTo("hand-written");
        assertThat(lookup("GET", "/orders/special")).isEqualTo(linear("GET", "/orders/special"));
        assertThat(lookup("GET", "/orders/1")).isEqualTo("oas");
    }

    @Test
    void unindexedRouteAfterTheCandidateLoses() {
        routes.add(plain("last-resort", "/**", 1000));
        routes.add(oas("orders", "/orders/{id}", "GET"));
        routes.add(oas("export", "/orders/export", "GET"));
        mapping.rebuild().block();

        for (String path : List.of("/orders/1", "/orders/export")) {
            assertThat(lookup("GET", path)).isEqualTo(linear("GET", path)).isNotEqualTo("last-resort");
        }
        assertThat(lookup("POST", "/orders/1")).isEqualTo("last-resort");
        assertThat(lookup("GET", "/unknown")).isEqualTo("last-resort");
        assertThat(lookup("GET", "/unknown")).isEqualTo(linear("GET", "/unknown"));
    }

    @Test
    void unindexedRoutesWithEqualOrderKeepTheLocatorPosition() {
        routes.add(oas("export", "/orders/export", "GET"));
        routes.add(plain("hand-written", "/orders/export"));
        mapping.rebuild().block();

        // Both have order 0. The stable sort of the locator keeps the OAS route first.
        assertThat(lookup("GET", "/orders/export")).isEqualTo("export");
        assertThat(lookup("GET", "/orders/export")).isEqualTo(linear("GET", "/orders/export"));
    }

    @Test
    void refreshAddsNewRoutes() {
        routes.add(oas("orders", "/orders", "GET"));
        mapping.rebuild().block();
        assertThat(lookup("GET", "/payments")).isNull();

        routes.add(oas("payments", "/payments", "GET"));
        mapping.rebuild().block();

        assertThat(lookup("GET", "/payments")).isEqualTo("payments");
    }

    @Test
    void failedRebuildKeepsThePreviousIndex() {
        routes.add(oas("orders", "/orders/{id}", "GET"));
        mapping.rebuild().block();
        RouteIndex previous = mapping.index();

        routes.add(oas("duplicate", "/orders/{key}", "GET"));
        mapping.rebuild().block();

        assertThat(mapping.index()).isSameAs(previous);
        assertThat(lookup("GET", "/orders/1")).isEqualTo("orders");
    }

    @Test
    void failedRefreshEventDoesNotChangeTheIndex() {
        routes.add(oas("orders", "/orders", "GET"));
        mapping.rebuild().block();
        RouteIndex previous = mapping.index();
        routes.add(oas("payments", "/payments", "GET"));
        int readsBefore = routeReads.get();

        mapping.onApplicationEvent(new RefreshRoutesResultEvent(this, new IllegalStateException("refresh failed")));

        // A rebuild reads the routes when it starts, during the event call.
        assertThat(routeReads.get()).isEqualTo(readsBefore);
        assertThat(mapping.index()).isSameAs(previous);
        assertThat(lookup("GET", "/payments")).isNull();
    }

    @Test
    void anOlderRebuildThatFinishesLastDoesNotReplaceTheNewerIndex() {
        Sinks.One<List<Route>> olderSnapshot = Sinks.one();
        Deque<Flux<Route>> snapshots = new ArrayDeque<>(List.of(
                olderSnapshot.asMono().flatMapIterable(list -> list),
                Flux.just(oas("orders", "/orders", "GET"), oas("payments", "/payments", "GET"))));
        IndexedRoutePredicateHandlerMapping overlapping = mapping(snapshots::removeFirst, null);

        CompletableFuture<Void> older = overlapping.rebuild().toFuture();
        overlapping.rebuild().block();
        RouteIndex newer = overlapping.index();
        olderSnapshot.tryEmitValue(List.of(oas("orders", "/orders", "GET"))).orThrow();
        older.join();

        assertThat(overlapping.index()).isSameAs(newer);
        assertThat(id(overlapping.lookupRoute(exchange("GET", "/payments")).block())).isEqualTo("payments");
    }

    @Test
    void segmentsAreDecodedAndATrailingSlashHasNoSegments() {
        assertThat(IndexedRoutePredicateHandlerMapping.segments(PathContainer.parsePath("/a/b%20c")))
                .containsExactly("a", "b c");
        assertThat(IndexedRoutePredicateHandlerMapping.segments(PathContainer.parsePath("/a;p=1/b")))
                .containsExactly("a", "b");
        assertThat(IndexedRoutePredicateHandlerMapping.segments(PathContainer.parsePath("/"))).isEmpty();
        assertThat(IndexedRoutePredicateHandlerMapping.segments(PathContainer.parsePath("/a/"))).isNull();
        assertThat(IndexedRoutePredicateHandlerMapping.segments(PathContainer.parsePath("/a//b"))).isNull();
        assertThat(IndexedRoutePredicateHandlerMapping.segments(PathContainer.parsePath("//a/b"))).isNull();
    }

    private String lookup(String method, String path) {
        return id(mapping.lookupRoute(exchange(method, path)).block());
    }

    private String linear(String method, String path) {
        return id(mapping.linearLookup(exchange(method, path)).block());
    }

    private static String id(Route route) {
        return route == null ? null : route.getId();
    }

    private static IndexedRoutePredicateHandlerMapping mapping(RouteLocator locator, String basePath) {
        return new IndexedRoutePredicateHandlerMapping(new FilteringWebHandler(List.of(), false),
                locator, new GlobalCorsProperties(), new MockEnvironment(), basePath);
    }

    private static ServerWebExchange exchange(String method, String path) {
        // A ready URI: a URI template would encode the '%' of an encoded path again.
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.valueOf(method), URI.create("http://localhost" + path)));
    }

    private static ServerWebExchange withContextPath(String contextPath, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).contextPath(contextPath));
    }

    private static ServerWebExchange forwarded(String prefix, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(path).header("X-Forwarded-Prefix", prefix));
        ServerHttpRequest request = new ForwardedHeaderTransformer().apply(exchange.getRequest());
        return exchange.mutate().request(request).build();
    }

    /** A route as the OAS locator creates it: Path and Method predicates, OAS metadata, and order. */
    private static Route oas(String id, String pattern, String... methods) {
        return oas(id, pattern, new WebFluxProperties(), methods);
    }

    private static Route oas(String id, String pattern, WebFluxProperties properties, String... methods) {
        PathRoutePredicateFactory.Config path = new PathRoutePredicateFactory.Config().setPatterns(List.of(pattern));
        MethodRoutePredicateFactory.Config method = new MethodRoutePredicateFactory.Config();
        method.setMethods(Arrays.stream(methods).map(HttpMethod::valueOf).toArray(HttpMethod[]::new));
        return Route.async()
                .id(id)
                .uri(URI.create("http://localhost:9"))
                .order(pattern.contains("{") ? 100 : 0)
                .asyncPredicate(new PathRoutePredicateFactory(properties).applyAsync(path)
                        .and(new MethodRoutePredicateFactory().applyAsync(method)))
                .metadata(Map.of(RouteDefinitionMapper.OAS_PATH, pattern,
                        RouteDefinitionMapper.OAS_METHODS, new ArrayList<>(List.of(methods))))
                .build();
    }

    /** The same route with the Path predicate of another pattern, so that the metadata is wrong. */
    private static Route withPredicateOf(Route route, String pattern) {
        PathRoutePredicateFactory.Config path = new PathRoutePredicateFactory.Config().setPatterns(List.of(pattern));
        return Route.async()
                .id(route.getId())
                .uri(route.getUri())
                .order(route.getOrder())
                .asyncPredicate(new PathRoutePredicateFactory(new WebFluxProperties()).applyAsync(path))
                .metadata(route.getMetadata())
                .build();
    }

    /** A route without OAS metadata, for example from application.yaml. */
    private static Route plain(String id, String pattern) {
        return plain(id, pattern, 0);
    }

    private static Route plain(String id, String pattern, int order) {
        PathRoutePredicateFactory.Config path = new PathRoutePredicateFactory.Config().setPatterns(List.of(pattern));
        return Route.async()
                .id(id)
                .uri(URI.create("http://localhost:9"))
                .order(order)
                .asyncPredicate(new PathRoutePredicateFactory(new WebFluxProperties()).applyAsync(path))
                .build();
    }
}
