package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import java.util.Iterator;

/**
 * Each security requirement is an object, and each scheme name in it exists in {@code components.securitySchemes}.
 */
public final class SecuritySchemeExistsRule implements SpecRule {

    private static final RuleMeta META = ContractRule.meta(
            "security-scheme-exists",
            "Each security requirement is an object, and each scheme name in it exists in"
                    + " components.securitySchemes.",
            "A requirement for an unknown scheme cannot be enforced.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(SpecContext context, Reporter reporter) {
        JsonNode schemes = context.spec().raw().at("/components/securitySchemes");
        SecurityRequirements.each(context.spec(), (pointer, security) -> {
            if (!security.isArray()) {
                return;
            }
            for (int i = 0; i < security.size(); i++) {
                if (!security.get(i).isObject()) {
                    reporter.report(pointer + "/" + i,
                            "Security requirement must be an object, for example '- entra: []'.");
                    continue;
                }
                for (Iterator<String> it = security.get(i).fieldNames(); it.hasNext(); ) {
                    String name = it.next();
                    if (!schemes.isObject() || !schemes.has(name)) {
                        reporter.report(pointer + "/" + i,
                                "Security scheme '" + name + "' is not defined in components.securitySchemes.");
                    }
                }
            }
        });
    }
}
