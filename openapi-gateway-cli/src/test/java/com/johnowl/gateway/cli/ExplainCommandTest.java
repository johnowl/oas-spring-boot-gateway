package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.validation.Rule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The subcommand {@code explain} (spec 032). */
class ExplainCommandTest {

    @Test
    void explainsABestPracticeRule() throws IOException {
        Cli.Result result = Cli.run(new ValidateCommand(), "explain", "operation-id-camel-case");

        assertThat(result.code()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).isEqualTo(RulesCommandTest.expected("explain-operation-id-camel-case.txt"));
    }

    @Test
    void contractRuleIsNotConfigurable() throws IOException {
        Cli.Result result = Cli.run(new ValidateCommand(), "explain", "security-explicit");

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo(RulesCommandTest.expected("explain-security-explicit.txt"));
        assertThat(result.out()).contains("  Configurable:     no\n");
    }

    @Test
    void showsTheOptionsAndTheirDefaults() {
        Cli.Result result = Cli.run(new ValidateCommand(), "explain", "error-response-problem-json");

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("""

                Options
                  media-types (string-list), default: [application/problem+json]

                Bad example
                """);
    }

    @Test
    void noOptionsSectionWithoutOptions() {
        assertThat(Cli.run(new ValidateCommand(), "explain", "path-no-verbs").out()).doesNotContain("Options");
    }

    @ParameterizedTest
    @MethodSource("builtInRules")
    void explainsEachBuiltInRule(Rule rule) {
        Cli.Result result = Cli.run(new ValidateCommand(), "explain", rule.meta().id());

        assertThat(result.code()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out())
                .startsWith(rule.meta().id() + "\n  Category:         " + rule.meta().category().name() + "\n")
                .contains("\nDescription\n  " + rule.meta().description() + "\n")
                .contains("\nRationale\n  " + rule.meta().rationale() + "\n")
                .contains("\nBad example\n  ", "\nGood example\n  ");
    }

    static Iterable<Rule> builtInRules() {
        return BuiltInRules.all();
    }

    @Test
    void unknownRuleIdReturnsTwoWithTheHint() {
        Cli.Result result = Cli.run(new ValidateCommand(), "explain", "unknown-rule");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo(
                "Unknown rule id 'unknown-rule'. Run 'oas-gateway-validate rules' to list rule ids.\n");
    }

    @Test
    void unknownRuleIdIsEscaped() {
        Cli.Result result = Cli.run(new ValidateCommand(), "explain", "x\n::error::forged‮");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).isEqualTo("Unknown rule id 'x\\n::error::forged\\u202E'."
                + " Run 'oas-gateway-validate rules' to list rule ids.\n");
    }

    @Test
    void pathLikeRuleIdIsUnknown() {
        Cli.Result result = Cli.run(new ValidateCommand(), "explain", "../x-gateway.schema.json");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).startsWith("Unknown rule id '../x-gateway.schema.json'.");
    }

    @Test
    void missingRuleIdIsAUsageError() {
        Cli.Result result = Cli.run(new ValidateCommand(), "explain");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("Missing required parameter: '<rule-id>'", "Usage:");
    }

    /** QA-71-1: explain has no --fail-on, so the message does not tell the user to write it after explain. */
    @Test
    void optionBeforeTheSubcommandIsAUsageError() {
        Cli.Result result = Cli.run(new ValidateCommand(), "--fail-on", "warn", "explain", "path-no-verbs");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("Options before 'explain' do not apply to 'explain'. To validate a directory"
                + " named explain, write ./explain.").doesNotContain("Write --fail-on");
    }

    @Test
    void pathBeforeTheSubcommandIsAUsageError() {
        Cli.Result result = Cli.run(new ValidateCommand(), "openapi", "explain", "path-no-verbs");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains(
                "'explain' takes no files or directories. To validate a directory named explain, write ./explain.");
    }

    /** SEC-71-1. */
    @Test
    void pathNamedExplainInTheWorkingDirectoryReturnsTwo(@TempDir Path directory) throws IOException {
        Files.createDirectory(directory.resolve("explain"));

        Cli.Result result = Cli.run(new ValidateCommand(null, directory), "explain", "path-no-verbs");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("Error: A path named 'explain' exists. Write ./explain to validate it, or"
                + " run the command from another directory.\n");
        assertThat(Cli.run(new ValidateCommand(null, directory), "rules").code()).isZero();
    }

    /** QA-71-5. */
    @Test
    void printsTheVersion() {
        Cli.Result result = Cli.run(new ValidateCommand(), "explain", "--version");

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo("oas-gateway-validate development\n");
    }
}
