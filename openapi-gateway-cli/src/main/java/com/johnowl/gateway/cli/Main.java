package com.johnowl.gateway.cli;

import java.io.PrintWriter;
import picocli.CommandLine;

/** Entry point of {@code oas-gateway-validate}. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(execute(commandLine(new ValidateCommand()), args));
    }

    /**
     * Runs the command line. An {@link Error}, for example {@link OutOfMemoryError}, also returns exit code 2,
     * not the exit code 1 of the JVM, because exit code 1 means "violations found".
     */
    static int execute(CommandLine commandLine, String... args) {
        try {
            return commandLine.execute(args);
        } catch (Error e) {
            commandLine.getErr().println("Error: " + describe(e));
            commandLine.getErr().flush();
            return ExitCodes.ERROR;
        }
    }

    /**
     * The configured command line. An exception in the command returns exit code 2, because exit
     * code 1 means "violations found".
     */
    static CommandLine commandLine(ValidateCommand command) {
        return commandLine(command, CommandLine.defaultFactory());
    }

    /**
     * For tests: the factory creates the subcommands, for example a {@link DiffCommand} with another environment
     * lookup.
     */
    static CommandLine commandLine(ValidateCommand command, CommandLine.IFactory factory) {
        CommandLine commandLine = new CommandLine(command, factory);
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        // An argument such as @args.txt is a path, not a file of more arguments (SEC-71-3).
        commandLine.setExpandAtFiles(false);
        commandLine.setParameterExceptionHandler(Main::usageError);
        commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> {
            cmd.getErr().println("Error: " + describe(exception));
            return ExitCodes.ERROR;
        });
        return commandLine;
    }

    /**
     * The handler of picocli without the raw arguments: the message can contain an argument of the user, so no line of
     * it may start a GitHub workflow command such as {@code ::error} (SEC-71-3).
     */
    private static int usageError(CommandLine.ParameterException exception, String[] args) {
        CommandLine cmd = exception.getCommandLine();
        PrintWriter err = cmd.getErr();
        err.println(ConsoleText.untrusted(exception.getMessage()));
        if (!CommandLine.UnmatchedArgumentException.printSuggestions(exception, err)) {
            cmd.usage(err, cmd.getColorScheme());
        }
        err.flush();
        return cmd.getCommandSpec().exitCodeOnInvalidInput();
    }

    /** The message, or the class name if the exception has no message. */
    private static String describe(Throwable exception) {
        return ConsoleText.printable(exception.getMessage() == null ? exception.toString() : exception.getMessage());
    }
}
