package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XGatewayResponseTimeoutRuleTest {

    private static final String POINTER = "/paths/~1reports/get/x-gateway-response-timeout";

    private final XGatewayResponseTimeoutRule rule = new XGatewayResponseTimeoutRule();

    @ParameterizedTest
    @ValueSource(strings = {"100", "30000", "60000"})
    void acceptsValuesInRange(String value) {
        assertThat(check(value)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"99", "60001", "0", "-1", "50", "99999999999999999999"})
    void rejectsValuesOutOfRange(String value) {
        assertThat(check(value))
                .containsExactly(POINTER + " Value " + value + " is out of range 100–60000 ms.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1500.5", "\"500\"", "true", "1000.0", "[500]"})
    void rejectsValuesThatAreNotIntegers(String value) {
        assertThat(check(value)).containsExactly(POINTER + " Value must be an integer (milliseconds).");
    }

    @Test
    void ignoresOperationWithoutTimeout() {
        assertThat(check(null)).isEmpty();
    }

    private List<String> check(String value) {
        String extension = value == null ? "" : "      x-gateway-response-timeout: " + value + "\n";
        return RuleRunner.run(rule, """
                openapi: 3.0.3
                info: { title: Reports, version: 1.0.0 }
                paths:
                  /reports:
                    get:
                """ + extension + """
                      responses:
                        "200": { description: OK }
                """).stream().map(v -> v.pointer() + " " + v.message()).toList();
    }
}
