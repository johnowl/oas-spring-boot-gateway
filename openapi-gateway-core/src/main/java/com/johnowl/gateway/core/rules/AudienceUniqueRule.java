package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.validation.CrossSpecRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.SpecContext;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Each value of {@code x-gateway.audience} appears in exactly one OAS file of the run. The rule binds each audience
 * to one file, and so to one upstream: the gateway relays the token of an audience only to the upstream of its file.
 */
public final class AudienceUniqueRule implements CrossSpecRule {

    private static final String POINTER = "/x-gateway/audience";

    private static final RuleMeta META = ContractRule.meta(
            "audience-unique",
            "Each value of x-gateway.audience appears in exactly one OAS file.",
            "The gateway relays the token of an audience to the upstream of the same file. A second file with"
                    + " the audience of another backend and its own upstream would receive the tokens of that"
                    + " backend.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(List<SpecContext> specs, Reporter.ForFile reporter) {
        Map<String, Set<String>> filesByAudience = new LinkedHashMap<>();
        for (SpecContext spec : specs) {
            JsonNode xGateway = spec.spec().raw().get("x-gateway");
            JsonNode audience = xGateway == null ? null : xGateway.get("audience");
            if (audience == null || !audience.isTextual()) {
                // x-gateway-schema and audience-required report a missing object or an audience that is not a GUID.
                continue;
            }
            filesByAudience.computeIfAbsent(audience.asText(), k -> new LinkedHashSet<>()).add(spec.file());
        }
        filesByAudience.forEach((audience, files) -> {
            if (files.size() < 2) {
                return;
            }
            for (String file : files) {
                List<String> others = files.stream().filter(other -> !other.equals(file)).toList();
                int more = others.size() - 1;
                reporter.report(file, POINTER, "Audience '" + audience + "' is also declared in " + others.getFirst()
                        + (more > 0 ? " and in " + more + " more file(s)" : "")
                        + ". An audience can appear in one OAS file only.");
            }
        });
    }
}
