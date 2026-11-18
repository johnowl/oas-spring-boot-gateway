package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.suppress.Deviations;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;

/**
 * Metadata of the {@code deviation-unused} violations. The engine creates them after all rules,
 * from the suppression policy ({@code ValidationEngine.UNUSED_SUPPRESSION_RULE_ID}). This rule never reports.
 */
public final class DeviationUnusedRule implements SpecRule {

    static final RuleMeta META = new RuleMeta(
            Deviations.UNUSED_RULE_ID,
            Category.DESIGN,
            Severity.WARN,
            true,
            "Each x-deviation entry covers at least one violation.",
            "A deviation that covers nothing is noise, and it can hide a future problem.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(SpecContext context, Reporter reporter) {
        // The engine reports unused suppressions after all other rules.
    }
}
