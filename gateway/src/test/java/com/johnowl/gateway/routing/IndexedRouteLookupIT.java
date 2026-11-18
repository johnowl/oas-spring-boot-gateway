package com.johnowl.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.benchmark.SyntheticSpecs;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;
import reactor.core.publisher.Mono;

/** The indexed lookup in the running gateway, compared with the linear lookup (spec 017). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IndexedRouteLookupIT {

    private static final long SEED = 20261112L;
    private static final List<String> METHODS =
            List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    private static final String YAML_ROUTE = "yaml-svc-1x";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private RouteDefinitionWriter routeDefinitionWriter;

    @Autowired
    private MeterRegistry meterRegistry;

    private IndexedRoutePredicateHandlerMapping mapping;

    @DynamicPropertySource
    static void syntheticSpecs(DynamicPropertyRegistry registry) {
        try {
            Path directory = SyntheticSpecs.write(1000, SEED, 9, Files.createTempDirectory("indexed-lookup-it"));
            registry.add("gateway.openapi.specs", () -> "file:" + directory + "/*.yaml");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // A route without OAS metadata, as in application.yaml. Its order 50 is between the order of
        // OAS literal routes (0) and OAS template routes (100).
        String route = "spring.cloud.gateway.server.webflux.routes[0].";
        registry.add(route + "id", () -> YAML_ROUTE);
        registry.add(route + "uri", () -> "http://localhost:9");
        registry.add(route + "predicates[0]", () -> "Path=/svc-1*-api/**");
        registry.add(route + "order", () -> "50");
    }

    /** Allows the route without OAS metadata (spec 024, "Only OAS routes"). */
    @TestConfiguration(proxyBeanMethods = false)
    static class YamlRouteAllowed {

        @Bean
        TestRouteIds yamlRouteId() {
            return new TestRouteIds() {
                @Override
                public Set<String> routeIds() {
                    return Set.of(YAML_ROUTE);
                }
            };
        }
    }

    @BeforeEach
    void awaitIndex() throws InterruptedException {
        mapping = context.getBean(IndexedRoutePredicateHandlerMapping.class);
        RouteIndexAwait.awaitIndex(mapping);
    }

    @Test
    void theOnlyHandlerMappingIsTheIndexedOne() {
        assertThat(context.getBeansOfType(RoutePredicateHandlerMapping.class).values())
                .singleElement()
                .isInstanceOf(IndexedRoutePredicateHandlerMapping.class);
    }

    @Test
    void fallbacksAreAMetric() {
        assertThat(meterRegistry.get("gateway.route.index.fallback").functionCounter().count())
                .isEqualTo(mapping.fallbackCount());
    }

    @Test
    void indexedAndLinearLookupsAgreeOnRandomRequests() {
        List<String> patterns = new ArrayList<>();
        for (Route route : routeLocator.getRoutes().collectList().block()) {
            Object pattern = route.getMetadata().get(RouteDefinitionMapper.OAS_PATH);
            if (pattern instanceof String value) {
                patterns.add(value);
            }
        }
        assertThat(patterns).hasSizeGreaterThanOrEqualTo(1000);
        long fallbacksBefore = mapping.fallbackCount();

        Random random = new Random(SEED);
        Map<String, Integer> variations = new LinkedHashMap<>();
        int matched = 0;
        int yamlRoute = 0;
        for (int i = 0; i < 10_000; i++) {
            String method = METHODS.get(random.nextInt(METHODS.size()));
            String path = randomPath(random, patterns);
            Request request = vary(random, method, path);
            variations.merge(request.variation(), 1, Integer::sum);
            String indexed = id(mapping.lookupRoute(request.exchange()).block());
            String linear = id(mapping.linearLookup(request.exchange()).block());
            assertThat(indexed).as("seed %d, request %d: %s", SEED, i, request).isEqualTo(linear);
            matched += indexed == null ? 0 : 1;
            yamlRoute += YAML_ROUTE.equals(indexed) ? 1 : 0;
        }
        // A wrong trie candidate falls back to the linear lookup and hides the error. So no fallback
        // is allowed.
        assertThat(mapping.fallbackCount()).as("fallbacks to the linear lookup").isEqualTo(fallbacksBefore);
        // The generator must exercise both outcomes, the unindexed route, and each variation.
        assertThat(matched).isBetween(1_000, 9_000);
        assertThat(yamlRoute).isPositive();
        assertThat(variations).containsOnlyKeys(
                "plain", "encoded", "matrix", "trailing-slash", "double-slash", "context-path", "forwarded-prefix");
    }

    @Test
    void refreshRoutesEventAfterARouteChangeRebuildsTheIndex() throws InterruptedException {
        RouteIndex previous = mapping.index();
        assertThat(lookup("GET", "/refresh-it/items/7")).isNull();

        routeDefinitionWriter.save(Mono.just(oasRouteDefinition())).block();
        context.publishEvent(new RefreshRoutesEvent(this));

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (mapping.index() == previous && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(mapping.index()).as("index after the refresh").isNotSameAs(previous);
        assertThat(mapping.index().trie().match(List.of("refresh-it", "items", "7"), "GET")).isPresent();
        long fallbacksBefore = mapping.fallbackCount();
        assertThat(lookup("GET", "/refresh-it/items/7")).isEqualTo("refresh-it");
        assertThat(mapping.fallbackCount()).isEqualTo(fallbacksBefore);
    }

    /** A route definition as the OAS locator creates it. */
    private static RouteDefinition oasRouteDefinition() {
        RouteDefinition definition = new RouteDefinition();
        definition.setId("refresh-it");
        definition.setUri(URI.create("http://localhost:9"));
        definition.setOrder(100);
        definition.setPredicates(List.of(
                new PredicateDefinition("Path=/refresh-it/items/{id}"), new PredicateDefinition("Method=GET")));
        definition.setMetadata(Map.of(
                RouteDefinitionMapper.OAS_PATH, "/refresh-it/items/{id}",
                RouteDefinitionMapper.OAS_METHODS, List.of("GET")));
        return definition;
    }

    private String lookup(String method, String path) {
        return id(mapping.lookupRoute(exchange(method, path)).block());
    }

    /** A documented path with random variable values, or a path that no route has. */
    private static String randomPath(Random random, List<String> patterns) {
        String pattern = patterns.get(random.nextInt(patterns.size()));
        return switch (random.nextInt(4)) {
            case 0 -> "/svc-" + random.nextInt(200) + "-api/unknown-" + random.nextInt(50);
            case 1 -> pattern.replaceAll("\\{[^}]+}", "v" + random.nextInt(100)) + "/extra";
            default -> pattern.replaceAll("\\{[^}]+}", random.nextBoolean() ? "summary" : "v" + random.nextInt(100));
        };
    }

    /** One request, with a variation of the path that the trie and the Path predicate must treat alike. */
    private static Request vary(Random random, String method, String path) {
        List<Integer> separators = new ArrayList<>();
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                separators.add(i);
            }
        }
        int separator = separators.get(random.nextInt(separators.size()));
        String firstSegment = path.substring(0, separators.size() > 1 ? separators.get(1) : path.length());
        return switch (random.nextInt(12)) {
            case 0 -> {
                // Percent-encode one character, for example /svc-1-api -> /%73vc-1-api.
                int at = separator + 1 < path.length() ? separator + 1 : 1;
                String encoded = path.substring(0, at) + "%" + Integer.toHexString(path.charAt(at)).toUpperCase()
                        + path.substring(at + 1);
                yield new Request("encoded", method, encoded, null, null);
            }
            case 1 -> {
                int end = path.indexOf('/', separator + 1);
                String withMatrix = end < 0 ? path + ";p=1" : path.substring(0, end) + ";p=1" + path.substring(end);
                yield new Request("matrix", method, withMatrix, null, null);
            }
            case 2 -> new Request("trailing-slash", method, path + "/", null, null);
            case 3 -> new Request("double-slash", method,
                    path.substring(0, separator) + "/" + path.substring(separator), null, null);
            case 4 -> new Request("context-path", method, path, firstSegment, null);
            case 5 -> random.nextBoolean()
                    ? new Request("forwarded-prefix", method, path.substring(firstSegment.length()), null, firstSegment)
                    : new Request("forwarded-prefix", method, path, null, "/edge");
            default -> new Request("plain", method, path, null, null);
        };
    }

    /**
     * A request for the differential test.
     *
     * @param contextPath the context path, or {@code null}
     * @param forwardedPrefix the value of {@code X-Forwarded-Prefix}, or {@code null}
     */
    private record Request(String variation, String method, String path, String contextPath,
            String forwardedPrefix) {

        /** A new exchange for each lookup, as for a new request. */
        ServerWebExchange exchange() {
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest
                    .method(HttpMethod.valueOf(method), URI.create("http://localhost" + (path.isEmpty() ? "/" : path)));
            if (contextPath != null) {
                builder.contextPath(contextPath);
            }
            if (forwardedPrefix == null) {
                return MockServerWebExchange.from(builder);
            }
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    builder.header("X-Forwarded-Prefix", forwardedPrefix));
            return exchange.mutate().request(new ForwardedHeaderTransformer().apply(exchange.getRequest())).build();
        }
    }

    private static MockServerWebExchange exchange(String method, String path) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.valueOf(method), URI.create("http://localhost" + path)));
    }

    private static String id(Route route) {
        return route == null ? null : route.getId();
    }
}
