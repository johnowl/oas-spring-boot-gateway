package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Rule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Locates rule fixtures and checks that the rule catalog and the fixtures match.
 *
 * <p>A fixture directory has {@code expected.txt} and either the files {@code valid.yaml} and
 * {@code invalid.yaml}, or the directories {@code valid/} and {@code invalid/} with several OAS
 * files for a cross-spec rule. For directories, each line of {@code expected.txt} has the format
 * {@code <path relative to invalid/>#<pointer>}, and there is one line for each violation.
 */
final class RuleFixtures {

    static final String EXPECTED = "expected.txt";

    private static final Pattern KEBAB_CASE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private RuleFixtures() {
    }

    /** The directory {@code src/test/resources/rules} on the test classpath. */
    static Path root() {
        try {
            return Path.of(RuleFixtures.class.getResource("/rules").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads {@code expected.txt}: one entry on each line; blank lines are ignored. */
    static Set<String> expected(Path directory) throws IOException {
        return new HashSet<>(expectedEntries(directory));
    }

    /** Reads {@code expected.txt} and keeps duplicate entries. */
    static List<String> expectedEntries(Path directory) throws IOException {
        List<String> entries = new ArrayList<>();
        for (String line : Files.readAllLines(directory.resolve(EXPECTED))) {
            if (!line.isBlank()) {
                entries.add(line.strip());
            }
        }
        return entries;
    }

    private static boolean hasNoEntry(Path directory) {
        try {
            return expected(directory).isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns one message for each problem. An empty list means that catalog and fixtures match. */
    static List<String> problems(List<Rule> rules, Path root) {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Rule rule : rules) {
            String id = rule.meta().id();
            if (!ids.add(id)) {
                problems.add("Duplicate rule id '" + id + "'");
            }
            if (!KEBAB_CASE.matcher(id).matches()) {
                problems.add("Rule id '" + id + "' is not kebab-case");
            }
            for (String fixture : List.of("valid", "invalid")) {
                Path directory = root.resolve(id);
                if (!Files.isRegularFile(directory.resolve(fixture + ".yaml"))
                        && !Files.isDirectory(directory.resolve(fixture))) {
                    problems.add("Rule '" + id + "' has no fixture " + id + "/" + fixture + ".yaml or "
                            + id + "/" + fixture + "/");
                }
            }
            if (!Files.isRegularFile(root.resolve(id).resolve(EXPECTED))) {
                problems.add("Rule '" + id + "' has no fixture file " + id + "/" + EXPECTED);
            } else if (hasNoEntry(root.resolve(id))) {
                problems.add("Rule '" + id + "' has an empty " + id + "/" + EXPECTED);
            }
        }
        try (Stream<Path> directories = Files.list(root)) {
            directories.filter(Files::isDirectory)
                    .map(directory -> directory.getFileName().toString())
                    .filter(name -> !ids.contains(name))
                    .sorted()
                    .forEach(name -> problems.add("Fixture directory '" + name + "' has no rule"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return problems;
    }
}
