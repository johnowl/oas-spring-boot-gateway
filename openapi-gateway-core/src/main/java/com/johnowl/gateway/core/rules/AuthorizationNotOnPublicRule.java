package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.access.AccessRuleCompiler;
import com.johnowl.gateway.core.access.EffectiveSecurity;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;

/** A public operation has no {@code x-gateway-authorization}. */
public final class AuthorizationNotOnPublicRule implements OperationRule {

    private static final RuleMeta META = ContractRule.meta(
            "authorization-not-on-public",
            "A public operation (security: []) has no x-gateway-authorization.",
            "The gateway does not check permissions on a public operation. The extension would look like"
                    + " a protection that does not exist.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        boolean present = context.operation().getExtensions() != null
                && context.operation().getExtensions().containsKey(AccessRuleCompiler.EXTENSION);
        if (present && EffectiveSecurity.of(context.spec().spec(), context.path(), context.method().name()).isPublic()) {
            reporter.report(context.pointer() + "/" + AccessRuleCompiler.EXTENSION,
                    "Public operation has x-gateway-authorization. Remove the extension, or remove"
                            + " 'security: []' to protect the operation.");
        }
    }
}
