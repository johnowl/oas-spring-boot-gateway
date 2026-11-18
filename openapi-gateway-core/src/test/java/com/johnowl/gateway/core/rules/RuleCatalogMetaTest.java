package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.config.OptionType;
import com.johnowl.gateway.core.config.RuleOptions;
import com.johnowl.gateway.core.config.RuleWithOptions;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleCatalogMetaTest {

    private static final String EXAMPLES = "rule-examples-test";

    @TempDir
    Path root;

    @Test
    void builtInRulesMatchTheirFixtures() {
        assertThat(RuleFixtures.problems(BuiltInRules.all(), RuleFixtures.root())).isEmpty();
    }

    /** Spec 032: explain prints the description, the rationale, and both examples of each rule. */
    @Test
    void builtInRulesHaveAnExplanation() {
        assertThat(RuleExplanations.problems(BuiltInRules.all(), RuleExamples.ROOT)).isEmpty();
    }

    @Test
    void acceptsCompleteExplanation() {
        assertThat(RuleExplanations.problems(List.of(fakeRule("complete-rule")), EXAMPLES))
                .noneMatch(problem -> problem.contains("'complete-rule'"));
    }

    @Test
    void reportsRuleWithoutExamples() {
        assertThat(RuleExplanations.problems(List.of(fakeRule("fake-rule"), fakeRule("only-bad-rule")), EXAMPLES))
                .contains(
                        "Rule 'fake-rule' has no example rule-examples-test/fake-rule/bad.yaml",
                        "Rule 'only-bad-rule' has no example rule-examples-test/only-bad-rule/good.yaml");
    }

    @Test
    void reportsBlankDescriptionAndRationale() {
        Rule blank = rule(new RuleMeta("complete-rule", Category.DESIGN, Severity.WARN, true, " ", ""));

        assertThat(RuleExplanations.problems(List.of(blank), EXAMPLES)).contains(
                "Rule 'complete-rule' has a blank description",
                "Rule 'complete-rule' has a blank rationale");
    }

    @Test
    void reportsBlankInvalidAndEqualExamples() {
        assertThat(RuleExplanations.problems(List.of(fakeRule("broken-rule"), fakeRule("same-rule")), EXAMPLES))
                .contains(
                        "Rule 'broken-rule' has an example bad.yaml that is not valid YAML",
                        "Rule 'broken-rule' has a blank example good.yaml",
                        "Rule 'same-rule' has the same bad and good example");
    }

    @Test
    void reportsExampleDirectoryWithoutRule() {
        assertThat(RuleExplanations.problems(List.of(), EXAMPLES)).contains(
                "Example directory 'complete-rule' has no rule");
    }

    @Test
    void reportsOptionWithoutDefault() {
        Rule withOptions = new OptionsWithDefaults(Map.of());

        assertThat(RuleExplanations.problems(List.of(withOptions), EXAMPLES))
                .contains("Rule 'complete-rule' has no default value for each option");
    }

    /** QA-70-3: a default is not null and has the type of the option. */
    @Test
    void reportsOptionDefaultOfTheWrongType() {
        Map<String, Object> nullDefault = new HashMap<>();
        nullDefault.put("media-types", null);

        for (Object defaults : List.of(nullDefault, Map.of("media-types", List.of()),
                Map.of("media-types", List.of(1)), Map.of("media-types", "application/json"))) {
            @SuppressWarnings("unchecked")
            Rule withOptions = new OptionsWithDefaults((Map<String, Object>) defaults);

            assertThat(RuleExplanations.problems(List.of(withOptions), EXAMPLES)).as("%s", defaults)
                    .contains("Rule 'complete-rule' has a default value of option 'media-types' that is not a"
                            + " string-list");
        }
    }

    @Test
    void acceptsOptionDefaultOfTheRightType() {
        Rule withOptions = new OptionsWithDefaults(Map.of("media-types", List.of("application/problem+json")));

        assertThat(RuleExplanations.problems(List.of(withOptions), EXAMPLES))
                .noneMatch(problem -> problem.contains("option"));
    }

    @Test
    void reportsBadExampleThatTheRuleDoesNotReport() {
        Rule silent = rule(new RuleMeta("complete-rule", Category.DESIGN, Severity.WARN, true, "Fake.", "Fake."));

        assertThat(RuleExplanations.problems(List.of(silent), EXAMPLES))
                .contains("Rule 'complete-rule' does not report its example bad.yaml");
    }

    @Test
    void reportsGoodExampleThatTheRuleReports() {
        Rule always = rule(new RuleMeta("complete-rule", Category.DESIGN, Severity.WARN, true, "Fake.", "Fake."),
                (context, reporter) -> reporter.report("/paths", "Always."));

        assertThat(RuleExplanations.problems(List.of(always), EXAMPLES))
                .contains("Rule 'complete-rule' reports its example good.yaml: example.yaml#/paths Always.");
    }

    @Test
    void reportsExampleThatDoesNotParse() {
        assertThat(RuleExplanations.problems(List.of(fakeRule("unparsed-rule")), EXAMPLES))
                .anyMatch(problem -> problem.startsWith(
                        "Rule 'unparsed-rule' has an example bad.yaml that does not parse: example.yaml#"));
    }

    /** The good example has two documents with a file name comment each; the bad example has none. */
    @Test
    void reportsDocumentWithoutFileName() {
        assertThat(RuleExplanations.problems(List.of(fakeRule("multi-file-rule")), EXAMPLES))
                .filteredOn(problem -> problem.startsWith("Rule "))
                .containsExactly(
                "Rule 'multi-file-rule' has an example bad.yaml that has a document that does not start with a"
                        + " comment like '# orders.yaml'");
    }

    @Test
    void reportsRuleWithoutFixtureDirectory() {
        assertThat(RuleFixtures.problems(List.of(fakeRule("fake-rule")), root))
                .contains("Rule 'fake-rule' has no fixture fake-rule/valid.yaml or fake-rule/valid/");
    }

    @Test
    void reportsIncompleteFixtureDirectory() throws IOException {
        Files.createDirectories(root.resolve("fake-rule"));
        Files.writeString(root.resolve("fake-rule/valid.yaml"), "");

        assertThat(RuleFixtures.problems(List.of(fakeRule("fake-rule")), root))
                .containsExactly(
                        "Rule 'fake-rule' has no fixture fake-rule/invalid.yaml or fake-rule/invalid/",
                        "Rule 'fake-rule' has no fixture file fake-rule/expected.txt");
    }

    @Test
    void reportsEmptyExpectedFile() throws IOException {
        Files.createDirectories(root.resolve("fake-rule"));
        Files.writeString(root.resolve("fake-rule/valid.yaml"), "");
        Files.writeString(root.resolve("fake-rule/invalid.yaml"), "");
        Files.writeString(root.resolve("fake-rule/expected.txt"), "\n  \n");

        assertThat(RuleFixtures.problems(List.of(fakeRule("fake-rule")), root))
                .containsExactly("Rule 'fake-rule' has an empty fake-rule/expected.txt");
    }

    @Test
    void acceptsMultiFileFixtureDirectories() throws IOException {
        Files.createDirectories(root.resolve("fake-rule/valid"));
        Files.createDirectories(root.resolve("fake-rule/invalid"));
        Files.writeString(root.resolve("fake-rule/expected.txt"), "a.yaml#/paths");

        assertThat(RuleFixtures.problems(List.of(fakeRule("fake-rule")), root)).isEmpty();
    }

    @Test
    void reportsFixtureDirectoryWithoutRule() throws IOException {
        Files.createDirectories(root.resolve("removed-rule"));

        assertThat(RuleFixtures.problems(List.of(), root))
                .containsExactly("Fixture directory 'removed-rule' has no rule");
    }

    @Test
    void reportsDuplicateAndNonKebabCaseIds() {
        assertThat(RuleFixtures.problems(List.of(fakeRule("same"), fakeRule("same"), fakeRule("NotKebab")), root))
                .contains("Duplicate rule id 'same'", "Rule id 'NotKebab' is not kebab-case");
    }

    /** A rule that reports the path /getOrders, the path of the bad examples in rule-examples-test. */
    private static Rule fakeRule(String id) {
        return rule(new RuleMeta(id, Category.DESIGN, Severity.WARN, true, "Fake.", "Fake."),
                RuleCatalogMetaTest::verbs);
    }

    private static void verbs(SpecContext context, Reporter reporter) {
        if (context.spec().raw().path("paths").has("/getOrders")) {
            reporter.report("/paths/~1getOrders", "Verb.");
        }
    }

    private static Rule rule(RuleMeta meta) {
        return rule(meta, (context, reporter) -> {
        });
    }

    private static Rule rule(RuleMeta meta, BiConsumer<SpecContext, Reporter> check) {
        return new SpecRule() {
            @Override
            public RuleMeta meta() {
                return meta;
            }

            @Override
            public void check(SpecContext context, Reporter reporter) {
                check.accept(context, reporter);
            }
        };
    }

    private record OptionsWithDefaults(Map<String, Object> optionDefaults) implements SpecRule, RuleWithOptions {

        @Override
        public RuleMeta meta() {
            return new RuleMeta("complete-rule", Category.DESIGN, Severity.WARN, true, "Fake.", "Fake.");
        }

        @Override
        public Map<String, OptionType> optionTypes() {
            return Map.of("media-types", OptionType.STRING_LIST);
        }

        @Override
        public Rule withOptions(RuleOptions options) {
            return this;
        }

        @Override
        public void check(SpecContext context, Reporter reporter) {
            verbs(context, reporter);
        }
    }
}
