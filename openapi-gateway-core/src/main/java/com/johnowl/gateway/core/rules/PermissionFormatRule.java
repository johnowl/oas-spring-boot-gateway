package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.access.AccessRuleCompiler;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Each permission name has the Microsoft Graph style, for example {@code Orders.Read}. */
public final class PermissionFormatRule implements OperationRule {

    static final Pattern FORMAT = Pattern.compile("^[A-Z][A-Za-z0-9]*(\\.[A-Z][A-Za-z0-9]*)+$");

    private static final RuleMeta META = ContractRule.meta(
            "permission-format",
            "Each permission in x-gateway-authorization matches " + FORMAT.pattern() + ", for example Orders.Read.",
            "Permissions are Entra App Role values. One naming style prevents near-duplicates such as"
                    + " orders.read and Orders.Read, which the gateway compares exactly.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        Map<String, Object> extensions = context.operation().getExtensions();
        if (extensions == null || !(extensions.get(AccessRuleCompiler.EXTENSION) instanceof Map<?, ?> authorization)) {
            return;
        }
        for (String key : List.of("all-of", "any-of")) {
            if (!(authorization.get(key) instanceof List<?> permissions)) {
                continue;
            }
            for (int i = 0; i < permissions.size(); i++) {
                if (permissions.get(i) instanceof String permission && !FORMAT.matcher(permission).matches()) {
                    reporter.report(
                            context.pointer() + "/" + AccessRuleCompiler.EXTENSION + "/" + key + "/" + i,
                            "Permission '" + permission + "' does not match " + FORMAT.pattern()
                                    + ". Example: Orders.Read.");
                }
            }
        }
    }
}
