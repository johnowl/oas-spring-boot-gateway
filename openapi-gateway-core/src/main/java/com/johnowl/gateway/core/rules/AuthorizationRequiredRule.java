package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.access.AccessRuleCompiler;
import com.johnowl.gateway.core.access.EffectiveSecurity;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;

/**
 * Each operation with a security requirement has {@code x-gateway-authorization}. An operation
 * without security is reported by {@code security-explicit} only.
 */
public final class AuthorizationRequiredRule implements OperationRule {

    private static final RuleMeta META = ContractRule.meta(
            "authorization-required",
            "Each non-public operation has x-gateway-authorization.",
            "The permission of each operation is a decision. Without the extension, any authenticated"
                    + " user could call the operation by mistake. Use authenticated-only: true to make the"
                    + " decision explicit.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        EffectiveSecurity security = EffectiveSecurity.of(context.spec().spec(), context.path(), context.method().name());
        boolean present = context.operation().getExtensions() != null
                && context.operation().getExtensions().containsKey(AccessRuleCompiler.EXTENSION);
        if (security.kind() == EffectiveSecurity.Kind.REQUIRED && !present) {
            reporter.report(context.pointer(), "Operation has no x-gateway-authorization. Declare all-of, any-of,"
                    + " or authenticated-only: true.");
        }
    }
}
