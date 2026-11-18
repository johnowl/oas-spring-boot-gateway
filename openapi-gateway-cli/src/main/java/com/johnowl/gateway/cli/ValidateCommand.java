package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.ValidationEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Validates OAS files with all built-in rules: the CONTRACT rules of the gateway and the best-practice rules. */
@Command(
        name = "oas-gateway-validate",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        description = "Validates OAS files for the gateway.",
        subcommands = {BaselineCommand.class, RulesCommand.class, ExplainCommand.class, DiffCommand.class},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
            "0:No violation at or above the --fail-on severity.",
            "1:At least one violation at or above the --fail-on severity.",
            "2:Usage error, I/O error, or unexpected error."
        })
final class ValidateCommand implements Callable<Integer> {

    /** Rules of a test, or {@code null} for the built-in rules. */
    private final List<Rule> testRules;

    /** The directory in which {@code rules}, {@code explain} and {@code diff} look for a path with their name. */
    private final Path workingDirectory;

    @Spec
    private CommandSpec spec;

    // Not required for picocli, so that the subcommands "baseline", "rules", "explain" and "diff" work. call() checks it.
    @Parameters(arity = "0..*", paramLabel = "<files-or-dirs>",
            description = "OAS files or directories. Directories are searched recursively for *.yaml, *.yml and *.json."
                    + " Write a directory named baseline, rules, explain or diff with ./ in front, for example"
                    + " ./baseline.")
    private List<Path> paths = List.of();

    @Option(names = "--format", defaultValue = "text", paramLabel = "<format>",
            completionCandidates = LowercaseValues.Formats.class,
            description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    private OutputFormat format;

    @Option(names = "--fail-on", defaultValue = "error", paramLabel = "<severity>",
            completionCandidates = LowercaseValues.FailOns.class,
            description = "Lowest severity that returns exit code 1: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    private FailOn failOn;

    @Option(names = "--config", paramLabel = "<file>",
            description = "Rule configuration (.oas-gateway.yaml): profile, severities, options, ignore-files.")
    private Path configFile;

    @Option(names = "--baseline", paramLabel = "<file>",
            description = "Baseline file (.oas-baseline.json). Known violations of configurable rules are not reported.")
    private Path baselineFile;

    ValidateCommand() {
        this(null);
    }

    /**
     * For tests: runs other rules than the built-in rules.
     *
     * @param rules rules of a test, or {@code null} for the built-in rules
     */
    ValidateCommand(List<Rule> rules) {
        this(rules, Path.of(""));
    }

    /**
     * For tests: runs in another working directory.
     *
     * @param rules rules of a test, or {@code null} for the built-in rules
     */
    ValidateCommand(List<Rule> rules, Path workingDirectory) {
        this.testRules = rules == null ? null : List.copyOf(rules);
        this.workingDirectory = workingDirectory;
    }

    List<Rule> testRules() {
        return testRules;
    }

    Path workingDirectory() {
        return workingDirectory;
    }

    @Override
    public Integer call() {
        if (paths.isEmpty()) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Missing required parameter: '<files-or-dirs>'");
        }
        ValidationRun.Outcome outcome = ValidationRun.run(paths, configFile, baselineFile, testRules,
                ValidationEngine.VIOLATION_LIMIT, spec.commandLine().getErr());
        if (outcome.failed()) {
            return outcome.errorCode();
        }
        formatter(outcome.rules()).write(outcome.report(), outcome.fileCount(), spec.commandLine().getOut());
        return outcome.report().hasAtLeast(failOn.severity()) ? ExitCodes.VIOLATIONS : ExitCodes.OK;
    }

    private ReportFormatter formatter(List<Rule> rules) {
        return switch (format) {
            case TEXT -> new TextReportFormatter();
            case GITHUB -> new GithubReportFormatter(new SourceLocator());
            case JSON -> new JsonReportFormatter(new SourceLocator(), ManifestVersionProvider.version());
            case SARIF -> new SarifReportFormatter(new SourceLocator(), ManifestVersionProvider.version(), rules);
        };
    }
}
