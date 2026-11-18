package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import java.util.List;
import java.util.regex.Pattern;

/** Each literal segment of the OAS path is kebab case, for example {@code /order-items}. */
public final class PathKebabCaseRule implements OperationRule {

    static final Pattern SEGMENT = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private static final RuleMeta META = BestPracticeRule.meta(
            "path-kebab-case", Category.NAMING, Severity.WARN,
            "Each literal segment of the path matches " + SEGMENT.pattern() + ". Variables are not checked.",
            "URLs are case-sensitive. One style avoids paths that differ only in case or separators.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        List<String> bad = PathSegments.literal(context.path()).stream()
                .filter(segment -> !SEGMENT.matcher(segment).matches())
                .toList();
        if (!bad.isEmpty()) {
            reporter.report(context.pointer(), "Path '" + context.path() + "' has segments that are not kebab case: "
                    + String.join(", ", bad) + ".");
        }
    }
}
