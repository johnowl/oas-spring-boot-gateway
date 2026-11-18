package com.johnowl.gateway.core.validation;

import java.util.List;
import java.util.Optional;

/**
 * Sets the effective severity of a configurable rule for one file. The engine does not call
 * the policy for rules with {@code configurable=false}.
 */
@FunctionalInterface
public interface SeverityPolicy {

    /**
     * @return the effective severity, or empty if the rule is off for this file. A rule that is
     *     off does not run for the file.
     */
    Optional<Severity> severity(RuleMeta rule, String file);

    /**
     * The rules that the engine runs. A policy with rule options, for example the rule configuration of spec 027,
     * returns the rules with these options. The engine calls this method itself, so that a caller cannot forget
     * the options. The default returns the rules as they are.
     */
    default List<Rule> configure(List<Rule> rules) {
        return rules;
    }

    /** Uses the default severity of each rule for every file. */
    static SeverityPolicy defaults() {
        return (rule, file) -> Optional.of(rule.defaultSeverity());
    }
}
