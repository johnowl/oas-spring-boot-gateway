package com.johnowl.gateway.benchmark;

import com.johnowl.gateway.GatewayApplication;
import com.johnowl.gateway.core.route.GatewayRoute;
import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.validation.ValidationEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.boot.builder.SpringApplicationBuilder;
import com.johnowl.gateway.routing.IndexedRoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.handler.RouteLookupAccess;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * Measures {@code RoutePredicateHandlerMapping.lookupRoute} of Spring Cloud Gateway with routes
 * that the real locator creates from synthetic OAS files (spec 016).
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
public class RouteLookupBenchmark {

    @Param({"100", "1000", "5000"})
    public int routes;

    @Param({"firstMatch", "lastMatch", "notFound"})
    public String scenario;

    /** {@code linear}: Spring Cloud Gateway; {@code indexed}: the trie of spec 017. */
    @Param({"linear", "indexed"})
    public String lookup;

    private ConfigurableApplicationContext context;
    private IndexedRoutePredicateHandlerMapping mapping;
    private BenchmarkRequests request;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        Path directory = SyntheticSpecs.write(routes, 42, 8080, Files.createTempDirectory("routing-benchmark"));
        context = new SpringApplicationBuilder(GatewayApplication.class)
                .properties(
                        "server.port=0",
                        // No actuator: the gateway does not start with port 0 (spec 024, "Management port").
                        "management.server.port=-1",
                        "gateway.openapi.specs=file:" + directory + "/*.yaml",
                        "logging.level.root=WARN")
                .run();
        mapping = context.getBean(IndexedRoutePredicateHandlerMapping.class);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!mapping.isIndexed() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        if (!mapping.isIndexed()) {
            throw new IllegalStateException("The route index was not built");
        }

        List<GatewayRoute> gatewayRoutes = new ValidationEngine(BuiltInRules.contract())
                .validate(SyntheticSpecs.generate(routes, 42, 8080)).routes();
        request = BenchmarkRequests.of(scenario, gatewayRoutes);

        // A broken setup must not produce numbers.
        checkRouteOrder();
        Route found = lookup();
        String foundId = found == null ? null : found.getId();
        if (!Objects.equals(foundId, request.expectedRouteId())) {
            throw new IllegalStateException(
                    "Scenario " + scenario + " expected route " + request.expectedRouteId() + " but got " + foundId);
        }
    }

    /**
     * The expected route of {@code firstMatch} and {@code lastMatch} comes from a separate engine run.
     * Check that it is really the first or the last route of the running gateway, in the order that
     * {@code lookupRoute} evaluates.
     */
    private void checkRouteOrder() {
        List<Route> ordered = context.getBean(RouteLocator.class).getRoutes().collectList().block();
        if (ordered == null || ordered.size() != routes) {
            throw new IllegalStateException(
                    "Expected " + routes + " routes in the gateway but got " + (ordered == null ? 0 : ordered.size()));
        }
        String expectedId = switch (scenario) {
            case "firstMatch" -> ordered.getFirst().getId();
            case "lastMatch" -> ordered.getLast().getId();
            default -> null;
        };
        if (!Objects.equals(expectedId, request.expectedRouteId())) {
            throw new IllegalStateException("Scenario " + scenario + " uses route " + request.expectedRouteId()
                    + ", but the gateway evaluates route " + expectedId + " at that position");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        context.close();
    }

    @Benchmark
    public Route lookupRoute() {
        return lookup();
    }

    /** A new exchange for each call, because predicates write exchange attributes. */
    private Route lookup() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.valueOf(request.method()), request.path()));
        return lookup.equals("indexed")
                ? RouteLookupAccess.lookupRoute(mapping, exchange).block()
                : mapping.linearLookup(exchange).block();
    }
}
