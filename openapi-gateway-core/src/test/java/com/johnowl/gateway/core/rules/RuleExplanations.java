package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.johnowl.gateway.core.config.RuleWithOptions;
import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Violation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks that each rule has what {@code explain} prints (spec 032): a description, a rationale, a bad and a good
 * example, and a default value of the right type for each option. It also runs the rule on both examples, wrapped by
 * {@link RuleExampleDocuments}: the rule reports the bad example and does not report the good example.
 */
final class RuleExplanations {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final SpecLoader LOADER = new SpecLoader();

    private RuleExplanations() {
    }

    /** Returns one message for each problem. An empty list means that each rule has a complete explanation. */
    static List<String> problems(List<Rule> rules, String root) {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Rule rule : rules) {
            RuleMeta meta = rule.meta();
            ids.add(meta.id());
            if (meta.description().isBlank()) {
                problems.add("Rule '" + meta.id() + "' has a blank description");
            }
            if (meta.rationale().isBlank()) {
                problems.add("Rule '" + meta.id() + "' has a blank rationale");
            }
            if (rule instanceof RuleWithOptions options) {
                checkDefaults(meta.id(), options, problems);
            }
            RuleExamples.Examples examples;
            try {
                examples = RuleExamples.of(meta.id(), root);
            } catch (IllegalStateException | IllegalArgumentException e) {
                problems.add(e.getMessage());
                continue;
            }
            if (check(meta.id(), "bad.yaml", examples.bad(), problems)) {
                run(rule, "bad.yaml", examples.bad(), true, problems);
            }
            if (check(meta.id(), "good.yaml", examples.good(), problems)) {
                run(rule, "good.yaml", examples.good(), false, problems);
            }
            if (examples.bad().equals(examples.good())) {
                problems.add("Rule '" + meta.id() + "' has the same bad and good example");
            }
        }
        try (Stream<Path> directories = Files.list(directory(root))) {
            directories.filter(Files::isDirectory)
                    .map(directory -> directory.getFileName().toString())
                    .filter(name -> !ids.contains(name))
                    .sorted()
                    .forEach(name -> problems.add("Example directory '" + name + "' has no rule"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return problems;
    }

    /** Each option has a default value, and the value has the type of the option (QA-70-3). */
    private static void checkDefaults(String id, RuleWithOptions rule, List<String> problems) {
        Map<String, Object> defaults = rule.optionDefaults();
        if (!defaults.keySet().equals(rule.optionTypes().keySet())) {
            problems.add("Rule '" + id + "' has no default value for each option");
        }
        rule.optionTypes().forEach((name, type) -> {
            if (!defaults.containsKey(name)) {
                return;
            }
            Object value = defaults.get(name);
            boolean matches = switch (type) {
                case STRING_LIST -> value instanceof List<?> list && !list.isEmpty()
                        && list.stream().allMatch(String.class::isInstance);
            };
            if (!matches) {
                problems.add("Rule '" + id + "' has a default value of option '" + name + "' that is not a "
                        + type.name().toLowerCase(Locale.ROOT).replace('_', '-'));
            }
        });
    }

    /** An example is not blank, and each of its YAML documents parses. Returns false for a problem. */
    private static boolean check(String id, String file, String example, List<String> problems) {
        if (example.isBlank()) {
            problems.add("Rule '" + id + "' has a blank example " + file);
            return false;
        }
        try (var documents = YAML.readerFor(Object.class).readValues(example)) {
            documents.readAll();
            return true;
        } catch (JacksonException e) {
            problems.add("Rule '" + id + "' has an example " + file + " that is not valid YAML");
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Runs the rule on the wrapped example. The rule reports the bad example and does not report the good example.
     * Only the examples of {@code oas-parse} may give {@code oas-parse} violations.
     */
    private static void run(Rule rule, String file, String example, boolean bad, List<String> problems) {
        String id = rule.meta().id();
        List<SpecSource> sources;
        try {
            sources = RuleExampleDocuments.wrap(example);
        } catch (RuleExampleDocuments.InvalidExampleException e) {
            problems.add("Rule '" + id + "' has an example " + file + " that " + e.getMessage());
            return;
        }
        List<Violation> violations = RuleRunner.runLoaded(rule,
                sources.stream().map(LOADER::load).toList());
        if (!id.equals(SpecLoader.RULE_ID)) {
            violations.stream().filter(v -> v.ruleId().equals(SpecLoader.RULE_ID)).findFirst().ifPresent(v ->
                    problems.add("Rule '" + id + "' has an example " + file + " that does not parse: "
                            + describe(v)));
        }
        List<Violation> reported = violations.stream()
                .filter(v -> v.ruleId().equals(id) && !v.message().startsWith("Rule failed:"))
                .toList();
        if (bad && reported.isEmpty()) {
            problems.add("Rule '" + id + "' does not report its example bad.yaml");
        }
        if (!bad) {
            violations.stream().filter(v -> v.ruleId().equals(id)).findFirst().ifPresent(v ->
                    problems.add("Rule '" + id + "' reports its example good.yaml: " + describe(v)));
        }
        violations.stream().filter(v -> v.message().startsWith("Rule failed:")).findFirst().ifPresent(v ->
                problems.add("Rule '" + id + "' fails on its example " + file + ": " + describe(v)));
    }

    private static String describe(Violation violation) {
        return violation.file() + "#" + violation.pointer() + " " + violation.message();
    }

    private static Path directory(String root) {
        URL url = RuleExplanations.class.getClassLoader().getResource(root);
        if (url == null) {
            throw new IllegalStateException("No resource directory " + root);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
