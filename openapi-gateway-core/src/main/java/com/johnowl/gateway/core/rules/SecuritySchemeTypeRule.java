package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Each security scheme has type {@code openIdConnect} or {@code oauth2}: the Microsoft Entra ID
 * login that the gateway enforces. Other types describe a protection that the gateway does not do.
 * A scheme with {@code $ref} has no type in the raw document, so the rule reads the type of the
 * resolved scheme. If the type of a {@code $ref} scheme is unknown, the rule also reports it.
 */
public final class SecuritySchemeTypeRule implements SpecRule {

    static final Set<String> ALLOWED = Set.of("openIdConnect", "oauth2");

    private static final RuleMeta META = ContractRule.meta(
            "security-scheme-type",
            "Each scheme in components.securitySchemes has type openIdConnect or oauth2, also when it uses $ref.",
            "The gateway authenticates users with a Microsoft Entra ID session. A scheme such as apiKey,"
                    + " http basic, or mutualTLS would document a protection that the gateway does not enforce.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(SpecContext context, Reporter reporter) {
        JsonNode schemes = context.spec().raw().at("/components/securitySchemes");
        if (!schemes.isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = schemes.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> scheme = it.next();
            String name = scheme.getKey();
            String pointer = "/components/securitySchemes/" + JsonPointers.escape(name);
            JsonNode type = scheme.getValue().get("type");
            if (type != null && type.isTextual()) {
                if (!ALLOWED.contains(type.asText())) {
                    reporter.report(pointer + "/type", "Security scheme '" + name + "' has type '" + type.asText()
                            + "'. The gateway supports only openIdConnect and oauth2 (Microsoft Entra ID).");
                }
            } else if (scheme.getValue().has("$ref")) {
                String resolved = resolvedType(context, name);
                if (resolved == null) {
                    reporter.report(pointer, "Security scheme '" + name + "' uses $ref, and the type of the"
                            + " referenced scheme is unknown. The gateway supports only openIdConnect and oauth2"
                            + " (Microsoft Entra ID).");
                } else if (!ALLOWED.contains(resolved)) {
                    reporter.report(pointer, "Security scheme '" + name + "' refers to a scheme with type '"
                            + resolved + "'. The gateway supports only openIdConnect and oauth2 (Microsoft Entra ID).");
                }
            }
        }
    }

    /** The type of the scheme after swagger-parser resolved the references, or null. */
    private static String resolvedType(SpecContext context, String name) {
        Components components = context.spec().openApi().getComponents();
        Map<String, SecurityScheme> schemes = components == null ? null : components.getSecuritySchemes();
        SecurityScheme scheme = schemes == null ? null : schemes.get(name);
        return scheme == null || scheme.getType() == null ? null : scheme.getType().toString();
    }
}
