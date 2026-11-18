package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;

/** No security requirement object is empty ({@code - {}}), because that means optional authentication. */
public final class SecurityNoEmptyRequirementRule implements SpecRule {

    private static final RuleMeta META = ContractRule.meta(
            "security-no-empty-requirement",
            "No security requirement object is empty (- {}).",
            "An empty requirement means that anonymous access is also allowed. The gateway does not"
                    + " support optional authentication.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(SpecContext context, Reporter reporter) {
        SecurityRequirements.each(context.spec(), (pointer, security) -> {
            if (!security.isArray()) {
                return;
            }
            for (int i = 0; i < security.size(); i++) {
                JsonNode requirement = security.get(i);
                if (requirement.isObject() && requirement.isEmpty()) {
                    reporter.report(pointer + "/" + i, "Empty security requirement allows anonymous access."
                            + " The gateway does not support optional authentication. Use 'security: []' for a"
                            + " public operation.");
                }
            }
        });
    }
}
