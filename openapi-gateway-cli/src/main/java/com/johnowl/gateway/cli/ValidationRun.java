package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.config.RuleConfig;
import com.johnowl.gateway.core.config.RuleConfigException;
import com.johnowl.gateway.core.config.RuleConfigLoader;
import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.spec.SpecFinder;
import com.johnowl.gateway.core.spec.SpecLoadResult;
import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.suppress.Baseline;
import com.johnowl.gateway.core.suppress.BaselineException;
import com.johnowl.gateway.core.suppress.DeviationSuppressions;
import com.johnowl.gateway.core.validation.BaselinePolicy;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.ValidationReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One validation run of the CLI: configuration, baseline, OAS files, engine. Usage and input errors are written to
 * stderr and give exit code 2, before any output on stdout.
 */
final class ValidationRun {

    /** The result: either an error exit code, or the report. */
    record Outcome(int errorCode, ValidationReport report, int fileCount, List<Rule> rules) {

        static Outcome error() {
            return new Outcome(ExitCodes.ERROR, null, 0, List.of());
        }

        boolean failed() {
            return report == null;
        }
    }

    /** The largest configuration file that the CLI reads (SEC-71-5). */
    static final int MAX_CONFIG_BYTES = 1024 * 1024;

    private ValidationRun() {
    }

    /**
     * @param baselineFile {@code null} for no baseline
     * @param testRules rules of a test, or {@code null} for the built-in rules
     * @param violationLimit {@link ValidationEngine#VIOLATION_LIMIT}, or {@link ValidationEngine#NO_LIMIT} for
     *     {@code baseline create}
     */
    static Outcome run(List<Path> paths, Path configFile, Path baselineFile, List<Rule> testRules,
            int violationLimit, PrintWriter err) {
        List<Rule> rules = testRules != null ? testRules : BuiltInRules.all();

        // Configuration errors come before any OAS file is read.
        RuleConfig config = config(configFile, rules, err);
        if (config == null) {
            return Outcome.error();
        }

        BaselinePolicy baseline = BaselinePolicy.empty();
        if (baselineFile != null) {
            Path file = relativeToWorkingDirectory(baselineFile);
            try {
                baseline = Baseline.read(display(file), Files.readString(file, StandardCharsets.UTF_8), metas(rules));
            } catch (IOException e) {
                err.println("Error: cannot read the baseline " + ConsoleText.printable(file + ": " + e.getMessage()));
                return Outcome.error();
            } catch (BaselineException e) {
                // One line for each problem. A line break inside a rule id must not start a new log line.
                e.problems().forEach(problem -> err.println("Error: " + ConsoleText.printable(display(file) + ": " + problem)));
                return Outcome.error();
            }
        }

        List<Path> files = find(paths, err);
        if (files == null) {
            return Outcome.error();
        }
        List<SpecLoadResult> loaded = load(files, err);
        if (loaded == null) {
            return Outcome.error();
        }

        DeviationSuppressions suppressions = new DeviationSuppressions(rules.stream().map(Rule::meta).toList());
        // The engine applies the rule options of the configuration (RuleConfig.configure).
        ValidationReport report = new ValidationEngine(rules, config, suppressions, baseline, violationLimit)
                .validateLoaded(loaded);
        return new Outcome(ExitCodes.OK, report, files.size(), rules);
    }

    /**
     * The rule configuration of the file, or {@link RuleConfig#defaults()} without a file. Writes each configuration
     * error and returns {@code null} if the file cannot be read or is invalid. The file is a regular file, not a
     * symbolic link, with at most {@link #MAX_CONFIG_BYTES} bytes (SEC-71-5).
     *
     * @param configFile {@code null} for no configuration file
     */
    static RuleConfig config(Path configFile, List<Rule> rules, PrintWriter err) {
        if (configFile == null) {
            return RuleConfig.defaults();
        }
        Path file = relativeToWorkingDirectory(configFile);
        try {
            String problem = configProblem(file);
            if (problem != null) {
                err.println("Error: the configuration " + ConsoleText.printable(display(file)) + " " + problem);
                return null;
            }
            return new RuleConfigLoader(rules).load(display(file), readConfig(file),
                    file.toAbsolutePath().getParent());
        } catch (IOException e) {
            err.println("Error: cannot read the configuration " + ConsoleText.printable(file + ": " + e.getMessage()));
            return null;
        } catch (RuleConfigException e) {
            e.problems().forEach(problem -> err.println(ConsoleText.printable(e.file() + ": " + problem)));
            return null;
        }
    }

    /** Returns why the CLI does not read the configuration file, or {@code null}. */
    private static String configProblem(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            return "is a symbolic link. Pass the file that it points to.";
        }
        if (!attributes.isRegularFile()) {
            return "is not a regular file.";
        }
        return null;
    }

    /**
     * Reads at most {@link #MAX_CONFIG_BYTES} bytes as UTF-8. The stream does not follow a symbolic link, also when the
     * file changes after {@link #configProblem}.
     */
    private static String readConfig(Path file) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(MAX_CONFIG_BYTES + 1);
        }
        if (bytes.length > MAX_CONFIG_BYTES) {
            throw new IOException("the file has more than " + MAX_CONFIG_BYTES + " bytes (1 MB)");
        }
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** The sorted OAS files of the paths. Writes the error and returns {@code null} if there is none. */
    static List<Path> find(List<Path> paths, PrintWriter err) {
        List<Path> files;
        try {
            files = new SpecFinder().find(paths.stream().map(ValidationRun::relativeToWorkingDirectory).toList());
        } catch (NoSuchFileException e) {
            err.println("Error: path does not exist: " + ConsoleText.printable(e.getFile()));
            return null;
        } catch (IOException e) {
            err.println("Error: cannot read " + ConsoleText.printable(e.getMessage()));
            return null;
        }
        if (files.isEmpty()) {
            err.println("Error: no *.yaml, *.yml or *.json file found in " + ConsoleText.printable(paths));
            return null;
        }
        return files;
    }

    /** Parses the files. Writes the error and returns {@code null} if a file cannot be read. */
    static List<SpecLoadResult> load(List<Path> files, PrintWriter err) {
        SpecLoader loader = new SpecLoader();
        List<SpecLoadResult> loaded = new ArrayList<>();
        for (Path file : files) {
            try {
                loaded.add(loader.load(file));
            } catch (IOException e) {
                err.println("Error: cannot read " + ConsoleText.printable(file + ": " + e.getMessage()));
                return null;
            }
        }
        return loaded;
    }

    static Map<String, RuleMeta> metas(List<Rule> rules) {
        Map<String, RuleMeta> metas = new LinkedHashMap<>();
        rules.forEach(rule -> metas.put(rule.meta().id(), rule.meta()));
        return metas;
    }

    /** Violations show paths relative to the working directory when the path is inside it. */
    static Path relativeToWorkingDirectory(Path path) {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(workingDirectory) ? workingDirectory.relativize(absolute) : absolute;
    }

    static String display(Path file) {
        return file.toString().replace('\\', '/');
    }
}
