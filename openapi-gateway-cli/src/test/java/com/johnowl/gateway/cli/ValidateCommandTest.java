package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

class ValidateCommandTest {

    private static final String CLI = "src/test/resources/cli/";

    @Test
    void validDirectoryReturnsZero() {
        Result result = run(BuiltInRules.all(), CLI + "valid");

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo("2 files checked: 0 errors, 0 warnings, 0 info.\n");
    }

    @Test
    void errorReturnsOneAndShowsRuleFileAndPointer() {
        Result result = run(BuiltInRules.all(), CLI + "error");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).startsWith(
                "ERROR x-gateway-schema src/test/resources/cli/error/orders.yaml#/x-gateway/upstream\n");
        assertThat(result.out()).endsWith("1 file checked: 1 error, 0 warnings, 0 info.\n");
    }

    @Test
    void invalidYamlReturnsOneWithOasParse() {
        Result result = run(BuiltInRules.all(), CLI + "invalid-yaml");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains("ERROR oas-parse src/test/resources/cli/invalid-yaml/broken.yaml#");
    }

    @ParameterizedTest
    @CsvSource({"WARN, error, 0", "WARN, warn, 1", "WARN, info, 1", "INFO, warn, 0", "INFO, info, 1"})
    void failOnSelectsTheThreshold(Severity severity, String failOn, int expected) {
        Result result = run(withFakeRule(severity), "--fail-on", failOn, CLI + "valid/orders.yaml");

        assertThat(result.code()).isEqualTo(expected);
    }

    @Test
    void failOnAcceptsUppercase() {
        assertThat(run(BuiltInRules.all(), "--fail-on", "WARN", CLI + "valid").code()).isZero();
    }

    @Test
    void findsNestedFilesAndIgnoresOtherFiles() {
        Result result = run(BuiltInRules.all(), CLI + "nested");

        assertThat(result.code()).isZero();
        assertThat(result.out()).startsWith("3 files checked:");
    }

    @Test
    void unknownOptionReturnsTwo() {
        Result result = run(BuiltInRules.all(), "--colour", CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("Unknown option: '--colour'");
    }

    /** SEC-71-3: a usage error shows the argument with escapes, so no line of stderr starts a workflow command. */
    @Test
    void usageErrorEscapesTheArgument() {
        Result result = run(BuiltInRules.all(), "--fail-on", "x\n::error::forged\u202E", CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("Invalid value for option '--fail-on'", "x\\n::error::forged\\u202E",
                "Usage:");
        assertThat(result.err().lines()).noneMatch(line -> line.stripLeading().startsWith("::"));
        assertThat(result.err()).doesNotContain("\u202E");
    }

    @Test
    void usageErrorEscapesAnUnknownOption() {
        Result result = run(BuiltInRules.all(), "--x\n::error::forged", CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("--x\\n::error::forged");
        assertThat(result.err().lines()).noneMatch(line -> line.stripLeading().startsWith("::"));
    }

    /** SEC-71-3: picocli does not read more arguments from a file of the repository. */
    @Test
    void atFileIsAPathNotAnArgumentFile(@TempDir Path dir) throws IOException {
        Path arguments = Files.writeString(dir.resolve("args.txt"), "rules\n");

        Result result = run(BuiltInRules.all(), "@" + arguments);

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).startsWith("Error: path does not exist: @");
    }

    /** QA-71-5: each subcommand prints the version of the CLI. */
    @ParameterizedTest
    @CsvSource({"rules", "explain", "baseline", "baseline create"})
    void eachSubcommandPrintsTheVersion(String command) {
        List<String> args = new ArrayList<>(List.of(command.split(" ")));
        args.add("--version");

        Result result = run(BuiltInRules.all(), args.toArray(String[]::new));

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo("oas-gateway-validate development\n");
    }

    @Test
    void unknownFailOnValueReturnsTwo() {
        assertThat(run(BuiltInRules.all(), "--fail-on", "fatal", CLI + "valid").code()).isEqualTo(2);
    }

    @Test
    void missingPathArgumentReturnsTwo() {
        assertThat(run(BuiltInRules.all()).code()).isEqualTo(2);
    }

    @Test
    void missingPathReturnsTwo() {
        Result result = run(BuiltInRules.all(), CLI + "does-not-exist");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("path does not exist: src/test/resources/cli/does-not-exist");
    }

    @Test
    void directoryWithoutOasFilesReturnsTwo() {
        Result result = run(BuiltInRules.all(), CLI + "no-oas");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("no *.yaml, *.yml or *.json file found");
    }

    @Test
    void ruleExceptionIsAViolationNotACrash() {
        SpecRule failing = new FakeRule("failing-rule", Severity.WARN) {
            @Override
            public void check(SpecContext context, Reporter reporter) {
                throw new IllegalStateException("boom");
            }
        };

        Result result = run(List.of(failing), CLI + "valid/orders.yaml");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains("Rule failed: boom");
    }

    @Test
    void commandExceptionReturnsTwoWithClassNameWhenThereIsNoMessage() {
        Result result = run(List.of(new FakeRule("broken-rule", Severity.WARN) {
            @Override
            public RuleMeta meta() {
                throw new NullPointerException();
            }
        }), CLI + "valid/orders.yaml");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).isEqualTo("Error: java.lang.NullPointerException\n");
    }

    @Test
    void errorInCommandReturnsTwo() {
        StringWriter err = new StringWriter();
        CommandLine commandLine = Main.commandLine(new ValidateCommand(List.of(new FakeRule("broken-rule", Severity.WARN) {
            @Override
            public RuleMeta meta() {
                throw new OutOfMemoryError("Java heap space");
            }
        })));
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(err));

        assertThat(Main.execute(commandLine, CLI + "valid/orders.yaml")).isEqualTo(2);
        assertThat(normalize(err)).isEqualTo("Error: Java heap space\n");
    }

    @Test
    void externalUrlReferenceIsAnOasParseError() {
        Result result = run(BuiltInRules.all(), CLI + "external-ref");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains("ERROR oas-parse src/test/resources/cli/external-ref/orders.yaml#/paths/");
    }

    // SEC-66-1: swagger-parser does not resolve this reference, so no rule would see the operations.
    @Test
    void pathItemReferenceToComponentsIsAnOasParseError() {
        Result result = run(BuiltInRules.all(), CLI + "path-item-ref");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(
                "ERROR oas-parse src/test/resources/cli/path-item-ref/orders.yaml#/paths/~1orders~1admin\n"
                        + "  Path item reference '#/components/pathItems/Admin' is not supported."
                        + " Inline the operations.\n"
                        + "\n"
                        + "1 file checked: 1 error, 0 warnings, 0 info.\n");
    }

    // SEC-67-1: swagger-parser drops the get of #/webhooks/Admin. Before the fix, the CLI returned exit code 0.
    @Test
    void pathItemReferenceChainIsAnOasParseError() {
        Result result = run(BuiltInRules.all(), CLI + "path-item-ref-chain");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(
                "ERROR oas-parse src/test/resources/cli/path-item-ref-chain/orders.yaml#/paths/~1orders~1admin\n"
                        + "  Path item reference '#/webhooks/Admin' is not supported. Inline the operations.\n"
                        + "ERROR oas-parse src/test/resources/cli/path-item-ref-chain/orders.yaml#/webhooks/Admin\n"
                        + "  Path item reference '#/webhooks/Inner' is not supported. Inline the operations.\n"
                        + "\n"
                        + "1 file checked: 2 errors, 0 warnings, 0 info.\n");
    }

    // SEC-67-4: the parsers read only the first document. Before the fix, the CLI returned exit code 0. The comment
    // on line 6 is allowed in a .yaml file (QA-69-2), so the error is at the "---" on line 7.
    @Test
    void contentAfterTheFirstDocumentIsAnOasParseError() {
        Result result = run(BuiltInRules.all(), CLI + "trailing-document");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(
                "ERROR oas-parse src/test/resources/cli/trailing-document/orders.yaml#\n"
                        + "  Invalid JSON at line 7, column 1: content after the first JSON value is not allowed\n"
                        + "\n"
                        + "1 file checked: 1 error, 0 warnings, 0 info.\n");
    }

    @Test
    void controlCharactersInErrorMessagesAreEscaped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("x\n::warning title=injected::forged\n.yaml");
        Files.write(file, new byte[] {(byte) 0xff, (byte) 0xfe, 'a'});

        Result result = run(BuiltInRules.all(), file.toString());

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("x\\n::warning title=injected::forged\\n.yaml")
                .doesNotContain("\n::");
    }

    @Test
    void helpAndVersionReturnZero() {
        assertThat(run(BuiltInRules.all(), "--help").out()).startsWith("Usage: oas-gateway-validate ");
        assertThat(run(BuiltInRules.all(), "--help").code()).isZero();
        Result version = run(BuiltInRules.all(), "--version");
        assertThat(version.code()).isZero();
        assertThat(version.out()).startsWith("oas-gateway-validate ");
    }

    @ParameterizedTest
    @CsvSource({"valid, 0", "error, 1", "invalid-yaml, 1"})
    void exitCodeIsTheSameForEachFormat(String fixture, int expected) {
        for (String format : List.of("text", "github", "json", "sarif")) {
            assertThat(run(BuiltInRules.all(), "--format", format, CLI + fixture).code())
                    .as(format).isEqualTo(expected);
        }
    }

    @Test
    void githubFormatAnnotatesTheLine() {
        Result result = run(BuiltInRules.all(), "--format", "github", CLI + "error");

        assertThat(result.out()).startsWith("::error file=src/test/resources/cli/error/orders.yaml,"
                + "line=6,col=3,title=x-gateway-schema::'upstream' must be");
    }

    @Test
    void jsonFormatIsValidJson() throws Exception {
        Result result = run(BuiltInRules.all(), "--format", "json", CLI + "error");

        JsonNode json = new ObjectMapper().readTree(result.out());
        assertThat(json.at("/violations/0/ruleId").asText()).isEqualTo("x-gateway-schema");
        assertThat(json.at("/violations/0/line").asInt()).isEqualTo(6);
        assertThat(json.at("/summary/files").asInt()).isEqualTo(1);
    }

    @Test
    void invalidYamlGetsTheLineOfTheParseError() throws Exception {
        Result github = run(BuiltInRules.all(), "--format", "github", CLI + "invalid-yaml");
        Result json = run(BuiltInRules.all(), "--format", "json", CLI + "invalid-yaml");

        assertThat(github.out()).startsWith("::error file=src/test/resources/cli/invalid-yaml/broken.yaml,"
                + "line=4,col=12,title=oas-parse::Invalid YAML at line 4, column 12: ");
        JsonNode violation = new ObjectMapper().readTree(json.out()).at("/violations/0");
        assertThat(violation.get("line").asInt()).isEqualTo(4);
        assertThat(violation.get("column").asInt()).isEqualTo(12);
    }

    @Test
    void githubFormatEscapesControlCharactersOnStderr(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("x\n::warning title=injected::forged\n.yaml");
        Files.write(file, new byte[] {(byte) 0xff, (byte) 0xfe, 'a'});

        Result result = run(BuiltInRules.all(), "--format", "github", file.toString());

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).doesNotContain("\n::");
    }

    @Test
    void usageErrorWritesNothingToStdout() {
        Result result = run(BuiltInRules.all(), "--format", "json", CLI + "does-not-exist");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
    }

    /** The order of the arguments and of the directory listing does not change the output. */
    @Test
    void protectedOperationsPassWithoutCatalog() {
        // Removal of the permission catalog (2026-11-13): the CLI checks the format, not the permissions in Entra.
        Result result = run(null, CLI + "protected");

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo("1 file checked: 0 errors, 0 warnings, 0 info.\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void publicFileWithAudiencePasses() {
        Result result = run(null, CLI + "public-audience");

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo("1 file checked: 0 errors, 0 warnings, 0 info.\n");
    }

    @ParameterizedTest
    @CsvSource({"--catalog, permissions.yaml", "--entra, ''"})
    void catalogOptionsAreUnknown(String option, String value) {
        List<String> args = new ArrayList<>(List.of(option));
        if (!value.isEmpty()) {
            args.add(value);
        }
        args.add(CLI + "protected");

        Result result = run(null, args.toArray(String[]::new));

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("Unknown option: '" + option + "'");
    }

    @Test
    void catalogExportIsNotASubcommand() {
        Result result = run(null, "catalog", "export", CLI + "protected");

        // "catalog" is a path now, and it does not exist.
        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(run(BuiltInRules.all(), "--help").out()).doesNotContain("catalog");
    }

    @Test
    void repeatedAudienceIsAnError() {
        Result result = run(null, "src/test/resources/cli-audience");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("""
                ERROR audience-unique src/test/resources/cli-audience/orders.yaml#/x-gateway/audience
                  Audience '6f1c2e8a-0000-0000-0000-000000000000' is also declared in \
                src/test/resources/cli-audience/payments.yaml. An audience can appear in one OAS file only.
                ERROR audience-unique src/test/resources/cli-audience/payments.yaml#/x-gateway/audience
                  Audience '6f1c2e8a-0000-0000-0000-000000000000' is also declared in \
                src/test/resources/cli-audience/orders.yaml. An audience can appear in one OAS file only.

                2 files checked: 2 errors, 0 warnings, 0 info.
                """);
    }

    @Test
    void configCannotTurnOffTheAudienceRules(@TempDir Path dir) throws IOException {
        Path config = Files.writeString(dir.resolve("config.yaml"),
                "rules:\n  audience-unique: off\n  audience-not-first-party: off\n");

        Result result = run(null, "--config", config.toString(), "src/test/resources/cli-audience");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains(
                "rules.audience-unique: rule 'audience-unique' is a CONTRACT rule and cannot be configured.",
                "rules.audience-not-first-party: rule 'audience-not-first-party' is a CONTRACT rule and cannot be"
                        + " configured.");
    }

    @Test
    void configCanTurnOffARule(@TempDir Path dir) throws IOException {
        Path specs = Files.createDirectories(dir.resolve("specs"));
        Files.writeString(specs.resolve("orders.yaml"), Files.readString(Path.of(CLI + "valid/orders.yaml"))
                .replaceFirst("operationId: \\w+", "operationId: list_orders"));

        assertThat(run(null, specs.toString()).out()).contains("WARN  operation-id-camel-case");
        Result result = run(null, "--config", "src/test/resources/cli-config/unused-off.yaml", specs.toString());

        assertThat(result.code()).isZero();
        assertThat(result.out()).doesNotContain("operation-id-camel-case");
    }

    @Test
    void configOptionChangesTheAcceptedErrorMediaTypes(@TempDir Path dir) throws IOException {
        Path specs = Files.createDirectories(dir.resolve("specs"));
        Files.writeString(specs.resolve("orders.yaml"), Files.readString(Path.of(CLI + "valid/orders.yaml"))
                + """
                        "404":
                          description: Not found.
                          content:
                            application/problem+json: { schema: { type: object }, example: { title: Not found } }
                            application/vnd.error+json: { schema: { type: object }, example: { title: Not found } }
                """);
        Path config = Files.writeString(dir.resolve("config.yaml"), """
                rules:
                  error-response-problem-json:
                    options:
                      media-types: [application/vnd.error+json]
                """);

        String pointer = "#/paths/~1orders/get/responses/404/content/";
        assertThat(run(null, specs.toString()).out())
                .contains("WARN  error-response-problem-json")
                .contains(pointer + "application~1vnd.error+json")
                .doesNotContain(pointer + "application~1problem+json");
        Result result = run(null, "--config", config.toString(), specs.toString());

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains(pointer + "application~1problem+json")
                .doesNotContain(pointer + "application~1vnd.error+json");
    }

    @Test
    void configEntryForARemovedCatalogRuleReturnsTwo(@TempDir Path dir) throws IOException {
        Path config = Files.writeString(dir.resolve("config.yaml"), "rules:\n  permissions-unused: off\n");

        Result result = run(null, "--config", config.toString(), CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("rules.permissions-unused: unknown rule id.");
    }

    @Test
    void baselineEntryForARemovedCatalogRuleReturnsTwo(@TempDir Path dir) throws IOException {
        Path baseline = Files.writeString(dir.resolve("baseline.json"), """
                {"version": 1, "entries": [{"ruleId": "permissions-unused", "file": "a.yaml", "pointer": "/"}]}
                """);

        Result result = run(null, "--baseline", baseline.toString(), CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("entries[0]: unknown rule id 'permissions-unused'.");
    }

    @Test
    void invalidConfigReturnsTwoWithAllProblems() {
        Result result = run(null, "--config", "src/test/resources/cli-config/invalid.yaml", CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains(
                "src/test/resources/cli-config/invalid.yaml: extends: must be recommended or strict.",
                "src/test/resources/cli-config/invalid.yaml: rules.security-explicit: rule 'security-explicit' is a"
                        + " CONTRACT rule and cannot be configured.");
    }

    @Test
    void ignoreFilesAreRelativeToTheDirectoryOfTheConfig() {
        String dir = "src/test/resources/cli-config/ignore/";

        // The glob is relative to the directory of the config file, not to the working directory.
        Result result = run(withFakeRule(Severity.WARN), "--config", dir + ".oas-gateway.yaml", dir + "specs");

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo("WARN  fake-rule " + dir + "specs/current/orders.yaml#/info\n"
                + "  Fake problem.\n\n"
                + "2 files checked: 0 errors, 1 warning, 0 info.\n");
    }

    @Test
    void configProblemsAreEscapedOnStderr(@TempDir Path dir) throws IOException {
        Path config = Files.writeString(dir.resolve("config.yaml"), "\"x\\n::error::forged\": 1\n");

        Result result = run(null, "--config", config.toString(), CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("x\\n::error::forged: unknown key.").doesNotContain("\n::");
    }

    @Test
    void unreadableConfigNameIsEscapedOnStderr(@TempDir Path dir) {
        Result result = run(null, "--config", dir.resolve("x\n::error::forged.yaml").toString(), CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("cannot read the configuration", "x\\n::error::forged.yaml")
                .doesNotContain("\n::");
    }

    @Test
    void missingConfigReturnsTwo() {
        Result result = run(null, "--config", "src/test/resources/cli-config/nope.yaml", CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("cannot read the configuration");
    }

    @Test
    void deviationEntriesAreValidated() {
        Result result = run(null, "src/test/resources/cli-deviation");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains(
                "ERROR deviation-schema src/test/resources/cli-deviation/orders.yaml#/paths/~1orders/get/x-deviation/0/rule\n"
                        + "  Rule 'security-explicit' cannot have a deviation, because it is a CONTRACT rule.",
                "ERROR deviation-schema src/test/resources/cli-deviation/orders.yaml#/paths/~1orders/get/x-deviation/1/rule");
    }

    @Test
    void deviationEntryForAConfigurableRuleCoversTheViolation() {
        Result result = run(null, "src/test/resources/cli-deviation-catalog-rule");

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo("1 file checked: 0 errors, 0 warnings, 0 info.\n");
    }

    @Test
    void baselineCreateWritesTheConfigurableViolations() {
        Result result = run(withFakeRule(Severity.WARN), "baseline", "create", "src/test/resources/cli-baseline");

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo("""
                {
                  "version" : 1,
                  "entries" : [ {
                    "ruleId" : "fake-rule",
                    "file" : "src/test/resources/cli-baseline/orders.yaml",
                    "pointer" : "/info"
                  } ]
                }
                """);
        assertThat(run(withFakeRule(Severity.WARN), "baseline", "create", "src/test/resources/cli-baseline").out())
                .isEqualTo(result.out());
    }

    @Test
    void baselineHidesKnownViolationsAndKeepsNewOnes(@TempDir Path directory) throws Exception {
        Path baseline = directory.resolve("baseline.json");
        Files.writeString(baseline,
                run(withFakeRule(Severity.ERROR), "baseline", "create", "src/test/resources/cli-baseline").out());

        Result known = run(withFakeRule(Severity.ERROR), "--baseline", baseline.toString(), "src/test/resources/cli-baseline");
        assertThat(known.code()).isZero();
        assertThat(known.out()).isEqualTo("1 file checked: 0 errors, 0 warnings, 0 info.\n");

        // Lines above the violation do not change the fingerprint.
        Path moved = directory.resolve("cli-baseline");
        Files.createDirectories(moved);
        Files.writeString(moved.resolve("orders.yaml"), "# a new comment line\n# another one\n"
                + Files.readString(Path.of("src/test/resources/cli-baseline/orders.yaml")));
        String relocated = Files.readString(baseline).replace("src/test/resources/cli-baseline/orders.yaml",
                ValidationRun.relativeToWorkingDirectory(moved.resolve("orders.yaml")).toString());
        Files.writeString(baseline, relocated);
        assertThat(run(withFakeRule(Severity.ERROR), "--baseline", baseline.toString(), moved.toString()).code()).isZero();

        // A new fingerprint is reported.
        SpecRule other = new FakeRule("fake-rule", Severity.ERROR) {
            @Override
            public void check(SpecContext context, Reporter reporter) {
                reporter.report("/paths", "New problem.");
            }
        };
        List<Rule> rules = new ArrayList<>(BuiltInRules.all());
        rules.add(other);
        Result changed = run(rules, "--baseline", baseline.toString(), moved.toString());
        assertThat(changed.code()).isEqualTo(1);
        assertThat(changed.out()).contains("ERROR fake-rule");
    }

    @Test
    void invalidBaselineReturnsTwo(@TempDir Path directory) throws Exception {
        Path baseline = Files.writeString(directory.resolve("baseline.json"), """
                {"version": 1, "entries": [{"ruleId": "security-explicit", "file": "a.yaml", "pointer": "/"}]}
                """);

        Result result = run(BuiltInRules.all(), "--baseline", baseline.toString(), CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("rule 'security-explicit' cannot be in a baseline.");
    }

    @Test
    void baselineMatchesPointersFromTheDocumentAfterLinesAreAdded(@TempDir Path directory) throws Exception {
        // The rule reports each path item, so the pointer comes from the document.
        SpecRule pathItems = new FakeRule("fake-rule", Severity.ERROR) {
            @Override
            public void check(SpecContext context, Reporter reporter) {
                context.spec().raw().get("paths").properties().forEach(path ->
                        reporter.report("/paths/" + path.getKey().replace("~", "~0").replace("/", "~1"), "Path item."));
            }
        };
        List<Rule> rules = new ArrayList<>(BuiltInRules.all());
        rules.add(pathItems);
        Path specs = Files.createDirectories(directory.resolve("specs"));
        Path orders = specs.resolve("orders.yaml");
        String original = Files.readString(Path.of("src/test/resources/cli-baseline/orders.yaml"));
        Files.writeString(orders, original);
        Path baseline = directory.resolve("baseline.json");
        Files.writeString(baseline, run(rules, "baseline", "create", specs.toString()).out());

        Result before = run(rules, specs.toString());
        assertThat(before.code()).isEqualTo(1);
        assertThat(before.out()).contains("#/paths/~1orders\n");

        // Comment lines above, and a new path item above the known one.
        Files.writeString(orders, "# a new comment line\n# another one\n" + original.replace("paths:\n", """
                paths:
                  /items:
                    get:
                      operationId: listItems
                      security: []
                      responses:
                        "200":
                          description: The items.
                """));
        Result after = run(rules, "--baseline", baseline.toString(), specs.toString());

        assertThat(after.code()).isEqualTo(1);
        assertThat(after.out()).contains("#/paths/~1items\n  Path item.").doesNotContain("#/paths/~1orders\n");
    }

    @Test
    void validationOptionsBeforeBaselineAreAUsageError() {
        Result config = run(withFakeRule(Severity.WARN), "--config", "src/test/resources/cli-config/unused-off.yaml",
                "baseline", "create", "src/test/resources/cli-baseline");

        assertThat(config.code()).isEqualTo(2);
        assertThat(config.out()).isEmpty();
        assertThat(config.err()).contains("Write --config after 'baseline create'.");
    }

    @Test
    void baselineProblemsAreEscapedOnStderr(@TempDir Path directory) throws IOException {
        Path baseline = Files.writeString(directory.resolve("baseline.json"), """
                {"version": 1, "entries": [{"ruleId": "x\\n::error::forged", "file": "a.yaml", "pointer": "/"}]}
                """);

        Result result = run(BuiltInRules.all(), "--baseline", baseline.toString(), CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("entries[0]: unknown rule id 'x\\n::error::forged'.").doesNotContain("\n::");
    }

    @Test
    void unreadableBaselineNameIsEscapedOnStderr(@TempDir Path directory) {
        Result result = run(BuiltInRules.all(), "--baseline", directory.resolve("x\n::error::forged.json").toString(),
                CLI + "valid");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("cannot read the baseline", "x\\n::error::forged.json")
                .doesNotContain("\n::");
    }

    @Test
    void helpExplainsHowToValidateADirectoryNamedLikeASubcommand() {
        assertThat(run(BuiltInRules.all(), "--help").out().replaceAll("\\s+", " "))
                .contains("baseline, rules, explain or diff", "./baseline");
    }

    @Test
    void baselineWithoutSubcommandIsAUsageError() {
        assertThat(run(BuiltInRules.all(), "baseline").code()).isEqualTo(2);
    }

    @Test
    void outputIsDeterministic() {
        Result forward = run(BuiltInRules.all(), CLI + "error", CLI + "invalid-yaml", CLI + "valid", CLI + "nested");
        Result reverse = run(BuiltInRules.all(), CLI + "nested", CLI + "valid", CLI + "invalid-yaml", CLI + "error");

        assertThat(forward.out()).contains("x-gateway-schema", "oas-parse", "7 files checked");
        assertThat(reverse.out()).isEqualTo(forward.out());
    }

    private static List<Rule> withFakeRule(Severity severity) {
        List<Rule> rules = new ArrayList<>(BuiltInRules.all());
        rules.add(new FakeRule("fake-rule", severity));
        return rules;
    }

    private static Result run(List<Rule> rules, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = Main.commandLine(new ValidateCommand(rules));
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));
        int code = commandLine.execute(args);
        return new Result(code, normalize(out), normalize(err));
    }

    private static String normalize(StringWriter writer) {
        return writer.toString().replace(System.lineSeparator(), "\n");
    }

    private record Result(int code, String out, String err) {
    }

    private static class FakeRule implements SpecRule {

        private final RuleMeta meta;

        FakeRule(String id, Severity severity) {
            this.meta = new RuleMeta(id, Category.DESIGN, severity, true, "Fake.", "Fake.");
        }

        @Override
        public RuleMeta meta() {
            return meta;
        }

        @Override
        public void check(SpecContext context, Reporter reporter) {
            reporter.report("/info", "Fake problem.");
        }
    }
}
