package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.suppress.Deviations;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Each {@code x-deviation} entry has a known configurable rule and a reason. Always ERROR and not
 * configurable, so a deviation cannot hide itself. CLI only: the gateway ignores x-deviation.
 */
public final class DeviationSchemaRule implements OperationRule {

    static final RuleMeta META = new RuleMeta(
            Deviations.SCHEMA_RULE_ID,
            Category.DESIGN,
            Severity.ERROR,
            false,
            "Each x-deviation entry is an object with a known configurable rule id and a non-blank reason.",
            "A deviation is a visible, accepted decision. It needs a reason, and it cannot cover a CONTRACT rule.");

    private static final Set<String> KEYS = Set.of("rule", "reason");

    /** The name before 2026-11-12. An entry with this key has no effect, so the rule reports it. */
    private static final String OLD_EXTENSION = "x-lint-ignore";

    private final Map<String, RuleMeta> rules;

    /** @param rules all rules that the CLI can run, this rule included */
    DeviationSchemaRule(Map<String, RuleMeta> rules) {
        this.rules = Map.copyOf(rules);
    }

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        JsonNode operation = context.spec().spec().raw().at(context.pointer());
        if (operation.isObject() && operation.has(OLD_EXTENSION)) {
            reporter.report(context.pointer() + "/" + OLD_EXTENSION,
                    "x-lint-ignore was renamed to x-deviation. Rename the key; the old key has no effect.");
        }
        JsonNode list = operation.isObject() ? operation.get(Deviations.EXTENSION) : null;
        if (list == null) {
            return;
        }
        String base = context.pointer() + "/" + Deviations.EXTENSION;
        if (!list.isArray()) {
            reporter.report(base, "x-deviation must be a list of {rule, reason} objects.");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            String pointer = base + "/" + i;
            JsonNode entry = list.get(i);
            if (!entry.isObject()) {
                reporter.report(pointer, "The entry must be an object with rule and reason.");
                continue;
            }
            for (Iterator<String> it = entry.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                if (!KEYS.contains(key)) {
                    reporter.report(pointer, "Unknown key '" + key + "'. Allowed: rule, reason.");
                }
            }
            if (!entry.path("reason").isTextual() || entry.get("reason").asText().isBlank()) {
                reporter.report(pointer, "The entry needs a reason.");
            }
            if (!entry.path("rule").isTextual()) {
                reporter.report(pointer, "The entry needs a rule id.");
                continue;
            }
            String id = entry.get("rule").asText();
            RuleMeta rule = rules.get(id);
            if (rule == null) {
                reporter.report(pointer + "/rule", "Unknown rule id '" + id + "'.");
            } else if (!rule.configurable()) {
                reporter.report(pointer + "/rule", "Rule '" + id + "' cannot have a deviation"
                        + (rule.category() == Category.CONTRACT ? ", because it is a CONTRACT rule." : "."));
            } else if (id.equals(Deviations.UNUSED_RULE_ID)) {
                // The entry would cover the warning about itself.
                reporter.report(pointer + "/rule", "Rule '" + id + "' cannot have a deviation."
                        + " Configure it in .oas-gateway.yaml.");
            }
        }
    }
}
