package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.access.AccessDiff;
import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.spec.SpecFile;
import com.johnowl.gateway.core.spec.SpecFinder;
import com.johnowl.gateway.core.spec.SpecLoadResult;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.ValidationReport;
import com.johnowl.gateway.core.validation.Violation;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.UnaryOperator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * {@code diff <base-dir> <head-dir>}: reports each change that loosens the access to an existing operation, and each
 * new or changed audience binding. See specs/034-access-change-review.md.
 */
@Command(
        name = "diff",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        description = "Reports each change of the head directory that loosens the access to an operation of the base"
                + " directory, and each new or changed audience binding. Both need a review.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
            "0:No access loosening change and no new or changed audience binding.",
            "1:At least one access loosening change, or a new or changed audience binding.",
            "2:Usage error, I/O error, a file with a parse error or a CONTRACT error, a head directory without OAS"
                    + " files, or a path named diff in the working directory."
        })
final class DiffCommand implements Callable<Integer> {

    /** The environment variable of the job summary file of GitHub Actions. */
    static final String STEP_SUMMARY = "GITHUB_STEP_SUMMARY";

    /** The environment lookup of the job summary, for tests. */
    private final UnaryOperator<String> environment;

    @ParentCommand
    private ValidateCommand parent;

    @Spec
    private CommandSpec spec;

    @Option(names = "--format", defaultValue = "text", paramLabel = "<format>",
            completionCandidates = LowercaseValues.DiffFormats.class,
            description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    private DiffFormat format;

    @Parameters(index = "0", paramLabel = RootArguments.BASE_DIR,
            description = "The directory with the OAS files before the change, for example of the base branch.")
    private Path baseDirectory;

    @Parameters(index = "1", paramLabel = "<head-dir>",
            description = "The directory with the OAS files after the change.")
    private Path headDirectory;

    DiffCommand() {
        this(System::getenv);
    }

    DiffCommand(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    @Override
    public Integer call() {
        RootArguments.reject(spec, "diff", "diff");
        PrintWriter err = spec.commandLine().getErr();
        if (RootArguments.pathWithSameName("diff", parent.workingDirectory(), err)) {
            return ExitCodes.ERROR;
        }
        // Both sides are read and checked before any output on stdout.
        List<SpecFile> base = load("base", baseDirectory, true, err);
        if (base == null) {
            return ExitCodes.ERROR;
        }
        List<SpecFile> head = load("head", headDirectory, false, err);
        if (head == null) {
            return ExitCodes.ERROR;
        }
        AccessDiff.Result result = new AccessDiff().compare(base, head);

        PrintWriter out = spec.commandLine().getOut();
        switch (format) {
            case TEXT -> DiffReport.writeText(result, out);
            case GITHUB -> DiffReport.writeGithub(result, new SourceLocator(), out);
            case JSON -> DiffReport.writeJson(result, out);
        }
        out.flush();
        if (format == DiffFormat.GITHUB && !writeJobSummary(result, err)) {
            return ExitCodes.ERROR;
        }
        // A binding can send the token of an audience to another backend, so it needs the label too (SEC-74-1).
        return result.changes().isEmpty() && result.bindings().isEmpty() ? ExitCodes.OK : ExitCodes.VIOLATIONS;
    }

    /**
     * The OAS files of the directory, loaded and checked with the CONTRACT rules. Writes the error and returns
     * {@code null} if the directory does not exist, cannot be read, or has a file with a parse error or a CONTRACT
     * error: then the access rules cannot be compiled.
     *
     * @param emptyAllowed {@code true} for the base: without files, each operation of the head is new
     */
    private static List<SpecFile> load(String side, Path directory, boolean emptyAllowed, PrintWriter err) {
        Path relative = ValidationRun.relativeToWorkingDirectory(directory);
        if (!Files.isDirectory(relative)) {
            err.println("Error: the " + side + " directory " + ConsoleText.printable(ValidationRun.display(relative))
                    + (Files.exists(relative) ? " is not a directory." : " does not exist."));
            return null;
        }
        List<Path> files;
        try {
            files = new SpecFinder().find(List.of(relative));
        } catch (IOException e) {
            err.println("Error: cannot read " + ConsoleText.printable(e.getMessage()));
            return null;
        }
        if (files.isEmpty() && !emptyAllowed) {
            err.println("Error: no *.yaml, *.yml or *.json file found in the head directory "
                    + ConsoleText.printable(ValidationRun.display(relative)) + ".");
            return null;
        }
        List<SpecLoadResult> loaded = ValidationRun.load(files, err);
        if (loaded == null) {
            return null;
        }
        ValidationReport report = new ValidationEngine(BuiltInRules.contract()).validateLoaded(loaded);
        if (report.hasAtLeast(Severity.ERROR)) {
            err.println("Error: the " + side + " directory has files with errors, so diff cannot compile the access"
                    + " rules:");
            for (Violation violation : report.violations()) {
                err.println("Error: " + ConsoleText.printable(violation.file() + "#" + violation.pointer() + " "
                        + violation.ruleId() + ": " + violation.message()));
            }
            return null;
        }
        return loaded.stream().flatMap(result -> result.spec().stream()).toList();
    }

    /**
     * Appends the tables of the loosening changes and of the audience bindings to the job summary file, if the
     * environment has {@value #STEP_SUMMARY}. Each table is written only if it has a row. The tables are a control
     * for reviewers, so an I/O error returns {@code false}.
     */
    private boolean writeJobSummary(AccessDiff.Result result, PrintWriter err) {
        String file = environment.apply(STEP_SUMMARY);
        if (file == null || file.isEmpty() || result.changes().isEmpty() && result.bindings().isEmpty()) {
            return true;
        }
        String markdown = (result.changes().isEmpty() ? "" : JobSummary.changes(result.changes()))
                + (result.bindings().isEmpty() ? "" : JobSummary.bindings(result.bindings()));
        try {
            Files.writeString(Path.of(file), markdown, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            return true;
        } catch (IOException | RuntimeException e) {
            err.println("Error: cannot write the job summary " + ConsoleText.printable(file + ": " + e.getMessage()));
            return false;
        }
    }
}
