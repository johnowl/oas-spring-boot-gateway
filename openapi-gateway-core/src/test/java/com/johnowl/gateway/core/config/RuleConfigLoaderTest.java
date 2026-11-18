package com.johnowl.gateway.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RuleConfigLoaderTest {

    static final RuleMeta NAMING = new RuleMeta("path-kebab-case", Category.NAMING, Severity.WARN, true, "d", "r");
    static final RuleMeta INFO_RULE = new RuleMeta("schema-examples-present", Category.DOCUMENTATION, Severity.INFO, true, "d", "r");
    static final RuleMeta CONTRACT = new RuleMeta("security-explicit", Category.CONTRACT, Severity.ERROR, false, "d", "r");
    static final RuleMeta FIXED = new RuleMeta("deviation-schema", Category.DESIGN, Severity.ERROR, false, "d", "r");
    static final RuleMeta WITH_OPTIONS = new RuleMeta("error-response-problem-json", Category.ERRORS, Severity.WARN, true, "d", "r");

    private final RuleConfigLoader loader = new RuleConfigLoader(List.of(
            rule(NAMING), rule(INFO_RULE), rule(CONTRACT), rule(FIXED), new OptionsRule()));

    @Test
    void emptyFileIsTheRecommendedProfile() {
        RuleConfig config = load("");

        assertThat(config.profile()).isEqualTo(Profile.RECOMMENDED);
        assertThat(config.severity(NAMING, "a.yaml")).contains(Severity.WARN);
    }

    @Test
    void strictProfileRaisesSeverities() {
        RuleConfig config = load("extends: strict");

        assertThat(config.severity(NAMING, "a.yaml")).contains(Severity.ERROR);
        assertThat(config.severity(INFO_RULE, "a.yaml")).contains(Severity.WARN);
    }

    @Test
    void ruleEntryOverridesTheProfile() {
        RuleConfig config = load("""
                extends: strict
                rules:
                  path-kebab-case: info
                  schema-examples-present: off
                """);
        RuleConfig quoted = load("rules: { schema-examples-present: 'off', path-kebab-case: { severity: off } }");

        assertThat(config.severity(NAMING, "a.yaml")).contains(Severity.INFO);
        assertThat(config.severity(INFO_RULE, "a.yaml")).isEmpty();
        assertThat(quoted.severity(INFO_RULE, "a.yaml")).isEmpty();
        assertThat(quoted.severity(NAMING, "a.yaml")).isEmpty();
    }

    @Test
    void longFormSetsSeverityAndOptions() {
        RuleConfig config = load("""
                rules:
                  error-response-problem-json:
                    severity: error
                    options:
                      media-types: [application/problem+json, application/json]
                """);

        assertThat(config.severity(WITH_OPTIONS, "a.yaml")).contains(Severity.ERROR);
        assertThat(config.options("error-response-problem-json").stringList("media-types"))
                .contains(List.of("application/problem+json", "application/json"));
        assertThat(config.options("path-kebab-case").stringList("media-types")).isEmpty();
    }

    @Test
    void ignoreFilesAreRelativeToTheConfigDirectory() {
        RuleConfig config = loader.load(".oas-gateway.yaml", "ignore-files: ['legacy/**']", Path.of("specs"));

        assertThat(config.severity(NAMING, "specs/legacy/old/orders.yaml")).isEmpty();
        assertThat(config.severity(NAMING, "specs/orders.yaml")).contains(Severity.WARN);
        assertThat(config.severity(NAMING, "other/legacy/orders.yaml")).contains(Severity.WARN);
    }

    /** SEC-71-2: the subcommand rules shows the globs. */
    @Test
    void keepsTheIgnoreFilesGlobsInTheOrderOfTheFile() {
        RuleConfig config = loader.load(".oas-gateway.yaml", "ignore-files: ['legacy/**', 'generated/*.yaml']",
                Path.of("specs"));

        assertThat(config.ignoreFiles()).containsExactly("legacy/**", "generated/*.yaml");
        assertThat(RuleConfig.defaults().ignoreFiles()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "rule: {}                                       | rule: unknown key. Allowed: extends, rules, ignore-files.",
        "extends: lenient                               | extends: must be recommended or strict.",
        "rules: { path-kebabcase: warn }                | rules.path-kebabcase: unknown rule id.",
        "rules: { security-explicit: warn }             | rules.security-explicit: rule 'security-explicit' is a CONTRACT rule and cannot be configured.",
        "rules: { deviation-schema: off }             | rules.deviation-schema: rule 'deviation-schema' is not configurable and cannot be configured.",
        "rules: { path-kebab-case: fatal }              | rules.path-kebab-case: severity must be off, info, warn, or error.",
        "rules: { path-kebab-case: {} }                 | rules.path-kebab-case: the setting is empty. Use severity, options, or both.",
        "rules: { path-kebab-case: { level: warn } }    | rules.path-kebab-case.level: unknown key. Allowed: severity, options.",
        "rules: { path-kebab-case: { options: { x: [a] } } } | rules.path-kebab-case.options.x: unknown option. The rule has no options.",
        "rules: { error-response-problem-json: { options: { media-types: [] } } } | rules.error-response-problem-json.options.media-types: must be a non-empty list of strings.",
        "rules: { error-response-problem-json: { options: { mediatypes: [a] } } } | rules.error-response-problem-json.options.mediatypes: unknown option. Allowed: media-types.",
        "ignore-files: ['legacy/[x']                    | ignore-files[0]: invalid glob: ",
        "ignore-files: ['../legacy/**']                 | ignore-files[0]: the glob must be relative to the directory of the configuration file and must not contain '..'.",
        "ignore-files: ['a/../../b/*.yaml']             | ignore-files[0]: the glob must be relative to the directory of the configuration file and must not contain '..'.",
        "ignore-files: ['/legacy/**']                   | ignore-files[0]: the glob must be relative to the directory of the configuration file and must not contain '..'.",
        "ignore-files: ['**a**a**a**a**a**a**a**ab']    | ignore-files[0]: the glob has 8 wildcards (* or **). The maximum is 4."
    })
    void reportsEachProblem(String content, String problem) {
        assertThat(problems(content)).anySatisfy(p -> assertThat(p).startsWith(problem.strip()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"extends: strict\nrules:\n#  path-kebab-case: off\n", "rules:\nignore-files:\nextends:\n"})
    void emptyKeysAreTheSameAsMissingKeys(String content) {
        RuleConfig config = load(content);

        assertThat(config.severity(NAMING, "a.yaml")).isPresent();
    }

    @Test
    void emptyExtendsIsTheRecommendedProfile() {
        assertThat(load("extends:\n").profile()).isEqualTo(Profile.RECOMMENDED);
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThat(problems("""
                rules:
                  path-kebab-case: off
                  path-kebab-case: error
                """)).containsExactly("rules.path-kebab-case: duplicate key at line 3.");
        assertThat(problems("extends: strict\nextends: recommended\n"))
                .containsExactly("extends: duplicate key at line 2.");
    }

    @Test
    void acceptsGlobsAtTheLimits() {
        String longGlob = "a/".repeat(99) + "**";

        assertThat(load("ignore-files: ['" + longGlob + "', '**/legacy/**/*.yaml', 'a*/**/b*']")).isNotNull();
        assertThat(problems("ignore-files: ['" + "a/".repeat(100) + "*']")).containsExactly(
                "ignore-files[0]: the glob has 201 characters. The maximum is 200.");
    }

    @Test
    void reportsAllProblemsInOneRun() {
        assertThat(problems("""
                extends: lenient
                rules:
                  security-explicit: warn
                  path-kebabcase: warn
                """)).hasSize(3);
    }

    @Test
    void reportsInvalidYamlWithLineAndColumn() {
        assertThat(problems("rules:\n  a: b\n    c: d")).singleElement()
                .satisfies(p -> assertThat(p).startsWith("invalid YAML at line 3, column"));
    }

    private RuleConfig load(String content) {
        return loader.load(".oas-gateway.yaml", content, Path.of(""));
    }

    private List<String> problems(String content) {
        RuleConfigException exception = catchThrowableOfType(RuleConfigException.class, () -> load(content));
        assertThat(exception).as("expected RuleConfigException").isNotNull();
        return exception.problems();
    }

    private static Rule rule(RuleMeta meta) {
        return new SpecRule() {
            @Override
            public RuleMeta meta() {
                return meta;
            }

            @Override
            public void check(SpecContext context, Reporter reporter) {
            }
        };
    }

    private static final class OptionsRule implements SpecRule, RuleWithOptions {

        @Override
        public RuleMeta meta() {
            return WITH_OPTIONS;
        }

        @Override
        public Map<String, OptionType> optionTypes() {
            return Map.of("media-types", OptionType.STRING_LIST);
        }

        @Override
        public Map<String, Object> optionDefaults() {
            return Map.of("media-types", List.of("application/json"));
        }

        @Override
        public Rule withOptions(RuleOptions options) {
            return this;
        }

        @Override
        public void check(SpecContext context, Reporter reporter) {
        }
    }
}
