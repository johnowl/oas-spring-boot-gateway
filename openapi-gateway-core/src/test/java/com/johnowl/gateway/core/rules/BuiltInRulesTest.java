package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Severity;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BuiltInRulesTest {

    @Test
    void contractContainsOasParseAndOnlyContractRules() {
        assertThat(BuiltInRules.contract()).extracting(rule -> rule.meta().id()).contains("oas-parse");
        assertThat(BuiltInRules.contract()).allSatisfy(rule ->
                assertThat(rule.meta().category()).isEqualTo(Category.CONTRACT));
    }

    @Test
    void contractIsTheSetOfContractRulesInAll() {
        assertThat(BuiltInRules.all()).containsAll(BuiltInRules.contract());
        assertThat(BuiltInRules.all().stream().filter(rule -> rule.meta().category() == Category.CONTRACT))
                .containsExactlyInAnyOrderElementsOf(BuiltInRules.contract());
    }

    @Test
    void ruleIdsAreUnique() {
        assertThat(BuiltInRules.all()).extracting(rule -> rule.meta().id()).doesNotHaveDuplicates();
    }

    @Test
    void theCatalogRulesAreRemoved() {
        // Removal of the permission catalog (2026-11-13).
        assertThat(BuiltInRules.all()).extracting(rule -> rule.meta().id())
                .doesNotContain("audience-known", "permission-exists", "permissions-unused");
    }

    @Test
    void deviationSchemaReportsTheRemovedCatalogRulesAsUnknown() {
        SpecSource orders = new SpecSource("orders.yaml", """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: http://orders:8080, audience: 6f1c2e8a-0000-0000-0000-000000000000 }
                paths:
                  /orders:
                    get:
                      operationId: listOrders
                      security: []
                      x-deviation:
                        - { rule: permissions-unused, reason: Roles for the next release. }
                        - { rule: audience-known, reason: Not allowed. }
                      responses: { "200": { description: OK } }
                """);

        assertThat(deviationSchema(BuiltInRules.all(), orders)).containsExactly(
                "/paths/~1orders/get/x-deviation/0/rule Unknown rule id 'permissions-unused'.",
                "/paths/~1orders/get/x-deviation/1/rule Unknown rule id 'audience-known'.");
    }

    private static List<String> deviationSchema(List<Rule> rules, SpecSource source) {
        return new ValidationEngine(rules).validate(List.of(source)).violations().stream()
                .filter(v -> v.ruleId().equals("deviation-schema"))
                .map(v -> v.pointer() + " " + v.message())
                .toList();
    }

    @ParameterizedTest
    @CsvSource({
        "path-no-verbs, DESIGN, WARN",
        "operation-success-response, DESIGN, ERROR",
        "get-no-request-body, DESIGN, ERROR",
        "post-201-location-header, DESIGN, INFO",
        "components-unused, DESIGN, WARN",
        "error-response-problem-json, ERRORS, WARN",
        "servers-https-only, SECURITY, ERROR"
    })
    void designRulesHaveTheMetadataOfSpec030(String id, Category category, Severity severity) {
        assertBestPracticeRule(id, category, severity);
    }

    @ParameterizedTest
    @CsvSource({
        "operation-summary-required, DOCUMENTATION, WARN",
        "operation-tags-defined, DOCUMENTATION, WARN",
        "schema-examples-present, DOCUMENTATION, INFO"
    })
    void documentationRulesHaveTheMetadataOfSpec031(String id, Category category, Severity severity) {
        assertBestPracticeRule(id, category, severity);
    }

    private static void assertBestPracticeRule(String id, Category category, Severity severity) {
        assertThat(BuiltInRules.all()).filteredOn(rule -> rule.meta().id().equals(id)).singleElement()
                .satisfies(rule -> {
                    assertThat(rule.meta().category()).isEqualTo(category);
                    assertThat(rule.meta().defaultSeverity()).isEqualTo(severity);
                    assertThat(rule.meta().configurable()).isTrue();
                });
    }

    @Test
    void contractRulesAreNotConfigurable() {
        assertThat(BuiltInRules.contract()).noneMatch(rule -> rule.meta().configurable());
    }

    @Test
    void contractContainsTheXGatewayRulesAsNonConfigurableErrors() {
        List<String> ids = List.of(
                "oas-parse", "x-gateway-schema", "x-gateway-unknown-extension", "x-gateway-response-timeout");

        assertThat(BuiltInRules.contract()).extracting(rule -> rule.meta().id()).containsAll(ids);
        assertThat(BuiltInRules.contract()).filteredOn(rule -> ids.contains(rule.meta().id()))
                .hasSize(ids.size())
                .allSatisfy(rule -> {
                    assertThat(rule.meta().category()).isEqualTo(Category.CONTRACT);
                    assertThat(rule.meta().defaultSeverity()).isEqualTo(Severity.ERROR);
                    assertThat(rule.meta().configurable()).isFalse();
                });
    }

    @Test
    void schemaRuleLoadsTheSchemaOnceInAStaticField() throws NoSuchFieldException {
        int modifiers = XGatewaySchemaRule.class.getDeclaredField("SCHEMA").getModifiers();

        assertThat(Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)).isTrue();
    }
}
