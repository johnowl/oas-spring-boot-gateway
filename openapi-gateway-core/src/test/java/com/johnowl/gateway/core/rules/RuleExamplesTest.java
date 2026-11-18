package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleExamplesTest {

    @Test
    void loadsBadAndGoodExampleOfABuiltInRule() {
        RuleExamples.Examples examples = RuleExamples.of("operation-id-camel-case");

        assertThat(examples.bad()).contains("operationId: get_orders");
        assertThat(examples.good()).contains("operationId: listOrders");
    }

    @Test
    void loadsExamplesFromAnotherRoot() {
        RuleExamples.Examples examples = RuleExamples.of("complete-rule", "rule-examples-test");

        assertThat(examples.bad()).isEqualTo("paths:\n  /getOrders:\n    get: {}\n");
        assertThat(examples.good()).isEqualTo("paths:\n  /orders:\n    get: {}\n");
    }

    @Test
    void namesTheMissingResource() {
        assertThatThrownBy(() -> RuleExamples.of("only-bad-rule", "rule-examples-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rule 'only-bad-rule' has no example rule-examples-test/only-bad-rule/good.yaml");
        assertThatThrownBy(() -> RuleExamples.of("unknown-rule"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rule 'unknown-rule' has no example rule-examples/unknown-rule/bad.yaml");
    }

    @Test
    void rejectsARuleIdThatIsNotKebabCase() {
        assertThatThrownBy(() -> RuleExamples.of("../x-gateway.schema.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rule id is not kebab-case: ../x-gateway.schema.json");
    }

    /** SEC-70-1: no id can select another resource, and a line end or a bidi control is not kebab-case. */
    @ParameterizedTest
    @ValueSource(strings = {"../x", "a/b", "A", "x\n", "x\u202e", "", "-x", "x-", "a--b", "x.yaml"})
    void rejectsEachIdThatIsNotKebabCase(String ruleId) {
        String id = ruleId.translateEscapes();

        assertThatThrownBy(() -> RuleExamples.of(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rule id is not kebab-case: " + id);
    }
}
