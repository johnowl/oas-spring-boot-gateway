package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import java.util.regex.Pattern;

/** {@code operationId} is lower camel case, for example {@code getOrders}. */
public final class OperationIdCamelCaseRule implements OperationRule {

    static final Pattern FORMAT = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

    private static final RuleMeta META = BestPracticeRule.meta(
            "operation-id-camel-case", Category.NAMING, Severity.WARN,
            "operationId matches " + FORMAT.pattern() + ", for example getOrders.",
            "One naming style gives predictable method names in generated clients.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        String id = context.operation().getOperationId();
        if (id != null && !id.isBlank() && !FORMAT.matcher(id).matches()) {
            reporter.report(context.pointer() + "/operationId",
                    "operationId '" + id + "' is not lower camel case. Example: getOrders.");
        }
    }
}
