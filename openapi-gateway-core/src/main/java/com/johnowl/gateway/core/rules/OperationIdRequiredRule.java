package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;

/** Each operation has a non-blank {@code operationId}. */
public final class OperationIdRequiredRule implements OperationRule {

    private static final RuleMeta META = BestPracticeRule.meta(
            "operation-id-required", Category.NAMING, Severity.ERROR,
            "Each operation has an operationId that is not empty.",
            "Code generators, logs, and API catalogs use the operationId as the name of the operation.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        String id = context.operation().getOperationId();
        if (id == null || id.isBlank()) {
            reporter.report(context.pointer(), "Operation has no operationId.");
        }
    }
}
