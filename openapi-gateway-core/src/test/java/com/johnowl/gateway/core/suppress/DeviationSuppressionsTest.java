package com.johnowl.gateway.core.suppress;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.validation.BaselinePolicy;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SeverityPolicy;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeviationSuppressionsTest {

    private static final RuleMeta SUMMARY =
            new RuleMeta("operation-summary-required", Category.DOCUMENTATION, Severity.WARN, true, "d", "r");
    private static final RuleMeta OTHER = new RuleMeta("path-no-verbs", Category.DESIGN, Severity.WARN, true, "d", "r");
    private static final RuleMeta UNUSED =
            new RuleMeta("deviation-unused", Category.DESIGN, Severity.WARN, true, "d", "r");

    private static final SpecSource SPEC = new SpecSource("orders.yaml", """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            paths:
              /orders:
                get:
                  x-deviation:
                    - { rule: operation-summary-required, reason: Internal. }
                  responses: { "200": { description: OK } }
                post:
                  responses: { "201": { description: Created } }
            """);

    @Test
    void coversSamePointerAndChildPointerOfTheOperation() {
        List<Violation> violations = run(List.of(reporting(SUMMARY, "", "/responses/200"), reporting(OTHER, "")),
                SeverityPolicy.defaults());

        assertThat(violations).extracting(v -> v.ruleId() + " " + v.pointer()).containsExactlyInAnyOrder(
                "operation-summary-required /paths/~1orders/post",
                "operation-summary-required /paths/~1orders/post/responses/200",
                "path-no-verbs /paths/~1orders/get",
                "path-no-verbs /paths/~1orders/post");
    }

    @Test
    void reportsUnusedSuppression() {
        List<Violation> violations = run(List.of(reporting(SUMMARY), reporting(OTHER, "")), SeverityPolicy.defaults());

        assertThat(violations).filteredOn(v -> v.ruleId().equals("deviation-unused")).singleElement()
                .satisfies(v -> {
                    assertThat(v.pointer()).isEqualTo("/paths/~1orders/get/x-deviation/0");
                    assertThat(v.severity()).isEqualTo(Severity.WARN);
                    assertThat(v.message()).isEqualTo(
                            "Deviation from 'operation-summary-required' covers no violation. Remove it.");
                });
    }

    @Test
    void suppressionForARuleThatIsOffIsNotUnused() {
        List<Violation> violations = run(List.of(reporting(SUMMARY), reporting(OTHER, "")),
                (rule, file) -> rule.id().equals(SUMMARY.id()) ? Optional.empty() : Optional.of(rule.defaultSeverity()));

        assertThat(violations).extracting(Violation::ruleId).doesNotContain("deviation-unused");
    }

    @Test
    void suppressionForARuleThatDidNotRunIsNotUnused() {
        List<Violation> violations = run(List.of(reporting(OTHER, "")), SeverityPolicy.defaults());

        assertThat(violations).extracting(Violation::ruleId).doesNotContain("deviation-unused");
    }

    @Test
    void usedSuppressionIsNotUnused() {
        List<Violation> violations = run(List.of(reporting(SUMMARY, "")), SeverityPolicy.defaults());

        assertThat(violations).extracting(Violation::ruleId).doesNotContain("deviation-unused");
    }

    private static List<Violation> run(List<Rule> rules, SeverityPolicy severity) {
        List<Rule> all = new ArrayList<>(rules);
        all.add(metadataOnly(UNUSED));
        List<RuleMeta> metas = List.of(SUMMARY, OTHER, UNUSED);
        return new ValidationEngine(all, severity, new DeviationSuppressions(metas), BaselinePolicy.empty())
                .validate(List.of(SPEC)).violations();
    }

    /** Reports at the operation pointer plus each suffix. */
    private static Rule reporting(RuleMeta meta, String... suffixes) {
        return new OperationRule() {
            @Override
            public RuleMeta meta() {
                return meta;
            }

            @Override
            public void check(OperationContext context, Reporter reporter) {
                for (String suffix : suffixes) {
                    reporter.report(context.pointer() + suffix, "problem");
                }
            }
        };
    }

    private static Rule metadataOnly(RuleMeta meta) {
        return new SpecRule() {
            @Override
            public RuleMeta meta() {
                return meta;
            }

            @Override
            public void check(SpecContext context, Reporter reporter) {
            }
        };
    }
}
