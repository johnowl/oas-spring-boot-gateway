package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The subcommand {@code diff} (spec 034). The fixtures are in src/test/resources/diff/<case>/base and head. */
class DiffCommandTest {

    private static final String DIFF = "src/test/resources/diff/";

    @Test
    void eachLooseningChangeIdIsReportedInText() throws IOException {
        Cli.Result result = diff("loosening");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).isEqualTo(expected("loosening", "expected.txt"));
        assertThat(result.out()).contains("LOOSENING became-public ", "LOOSENING audience-changed ",
                "LOOSENING authenticated-only-replaced-permissions ", "LOOSENING permission-removed-from-all-of ",
                "LOOSENING permission-added-to-any-of ", "LOOSENING any-of-removed ");
    }

    @Test
    void githubFormatPrintsOneErrorLineForEachChangeAtTheHeadOperation() throws IOException {
        Cli.Result result = diff("loosening", "--format", "github");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(expected("loosening", "expected-github.txt"));
        assertThat(result.out().lines().filter(line -> line.contains("::Access loosening (")))
                .hasSize(8)
                .allMatch(line -> line.matches("::error file=" + DIFF + "loosening/head/\\w+\\.yaml,line=\\d+,col=5::.*"));
        // DELETE /orders-api/orders/{id} is on line 29 of the head file.
        assertThat(result.out()).contains("::error file=" + DIFF + "loosening/head/orders.yaml,line=29,col=5::Access"
                + " loosening (permission-removed-from-all-of) of DELETE /orders-api/orders/{id}: Orders.Delete removed"
                + " from all-of.");
    }

    @Test
    void jsonFormatHasTheLayoutOfTheSpec() throws IOException {
        Cli.Result result = diff("loosening", "--format", "json");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(expected("loosening", "expected.json")
                .replace("${version}", ManifestVersionProvider.version()));
        JsonNode change = new ObjectMapper().readTree(result.out()).get("changes").get(0);
        assertThat(change.properties()).extracting(Map.Entry::getKey).containsExactly(
                "changeId", "method", "publicPath", "file", "pointer", "permission", "message");
        assertThat(change.get("permission").asText()).isEqualTo("Orders.Delete");
        assertThat(new ObjectMapper().readTree(result.out()).get("changes").get(2).get("permission").isNull()).isTrue();
    }

    /** QA-74-1, SEC-74-1: a binding can send the token of an audience to another backend, so it fails the job. */
    @Test
    void bindingsAreErrorsAndReturnOne() throws IOException {
        Cli.Result text = diff("bindings");
        Cli.Result github = diff("bindings", "--format", "github");

        assertThat(text.code()).isEqualTo(1);
        assertThat(text.out()).isEqualTo(expected("bindings", "expected.txt"));
        assertThat(github.code()).isEqualTo(1);
        assertThat(github.out()).isEqualTo(expected("bindings", "expected-github.txt"));
        assertThat(github.out().lines().filter(line -> line.startsWith("::"))).hasSize(3)
                .allMatch(line -> line.startsWith("::error file="));
        // SEC-73-6: health.yaml has no audience, but its changed upstream is a binding too.
        assertThat(text.out()).contains("BINDING binding-changed without an audience\n");
    }

    @Test
    void jsonFormatHasTheArrayBindings() throws IOException {
        Cli.Result result = diff("bindings", "--format", "json");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(expected("bindings", "expected.json")
                .replace("${version}", ManifestVersionProvider.version()));
        JsonNode bindings = new ObjectMapper().readTree(result.out()).get("bindings");
        assertThat(bindings.get(0).properties()).extracting(Map.Entry::getKey).containsExactly(
                "kind", "audience", "baseUpstream", "headUpstream", "basePrefix", "headPrefix", "baseUpstreamBasePath",
                "headUpstreamBasePath", "file", "pointer");
        assertThat(bindings.get(1).get("kind").asText()).isEqualTo("binding-new");
        assertThat(bindings.get(1).get("baseUpstream").isNull()).isTrue();
    }

    @Test
    void githubFormatAppendsTheBindingsToTheJobSummary(@TempDir Path directory) throws IOException {
        Path summary = Files.writeString(directory.resolve("summary.md"), "Earlier step\n");

        Cli.Result result = Cli.run(new ValidateCommand(), name -> name.equals("GITHUB_STEP_SUMMARY")
                ? summary.toString() : null, "diff", "--format", "github", DIFF + "bindings/base", DIFF + "bindings/head");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(summary)).isEqualTo("Earlier step\n" + expected("bindings", "expected-summary.md"));
    }

    /** SEC-75-3: GitHub shows only the first annotations of a step, so the summary lists every change too. */
    @Test
    void githubFormatAppendsTheLooseningChangesAndTheBindingsToTheJobSummary(@TempDir Path directory)
            throws IOException {
        Path summary = directory.resolve("summary.md");

        Cli.Result result = Cli.run(new ValidateCommand(), name -> name.equals("GITHUB_STEP_SUMMARY")
                ? summary.toString() : null, "diff", "--format", "github", DIFF + "loosening/base",
                DIFF + "loosening/head");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(summary)).isEqualTo(expected("loosening", "expected-summary.md"));
        // The header row of each table, one row for each of the 8 changes, and one row for the binding.
        assertThat(Files.readString(summary).lines().filter(line -> line.startsWith("| "))).hasSize(2 + 8 + 1);
    }

    @Test
    void withoutTheVariableNoSummaryIsWritten() {
        Cli.Result result = Cli.run(new ValidateCommand(), name -> null, "diff", "--format", "github",
                DIFF + "bindings/base", DIFF + "bindings/head");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).isEmpty();
    }

    @Test
    void textAndJsonFormatsWriteNoSummary(@TempDir Path directory) throws IOException {
        Path summary = directory.resolve("summary.md");
        for (String format : new String[] {"text", "json"}) {
            Cli.Result result = Cli.run(new ValidateCommand(), name -> summary.toString(), "diff", "--format", format,
                    DIFF + "bindings/base", DIFF + "bindings/head");
            assertThat(result.code()).isEqualTo(1);
        }
        assertThat(summary).doesNotExist();
    }

    /** The table is a control for reviewers: a summary that cannot be written fails the run. */
    @Test
    void summaryThatCannotBeWrittenReturnsTwo(@TempDir Path directory) {
        Cli.Result result = Cli.run(new ValidateCommand(), name -> directory.toString(), "diff", "--format", "github",
                DIFF + "bindings/base", DIFF + "bindings/head");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).startsWith("Error: cannot write the job summary " + directory);
    }

    @Test
    void renamedVariableMovedFileAndNewOrRemovedOperationsGiveNoChange() {
        Cli.Result result = diff("unchanged");

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo("0 access loosening changes in 0 operations.\n");
    }

    /** QA-74-2: the first PR with an audience needs the label once. */
    @Test
    void emptyBaseWithABindingReturnsOne() {
        Cli.Result result = diff("empty-base");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).startsWith("BINDING binding-new audience=7a2d3f9b-0000-0000-0000-000000000000\n")
                .endsWith("0 access loosening changes in 0 operations. 1 new or changed audience binding to review.\n");
    }

    /** QA-73-2, SEC-73-2: a new literal path takes requests from a protected template; a new public operation. */
    @Test
    void newOperationsThatLoosenAccessAreReported() throws IOException {
        Cli.Result text = diff("new-operations");
        Cli.Result github = diff("new-operations", "--format", "github");

        assertThat(text.code()).isEqualTo(1);
        assertThat(text.out()).isEqualTo(expected("new-operations", "expected.txt"));
        assertThat(github.code()).isEqualTo(1);
        assertThat(github.out()).isEqualTo(expected("new-operations", "expected-github.txt"));
        assertThat(text.out()).contains("LOOSENING shadows-protected-template GET /orders-api/orders/export\n",
                "LOOSENING new-public-operation GET /orders-api/status\n");
    }

    /** SEC-73-1: the operation moves to a new prefix and becomes public. Its backend target stays. */
    @Test
    void operationMovedToANewPrefixAndMadePublicIsReported() throws IOException {
        Cli.Result text = diff("moved-prefix");
        Cli.Result github = diff("moved-prefix", "--format", "github");

        assertThat(text.code()).isEqualTo(1);
        assertThat(text.out()).isEqualTo(expected("moved-prefix", "expected.txt"));
        assertThat(github.code()).isEqualTo(1);
        assertThat(github.out()).isEqualTo(expected("moved-prefix", "expected-github.txt"));
        assertThat(text.out()).contains("LOOSENING became-public GET /shop/orders/{id}\n",
                "LOOSENING new-public-operation GET /shop/orders/{id}\n",
                "BINDING binding-changed audience=6f1c2e8a-0000-0000-0000-000000000000\n");
    }

    /** QA-73-3: the prefix moves, the public path stays, and the rule is weaker. */
    @Test
    void prefixMoveWithTheSamePublicPathAndWeakerAccessIsReported() throws IOException {
        Cli.Result text = diff("prefix-move-same-path");
        Cli.Result github = diff("prefix-move-same-path", "--format", "github");

        assertThat(text.code()).isEqualTo(1);
        assertThat(text.out()).isEqualTo(expected("prefix-move-same-path", "expected.txt"));
        assertThat(github.code()).isEqualTo(1);
        assertThat(github.out()).isEqualTo(expected("prefix-move-same-path", "expected-github.txt"));
        assertThat(text.out()).contains("LOOSENING permission-removed-from-all-of DELETE /orders-api/orders/{id}\n",
                "prefix-move-same-path/head/orders.yaml#/x-gateway/prefix\n");
    }

    /**
     * QA-74-3, QA-73-4, QA-73-5: a binding without a prefix, a removed prefix (pointer /x-gateway, line of x-gateway),
     * and a changed upstream-base-path.
     */
    @Test
    void bindingFieldsInEachFormat() throws IOException {
        Cli.Result text = diff("binding-fields");
        Cli.Result github = diff("binding-fields", "--format", "github");
        Cli.Result json = diff("binding-fields", "--format", "json");

        assertThat(text.code()).isEqualTo(1);
        assertThat(text.out()).isEqualTo(expected("binding-fields", "expected.txt"));
        assertThat(github.code()).isEqualTo(1);
        assertThat(github.out()).isEqualTo(expected("binding-fields", "expected-github.txt"));
        assertThat(github.out()).contains("::error file=" + DIFF + "binding-fields/head/invoices.yaml,line=5,col=1::",
                " without a prefix (base: http://reports-service:8080 without a prefix).");
        assertThat(json.code()).isEqualTo(1);
        assertThat(json.out()).isEqualTo(expected("binding-fields", "expected.json")
                .replace("${version}", ManifestVersionProvider.version()));
        JsonNode reports = new ObjectMapper().readTree(json.out()).get("bindings").get(2);
        assertThat(reports.get("file").asText()).endsWith("reports.yaml");
        assertThat(reports.get("basePrefix").isNull()).isTrue();
        assertThat(reports.get("headPrefix").isNull()).isTrue();
    }

    /** SEC-73-5: the head removes GET /orders/export, so the weaker template GET /orders/{id} takes its requests. */
    @Test
    void literalRemovedToAWeakerTemplateIsReported() throws IOException {
        assertFixture("literal-removed", "LOOSENING literal-removed-to-template GET /orders-api/orders/{id}\n");
    }

    /** SEC-73-6: a file with only public operations points its routes at another upstream. */
    @Test
    void publicOnlyFileWithANewUpstreamReturnsOne() throws IOException {
        assertFixture("public-only-upstream", "BINDING binding-changed without an audience\n");
        Cli.Result json = diff("public-only-upstream", "--format", "json");
        assertThat(json.out()).isEqualTo(expected("public-only-upstream", "expected.json")
                .replace("${version}", ManifestVersionProvider.version()));
        JsonNode binding = new ObjectMapper().readTree(json.out()).get("bindings").get(0);
        assertThat(binding.get("audience").isNull()).isTrue();
        assertThat(binding.get("headUpstream").asText()).isEqualTo("http://169.254.169.254");
    }

    /** SEC-73-7: new HEAD and OPTIONS operations with a weaker rule than the base GET. */
    @Test
    void headAndOptionsWeakerThanGetAreReported() throws IOException {
        assertFixture("head-operation", "LOOSENING head-weaker-than-get HEAD /orders-api/orders/{id}\n",
                "LOOSENING head-weaker-than-get OPTIONS /orders-api/orders/{id}\n");
    }

    /** SEC-73-8: http://orders-service:80 and http://Orders-Service are the same backend. */
    @Test
    void canonicalUpstreamMatchesTheBackendTarget() throws IOException {
        assertFixture("canonical-upstream", "LOOSENING permission-removed-from-all-of DELETE /shop/orders/{id}\n");
    }

    @Test
    void headFileWithAContractErrorReturnsTwo() {
        Cli.Result result = diff("contract-error");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("Error: the head directory has files with errors, so diff cannot compile the"
                + " access rules:\nError: " + DIFF + "contract-error/head/orders.yaml#/paths/~1orders~1{id}/get"
                + " authorization-required: Operation has no x-gateway-authorization. Declare all-of, any-of, or"
                + " authenticated-only: true.\n");
    }

    @Test
    void baseFileWithAParseErrorReturnsTwo() {
        Cli.Result result = diff("parse-error");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).startsWith("Error: the base directory has files with errors")
                .contains(DIFF + "parse-error/base/orders.yaml# oas-parse: ");
    }

    /** A wrong head directory must not pass: without OAS files, no operation can be compared. */
    @Test
    void headWithoutOasFilesReturnsTwo() {
        Cli.Result result = diff("empty-head");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("Error: no *.yaml, *.yml or *.json file found in the head directory "
                + DIFF + "empty-head/head.\n");
    }

    @Test
    void missingDirectoryReturnsTwo() {
        Cli.Result result = Cli.run(new ValidateCommand(), "diff", DIFF + "missing", DIFF + "bindings/head");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).isEqualTo("Error: the base directory " + DIFF + "missing does not exist.\n");
    }

    @Test
    void fileInsteadOfADirectoryReturnsTwo() {
        Cli.Result result = Cli.run(new ValidateCommand(), "diff", DIFF + "bindings/base",
                DIFF + "bindings/head/orders.yaml");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).isEqualTo("Error: the head directory " + DIFF + "bindings/head/orders.yaml is not a"
                + " directory.\n");
    }

    @Test
    void missingArgumentIsAUsageError() {
        Cli.Result result = Cli.run(new ValidateCommand(), "diff", DIFF + "bindings/base");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).startsWith("Missing required parameter: '<head-dir>'");
    }

    @Test
    void sarifIsNotAFormatOfDiff() {
        Cli.Result result = diff("bindings", "--format", "sarif");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).startsWith("Invalid value for option '--format'");
    }

    @Test
    void optionBeforeDiffIsAUsageError() {
        Cli.Result result = Cli.run(new ValidateCommand(), "--format", "json", "diff", DIFF + "bindings/base",
                DIFF + "bindings/head");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).startsWith("Write --format after 'diff'. Options before 'diff' do not apply to"
                + " 'diff'.\n");
    }

    @Test
    void pathBeforeDiffIsAUsageError() {
        Cli.Result result = Cli.run(new ValidateCommand(), DIFF + "bindings/base", "diff", DIFF + "bindings/base",
                DIFF + "bindings/head");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).startsWith("Write the directories after 'diff'.\n");
    }

    /** SEC-71-1, applied to diff: a CI script that validates a directory named diff without ./ gets 2, not 0. */
    @Test
    void pathNamedDiffInTheWorkingDirectoryReturnsTwo(@TempDir Path directory) throws IOException {
        Files.createDirectory(directory.resolve("diff"));

        Cli.Result result = Cli.run(new ValidateCommand(null, directory), "diff", DIFF + "bindings/base",
                DIFF + "bindings/head");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("Error: A path named 'diff' exists. Write ./diff to validate it, or run"
                + " the command from another directory.\n");
        assertThat(Cli.run(new ValidateCommand(null, directory), "diff", "--help").code()).isZero();
    }

    private static Cli.Result diff(String fixture, String... options) {
        String[] args = new String[options.length + 3];
        args[0] = "diff";
        System.arraycopy(options, 0, args, 1, options.length);
        args[options.length + 1] = DIFF + fixture + "/base";
        args[options.length + 2] = DIFF + fixture + "/head";
        return Cli.run(new ValidateCommand(), args);
    }

    /** Exit code 1, and the text and GitHub output of the fixture. */
    private static void assertFixture(String fixture, String... textLines) throws IOException {
        Cli.Result text = diff(fixture);
        Cli.Result github = diff(fixture, "--format", "github");

        assertThat(text.code()).isEqualTo(1);
        assertThat(text.out()).isEqualTo(expected(fixture, "expected.txt")).contains(textLines);
        assertThat(github.code()).isEqualTo(1);
        assertThat(github.out()).isEqualTo(expected(fixture, "expected-github.txt"));
    }

    private static String expected(String fixture, String file) throws IOException {
        return Files.readString(Path.of(DIFF + fixture, file), StandardCharsets.UTF_8);
    }
}
