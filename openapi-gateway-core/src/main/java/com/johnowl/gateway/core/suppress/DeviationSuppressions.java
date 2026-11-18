package com.johnowl.gateway.core.suppress;

import com.johnowl.gateway.core.spec.SpecFile;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.SuppressionPolicy;
import com.johnowl.gateway.core.validation.UnusedSuppression;
import com.johnowl.gateway.core.validation.Violation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Suppresses violations with the {@code x-deviation} entries of the OAS files. An entry covers a
 * violation of the same rule in the same file, at the operation pointer or below it. Only entries
 * for known configurable rules count. Create one instance for each run: it records which entries
 * covered a violation.
 */
public final class DeviationSuppressions implements SuppressionPolicy {

    private final Map<String, RuleMeta> rules;
    private final Map<String, List<Deviations.Entry>> entriesByFile = new HashMap<>();
    private final Set<String> used = new HashSet<>();

    /** @param rules the rules that the CLI can run */
    public DeviationSuppressions(List<RuleMeta> rules) {
        this.rules = rules.stream().collect(Collectors.toMap(RuleMeta::id, Function.identity(), (a, b) -> a));
    }

    @Override
    public boolean isSuppressed(Violation violation, SpecFile spec) {
        for (Deviations.Entry entry : entries(spec)) {
            if (entry.rule().equals(violation.ruleId())
                    && (violation.pointer().equals(entry.operationPointer())
                            || violation.pointer().startsWith(entry.operationPointer() + "/"))) {
                used.add(spec.path() + "#" + entry.pointer());
                return true;
            }
        }
        return false;
    }

    @Override
    public List<UnusedSuppression> unused(List<SpecFile> specs) {
        List<UnusedSuppression> unused = new ArrayList<>();
        for (SpecFile spec : specs) {
            for (Deviations.Entry entry : entries(spec)) {
                if (!used.contains(spec.path() + "#" + entry.pointer())) {
                    unused.add(new UnusedSuppression(spec.path(), entry.pointer(), entry.rule()));
                }
            }
        }
        return unused;
    }

    /**
     * The entries for known configurable rules, except {@code deviation-unused} (it would cover
     * the warning about itself). {@code deviation-schema} reports the other entries.
     */
    private List<Deviations.Entry> entries(SpecFile spec) {
        return entriesByFile.computeIfAbsent(spec.path(), path -> Deviations.entries(spec).stream()
                .filter(entry -> rules.containsKey(entry.rule()) && rules.get(entry.rule()).configurable()
                        && !entry.rule().equals(Deviations.UNUSED_RULE_ID))
                .toList());
    }
}
