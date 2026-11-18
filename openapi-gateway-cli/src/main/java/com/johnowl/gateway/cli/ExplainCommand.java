package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.config.Profile;
import com.johnowl.gateway.core.config.RuleConfig;
import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.rules.RuleExamples;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * {@code explain <rule-id>}: prints the description, the rationale, and a bad and a good example of one rule. It
 * reads no configuration file, so it shows the default severity. See specs/032-cli-rules-and-explain.md.
 */
@Command(
        name = "explain",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        description = "Prints the description, the rationale, and a bad and a good example of a rule.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
            "0:The rule is explained.",
            "2:Usage error, an unknown rule id, or a path named explain in the working directory."
        })
final class ExplainCommand implements Callable<Integer> {

    @ParentCommand
    private ValidateCommand parent;

    @Spec
    private CommandSpec spec;

    @Parameters(paramLabel = "<rule-id>", description = "The id of a rule, for example operation-id-camel-case.")
    private String ruleId;

    @Override
    public Integer call() {
        RootArguments.reject(spec, "explain", "explain");
        if (RootArguments.pathWithSameName("explain", parent.workingDirectory(), spec.commandLine().getErr())) {
            return ExitCodes.ERROR;
        }
        List<Rule> rules = parent.testRules() != null ? parent.testRules() : BuiltInRules.all();
        Optional<Rule> found = rules.stream().filter(rule -> rule.meta().id().equals(ruleId)).findFirst();
        if (found.isEmpty()) {
            spec.commandLine().getErr().println("Unknown rule id '" + ConsoleText.printable(ruleId)
                    + "'. Run 'oas-gateway-validate rules' to list rule ids.");
            return ExitCodes.ERROR;
        }
        Rule rule = found.get();
        RuleMeta meta = rule.meta();
        RuleExamples.Examples examples = RuleExamples.of(meta.id());

        PrintWriter out = spec.commandLine().getOut();
        line(out, meta.id());
        line(out, "  Category:         " + meta.category().name());
        line(out, "  Default severity: " + RuleSettings.name(meta.defaultSeverity()));
        line(out, "  Strict severity:  " + RuleSettings.inProfile(meta, Profile.STRICT));
        line(out, "  Configurable:     " + (meta.configurable() ? "yes" : "no"));
        section(out, "Description", meta.description());
        section(out, "Rationale", meta.rationale());
        List<RuleSettings.Option> options = RuleSettings.options(rule, RuleConfig.defaults());
        if (!options.isEmpty()) {
            line(out, "");
            line(out, "Options");
            options.forEach(option -> line(out, "  " + option.name() + " (" + option.type() + "), default: "
                    + option.defaultValue()));
        }
        section(out, "Bad example", examples.bad());
        section(out, "Good example", examples.good());
        out.flush();
        return ExitCodes.OK;
    }

    /** An empty line, the heading, and each line of the text, indented by two spaces. */
    private static void section(PrintWriter out, String heading, String text) {
        line(out, "");
        line(out, heading);
        text.stripTrailing().lines().forEach(textLine -> line(out, textLine.isBlank() ? "" : "  " + textLine));
    }

    private static void line(PrintWriter out, String text) {
        out.print(ConsoleText.printable(text) + "\n");
    }
}
