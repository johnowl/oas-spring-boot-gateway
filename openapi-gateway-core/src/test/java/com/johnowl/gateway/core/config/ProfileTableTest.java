package com.johnowl.gateway.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The profiles give the built-in rules the severities of the profile table in spec 027. */
class ProfileTableTest {

    @ParameterizedTest
    @CsvSource({
        "deviation-unused, WARN, ERROR",
        "operation-id-required, ERROR, ERROR",
        "operation-id-camel-case, WARN, ERROR",
        "path-kebab-case, WARN, ERROR",
        "path-no-trailing-slash, ERROR, ERROR",
        "path-no-verbs, WARN, ERROR",
        "operation-success-response, ERROR, ERROR",
        "get-no-request-body, ERROR, ERROR",
        "post-201-location-header, INFO, WARN",
        "components-unused, WARN, ERROR",
        "error-response-problem-json, WARN, ERROR",
        "servers-https-only, ERROR, ERROR",
        "operation-summary-required, WARN, ERROR",
        "operation-tags-defined, WARN, ERROR",
        "schema-examples-present, INFO, WARN"
    })
    void profilesMatchTheTableOfSpec027(String ruleId, Severity recommended, Severity strict) {
        RuleMeta meta = BuiltInRules.all().stream()
                .map(rule -> rule.meta())
                .filter(m -> m.id().equals(ruleId))
                .findFirst()
                .orElseThrow();

        assertThat(meta.configurable()).isTrue();
        assertThat(Profile.RECOMMENDED.severity(meta)).isEqualTo(recommended);
        assertThat(Profile.STRICT.severity(meta)).isEqualTo(strict);
    }
}
