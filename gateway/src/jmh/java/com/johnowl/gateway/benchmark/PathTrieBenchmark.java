package com.johnowl.gateway.benchmark;

import com.johnowl.gateway.core.route.GatewayRoute;
import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.trie.PathTrie;
import com.johnowl.gateway.core.validation.ValidationEngine;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import org.openjdk.jmh.annotations.Warmup;

/** Measures {@code PathTrie.match} with the route sets of {@link RouteLookupBenchmark}. */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
public class PathTrieBenchmark {

    @Param({"100", "1000", "5000"})
    public int routes;

    @Param({"firstMatch", "lastMatch", "notFound"})
    public String scenario;

    private PathTrie<GatewayRoute> trie;
    private BenchmarkRequests request;

    @Setup(Level.Trial)
    public void setUp() {
        List<GatewayRoute> gatewayRoutes = new ValidationEngine(BuiltInRules.contract())
                .validate(SyntheticSpecs.generate(routes, 42, 8080)).routes();
        trie = new PathTrie<>();
        gatewayRoutes.forEach(route -> trie.insert(route.path(), route.methods(), route));
        request = BenchmarkRequests.of(scenario, gatewayRoutes);

        String foundId = match().map(GatewayRoute::id).orElse(null);
        if (!Objects.equals(foundId, request.expectedRouteId())) {
            throw new IllegalStateException(
                    "Scenario " + scenario + " expected route " + request.expectedRouteId() + " but got " + foundId);
        }
    }

    @Benchmark
    public Optional<GatewayRoute> match() {
        // The path split is part of the cost of a lookup.
        return trie.match(PathTrie.segments(request.path()), request.method());
    }
}
