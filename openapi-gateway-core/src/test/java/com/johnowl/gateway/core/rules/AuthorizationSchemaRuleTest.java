package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthorizationSchemaRuleTest {

    private static final String POINTER = "/paths/~1orders/get/x-gateway-authorization";

    private final AuthorizationSchemaRule rule = new AuthorizationSchemaRule();

    @ParameterizedTest
    @ValueSource(strings = {"", "Orders.Read", "[Orders.Read]", "true", "42"})
    void valueThatIsNotAnObjectGivesOnlyTheTypeMessage(String value) {
        assertThat(check(value)).containsExactly(POINTER + " x-gateway-authorization must be an object.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{authenticated-only: true, all-of: [Orders.Read]}",
        "{authenticated-only: true, any-of: [Orders.Read]}"})
    void authenticatedOnlyWithPermissionsGivesTheCombinationMessage(String value) {
        assertThat(check(value))
                .containsExactly(POINTER + " 'authenticated-only' cannot be used with 'all-of' or 'any-of'.");
    }

    private List<String> check(String value) {
        return RuleRunner.run(rule, """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                paths:
                  /orders:
                    get:
                      x-gateway-authorization: %s
                      responses:
                        "200": { description: OK }
                """.formatted(value)).stream().map(v -> v.pointer() + " " + v.message()).toList();
    }
}
