package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;

/** Each operation has a non-blank {@code summary}. A {@code description} does not replace it. */
public final class OperationSummaryRequiredRule implements OperationRule {

    private static final RuleMeta META = BestPracticeRule.meta(
            "operation-summary-required", Category.DOCUMENTATION, Severity.WARN,
            "Each operation has a summary that is not empty. A description does not replace the summary.",
            "Documentation tools show the summary as the title of the operation. Without it, they show only the"
                    + " path.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        String summary = context.operation().getSummary();
        if (summary == null || summary.isBlank()) {
            reporter.report(context.pointer(),
                    "Operation has no summary. Add a short title, for example 'Get an order'.");
        }
    }
}
