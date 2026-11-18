package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.spec.SpecFinder;
import com.johnowl.gateway.core.spec.SpecLoadResult;
import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs each built-in rule alone on its {@code valid} and {@code invalid} fixtures. See
 * {@link RuleFixtures} for the fixture format.
 */
class RuleFixtureTest {

    private final SpecLoader loader = new SpecLoader();

    static Stream<Named<Rule>> rules() {
        return BuiltInRules.all().stream().map(rule -> Named.of(rule.meta().id(), rule));
    }

    @ParameterizedTest
    @MethodSource("rules")
    void validFixtureHasNoViolation(Rule rule) throws IOException {
        List<Violation> violations = run(rule, "valid");

        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("rules")
    void invalidFixtureHasExpectedViolations(Rule rule) throws IOException {
        Path directory = RuleFixtures.root().resolve(rule.meta().id());
        Path invalid = directory.resolve("invalid");
        boolean multiFile = Files.isDirectory(invalid);

        List<Violation> violations = run(rule, "invalid");

        assertThat(violations).isNotEmpty().extracting(Violation::ruleId).containsOnly(rule.meta().id());
        assertThat(violations).extracting(Violation::message)
                .noneMatch(message -> message.startsWith("Rule failed:"));
        if (multiFile) {
            // One entry for each violation: two files with the same name are two entries.
            Function<Violation, String> entry = v ->
                    invalid.relativize(Path.of(v.file())).toString().replace('\\', '/') + "#" + v.pointer();
            assertThat(violations.stream().map(entry).sorted().toList())
                    .isEqualTo(RuleFixtures.expectedEntries(directory).stream().sorted().toList());
        } else {
            assertThat(violations.stream().map(Violation::pointer).collect(Collectors.toSet()))
                    .isEqualTo(RuleFixtures.expected(directory));
        }
    }

    /**
     * Runs the engine with only this rule. The engine loads the files with the full loader, so a
     * fixture that does not parse gives {@code oas-parse} violations, and the test fails.
     */
    private List<Violation> run(Rule rule, String fixture) throws IOException {
        Path directory = RuleFixtures.root().resolve(rule.meta().id());
        List<Path> files = Files.isDirectory(directory.resolve(fixture))
                ? new SpecFinder().find(List.of(directory.resolve(fixture)))
                : List.of(directory.resolve(fixture + ".yaml"));
        List<SpecLoadResult> loaded = new ArrayList<>();
        for (Path file : files) {
            loaded.add(loader.load(file));
        }
        return RuleRunner.runLoaded(rule, loaded);
    }
}
