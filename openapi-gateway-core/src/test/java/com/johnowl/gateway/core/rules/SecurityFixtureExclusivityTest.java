package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.spec.SpecFinder;
import com.johnowl.gateway.core.spec.SpecLoadResult;
import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.Violation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spec 018: each row of the "Behavior and errors" table produces exactly the listed rule id. {@link RuleFixtureTest}
 * runs each rule alone, so it cannot see a second rule on the same input. This test runs all CONTRACT rules on the
 * fixtures of the spec 018 rules.
 */
class SecurityFixtureExclusivityTest {

    private final SpecLoader loader = new SpecLoader();

    @ParameterizedTest
    @ValueSource(strings = {
        "security-explicit",
        "security-no-scopes",
        "security-no-empty-requirement",
        "security-scheme-exists",
        "security-scheme-type",
        "authorization-required",
        "authorization-schema",
        "authorization-not-on-public",
        "permission-format",
        "audience-required",
        "audience-not-first-party"
    })
    void invalidFixtureGivesOnlyItsOwnRuleAndValidFixtureGivesNone(String ruleId) throws IOException {
        Path directory = RuleFixtures.root().resolve(ruleId);

        assertThat(run(directory.resolve("invalid.yaml"))).isNotEmpty()
                .extracting(Violation::ruleId).containsOnly(ruleId);
        assertThat(run(directory.resolve("valid.yaml"))).isEmpty();
    }

    @Test
    void invalidAudienceUniqueDirectoryGivesOnlyItsOwnRuleAndValidDirectoryGivesNone() throws IOException {
        // audience-unique is a CrossSpecRule: its fixtures are directories with several files.
        Path directory = RuleFixtures.root().resolve("audience-unique");

        assertThat(run(new SpecFinder().find(List.of(directory.resolve("invalid")))))
                .extracting(Violation::ruleId, violation -> Path.of(violation.file()).getFileName().toString())
                .containsExactly(tuple("audience-unique", "orders.yaml"), tuple("audience-unique", "payments.yaml"));
        assertThat(run(new SpecFinder().find(List.of(directory.resolve("valid"))))).isEmpty();
    }

    private List<Violation> run(Path file) throws IOException {
        return run(List.of(file));
    }

    private List<Violation> run(List<Path> files) throws IOException {
        List<SpecLoadResult> loaded = new ArrayList<>();
        for (Path file : files) {
            loaded.add(loader.load(file));
        }
        return new ValidationEngine(BuiltInRules.contract())
                .validateLoaded(loaded)
                .violations();
    }
}
