package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.Violation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class XGatewaySchemaRuleTest {

    private final XGatewaySchemaRule rule = new XGatewaySchemaRule();

    @Test
    void isContractRule() {
        assertThat(rule.meta().category()).isEqualTo(Category.CONTRACT);
        assertThat(rule.meta().defaultSeverity()).isEqualTo(Severity.ERROR);
        assertThat(rule.meta().configurable()).isFalse();
    }

    @Test
    void acceptsCompleteObject() {
        assertThat(check("""
                upstream: http://orders-service:8080
                prefix: /orders-api
                upstream-base-path: /v1.2/api_x
                audience: 6f1c2e8a-0000-0000-0000-000000000000
                """)).isEmpty();
    }

    @Test
    void reportsMissingObjectAtDocumentRoot() {
        List<Violation> violations = RuleRunner.run(rule, document(null));

        assertThat(violations).extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple("", "Missing required object 'x-gateway'."));
    }

    @Test
    void reportsObjectOfWrongType() {
        assertThat(check("just-a-string")).containsExactly("/x-gateway 'x-gateway' must be an object.");
    }

    @Test
    void reportsMissingUpstream() {
        assertThat(check("prefix: /orders"))
                .containsExactly("/x-gateway Missing required property 'upstream'.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://orders-service:8080", "https://orders.internal", "http://10.0.0.1:65535"})
    void acceptsValidUpstream(String upstream) {
        assertThat(check("upstream: " + upstream)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://orders-service:8080/v1", "lb://orders", "orders:8080", "http://orders:123456"})
    void rejectsInvalidUpstream(String upstream) {
        assertThat(check("upstream: " + upstream)).containsExactly(
                "/x-gateway/upstream 'upstream' must be scheme://host[:port] without a path."
                        + " Use 'upstream-base-path' for a backend path.");
    }

    @ParameterizedTest
    @CsvSource({
        "prefix, /Orders, ^(/[a-z0-9-]+)+$",
        "prefix, orders, ^(/[a-z0-9-]+)+$",
        "prefix, /orders/, ^(/[a-z0-9-]+)+$",
        "upstream-base-path, /v1/, ^(/[A-Za-z0-9._-]+)+$",
        "audience, 6F1C2E8A-0000-0000-0000-000000000000, ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        "audience, not-a-guid, ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    })
    void rejectsValueThatDoesNotMatchPattern(String field, String value, String pattern) {
        assertThat(check("upstream: http://orders:8080\n" + field + ": " + value))
                .containsExactly("/x-gateway/" + field + " '" + field + "' does not match " + pattern + ".");
    }

    @Test
    void reportsUnknownProperty() {
        assertThat(check("upstream: http://orders:8080\nupstrem: http://orders:8080"))
                .containsExactly("/x-gateway Unknown property 'upstrem'.");
    }

    @Test
    void reportsWrongType() {
        assertThat(check("upstream: http://orders:8080\nprefix: 42"))
                .containsExactly("/x-gateway/prefix 'prefix' must be a string.");
    }

    @Test
    void reportsOneViolationForEachError() {
        assertThat(check("""
                upstream: http://orders:8080/v1
                prefix: /Orders
                upstrem: http://orders:8080
                """)).extracting(line -> line.substring(0, line.indexOf(' ')))
                .containsExactlyInAnyOrder("/x-gateway/upstream", "/x-gateway/prefix", "/x-gateway");
    }

    private List<String> check(String xGateway) {
        return RuleRunner.run(rule, document(xGateway)).stream()
                .map(v -> v.pointer() + " " + v.message())
                .toList();
    }

    private static String document(String xGateway) {
        String extension = xGateway == null ? "" : "x-gateway:\n" + xGateway.indent(2);
        if (xGateway != null && !xGateway.contains(":")) {
            extension = "x-gateway: " + xGateway + "\n";
        }
        return """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                paths: {}
                """ + extension;
    }
}
