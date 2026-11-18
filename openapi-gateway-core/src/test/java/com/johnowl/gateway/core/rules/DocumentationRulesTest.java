package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Messages and edge cases of the rules of spec 031. The fixtures cover the pointers. */
class DocumentationRulesTest {

    private static final String SPEC = """
            openapi: %s
            info: { title: Orders, version: 1.0.0 }
            x-gateway: { upstream: http://orders-service:8080 }
            %s
            """;

    @Test
    void operationSummaryRequiredReportsAnOperationWithOnlyADescription() {
        String yaml = SPEC.formatted("3.0.3", """
                paths:
                  /orders/{id}:
                    get:
                      operationId: getOrder
                      description: Returns one order.
                      parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
                      responses: { "200": { description: OK } }
                """);

        assertThat(RuleRunner.run(new OperationSummaryRequiredRule(), yaml))
                .extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple("/paths/~1orders~1{id}/get",
                        "Operation has no summary. Add a short title, for example 'Get an order'."));
    }

    @Test
    void operationTagsDefinedNamesTheUndeclaredTagAndTheDeclaredNameWithAnotherCase() {
        String yaml = SPEC.formatted("3.0.3", """
                tags: [ { name: orders }, { name: Refunds } ]
                paths:
                  /orders:
                    get:
                      tags: [Orders, items, "a\\tb"]
                      responses: { "200": { description: OK } }
                    post:
                      responses: { "201": { description: Created } }
                """);

        assertThat(RuleRunner.run(new OperationTagsDefinedRule(), yaml))
                .extracting(Violation::pointer, Violation::message)
                .containsExactly(
                        tuple("/paths/~1orders/get/tags/0", "Tag 'Orders' is not declared in the root tags list."
                                + " Did you mean 'orders'? The comparison is case-sensitive."),
                        tuple("/paths/~1orders/get/tags/1", "Tag 'items' is not declared in the root tags list."
                                + " Declare it, or use a declared tag."),
                        tuple("/paths/~1orders/get/tags/2", "Tag 'a\\u0009b' is not declared in the root tags list."
                                + " Declare it, or use a declared tag."),
                        tuple("/paths/~1orders/post", "Operation has no tags. Add a tag that the root tags list"
                                + " declares."));
    }

    @Test
    void operationTagsDefinedReportsEachTagWithoutARootTagsList() {
        String yaml = SPEC.formatted("3.0.3", """
                paths:
                  /orders:
                    get:
                      tags: [orders, refunds]
                      responses: { "200": { description: OK } }
                """);

        assertThat(RuleRunner.run(new OperationTagsDefinedRule(), yaml))
                .extracting(Violation::pointer)
                .containsExactly("/paths/~1orders/get/tags/0", "/paths/~1orders/get/tags/1");
    }

    /** The loader rejects a path item $ref to another file, so the rule does not run on the file. */
    @Test
    void operationTagsDefinedDoesNotRunOnAPathItemInAnotherFile(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), SPEC.formatted("3.0.3", """
                tags: [ { name: orders } ]
                paths:
                  /orders:
                    $ref: "./order-paths.yaml#/orders"
                """));
        Files.writeString(directory.resolve("order-paths.yaml"), """
                orders:
                  get:
                    tags: [orders, Refunds]
                    responses: { "200": { description: OK } }
                  post:
                    responses: { "201": { description: Created } }
                """);

        List<Violation> violations = new ValidationEngine(List.of(new OperationTagsDefinedRule()))
                .validateLoaded(List.of(new SpecLoader().load(directory.resolve("orders.yaml"))))
                .violations();

        assertThat(violations).extracting(Violation::ruleId, Violation::pointer)
                .containsExactly(tuple("oas-parse", "/paths/~1orders"));
    }

    @Test
    void schemaExamplesPresentNamesTheMediaTypeAndTheResponseOrTheRequestBody() {
        String yaml = SPEC.formatted("3.0.3", """
                paths:
                  /orders:
                    post:
                      requestBody:
                        content: { application/json: { schema: { type: object } } }
                      responses:
                        "201":
                          description: Created.
                          content: { "application/vnd.a\\tb+json": { schema: { type: object } } }
                """);

        assertThat(RuleRunner.run(new SchemaExamplesPresentRule(), yaml))
                .extracting(Violation::pointer, Violation::message)
                .containsExactly(
                        tuple("/paths/~1orders/post/requestBody/content/application~1json",
                                "Media type 'application/json' of the request body has no example. Add example or"
                                        + " examples to the media type or to its schema."),
                        tuple("/paths/~1orders/post/responses/201/content/application~1vnd.a\tb+json",
                                "Media type 'application/vnd.a\\u0009b+json' of response '201' has no example. Add"
                                        + " example or examples to the media type or to its schema."));
    }

    @Test
    void schemaExamplesPresentAcceptsTheExampleOfOas30() {
        String yaml = SPEC.formatted("3.0.3", """
                paths:
                  /orders:
                    get:
                      responses:
                        "200":
                          description: OK.
                          content:
                            application/json: { schema: { $ref: "#/components/schemas/Order" } }
                        "400":
                          description: Bad request.
                          content:
                            application/problem+json:
                              schema: { type: object }
                              example: { title: Bad request }
                components:
                  schemas:
                    Order: { type: object, example: { id: A-1001, status: OPEN } }
                """);

        assertThat(RuleRunner.run(new SchemaExamplesPresentRule(), yaml)).isEmpty();
    }

    /** OAS 3.0 ignores the siblings of a $ref (OAS 3.0.3, Reference Object). OAS 3.1 applies them. */
    @Test
    void schemaExamplesPresentIgnoresAnExampleNextToASchemaRefInOas30() {
        String paths = """
                paths:
                  /orders:
                    get:
                      responses:
                        "200":
                          description: OK.
                          content:
                            application/json:
                              schema: { $ref: "#/components/schemas/Order", %s }
                components:
                  schemas:
                    Order: { type: object }
                """;

        assertThat(RuleRunner.run(new SchemaExamplesPresentRule(),
                SPEC.formatted("3.0.3", paths.formatted("example: { id: A-1001 }"))))
                .extracting(Violation::pointer)
                .containsExactly("/paths/~1orders/get/responses/200/content/application~1json");
        assertThat(RuleRunner.run(new SchemaExamplesPresentRule(),
                SPEC.formatted("3.1.0", paths.formatted("examples: [ { id: A-1001 } ]"))))
                .isEmpty();
    }

    @Test
    void schemaExamplesPresentStopsAtASchemaRefCycle() {
        String yaml = SPEC.formatted("3.1.0", """
                paths:
                  /orders:
                    get:
                      responses:
                        "200":
                          description: OK.
                          content:
                            application/json: { schema: { $ref: "#/components/schemas/A" } }
                components:
                  schemas:
                    A: { $ref: "#/components/schemas/B" }
                    B: { $ref: "#/components/schemas/A" }
                """);

        assertThat(RuleRunner.run(new SchemaExamplesPresentRule(), yaml))
                .extracting(Violation::ruleId, Violation::pointer)
                .containsExactly(tuple("schema-examples-present",
                        "/paths/~1orders/get/responses/200/content/application~1json"));
    }

    /** The rule reads the root tags list once for each file. A second file with other root tags gets its own list. */
    @Test
    void operationTagsDefinedReadsTheRootTagsOfEachFile() {
        String orders = SPEC.formatted("3.0.3", """
                tags: [ { name: orders } ]
                paths:
                  /orders:
                    get:
                      tags: [orders, refunds]
                      responses: { "200": { description: OK } }
                """);
        String refunds = SPEC.formatted("3.0.3", """
                tags: [ { name: refunds } ]
                paths:
                  /refunds:
                    get:
                      tags: [orders, refunds]
                      responses: { "200": { description: OK } }
                """);

        List<Violation> violations = new ValidationEngine(List.of(new OperationTagsDefinedRule()))
                .validate(List.of(new SpecSource("orders.yaml", orders), new SpecSource("refunds.yaml", refunds),
                        new SpecSource("orders-again.yaml", orders)))
                .violations();

        assertThat(violations).extracting(Violation::file, Violation::pointer).containsExactly(
                tuple("orders-again.yaml", "/paths/~1orders/get/tags/1"),
                tuple("orders.yaml", "/paths/~1orders/get/tags/1"),
                tuple("refunds.yaml", "/paths/~1refunds/get/tags/0"));
    }

    /**
     * Two steps: a request body reference to a request body reference, and a response reference to a response
     * reference. Each ends in a JSON media type without an example.
     */
    @Test
    void schemaExamplesPresentFollowsRequestBodyAndResponseChains() {
        String yaml = SPEC.formatted("3.0.3", """
                paths:
                  /orders:
                    post:
                      requestBody: { $ref: "#/components/requestBodies/NewOrder" }
                      responses:
                        "201": { $ref: "#/components/responses/Created" }
                components:
                  requestBodies:
                    NewOrder: { $ref: "#/components/requestBodies/Order" }
                    Order:
                      content: { application/json: { schema: { type: object } } }
                  responses:
                    Created: { $ref: "#/components/responses/OrderResponse" }
                    OrderResponse:
                      description: The order.
                      content: { application/json: { schema: { type: object } } }
                """);

        assertThat(RuleRunner.run(new SchemaExamplesPresentRule(), yaml))
                .extracting(Violation::pointer)
                .containsExactly("/paths/~1orders/post/requestBody/content/application~1json",
                        "/paths/~1orders/post/responses/201/content/application~1json");
    }

    /** A request body that refers to itself: no violation, and the rule does not fail. */
    @Test
    void schemaExamplesPresentStopsAtARequestBodyRefCycle() {
        String yaml = SPEC.formatted("3.0.3", """
                paths:
                  /orders:
                    post:
                      requestBody: { $ref: "#/components/requestBodies/A" }
                      responses: { "201": { description: Created. } }
                components:
                  requestBodies:
                    A: { $ref: "#/components/requestBodies/A" }
                """);

        assertThat(RuleRunner.run(new SchemaExamplesPresentRule(), yaml)).isEmpty();
    }

    /** OAS 3.1: a schema $ref into the $defs of a schema. The rule does not resolve it and skips the media type. */
    @Test
    void schemaExamplesPresentSkipsASchemaRefIntoDefs() {
        String yaml = SPEC.formatted("3.1.0", """
                paths:
                  /orders:
                    get:
                      responses:
                        "200":
                          description: OK.
                          content:
                            application/json: { schema: { $ref: "#/components/schemas/Orders/$defs/Order" } }
                components:
                  schemas:
                    Orders:
                      type: array
                      items: { $ref: "#/components/schemas/Orders/$defs/Order" }
                      $defs:
                        Order: { type: object, example: { id: A-1001 } }
                """);

        assertThat(RuleRunner.run(new SchemaExamplesPresentRule(), yaml)).isEmpty();
    }

    /**
     * OAS 3.0: a schema in another file refers to a schema of that file with {@code #/Order}. swagger-parser leaves
     * such a reference unchanged in the model. The rule skips the media type.
     */
    @Test
    void schemaExamplesPresentSkipsAReferenceThatSwaggerParserLeavesUnchanged(@TempDir Path directory)
            throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), SPEC.formatted("3.0.3", """
                paths:
                  /orders:
                    get:
                      responses:
                        "200":
                          description: OK.
                          content:
                            application/json: { schema: { $ref: "./schemas.yaml#/OrderAlias" } }
                """));
        Files.writeString(directory.resolve("schemas.yaml"), """
                OrderAlias: { $ref: "#/Order" }
                Order: { type: object, example: { id: A-1001 } }
                """);

        List<Violation> violations = new ValidationEngine(List.of(new SchemaExamplesPresentRule()))
                .validateLoaded(List.of(new SpecLoader().load(directory.resolve("orders.yaml"))))
                .violations();

        assertThat(violations).isEmpty();
    }
}
