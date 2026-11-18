package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.access.EffectiveSecurity;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;

/** A file with at least one protected operation declares {@code x-gateway.audience}. */
public final class AudienceRequiredRule implements SpecRule {

    private static final RuleMeta META = ContractRule.meta(
            "audience-required",
            "A file with at least one non-public operation declares x-gateway.audience.",
            "The gateway checks that the token is for the backend of the route. Without an audience, a"
                    + " token for another app with the same role names would pass.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(SpecContext context, Reporter reporter) {
        JsonNode xGateway = context.spec().raw().get("x-gateway");
        if (xGateway == null || !xGateway.isObject() || xGateway.hasNonNull("audience")) {
            // x-gateway-schema reports a missing or invalid object.
            return;
        }
        if (context.spec().openApi().getPaths() == null) {
            return;
        }
        boolean protectedOperation = context.spec().openApi().getPaths().entrySet().stream().anyMatch(path ->
                path.getValue().readOperationsMap().keySet().stream().anyMatch(method ->
                        EffectiveSecurity.of(context.spec(), path.getKey(), method.name()).kind()
                                == EffectiveSecurity.Kind.REQUIRED));
        if (protectedOperation) {
            reporter.report("/x-gateway", "The file has protected operations but no 'audience'. Add the client id"
                    + " of the backend app registration.");
        }
    }
}
