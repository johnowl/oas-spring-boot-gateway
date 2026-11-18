package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.responses.ApiResponse;

/** The {@code 201} response of a POST operation declares the header {@code Location}. */
public final class Post201LocationHeaderRule implements OperationRule {

    private static final RuleMeta META = BestPracticeRule.meta(
            "post-201-location-header", Category.DESIGN, Severity.INFO,
            "The 201 response of a POST operation declares the header Location. The name is case-insensitive.",
            "Location tells the client the URL of the new resource (RFC 9110, section 15.3.2).");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        if (context.method() != PathItem.HttpMethod.POST || context.operation().getResponses() == null) {
            return;
        }
        ApiResponse created = context.operation().getResponses().get("201");
        if (created == null) {
            return;
        }
        ApiResponse resolved = ComponentReferences.response(context.spec().spec().openApi(), created);
        boolean hasLocation = resolved != null && resolved.getHeaders() != null
                && resolved.getHeaders().keySet().stream().anyMatch(name -> name.equalsIgnoreCase("Location"));
        if (!hasLocation) {
            reporter.report(context.pointer() + "/responses/201",
                    "The 201 response has no Location header. Declare Location with the URL of the new resource.");
        }
    }
}
