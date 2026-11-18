package com.johnowl.gateway.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * picocli accepts the options and paths of the validation before a subcommand, but the subcommand does not read
 * them. A baseline without the arguments of the user would be wrong, so this is a usage error.
 */
final class RootArguments {

    /** The parameter label of a subcommand that takes OAS files, for example {@code baseline create}. */
    private static final String FILES = "<files-or-dirs>";

    /** The parameter label of the first directory of {@code diff}. */
    static final String BASE_DIR = "<base-dir>";

    private RootArguments() {
    }

    /**
     * The message names only the options that the subcommand has. For a subcommand without files ({@code rules},
     * {@code explain}), it says how to validate a directory with the name of the subcommand (QA-71-1).
     *
     * @param parent the name of the parent command, for example {@code baseline}
     * @param command the full subcommand, for example {@code baseline create}
     * @throws CommandLine.ParameterException if the root command has options or paths
     */
    static void reject(CommandSpec spec, String parent, String command) {
        CommandLine.ParseResult root = spec.root().commandLine().getParseResult();
        List<String> options = root.matchedOptions().stream()
                .filter(option -> !option.usageHelp() && !option.versionHelp())
                .map(OptionSpec::longestName)
                .toList();
        boolean takesFiles = hasParameter(spec, FILES);
        boolean takesDirectories = hasParameter(spec, BASE_DIR);
        String directoryHint = takesFiles || takesDirectories ? ""
                : " To validate a directory named " + parent + ", write ./" + parent + ".";
        if (!options.isEmpty()) {
            List<String> known = options.stream().filter(name -> spec.findOption(name) != null).toList();
            String write = known.isEmpty() ? "" : "Write " + String.join(", ", known) + " after '" + command + "'. ";
            throw new CommandLine.ParameterException(spec.commandLine(), write
                    + "Options before '" + parent + "' do not apply to '" + command + "'." + directoryHint);
        }
        if (!root.matchedPositionals().isEmpty()) {
            throw new CommandLine.ParameterException(spec.commandLine(), takesFiles
                    ? "Write the files and directories after '" + command + "'."
                    : takesDirectories
                    ? "Write the directories after '" + command + "'."
                    : "'" + command + "' takes no files or directories." + directoryHint);
        }
    }

    private static boolean hasParameter(CommandSpec spec, String label) {
        return spec.positionalParameters().stream().anyMatch(p -> label.equals(p.paramLabel()));
    }

    /**
     * {@code rules}, {@code explain} and {@code diff} do not run when a file or directory with their name exists in the
     * working directory. A CI script that passes such a directory without {@code ./} would otherwise get exit code 0
     * without a validation (SEC-71-1). A symbolic link with the name counts, also when its target does not exist.
     *
     * @return {@code true} after the error is written to {@code err}
     */
    static boolean pathWithSameName(String command, Path workingDirectory, PrintWriter err) {
        if (!Files.exists(workingDirectory.resolve(command), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        err.println("Error: A path named '" + command + "' exists. Write ./" + command
                + " to validate it, or run the command from another directory.");
        return true;
    }
}
