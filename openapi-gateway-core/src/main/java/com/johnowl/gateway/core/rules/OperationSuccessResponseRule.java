package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.regex.Pattern;

/** Each operation documents a success response: a key from {@code 200} to {@code 299}, or {@code 2XX}. */
public final class OperationSuccessResponseRule implements OperationRule {

    static final Pattern SUCCESS = Pattern.compile("^(2[0-9][0-9]|2XX)$");

    private static final RuleMeta META = BestPracticeRule.meta(
            "operation-success-response", Category.DESIGN, Severity.ERROR,
            "responses has at least one key from 200 to 299, or the key 2XX.",
            "Clients and code generators need the success response. A default response alone does not say"
                    + " what success looks like.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        // The loader rejects an operation without responses with oas-parse, also in OAS 3.1. The null check
        // only protects a model that does not come from the loader.
        ApiResponses responses = context.operation().getResponses();
        if (responses == null || responses.keySet().stream().noneMatch(code -> SUCCESS.matcher(code).matches())) {
            reporter.report(context.pointer() + "/responses",
                    "Operation has no success response. Add a response from 200 to 299, or 2XX.");
        }
    }
}
