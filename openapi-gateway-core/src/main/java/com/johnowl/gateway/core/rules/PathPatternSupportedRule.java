package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.route.PathSyntax;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;

/** The OAS path uses only literal segments and whole-segment variables. */
public final class PathPatternSupportedRule implements OperationRule {

    private static final RuleMeta META = new RuleMeta(
            "path-pattern-supported",
            Category.CONTRACT,
            Severity.ERROR,
            false,
            "The path uses only literal segments and whole-segment variables such as /users/{id}.",
            "The gateway matches paths with Spring PathPattern. Other syntax, for example /files/**,"
                    + " has a different meaning there, or the gateway cannot create the route.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        PathSyntax.problem(context.path()).ifPresent(problem -> reporter.report(context.pointer(), problem));
    }
}
