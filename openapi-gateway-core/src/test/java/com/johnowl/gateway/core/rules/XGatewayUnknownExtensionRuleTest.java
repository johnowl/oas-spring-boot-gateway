package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XGatewayUnknownExtensionRuleTest {

    private final XGatewayUnknownExtensionRule rule = new XGatewayUnknownExtensionRule();

    @ParameterizedTest
    @ValueSource(strings = {
        "x-gateway-response-timeout: 500",
        "x-gateway-authorization: { any-of: [Reports.Read] }",
        "x-deviation: []",
        "x-other: 1"
    })
    void acceptsAllowedExtensions(String extension) {
        assertThat(check(extension)).isEmpty();
    }

    @Test
    void reportsUnknownGatewayExtension() {
        assertThat(check("x-gateway-respone-timeout: 500")).containsExactly(
                "/paths/~1reports/get/x-gateway-respone-timeout Unknown extension 'x-gateway-respone-timeout'."
                        + " Allowed: x-gateway-response-timeout, x-gateway-authorization.");
    }

    @Test
    void reportsExtensionThatIsExactlyTheRootName() {
        assertThat(check("x-gateway: {}")).containsExactly(
                "/paths/~1reports/get/x-gateway Unknown extension 'x-gateway'."
                        + " Allowed: x-gateway-response-timeout, x-gateway-authorization.");
    }

    private List<String> check(String extension) {
        return RuleRunner.run(rule, """
                openapi: 3.0.3
                info: { title: Reports, version: 1.0.0 }
                paths:
                  /reports:
                    get:
                      %s
                      responses:
                        "200": { description: OK }
                """.formatted(extension)).stream().map(v -> v.pointer() + " " + v.message()).toList();
    }
}
