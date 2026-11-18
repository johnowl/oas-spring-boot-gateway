package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;

/** Metadata of a best-practice rule: configurable, CLI only. */
final class BestPracticeRule {

    private BestPracticeRule() {
    }

    static RuleMeta meta(String id, Category category, Severity severity, String description, String rationale) {
        return new RuleMeta(id, category, severity, true, description, rationale);
    }
}
