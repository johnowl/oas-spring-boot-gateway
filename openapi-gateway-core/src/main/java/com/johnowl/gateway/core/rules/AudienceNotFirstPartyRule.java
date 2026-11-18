package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;

/** {@code x-gateway.audience} is not the app id of a well-known Microsoft first-party app. */
public final class AudienceNotFirstPartyRule implements SpecRule {

    private static final RuleMeta META = ContractRule.meta(
            "audience-not-first-party",
            "x-gateway.audience is not the app id of a well-known Microsoft first-party app, for example"
                    + " Microsoft Graph.",
            "The gateway requests a token for the audience and relays it to the upstream. gateway-bff has"
                    + " consent for Microsoft Graph, so a Graph audience would send Graph tokens of the users"
                    + " to the backend.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(SpecContext context, Reporter reporter) {
        JsonNode xGateway = context.spec().raw().get("x-gateway");
        JsonNode audience = xGateway == null ? null : xGateway.get("audience");
        if (audience == null || !audience.isTextual()) {
            // x-gateway-schema reports a missing object or an audience that is not a GUID.
            return;
        }
        String value = audience.asText();
        FirstPartyApps.name(value).ifPresent(name -> reporter.report("/x-gateway/audience",
                "Audience '" + value + "' is the Microsoft first-party app '" + name
                        + "'. The gateway does not request tokens for Microsoft APIs."));
    }
}
