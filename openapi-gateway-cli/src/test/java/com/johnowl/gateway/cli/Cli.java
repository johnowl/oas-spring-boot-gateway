package com.johnowl.gateway.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.UnaryOperator;
import picocli.CommandLine;

/** Runs the command line in tests. */
final class Cli {

    record Result(int code, String out, String err) {
    }

    private Cli() {
    }

    static Result run(ValidateCommand command, String... args) {
        return run(command, name -> null, args);
    }

    /** Runs {@code diff} with the environment lookup. */
    static Result run(ValidateCommand command, UnaryOperator<String> environment, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        CommandLine commandLine = Main.commandLine(command, new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> type) throws Exception {
                return type == DiffCommand.class ? (K) new DiffCommand(environment) : defaults.create(type);
            }
        });
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));
        int code = commandLine.execute(args);
        return new Result(code, normalize(out), normalize(err));
    }

    private static String normalize(StringWriter writer) {
        return writer.toString().replace(System.lineSeparator(), "\n");
    }
}
