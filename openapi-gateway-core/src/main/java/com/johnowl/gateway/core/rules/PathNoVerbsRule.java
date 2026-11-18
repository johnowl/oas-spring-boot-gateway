package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * No word of the OAS path outside its variables is a verb, for example {@code /get-orders} or
 * {@code /orders/{id}:delete}.
 */
public final class PathNoVerbsRule implements OperationRule {

    static final Set<String> VERBS = Set.of(
            "add", "create", "delete", "do", "edit", "execute", "fetch", "find", "get", "insert", "list",
            "make", "modify", "patch", "post", "put", "read", "remove", "retrieve", "save", "set", "update");

    private static final RuleMeta META = BestPracticeRule.meta(
            "path-no-verbs", Category.DESIGN, Severity.WARN,
            "No word of the path outside its variables is a verb such as get, create, or delete. Words are"
                    + " split on each character that is not a letter or a digit, and on a change from lowercase to"
                    + " uppercase.",
            "The HTTP method names the action. A path names the resource, so that one path serves all methods.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        Set<String> verbs = new LinkedHashSet<>();
        for (String word : PathSegments.wordsOutsideVariables(context.path())) {
            String lower = word.toLowerCase(Locale.ROOT);
            if (VERBS.contains(lower)) {
                verbs.add(lower);
            }
        }
        if (!verbs.isEmpty()) {
            reporter.report(context.pointer(), "Path '" + context.path() + "' contains verbs: "
                    + String.join(", ", verbs) + ". Use nouns, and let the HTTP method name the action.");
        }
    }
}
