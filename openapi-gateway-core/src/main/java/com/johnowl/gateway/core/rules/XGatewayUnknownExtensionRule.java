package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import java.util.List;
import java.util.Map;

/** Rejects operation keys that start with {@code x-gateway} and are not in the allowlist. */
public final class XGatewayUnknownExtensionRule implements OperationRule {

    static final List<String> ALLOWED = List.of(
            XGatewayResponseTimeoutRule.EXTENSION,
            "x-gateway-authorization");

    private static final RuleMeta META = new RuleMeta(
            "x-gateway-unknown-extension",
            Category.CONTRACT,
            Severity.ERROR,
            false,
            "Each operation key that starts with x-gateway is a known extension.",
            "A typo in an extension name is a valid OAS extension, so the gateway would ignore it"
                    + " without an error.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        Map<String, Object> extensions = context.operation().getExtensions();
        if (extensions == null) {
            return;
        }
        for (String key : extensions.keySet()) {
            if (key.startsWith(XGatewaySchemaRule.EXTENSION) && !ALLOWED.contains(key)) {
                reporter.report(
                        context.pointer() + "/" + JsonPointers.escape(key),
                        "Unknown extension '" + key + "'. Allowed: " + String.join(", ", ALLOWED) + ".");
            }
        }
    }
}
