package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import io.swagger.v3.oas.models.PathItem;

/** A GET operation has no {@code requestBody}. */
public final class GetNoRequestBodyRule implements OperationRule {

    private static final RuleMeta META = BestPracticeRule.meta(
            "get-no-request-body", Category.DESIGN, Severity.ERROR,
            "A GET operation has no requestBody.",
            "OAS 3.1 permits a GET request body, but many proxies, caches, and clients drop it.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        if (context.method() == PathItem.HttpMethod.GET && context.operation().getRequestBody() != null) {
            reporter.report(context.pointer() + "/requestBody",
                    "GET operation has a request body. Use query parameters, or use POST.");
        }
    }
}
