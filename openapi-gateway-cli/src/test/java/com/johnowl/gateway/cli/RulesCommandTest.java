package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.johnowl.gateway.core.config.Profile;
import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.text.UnsafeCharacters;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The subcommand {@code rules} (spec 032). */
class RulesCommandTest {

    private static final String RULES = "src/test/resources/cli-rules/";

    @Test
    void withoutConfigPrintsTheRecommendedSeverities() throws IOException {
        Cli.Result result = Cli.run(new ValidateCommand(), "rules");

        assertThat(result.code()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).isEqualTo(expected("rules-recommended.txt"));
    }

    @Test
    void printsOneLineForEachRuleSortedById() {
        List<String> lines = Cli.run(new ValidateCommand(), "rules").out().lines().toList();

        assertThat(lines.getFirst()).matches("ID +CATEGORY +SEVERITY");
        List<String> ids = lines.stream().skip(1).map(line -> line.split(" +")[0]).toList();
        assertThat(ids).isSorted().doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(BuiltInRules.all().stream().map(r -> r.meta().id()).toList());
        assertThat(lines).allSatisfy(line -> assertThat(line).doesNotEndWith(" "));
    }

    @Test
    void eachRowHasTheMetadataOfTheRule() {
        Map<String, String[]> rows = rows(Cli.run(new ValidateCommand(), "rules").out());

        for (Rule rule : BuiltInRules.all()) {
            RuleMeta meta = rule.meta();
            assertThat(rows.get(meta.id())).containsExactly(
                    meta.id(), meta.category().name(), meta.defaultSeverity().name().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void contractRulesShowError() {
        Map<String, String[]> rows = rows(Cli.run(new ValidateCommand(), "rules", "--config", RULES + "strict.yaml").out());

        assertThat(BuiltInRules.contract()).isNotEmpty().allSatisfy(rule ->
                assertThat(rows.get(rule.meta().id())).containsExactly(rule.meta().id(), "CONTRACT", "error"));
    }

    @Test
    void configPrintsOffForADisabledRuleAndTheOverrides() {
        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--config", RULES + "path-no-verbs-off.yaml");
        Map<String, String[]> rows = rows(result.out());

        assertThat(result.code()).isZero();
        assertThat(rows.get("path-no-verbs")).containsExactly("path-no-verbs", "DESIGN", "off");
        assertThat(rows.get("operation-id-camel-case")).containsExactly("operation-id-camel-case", "NAMING", "error");
        assertThat(rows.get("components-unused")).containsExactly("components-unused", "DESIGN", "warn");
    }

    @Test
    void strictProfileRaisesTheSeverities() {
        Map<String, String[]> rows = rows(Cli.run(new ValidateCommand(), "rules", "--config", RULES + "strict.yaml").out());

        for (Rule rule : BuiltInRules.all()) {
            String expected = rule.meta().configurable()
                    ? Profile.STRICT.severity(rule.meta()).name().toLowerCase(Locale.ROOT)
                    : rule.meta().defaultSeverity().name().toLowerCase(Locale.ROOT);
            assertThat(rows.get(rule.meta().id())[2]).as(rule.meta().id()).isEqualTo(expected);
        }
        assertThat(rows.get("schema-examples-present")[2]).isEqualTo("warn");
        assertThat(rows.get("deviation-schema")[2]).isEqualTo("error");
    }

    @Test
    void invalidConfigReturnsTwoWithTheErrorsOnStderr() {
        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--config", "src/test/resources/cli-config/invalid.yaml");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("rules.security-explicit:");
    }

    @Test
    void missingConfigReturnsTwo() {
        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--config", RULES + "missing.yaml");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).startsWith("Error: cannot read the configuration ");
    }

    @Test
    void optionBeforeTheSubcommandIsAUsageError() {
        Cli.Result result = Cli.run(new ValidateCommand(), "--config", RULES + "strict.yaml", "rules");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("Write --config after 'rules'. Options before 'rules' do not apply to"
                + " 'rules'. To validate a directory named rules, write ./rules.");
    }

    /** QA-71-1: rules has no --fail-on, so the message does not tell the user to write it after rules. */
    @Test
    void optionThatRulesDoesNotHaveIsNotNamed() {
        Cli.Result result = Cli.run(new ValidateCommand(), "--fail-on", "warn", "rules");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("Options before 'rules' do not apply to 'rules'. To validate a directory"
                + " named rules, write ./rules.").doesNotContain("Write --fail-on");
    }

    @Test
    void pathBeforeTheSubcommandIsAUsageError() {
        Cli.Result result = Cli.run(new ValidateCommand(), "openapi", "rules");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains(
                "'rules' takes no files or directories. To validate a directory named rules, write ./rules.");
    }

    /** SEC-71-1: a CI script that passes a directory named rules without ./ gets exit code 2, not 0. */
    @Test
    void pathNamedRulesInTheWorkingDirectoryReturnsTwo(@TempDir Path directory) throws IOException {
        Files.createDirectory(directory.resolve("rules"));

        Cli.Result result = Cli.run(new ValidateCommand(null, directory), "rules");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("Error: A path named 'rules' exists. Write ./rules to validate it, or run"
                + " the command from another directory.\n");
    }

    @Test
    void fileNamedRulesInTheWorkingDirectoryReturnsTwo(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("rules"), "");

        assertThat(Cli.run(new ValidateCommand(null, directory), "rules", "--format", "json").code()).isEqualTo(2);
        assertThat(Cli.run(new ValidateCommand(null, directory), "rules", "--help").code()).isZero();
        assertThat(Cli.run(new ValidateCommand(null, directory.resolve("rules").getParent().resolve("other")),
                "rules").code()).isZero();
    }

    /** QA-71-6. */
    @Test
    void unknownFormatReturnsTwo() {
        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--format", "sarif");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("Invalid value for option '--format'", "Usage:");
    }

    /** QA-71-5. */
    @Test
    void printsTheVersion() {
        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--version");

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo("oas-gateway-validate development\n");
    }

    @Test
    void jsonWithoutConfigMatchesTheExpectedFile() throws IOException {
        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--format", "json");

        assertThat(result.code()).isZero();
        assertThat(result.out()).isEqualTo(expected("rules-recommended.json"));
    }

    @Test
    void jsonHasEachRuleWithProfilesAndTheEffectiveOptions() throws IOException {
        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--format", "json",
                "--config", RULES + "path-no-verbs-off.yaml");
        JsonNode rules = new ObjectMapper().readTree(result.out()).get("rules");

        assertThat(rules).hasSize(BuiltInRules.all().size());
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        rules.forEach(rule -> byId.put(rule.get("id").asText(), rule));
        assertThat(byId.get("path-no-verbs").get("severity").asText()).isEqualTo("off");
        assertThat(byId.get("path-no-verbs").get("defaultSeverity").asText()).isEqualTo("warn");
        assertThat(byId.get("error-response-problem-json").get("options").get(0).get("value"))
                .extracting(JsonNode::asText)
                .containsExactly("application/problem+json", "application/json");
        assertThat(byId.get("error-response-problem-json").get("options").get(0).get("default"))
                .extracting(JsonNode::asText)
                .containsExactly("application/problem+json");
        for (Rule rule : BuiltInRules.all()) {
            JsonNode node = byId.get(rule.meta().id());
            assertThat(node.get("category").asText()).isEqualTo(rule.meta().category().name());
            assertThat(node.get("configurable").asBoolean()).isEqualTo(rule.meta().configurable());
            if (rule.meta().category() == Category.CONTRACT) {
                assertThat(node.get("profiles").get("strict").asText()).isEqualTo("error");
                assertThat(node.get("options")).isEmpty();
            }
        }
    }

    /**
     * SEC-71-4, QA-71-4: JSON escapes the unsafe characters of an option value, and a JSON parser reads the same value
     * back. A lone surrogate is an escape, not a {@code ?}.
     */
    @Test
    void unsafeCharactersInOptionValuesAreJsonEscapes(@TempDir Path directory) throws IOException {
        Path config = Files.writeString(directory.resolve("config.yaml"), """
                rules:
                  error-response-problem-json:
                    options:
                      media-types: ["application/problem+json\\u202E", "a\\u0001b", "line\\nfeed", "c1\\u0085", \
                "ls\\u2028", "del\\u007F", "emoji\\U0001F600"]
                """);

        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--format", "json", "--config", config.toString());

        assertThat(result.code()).isZero();
        assertThat(result.out().chars()).noneMatch(c -> c != '\n' && UnsafeCharacters.isUnsafe((char) c.intValue()))
                .noneMatch(c -> Character.isSurrogate((char) c.intValue()));
        assertThat(result.out()).contains("application/problem+json\\u202E", "a\\u0001b", "line\\nfeed",
                "c1\\u0085", "ls\\u2028", "del\\u007F", "emoji\\uD83D\\uDE00");
        JsonNode value = byId(result.out()).get("error-response-problem-json").get("options").get(0).get("value");
        assertThat(value).extracting(JsonNode::asText).containsExactly("application/problem+json\u202E", "a\u0001b",
                "line\nfeed", "c1\u0085", "ls\u2028", "del\u007F", "emoji\uD83D\uDE00");
    }

    @Test
    void loneSurrogateIsAJsonEscape() throws IOException {
        String json = RulesCommand.WRITER.writeValueAsString(List.of("x\uD800y"));

        assertThat(json).contains("x\\uD800y");
        assertThat(new ObjectMapper().readTree(json).get(0).asText()).isEqualTo("x\uD800y");
    }

    /** QA-71-4. */
    @Test
    void jsonWithStrictProfileShowsTheProfileAndTheRaisedSeverity() throws IOException {
        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--format", "json", "--config",
                RULES + "strict.yaml");
        JsonNode document = new ObjectMapper().readTree(result.out());
        JsonNode rule = byId(result.out()).get("operation-id-camel-case");

        assertThat(result.code()).isZero();
        assertThat(document.get("profile").asText()).isEqualTo("strict");
        assertThat(rule.get("defaultSeverity").asText()).isEqualTo("warn");
        assertThat(rule.get("severity").asText()).isEqualTo("error");
        assertThat(rule.get("profiles").get("strict").asText()).isEqualTo("error");
    }

    /** SEC-71-2: the table does not depend on ignore-files, so the output lists the globs. */
    @Test
    void showsTheIgnoreFilesGlobs(@TempDir Path directory) throws IOException {
        Path config = Files.writeString(directory.resolve("config.yaml"),
                "ignore-files: ['legacy/**', 'generated/*.yaml']\n");

        Cli.Result text = Cli.run(new ValidateCommand(), "rules", "--config", config.toString());
        Cli.Result json = Cli.run(new ValidateCommand(), "rules", "--format", "json", "--config", config.toString());

        assertThat(text.code()).isZero();
        assertThat(text.out()).endsWith("""

                Best-practice rules do not check the files of ignore-files:
                  legacy/**
                  generated/*.yaml
                """);
        assertThat(new ObjectMapper().readTree(json.out()).get("ignoreFiles")).extracting(JsonNode::asText)
                .containsExactly("legacy/**", "generated/*.yaml");
        assertThat(Cli.run(new ValidateCommand(), "rules").out()).doesNotContain("ignore-files");
    }

    /** SEC-71-5. */
    @Test
    void configThatIsASymbolicLinkReturnsTwo(@TempDir Path directory) throws IOException {
        Path target = Files.writeString(directory.resolve("target.yaml"), "extends: strict\n");
        Path link;
        try {
            link = Files.createSymbolicLink(directory.resolve("link.yaml"), target);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("The file system has no symbolic links: " + e);
            return;
        }

        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--config", link.toString());

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).startsWith("Error: the configuration ").endsWith(
                "link.yaml is a symbolic link. Pass the file that it points to.\n");
    }

    @Test
    void configThatIsADirectoryReturnsTwo(@TempDir Path directory) {
        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--config", directory.toString());

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).startsWith("Error: the configuration ").endsWith(" is not a regular file.\n");
    }

    @Test
    void configLargerThanOneMegabyteReturnsTwo(@TempDir Path directory) throws IOException {
        Path limit = Files.writeString(directory.resolve("limit.yaml"),
                "#" + " ".repeat(ValidationRun.MAX_CONFIG_BYTES - 2) + "\n");
        Path large = Files.writeString(directory.resolve("large.yaml"),
                "#" + " ".repeat(ValidationRun.MAX_CONFIG_BYTES - 1) + "\n");

        assertThat(Cli.run(new ValidateCommand(), "rules", "--config", limit.toString()).code()).isZero();
        Cli.Result result = Cli.run(new ValidateCommand(), "rules", "--config", large.toString());
        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).startsWith("Error: cannot read the configuration ")
                .endsWith("large.yaml: the file has more than 1048576 bytes (1 MB)\n");
    }

    private static Map<String, JsonNode> byId(String json) throws IOException {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        new ObjectMapper().readTree(json).get("rules").forEach(rule -> byId.put(rule.get("id").asText(), rule));
        return byId;
    }

    /** The rows of the table by rule id. */
    private static Map<String, String[]> rows(String out) {
        return out.lines().skip(1).map(line -> line.split(" +"))
                .collect(Collectors.toMap(row -> row[0], row -> row));
    }

    static String expected(String name) throws IOException {
        return Files.readString(Path.of(RULES, name), StandardCharsets.UTF_8);
    }
}
