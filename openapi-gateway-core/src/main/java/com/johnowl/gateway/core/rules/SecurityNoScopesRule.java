package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import java.util.Iterator;
import java.util.Map;

/** Each security requirement lists no scopes: {@code entra: []}. */
public final class SecurityNoScopesRule implements SpecRule {

    private static final RuleMeta META = ContractRule.meta(
            "security-no-scopes",
            "Each security requirement, at the root and on each operation, maps the scheme to an empty list.",
            "The gateway checks permissions (Entra App Roles), not scopes. A scope in the requirement"
                    + " looks like a check that the gateway does not do.");

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
                for (Iterator<Map.Entry<String, JsonNode>> it = requirement.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> scheme = it.next();
                    if (!scheme.getValue().isArray() || !scheme.getValue().isEmpty()) {
                        reporter.report(pointer + "/" + i + "/" + JsonPointers.escape(scheme.getKey()),
                                "Scheme '" + scheme.getKey() + "' lists scopes " + scheme.getValue()
                                        + ". Use an empty list: the gateway checks permissions"
                                        + " in x-gateway-authorization, not scopes.");
                    }
                }
            }
        });
    }
}
