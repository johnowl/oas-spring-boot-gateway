package com.johnowl.gateway.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.johnowl.gateway.core.config.Profile;
import com.johnowl.gateway.core.config.RuleConfig;
import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.text.UnsafeCharacters;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** {@code rules}: lists each rule with its category and its effective severity. See specs/032-cli-rules-and-explain.md. */
@Command(
        name = "rules",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        description = "Lists each rule, sorted by id, with its category and its effective severity.",
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
            "0:The rules are listed.",
            "2:Usage error, an invalid configuration file, or a path named rules in the working directory."
        })
final class RulesCommand implements Callable<Integer> {

    /** Increment on a breaking change to the JSON layout. */
    static final int FORMAT_VERSION = 1;

    /** The writer of the JSON report, with the escapes of {@link SafeCharacterEscapes}. */
    static final ObjectWriter WRITER = JsonReportFormatter.WRITER.with(new SafeCharacterEscapes());

    @ParentCommand
    private ValidateCommand parent;

    @Spec
    private CommandSpec spec;

    @Option(names = "--config", paramLabel = "<file>",
            description = "Rule configuration (.oas-gateway.yaml). Default: the recommended profile.")
    private Path configFile;

    @Option(names = "--format", defaultValue = "text", paramLabel = "<format>",
            completionCandidates = LowercaseValues.RulesFormats.class,
            description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    private RulesFormat format;

    @Override
    public Integer call() {
        RootArguments.reject(spec, "rules", "rules");
        if (RootArguments.pathWithSameName("rules", parent.workingDirectory(), spec.commandLine().getErr())) {
            return ExitCodes.ERROR;
        }
        List<Rule> rules = parent.testRules() != null ? parent.testRules() : BuiltInRules.all();
        RuleConfig config = ValidationRun.config(configFile, rules, spec.commandLine().getErr());
        if (config == null) {
            return ExitCodes.ERROR;
        }
        PrintWriter out = spec.commandLine().getOut();
        switch (format) {
            case TEXT -> writeText(RuleSettings.sorted(rules), config, out);
            case JSON -> writeJson(RuleSettings.sorted(rules), config, out);
        }
        out.flush();
        return ExitCodes.OK;
    }

    /**
     * The columns ID, CATEGORY and SEVERITY. Each column fits its longest value. The globs of {@code ignore-files}
     * follow the table, because the table does not show them (SEC-71-2).
     */
    private static void writeText(List<Rule> rules, RuleConfig config, PrintWriter out) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"ID", "CATEGORY", "SEVERITY"});
        for (Rule rule : rules) {
            RuleMeta meta = rule.meta();
            rows.add(new String[] {meta.id(), meta.category().name(), RuleSettings.effective(meta, config)});
        }
        int idWidth = rows.stream().mapToInt(row -> row[0].length()).max().orElse(0);
        int categoryWidth = rows.stream().mapToInt(row -> row[1].length()).max().orElse(0);
        for (String[] row : rows) {
            out.print(ConsoleText.printable(pad(row[0], idWidth) + "  " + pad(row[1], categoryWidth) + "  " + row[2])
                    + "\n");
        }
        if (!config.ignoreFiles().isEmpty()) {
            out.print("\nBest-practice rules do not check the files of ignore-files:\n");
            config.ignoreFiles().forEach(glob -> out.print("  " + ConsoleText.printable(glob) + "\n"));
        }
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /** The layout of specs/032-cli-rules-and-explain.md, "JSON format". */
    private static void writeJson(List<Rule> rules, RuleConfig config, PrintWriter out) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("formatVersion", FORMAT_VERSION);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "oas-gateway-validate");
        tool.put("version", ManifestVersionProvider.version());
        document.put("tool", tool);
        document.put("profile", config.profile().fileName());
        document.put("ignoreFiles", config.ignoreFiles());

        List<Map<String, Object>> entries = new ArrayList<>();
        for (Rule rule : rules) {
            RuleMeta meta = rule.meta();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", meta.id());
            entry.put("category", meta.category().name());
            entry.put("configurable", meta.configurable());
            entry.put("defaultSeverity", RuleSettings.name(meta.defaultSeverity()));
            Map<String, Object> profiles = new LinkedHashMap<>();
            for (Profile profile : Profile.values()) {
                profiles.put(profile.fileName(), RuleSettings.inProfile(meta, profile));
            }
            entry.put("profiles", profiles);
            entry.put("severity", RuleSettings.effective(meta, config));
            List<Map<String, Object>> options = new ArrayList<>();
            for (RuleSettings.Option option : RuleSettings.options(rule, config)) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("name", option.name());
                value.put("type", option.type());
                value.put("default", option.defaultValue());
                value.put("value", option.value());
                options.add(value);
            }
            entry.put("options", options);
            entries.add(entry);
        }
        document.put("rules", entries);

        try {
            out.print(WRITER.writeValueAsString(document) + "\n");
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * JSON escapes for the characters of {@link UnsafeCharacters} and for each surrogate, so that a value of the
     * configuration file shows no control character and no bidirectional control, and a JSON parser reads the same
     * value back (SEC-71-4). A surrogate pair becomes two escapes; a lone surrogate becomes one escape, and the
     * writer does not replace it with {@code ?}.
     */
    static final class SafeCharacterEscapes extends CharacterEscapes {

        private final int[] ascii = standardAsciiEscapesForJSON();

        /**
         * Each unsafe ASCII character without a JSON escape gets one. A short escape, for example for a line feed,
         * stays.
         */
        SafeCharacterEscapes() {
            for (char c = 0; c < ascii.length; c++) {
                if (UnsafeCharacters.isUnsafe(c) && ascii[c] == 0) {
                    ascii[c] = ESCAPE_STANDARD;
                }
            }
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return ascii;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            if (UnsafeCharacters.isUnsafe((char) ch) || Character.isSurrogate((char) ch)) {
                return new SerializedString(String.format("\\u%04X", ch));
            }
            return null;
        }
    }
}
