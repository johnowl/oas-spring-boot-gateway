package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.access.EffectiveSecurity;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;

/** Each operation has effective security. Public only with an explicit {@code security: []}. */
public final class SecurityExplicitRule implements OperationRule {

    private static final RuleMeta META = ContractRule.meta(
            "security-explicit",
            "Each operation has its own security field or a non-empty root security field. A public"
                    + " operation declares security: [] on the operation.",
            "The gateway never treats a missing declaration as public. A forgotten security field must"
                    + " not expose an operation without login.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        EffectiveSecurity security = EffectiveSecurity.of(context.spec().spec(), context.path(), context.method().name());
        if (security.kind() == EffectiveSecurity.Kind.MISSING) {
            reporter.report(context.pointer(), "Operation has no security. Add 'security: []' to make it public,"
                    + " or declare a security requirement on the operation or at the root.");
        }
    }
}
