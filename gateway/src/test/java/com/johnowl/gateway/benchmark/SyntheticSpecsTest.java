package com.johnowl.gateway.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.route.GatewayRoute;
import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.ValidationReport;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SyntheticSpecsTest {

    @ParameterizedTest
    @ValueSource(ints = {100, 1000, 5000})
    void generatesValidSpecsWithExactRouteCount(int routes) {
        ValidationReport report = new ValidationEngine(BuiltInRules.all())
                .validate(SyntheticSpecs.generate(routes, 42, 8080));

        assertThat(report.hasAtLeast(Severity.ERROR)).as("%s", report.violations()).isFalse();
        assertThat(report.routes()).hasSize(routes);
        long literal = report.routes().stream().filter(r -> r.order() == GatewayRoute.LITERAL_ORDER).count();
        long oneVariable = report.routes().stream().filter(r -> variables(r) == 1).count();
        long twoVariables = report.routes().stream().filter(r -> variables(r) == 2).count();
        // Mix of spec 016: 50% literal, 40% one variable, 10% two variables.
        assertThat(literal).isBetween(routes * 40L / 100, routes * 60L / 100);
        assertThat(oneVariable).isBetween(routes * 30L / 100, routes * 50L / 100);
        assertThat(twoVariables).isBetween(routes * 4L / 100, routes * 16L / 100);
        assertThat(literal + oneVariable + twoVariables).isEqualTo(routes);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 100, 1000, 5000})
    void eachFileHasTenToTwentyPathsAndOnlyTheLastCanHaveFewer(int routes) {
        List<SpecSource> specs = SyntheticSpecs.generate(routes, 42, 8080);
        List<Integer> paths = specs.stream().map(SyntheticSpecsTest::paths).toList();

        assertThat(paths.subList(0, paths.size() - 1)).allSatisfy(count -> assertThat(count).isBetween(10, 20));
        assertThat(paths.getLast()).isBetween(1, 20);
        assertThat(paths.stream().mapToInt(Integer::intValue).sum()).isEqualTo(routes);
    }

    private static long variables(GatewayRoute route) {
        return route.path().chars().filter(c -> c == '{').count();
    }

    private static int paths(SpecSource spec) {
        return (int) spec.content().lines().filter(line -> line.startsWith("  /")).count();
    }

    @ParameterizedTest
    @ValueSource(ints = {100})
    void sameSeedGivesSameSpecs(int routes) {
        assertThat(SyntheticSpecs.generate(routes, 7, 8080)).isEqualTo(SyntheticSpecs.generate(routes, 7, 8080));
        assertThat(SyntheticSpecs.generate(routes, 7, 8080)).isNotEqualTo(SyntheticSpecs.generate(routes, 8, 8080));
    }
}
