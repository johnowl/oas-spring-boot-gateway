package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.spec.SpecLoadResult;
import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.suppress.DeviationSuppressions;
import com.johnowl.gateway.core.suppress.Deviations;
import com.johnowl.gateway.core.validation.BaselinePolicy;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.SeverityPolicy;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.Violation;
import java.util.List;

/** Runs one rule on inline YAML in unit tests. */
final class RuleRunner {

    private RuleRunner() {
    }

    static List<Violation> run(Rule rule, String yaml) {
        return new ValidationEngine(List.of(rule))
                .validate(List.of(new SpecSource("test.yaml", yaml)))
                .violations();
    }

    /**
     * Runs the engine with only this rule. The engine loads the files with the full loader, so a file that does not
     * parse gives {@code oas-parse} violations.
     */
    static List<Violation> runLoaded(Rule rule, List<SpecLoadResult> loaded) {
        if (rule.meta().id().equals(Deviations.UNUSED_RULE_ID)) {
            // The rule depends on the other rules and on the x-deviation policy (spec 028).
            List<Rule> all = BuiltInRules.all();
            String id = rule.meta().id();
            return new ValidationEngine(all, SeverityPolicy.defaults(),
                    new DeviationSuppressions(all.stream().map(Rule::meta).toList()), BaselinePolicy.empty())
                    .validateLoaded(loaded).violations().stream().filter(v -> v.ruleId().equals(id)).toList();
        }
        return new ValidationEngine(List.of(rule)).validateLoaded(loaded).violations();
    }
}
