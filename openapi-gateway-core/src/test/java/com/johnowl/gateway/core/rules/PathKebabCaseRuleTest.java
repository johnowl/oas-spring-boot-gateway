package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.suppress.DeviationSuppressions;
import com.johnowl.gateway.core.validation.BaselinePolicy;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.SeverityPolicy;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.Violation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Spec 029: path-kebab-case reports once for each operation, so x-deviation works for one operation. */
class PathKebabCaseRuleTest {

    private static final String SPEC = """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            x-gateway: { upstream: http://orders-service:8080 }
            paths:
              /orderItems/{id}:
                parameters:
                  - { name: id, in: path, required: true, schema: { type: string } }
                get:
            %s
                  security: []
                  operationId: getOrderItem
                  responses: { "200": { description: OK } }
                delete:
                  security: []
                  operationId: deleteOrderItem
                  responses: { "204": { description: Deleted } }
            """;

    private final List<Rule> rules = List.of(new PathKebabCaseRule());

    @Test
    void reportsOnceAtEachOperation() {
        assertThat(run(SPEC.formatted(""))).extracting(Violation::ruleId, Violation::pointer)
                .containsExactly(
                        tuple("path-kebab-case", "/paths/~1orderItems~1{id}/delete"),
                        tuple("path-kebab-case", "/paths/~1orderItems~1{id}/get"));
    }

    @Test
    void deviationOnGetRemovesOnlyTheGetViolation() {
        String ignore = """
                      x-deviation:
                        - { rule: path-kebab-case, reason: The public path is fixed by an old client. }\
                """;

        assertThat(run(SPEC.formatted(ignore))).extracting(Violation::ruleId, Violation::pointer)
                .containsExactly(tuple("path-kebab-case", "/paths/~1orderItems~1{id}/delete"));
    }

    private List<Violation> run(String yaml) {
        DeviationSuppressions suppressions = new DeviationSuppressions(rules.stream().map(Rule::meta).toList());
        return new ValidationEngine(rules, SeverityPolicy.defaults(), suppressions, BaselinePolicy.empty())
                .validate(List.of(new SpecSource("orders.yaml", yaml)))
                .violations();
    }
}
