package com.johnowl.gateway.core.config;

import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SeverityPolicy;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The effective rule configuration. It is the severity policy of the engine, so it never changes a
 * rule with {@code configurable=false}: the engine does not ask the policy for such a rule.
 */
public final class RuleConfig implements SeverityPolicy {

    private static final RuleConfig DEFAULTS = new RuleConfig(Profile.RECOMMENDED, Map.of(), Map.of(), Path.of(""), Map.of());

    private final Profile profile;
    private final Map<String, Optional<Severity>> overrides;
    private final Map<String, RuleOptions> options;
    private final Path baseDirectory;
    private final List<String> ignoreFileGlobs;
    private final List<PathMatcher> ignoreFiles;

    /** @param ignoreFiles each glob of {@code ignore-files}, in the order of the file, with its matcher */
    RuleConfig(Profile profile, Map<String, Optional<Severity>> overrides, Map<String, RuleOptions> options,
            Path baseDirectory, Map<String, PathMatcher> ignoreFiles) {
        this.profile = profile;
        this.overrides = Map.copyOf(overrides);
        this.options = Map.copyOf(options);
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.ignoreFileGlobs = List.copyOf(ignoreFiles.keySet());
        this.ignoreFiles = List.copyOf(ignoreFiles.values());
    }

    /** The {@code recommended} profile without overrides, the same as no configuration file. */
    public static RuleConfig defaults() {
        return DEFAULTS;
    }

    public Profile profile() {
        return profile;
    }

    /** Returns empty if the rule is {@code off}, or if the file matches {@code ignore-files}. */
    @Override
    public Optional<Severity> severity(RuleMeta rule, String file) {
        if (isIgnored(file)) {
            return Optional.empty();
        }
        Optional<Severity> override = overrides.get(rule.id());
        return override != null ? override : Optional.of(profile.severity(rule));
    }

    /**
     * The globs of {@code ignore-files}, in the order of the file. The subcommand {@code rules} shows them (spec 032).
     */
    public List<String> ignoreFiles() {
        return ignoreFileGlobs;
    }

    public RuleOptions options(String ruleId) {
        return options.getOrDefault(ruleId, RuleOptions.none());
    }

    /**
     * The rules with the options of this configuration. Each {@link RuleWithOptions} gets its options, also
     * {@link RuleOptions#none()}, so that it uses its defaults. Other rules and rules with
     * {@code configurable=false} stay the same, in the same order. The engine calls this method when the
     * configuration is its severity policy.
     */
    @Override
    public List<Rule> configure(List<Rule> rules) {
        return rules.stream()
                .map(rule -> rule instanceof RuleWithOptions withOptions && rule.meta().configurable()
                        ? withOptions.withOptions(options(rule.meta().id()))
                        : rule)
                .toList();
    }

    /** Globs are relative to the directory of the configuration file. */
    boolean isIgnored(String file) {
        if (ignoreFiles.isEmpty() || file.isEmpty()) {
            return false;
        }
        Path absolute = Path.of(file).toAbsolutePath().normalize();
        if (!absolute.startsWith(baseDirectory)) {
            return false;
        }
        Path relative = baseDirectory.relativize(absolute);
        return ignoreFiles.stream().anyMatch(matcher -> matcher.matches(relative));
    }
}
