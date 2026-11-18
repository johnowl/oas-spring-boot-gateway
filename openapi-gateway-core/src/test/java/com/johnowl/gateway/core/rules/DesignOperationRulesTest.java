package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.validation.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Messages and edge cases of the operation rules of spec 030. The fixtures cover the pointers. */
class DesignOperationRulesTest {

    private static final String SPEC = """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            x-gateway: { upstream: http://orders-service:8080 }
            paths:
            %s
            """;

    @Test
    void pathNoVerbsNamesEachMatchedWordOnce() {
        String paths = """
                  /get-orders/getOrder/update:
                    get:
                      security: []
                      operationId: getOrders
                      responses: { "200": { description: OK } }
                """;

        assertThat(RuleRunner.run(new PathNoVerbsRule(), SPEC.formatted(paths)))
                .extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple("/paths/~1get-orders~1getOrder~1update/get",
                        "Path '/get-orders/getOrder/update' contains verbs: get, update."
                                + " Use nouns, and let the HTTP method name the action."));
    }

    @Test
    void operationSuccessResponseReportsAnOperationWithOnlyDefault() {
        String paths = """
                  /orders:
                    get:
                      security: []
                      operationId: listOrders
                      responses: { default: { description: A problem. } }
                    delete:
                      security: []
                      operationId: deleteOrders
                      responses: { "2XX": { description: Deleted. } }
                """;

        assertThat(RuleRunner.run(new OperationSuccessResponseRule(), SPEC.formatted(paths)))
                .extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple("/paths/~1orders/get/responses",
                        "Operation has no success response. Add a response from 200 to 299, or 2XX."));
    }

    /** OAS 3.1 permits an operation without responses, but the loader rejects it before the rules run. */
    @ParameterizedTest
    @ValueSource(strings = {"3.0.3", "3.1.0"})
    void operationWithoutResponsesNeverReachesTheRule(String version) {
        String spec = """
                openapi: %s
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: http://orders-service:8080 }
                paths:
                  /orders:
                    get:
                      security: []
                      operationId: listOrders
                """.formatted(version);

        assertThat(RuleRunner.run(new OperationSuccessResponseRule(), spec))
                .extracting(Violation::ruleId, Violation::message)
                .containsExactly(tuple("oas-parse", "attribute paths.'/orders'(get).responses is missing"));
    }

    @Test
    void getNoRequestBodyReportsAtTheRequestBody() {
        String paths = """
                  /orders:
                    get:
                      security: []
                      operationId: listOrders
                      requestBody: { content: { application/json: { schema: { type: object } } } }
                      responses: { "200": { description: OK } }
                """;

        assertThat(RuleRunner.run(new GetNoRequestBodyRule(), SPEC.formatted(paths)))
                .extracting(Violation::pointer)
                .containsExactly("/paths/~1orders/get/requestBody");
    }

    @Test
    void post201LocationHeaderAcceptsALowercaseName() {
        String paths = """
                  /orders:
                    post:
                      security: []
                      operationId: createOrder
                      responses:
                        "201":
                          description: Created.
                          headers: { location: { schema: { type: string } } }
                  /payments:
                    post:
                      security: []
                      operationId: createPayment
                      responses: { "201": { description: Created. } }
                """;

        assertThat(RuleRunner.run(new Post201LocationHeaderRule(), SPEC.formatted(paths)))
                .extracting(Violation::pointer)
                .containsExactly("/paths/~1payments/post/responses/201");
    }
}
