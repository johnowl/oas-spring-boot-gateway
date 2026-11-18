package com.johnowl.gateway.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class RuleMetaTest {

    @Test
    void rejectsConfigurableContractRule() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> meta(Category.CONTRACT, Severity.ERROR, true))
                .withMessageContaining("must not be configurable");
    }

    @Test
    void rejectsContractRuleWithSeverityOtherThanError() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> meta(Category.CONTRACT, Severity.WARN, false))
                .withMessageContaining("must have severity ERROR");
    }

    @Test
    void acceptsNonConfigurableBestPracticeRule() {
        assertThat(meta(Category.DESIGN, Severity.ERROR, false).configurable()).isFalse();
    }

    @Test
    void severitiesAreOrderedFromLowToHigh() {
        assertThat(Severity.ERROR).isGreaterThan(Severity.WARN);
        assertThat(Severity.WARN).isGreaterThan(Severity.INFO);
    }

    private static RuleMeta meta(Category category, Severity severity, boolean configurable) {
        return new RuleMeta("some-rule", category, severity, configurable, "Description.", "Rationale.");
    }
}
