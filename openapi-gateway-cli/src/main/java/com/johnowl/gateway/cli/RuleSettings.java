package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.config.OptionType;
import com.johnowl.gateway.core.config.Profile;
import com.johnowl.gateway.core.config.RuleConfig;
import com.johnowl.gateway.core.config.RuleWithOptions;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The severities and options of a rule, as the subcommands {@code rules} and {@code explain} show them. */
final class RuleSettings {

    /** The severity of a rule that does not run. */
    static final String OFF = "off";

    /**
     * An option of a rule.
     *
     * @param type for example {@code string-list}
     * @param defaultValue the value without configuration
     * @param value the value with the configuration
     */
    record Option(String name, String type, Object defaultValue, Object value) {
    }

    private RuleSettings() {
    }

    /** The rules sorted by id. */
    static List<Rule> sorted(List<Rule> rules) {
        return rules.stream().sorted(Comparator.comparing(rule -> rule.meta().id())).toList();
    }

    /** The value of the configuration file, for example {@code warn}. */
    static String name(Severity severity) {
        return severity.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The severity of the rule with the configuration, or {@code off}. {@code ignore-files} does not count, because it
     * depends on the file. A rule with {@code configurable=false} always has its default severity.
     */
    static String effective(RuleMeta rule, RuleConfig config) {
        if (!rule.configurable()) {
            return name(rule.defaultSeverity());
        }
        Optional<Severity> severity = config.severity(rule, "");
        return severity.map(RuleSettings::name).orElse(OFF);
    }

    /** The severity of the rule in the profile, without overrides. */
    static String inProfile(RuleMeta rule, Profile profile) {
        return name(rule.configurable() ? profile.severity(rule) : rule.defaultSeverity());
    }

    /** The options of the rule, sorted by name. A rule with {@code configurable=false} gets no options. */
    static List<Option> options(Rule rule, RuleConfig config) {
        if (!(rule instanceof RuleWithOptions withOptions) || !rule.meta().configurable()) {
            return List.of();
        }
        Map<String, Object> defaults = withOptions.optionDefaults();
        return withOptions.optionTypes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    String name = entry.getKey();
                    Object defaultValue = defaults.get(name);
                    Object value = switch (entry.getValue()) {
                        case STRING_LIST -> config.options(rule.meta().id()).stringList(name)
                                .map(Object.class::cast)
                                .orElse(defaultValue);
                    };
                    return new Option(name, typeName(entry.getValue()), defaultValue, value);
                })
                .toList();
    }

    private static String typeName(OptionType type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
