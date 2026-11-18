package com.johnowl.gateway.core.suppress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.Violation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BaselineTest {

    private static final Map<String, RuleMeta> RULES = Map.of(
            "path-no-verbs", new RuleMeta("path-no-verbs", Category.DESIGN, Severity.WARN, true, "d", "r"),
            "operation-summary-required",
            new RuleMeta("operation-summary-required", Category.DOCUMENTATION, Severity.WARN, true, "d", "r"),
            "security-explicit", new RuleMeta("security-explicit", Category.CONTRACT, Severity.ERROR, false, "d", "r"));

    private static Violation violation(String rule, String file, String pointer) {
        return new Violation(Severity.WARN, rule, file, pointer, "message");
    }

    @Test
    void writesSortedUniqueEntriesWithoutNonConfigurableRules() {
        String json = Baseline.write(List.of(
                violation("path-no-verbs", "b.yaml", "/paths/~1x/get"),
                violation("operation-summary-required", "a.yaml", "/paths/~1y/get"),
                violation("path-no-verbs", "a.yaml", "/paths/~1x/get"),
                violation("path-no-verbs", "a.yaml", "/paths/~1x/get"),
                violation("security-explicit", "a.yaml", "/paths/~1x/get")), RULES);

        // The same line ends on every operating system.
        assertThat(json).doesNotContain("\r").isEqualTo("""
                {
                  "version" : 1,
                  "entries" : [ {
                    "ruleId" : "operation-summary-required",
                    "file" : "a.yaml",
                    "pointer" : "/paths/~1y/get"
                  }, {
                    "ruleId" : "path-no-verbs",
                    "file" : "a.yaml",
                    "pointer" : "/paths/~1x/get"
                  }, {
                    "ruleId" : "path-no-verbs",
                    "file" : "b.yaml",
                    "pointer" : "/paths/~1x/get"
                  } ]
                }
                """);
    }

    @Test
    void readsWhatItWritesAndMatchesByFingerprint() {
        Violation known = violation("path-no-verbs", "a.yaml", "/paths/~1x/get");
        Baseline baseline = Baseline.read("b.json", Baseline.write(List.of(known), RULES), RULES);

        assertThat(baseline.isKnown(new Violation(Severity.ERROR, "path-no-verbs", "a.yaml", "/paths/~1x/get", "other text")))
                .isTrue();
        assertThat(baseline.isKnown(violation("path-no-verbs", "a.yaml", "/paths/~1y/get"))).isFalse();
        assertThat(baseline.isKnown(violation("operation-summary-required", "a.yaml", "/paths/~1x/get"))).isFalse();
    }

    @Test
    void rejectsInvalidFiles() {
        assertThat(problems("{")).singleElement().satisfies(p -> assertThat(p).startsWith("invalid JSON"));
        assertThat(problems("{\"version\": 2, \"entries\": []}")).containsExactly("version: must be 1.");
        assertThat(problems("{\"version\": 1}")).containsExactly("entries: must be a list.");
        assertThat(problems("""
                {"version": 1, "entries": [
                  {"ruleId": "security-explicit", "file": "a.yaml", "pointer": "/paths"},
                  {"ruleId": "unknown-rule", "file": "a.yaml", "pointer": "/paths"},
                  {"ruleId": "path-no-verbs", "file": "a.yaml"}
                ]}
                """)).containsExactly(
                "entries[0]: rule 'security-explicit' cannot be in a baseline.",
                "entries[1]: unknown rule id 'unknown-rule'.",
                "entries[2]: needs ruleId, file, and pointer.");
    }

    private static List<String> problems(String json) {
        BaselineException exception = catchThrowableOfType(BaselineException.class, () -> Baseline.read("b.json", json, RULES));
        assertThat(exception).as("expected BaselineException").isNotNull();
        return exception.problems();
    }
}
