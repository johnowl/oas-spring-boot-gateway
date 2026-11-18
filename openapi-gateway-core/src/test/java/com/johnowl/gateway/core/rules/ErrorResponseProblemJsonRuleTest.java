package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.config.RuleConfig;
import com.johnowl.gateway.core.config.RuleConfigLoader;
import com.johnowl.gateway.core.config.RuleOptions;
import com.johnowl.gateway.core.text.PrintableText;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.Violation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** error-response-problem-json and its option media-types (specs 027 and 030). */
class ErrorResponseProblemJsonRuleTest {

    private static final String SPEC = """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            paths:
              /orders/{id}:
                get:
                  operationId: getOrder
                  parameters: [{ name: id, in: path, required: true, schema: { type: string } }]
                  responses:
                    "200": { description: The order. }
                    "404": { description: Not found., content: { "%s": { schema: { type: object } } } }
            """;

    @Test
    void reportsApplicationJsonAndAcceptsProblemJsonWithParameters() {
        assertThat(RuleRunner.run(new ErrorResponseProblemJsonRule(), SPEC.formatted("application/json")))
                .extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple("/paths/~1orders~1{id}/get/responses/404/content/application~1json",
                        "Error response '404' has media type 'application/json'. Use application/problem+json."));
        assertThat(RuleRunner.run(new ErrorResponseProblemJsonRule(),
                SPEC.formatted("application/problem+json; charset=utf-8"))).isEmpty();
    }

    /** The message escapes control characters and cuts a long media type. The pointer keeps the full name. */
    @Test
    void theMessageShowsAPrintableShortMediaType() {
        String longName = "application/" + "x".repeat(300);

        assertThat(RuleRunner.run(new ErrorResponseProblemJsonRule(), SPEC.formatted("text/a\\u202Eb")))
                .extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple("/paths/~1orders~1{id}/get/responses/404/content/text~1a‮b",
                        "Error response '404' has media type 'text/a\\u202Eb'. Use application/problem+json."));
        assertThat(RuleRunner.run(new ErrorResponseProblemJsonRule(), SPEC.formatted(longName)))
                .extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple("/paths/~1orders~1{id}/get/responses/404/content/application~1"
                                + "x".repeat(300),
                        "Error response '404' has media type '" + longName.substring(0, PrintableText.MAX_LENGTH)
                                + "...'. Use application/problem+json."));
    }

    @Test
    void readsTheOptionMediaTypesThroughRuleOptions() {
        Rule rule = configured("""
                rules:
                  error-response-problem-json:
                    options:
                      media-types: [application/vnd.error+json]
                """);

        assertThat(((ErrorResponseProblemJsonRule) rule).mediaTypes()).containsExactly("application/vnd.error+json");
        assertThat(RuleRunner.run(rule, SPEC.formatted("application/vnd.error+json"))).isEmpty();
        assertThat(RuleRunner.run(rule, SPEC.formatted("application/problem+json")))
                .extracting(Violation::message)
                .containsExactly("Error response '404' has media type 'application/problem+json'."
                        + " Use application/vnd.error+json.");
    }

    @Test
    void withoutTheOptionTheRuleUsesTheDefault() {
        Rule rule = new ErrorResponseProblemJsonRule().withOptions(RuleOptions.none());

        assertThat(((ErrorResponseProblemJsonRule) rule).mediaTypes()).containsExactly("application/problem+json");
        assertThat(((ErrorResponseProblemJsonRule) configured("rules: { error-response-problem-json: error }"))
                .mediaTypes()).containsExactly("application/problem+json");
    }

    private static Rule configured(String yaml) {
        List<Rule> rules = BuiltInRules.all();
        RuleConfig config = new RuleConfigLoader(rules).load(".oas-gateway.yaml", yaml, Path.of(""));
        return config.configure(rules).stream()
                .filter(rule -> rule.meta().id().equals("error-response-problem-json"))
                .findFirst()
                .orElseThrow();
    }
}
