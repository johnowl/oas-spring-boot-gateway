package com.johnowl.gateway.core.validation;

import com.johnowl.gateway.core.access.AccessRuleCompiler;
import com.johnowl.gateway.core.access.OperationAccess;
import com.johnowl.gateway.core.route.GatewayRoute;
import com.johnowl.gateway.core.route.GatewayRouteBuilder;
import com.johnowl.gateway.core.spec.SpecFile;
import com.johnowl.gateway.core.spec.SpecLoadResult;
import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.spec.SpecSource;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runs rules on OAS files. Each file is traversed once. Rules report problems; the engine sets
 * the effective severity and applies suppressions and the baseline, in this order.
 *
 * <p>Rules with {@code configurable=false}, each {@link Category#CONTRACT} rule included, skip
 * all three policies: their violations are always {@link Severity#ERROR} and always reported.
 * The same is true for {@code oas-parse} violations and for violations that report a rule failure.
 *
 * <p>The engine runs the rules that {@link SeverityPolicy#configure(List)} returns, so that rule options of the
 * policy always reach the rules.
 *
 * <p>The report shows at most {@value #VIOLATION_LIMIT} violations of each rule in each file, the first ones in
 * the sort order. One more violation of the same rule then gives the number of the other violations. The limit
 * applies after the three policies, so that suppressions, {@code deviation-unused}, and the baseline see every
 * violation.
 *
 * <p>If no violation is an ERROR, the report also contains the routes and the access rules of all files.
 */
public final class ValidationEngine {

    private static final Comparator<Violation> ORDER = Comparator.comparing(Violation::file)
            .thenComparing(Violation::pointer)
            .thenComparing(Violation::ruleId)
            .thenComparing(Violation::message);

    /** The maximum number of violations of one rule in one file in a report (spec 005). */
    public static final int VIOLATION_LIMIT = 100;

    /** No limit, for {@code baseline create}: a baseline must contain every violation. */
    public static final int NO_LIMIT = Integer.MAX_VALUE;

    /** The rule id of the violations for suppressions that covered nothing (spec 028). */
    public static final String UNUSED_SUPPRESSION_RULE_ID = "deviation-unused";

    private final List<Rule> rules;
    private final Map<String, Rule> rulesById = new HashMap<>();
    private final SeverityPolicy severity;
    private final SuppressionPolicy suppression;
    private final BaselinePolicy baseline;
    private final int violationLimit;
    private final SpecLoader loader = new SpecLoader();
    private final GatewayRouteBuilder routeBuilder = new GatewayRouteBuilder();
    private final AccessRuleCompiler accessRuleCompiler = new AccessRuleCompiler();

    public ValidationEngine(
            List<Rule> rules, SeverityPolicy severity, SuppressionPolicy suppression, BaselinePolicy baseline) {
        this(rules, severity, suppression, baseline, VIOLATION_LIMIT);
    }

    /**
     * @param violationLimit the maximum number of violations of one rule in one file, or {@link #NO_LIMIT}
     */
    public ValidationEngine(List<Rule> rules, SeverityPolicy severity, SuppressionPolicy suppression,
            BaselinePolicy baseline, int violationLimit) {
        if (violationLimit < 1) {
            throw new IllegalArgumentException("violationLimit must be at least 1, but is " + violationLimit);
        }
        this.rules = List.copyOf(severity.configure(rules));
        this.severity = severity;
        this.suppression = suppression;
        this.baseline = baseline;
        this.violationLimit = violationLimit;
        Set<String> ids = new HashSet<>();
        for (Rule rule : this.rules) {
            if (!ids.add(rule.meta().id())) {
                throw new IllegalArgumentException("Duplicate rule id '" + rule.meta().id() + "'");
            }
            rulesById.put(rule.meta().id(), rule);
        }
    }

    /** Uses the default severities, no suppressions and no baseline, as the gateway does. */
    public ValidationEngine(List<Rule> rules) {
        this(rules, SeverityPolicy.defaults(), SuppressionPolicy.none(), BaselinePolicy.empty());
    }

    public ValidationReport validate(List<SpecSource> sources) {
        return validateLoaded(sources.stream().map(loader::load).toList());
    }

    /**
     * Validates files that the caller has loaded, for example with {@link SpecLoader#load(java.nio.file.Path)}
     * so that relative external references resolve.
     *
     * @throws IllegalArgumentException if two loaded files have the same path
     */
    public ValidationReport validateLoaded(List<SpecLoadResult> loaded) {
        List<Violation> result = new ArrayList<>();
        Map<String, SpecContext> specs = new LinkedHashMap<>();
        for (SpecLoadResult load : loaded) {
            result.addAll(load.violations());
            load.spec().ifPresent(spec -> {
                if (specs.putIfAbsent(spec.path(), new SpecContext(spec)) != null) {
                    throw new IllegalArgumentException("Two loaded files have the same path '" + spec.path() + "'");
                }
            });
        }

        for (SpecContext spec : specs.values()) {
            checkSpec(spec, result);
        }
        checkCrossSpec(List.copyOf(specs.values()), specs, result);
        reportUnusedSuppressions(specs, result);

        result.sort(ORDER);
        List<Violation> reported = limit(result);
        ValidationReport violations = new ValidationReport(reported);
        if (violations.hasAtLeast(Severity.ERROR)) {
            return violations;
        }
        List<GatewayRoute> routes = new ArrayList<>();
        List<OperationAccess> accessRules = new ArrayList<>();
        for (SpecContext spec : specs.values()) {
            List<GatewayRoute> specRoutes = routeBuilder.build(spec.spec());
            routes.addAll(specRoutes);
            accessRules.addAll(accessRuleCompiler.compile(spec.spec(), specRoutes));
        }
        routes.sort(Comparator.comparing(GatewayRoute::path).thenComparing(GatewayRoute::id));
        return new ValidationReport(reported, routes, accessRules);
    }

    /**
     * Keeps the first {@link #violationLimit} violations of each rule in each file, in the sort order. For each rule
     * and file with more violations, it adds one violation at the document root with the number of the others. Its
     * severity is the highest severity of the others, so that the exit code of the CLI and the routes do not change.
     */
    private List<Violation> limit(List<Violation> sorted) {
        Map<List<String>, Integer> counts = new HashMap<>();
        Map<List<String>, Severity> hidden = new LinkedHashMap<>();
        List<Violation> result = new ArrayList<>();
        for (Violation violation : sorted) {
            List<String> key = List.of(violation.file(), violation.ruleId());
            if (counts.merge(key, 1, Integer::sum) <= violationLimit) {
                result.add(violation);
            } else {
                hidden.merge(key, violation.severity(), (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
        }
        if (hidden.isEmpty()) {
            return sorted;
        }
        hidden.forEach((key, severity) -> result.add(new Violation(severity, key.get(1), key.get(0), "",
                notReported(counts.get(key) - violationLimit))));
        result.sort(ORDER);
        return result;
    }

    private String notReported(int count) {
        return count + (count == 1 ? " more violation of this rule in this file is" : " more violations of this"
                + " rule in this file are") + " not reported. The report shows at most " + violationLimit
                + " violations of each rule in each file.";
    }

    private void checkSpec(SpecContext spec, List<Violation> result) {
        List<SpecRule> specRules = new ArrayList<>();
        List<OperationRule> operationRules = new ArrayList<>();
        for (Rule rule : rules) {
            if (effectiveSeverity(rule.meta(), spec.file()).isEmpty()) {
                continue;
            }
            switch (rule) {
                case SpecRule r -> specRules.add(r);
                case OperationRule r -> operationRules.add(r);
                case CrossSpecRule r -> { }
            }
        }

        for (SpecRule rule : specRules) {
            run(rule, spec.file(), "", result, () -> rule.check(spec, collector(rule, spec.file(), spec.spec(), result)));
        }

        if (operationRules.isEmpty() || spec.spec().openApi().getPaths() == null) {
            return;
        }
        spec.spec().openApi().getPaths().forEach((path, pathItem) -> {
            for (Map.Entry<PathItem.HttpMethod, Operation> entry : pathItem.readOperationsMap().entrySet()) {
                OperationContext operation =
                        new OperationContext(spec, path, entry.getKey(), entry.getValue(), pathItem);
                for (OperationRule rule : operationRules) {
                    run(rule, spec.file(), operation.pointer(), result,
                            () -> rule.check(operation, collector(rule, spec.file(), spec.spec(), result)));
                }
            }
        });
    }

    private void checkCrossSpec(List<SpecContext> all, Map<String, SpecContext> byFile, List<Violation> result) {
        for (Rule rule : rules) {
            if (!(rule instanceof CrossSpecRule crossSpecRule)) {
                continue;
            }
            // A rule that is off does not run. ignore-files can only skip the files that the rule reports.
            if (effectiveSeverity(rule.meta(), "").isEmpty()) {
                continue;
            }
            run(rule, "", "", result, () -> crossSpecRule.check(all, (file, pointer, message) -> {
                // A file that is not an OAS file has no SpecFile.
                SpecContext spec = byFile.get(file);
                collector(rule, file, spec == null ? null : spec.spec(), result).report(pointer, message);
            }));
        }
    }

    /**
     * Reports the suppressions that covered nothing, with the rule {@code deviation-unused}, if
     * the run has that rule. A suppression for a rule that did not run for the file (not in the
     * rule set, or {@code off}) is not reported.
     */
    private void reportUnusedSuppressions(Map<String, SpecContext> specs, List<Violation> result) {
        Rule unusedRule = rulesById.get(UNUSED_SUPPRESSION_RULE_ID);
        if (unusedRule == null) {
            return;
        }
        List<SpecFile> files = specs.values().stream().map(SpecContext::spec).toList();
        for (UnusedSuppression unused : suppression.unused(files)) {
            Rule suppressed = rulesById.get(unused.ruleId());
            if (suppressed == null || effectiveSeverity(suppressed.meta(), unused.file()).isEmpty()) {
                continue;
            }
            collector(unusedRule, unused.file(), specs.get(unused.file()).spec(), result).report(
                    unused.pointer(), "Deviation from '" + unused.ruleId() + "' covers no violation. Remove it.");
        }
    }

    /**
     * Applies the policies to each reported problem and adds the remaining violations.
     *
     * @param spec the OAS file of the violations, or {@code null} for another file; then the
     *     suppression policy does not apply, because {@code x-deviation} lives in OAS files
     */
    private Reporter collector(Rule rule, String file, SpecFile spec, List<Violation> result) {
        RuleMeta meta = rule.meta();
        return (pointer, message) -> {
            Optional<Severity> effective = effectiveSeverity(meta, file);
            if (effective.isEmpty()) {
                return;
            }
            Violation violation = new Violation(effective.get(), meta.id(), file, pointer, message);
            if (meta.configurable()
                    && ((spec != null && suppression.isSuppressed(violation, spec)) || baseline.isKnown(violation))) {
                return;
            }
            result.add(violation);
        };
    }

    private Optional<Severity> effectiveSeverity(RuleMeta meta, String file) {
        return meta.configurable() ? severity.severity(meta, file) : Optional.of(meta.defaultSeverity());
    }

    /**
     * A failing rule gives one ERROR violation and does not stop the other rules. A rule can also fail with a
     * {@link StackOverflowError} (for example on a deep schema), a {@link LinkageError} or an {@link AssertionError}.
     */
    private static void run(
            Rule rule, String file, String pointer, List<Violation> result, Runnable check) {
        try {
            check.run();
        } catch (Exception | StackOverflowError | LinkageError | AssertionError e) {
            String message = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
            result.add(new Violation(Severity.ERROR, rule.meta().id(), file, pointer, "Rule failed: " + message));
        }
    }
}
