package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;

/** The OAS path does not end with {@code /}, except the root path. */
public final class PathNoTrailingSlashRule implements OperationRule {

    private static final RuleMeta META = BestPracticeRule.meta(
            "path-no-trailing-slash", Category.NAMING, Severity.ERROR,
            "The path is / or does not end with /.",
            "/orders and /orders/ are different paths for many tools. The CONTRACT rule"
                    + " path-pattern-supported also rejects a trailing slash.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        if (context.path().length() > 1 && context.path().endsWith("/")) {
            reporter.report(context.pointer(), "Path '" + context.path() + "' ends with '/'. Remove the trailing slash.");
        }
    }
}
