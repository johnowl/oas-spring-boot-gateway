package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code baseline create} after the removal of the permission catalog (2026-11-13). */
class BaselineCommandTest {

    private static final String PROTECTED = "src/test/resources/cli/protected";

    @Test
    void protectedOperationsNeedNoCatalog() throws Exception {
        Cli.Result result = Cli.run(new ValidateCommand(), "baseline", "create", PROTECTED);

        assertThat(result.code()).isZero();
        assertThat(result.err()).isEmpty();
        ObjectMapper json = new ObjectMapper();
        assertThat(json.readTree(result.out())).isEqualTo(json.readTree("""
                {"version":1,"entries":[]}
                """));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--catalog=permissions.yaml", "--entra"})
    void catalogOptionsAreUnknown(String option) {
        Cli.Result result = Cli.run(new ValidateCommand(), "baseline", "create", option, PROTECTED);

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("Unknown option: '" + option + "'");
    }

    /** validate shows at most 100 violations of a rule in a file. A baseline must contain all of them (spec 005). */
    @Test
    void baselineCreateIsNotLimited() throws Exception {
        SpecRule many = new SpecRule() {
            @Override
            public RuleMeta meta() {
                return new RuleMeta("many-rule", Category.DESIGN, Severity.WARN, true, "Many.", "Many.");
            }

            @Override
            public void check(SpecContext context, Reporter reporter) {
                for (int i = 0; i < 150; i++) {
                    reporter.report("/x/" + i, "Problem " + i + ".");
                }
            }
        };

        Cli.Result validate = Cli.run(new ValidateCommand(List.of(many)), PROTECTED);
        Cli.Result baseline = Cli.run(new ValidateCommand(List.of(many)), "baseline", "create", PROTECTED);

        assertThat(validate.out()).contains("50 more violations of this rule in this file are not reported.");
        assertThat(validate.out().lines().filter(line -> line.contains("many-rule"))).hasSize(101);
        assertThat(baseline.code()).isZero();
        assertThat(new ObjectMapper().readTree(baseline.out()).get("entries")).hasSize(150);
    }
}
