package com.johnowl.gateway.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.rules.ErrorResponseProblemJsonRule;
import com.johnowl.gateway.core.rules.PathNoVerbsRule;
import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.validation.BaselinePolicy;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import com.johnowl.gateway.core.validation.SuppressionPolicy;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The configuration as the severity policy of the engine. */
class RuleConfigEngineTest {

    private static final SpecSource LEGACY = new SpecSource("legacy/orders.yaml", """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            paths: {}
            """);

    @Test
    void ignoredFileKeepsContractViolations() {
        List<Rule> rules = List.of(reporting(RuleConfigLoaderTest.NAMING), reporting(RuleConfigLoaderTest.CONTRACT));
        RuleConfig config = new RuleConfigLoader(rules).load(".oas-gateway.yaml", "ignore-files: ['legacy/**']", Path.of(""));

        List<Violation> violations = new ValidationEngine(rules, config, SuppressionPolicy.none(), BaselinePolicy.empty())
                .validate(List.of(LEGACY)).violations();

        assertThat(violations).extracting(Violation::ruleId).containsExactly("security-explicit");
    }

    @Test
    void offRemovesAllViolationsOfTheRule() {
        List<Rule> rules = List.of(new PathNoVerbsRule());
        SpecSource orders = new SpecSource("orders.yaml", """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                paths:
                  /get-orders:
                    get: { operationId: getOrders, responses: { "200": { description: OK } } }
                  /createOrder:
                    post: { operationId: createOrder, responses: { "201": { description: Created } } }
                """);
        RuleConfig config = new RuleConfigLoader(rules).load(".oas-gateway.yaml", "rules:\n  path-no-verbs: off\n",
                Path.of(""));

        assertThat(new ValidationEngine(rules).validate(List.of(orders)).violations()).hasSize(2);
        assertThat(new ValidationEngine(rules, config, SuppressionPolicy.none(), BaselinePolicy.empty())
                .validate(List.of(orders)).violations()).isEmpty();
    }

    @Test
    void engineAppliesTheRuleOptionsOfTheConfiguration() {
        List<Rule> rules = List.of(new ErrorResponseProblemJsonRule());
        SpecSource orders = new SpecSource("orders.yaml", """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                paths:
                  /orders:
                    get:
                      operationId: listOrders
                      responses:
                        "200": { description: OK }
                        "400": { description: Bad, content: { application/vnd.error+json: {} } }
                        "404": { description: Missing, content: { application/problem+json: {} } }
                """);
        RuleConfig config = new RuleConfigLoader(rules).load(".oas-gateway.yaml", """
                rules:
                  error-response-problem-json:
                    options:
                      media-types: [application/vnd.error+json]
                """, Path.of(""));

        // The rules are not configured by the caller: the engine does it.
        assertThat(new ValidationEngine(rules, config, SuppressionPolicy.none(), BaselinePolicy.empty())
                .validate(List.of(orders)).violations())
                .extracting(Violation::pointer)
                .containsExactly("/paths/~1orders/get/responses/404/content/application~1problem+json");
    }

    private static Rule reporting(RuleMeta meta) {
        return new SpecRule() {
            @Override
            public RuleMeta meta() {
                return meta;
            }

            @Override
            public void check(SpecContext context, Reporter reporter) {
                reporter.report("", "problem");
            }
        };
    }
}
