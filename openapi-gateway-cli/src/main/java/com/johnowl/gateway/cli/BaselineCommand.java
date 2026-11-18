package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.suppress.Baseline;
import com.johnowl.gateway.core.validation.ValidationEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** {@code baseline create}: writes the baseline of the current violations to stdout. */
@Command(
        name = "baseline",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        description = "Manages the baseline of known violations.",
        subcommands = BaselineCommand.Create.class)
final class BaselineCommand implements Callable<Integer> {

    @ParentCommand
    ValidateCommand parent;

    @Spec
    private CommandSpec spec;

    /** {@code baseline} without a subcommand is a usage error. */
    @Override
    public Integer call() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand: create");
    }

    @Command(
            name = "create",
            mixinStandardHelpOptions = true,
            versionProvider = ManifestVersionProvider.class,
            description = "Writes a baseline (JSON) of all violations of configurable rules to stdout.")
    static final class Create implements Callable<Integer> {

        @ParentCommand
        private BaselineCommand baseline;

        @Spec
        private CommandSpec spec;

        @Parameters(arity = "1..*", paramLabel = "<files-or-dirs>", description = "OAS files or directories.")
        private List<Path> paths;

        @Option(names = "--config", paramLabel = "<file>", description = "Rule configuration (.oas-gateway.yaml).")
        private Path configFile;

        @Override
        public Integer call() {
            RootArguments.reject(spec, "baseline", "baseline create");
            ValidateCommand root = baseline.parent;
            ValidationRun.Outcome outcome = ValidationRun.run(paths, configFile, null, root.testRules(),
                    ValidationEngine.NO_LIMIT, spec.commandLine().getErr());
            if (outcome.failed()) {
                return outcome.errorCode();
            }
            spec.commandLine().getOut().print(
                    Baseline.write(outcome.report().violations(), ValidationRun.metas(outcome.rules())));
            spec.commandLine().getOut().flush();
            return ExitCodes.OK;
        }
    }
}
