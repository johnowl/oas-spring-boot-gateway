package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;

/** Metadata of a CONTRACT rule: always ERROR and not configurable. */
final class ContractRule {

    private ContractRule() {
    }

    static RuleMeta meta(String id, String description, String rationale) {
        return new RuleMeta(id, Category.CONTRACT, Severity.ERROR, false, description, rationale);
    }
}
