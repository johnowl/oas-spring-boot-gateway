package com.johnowl.gateway.core.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.Violation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpecLoaderTest {

    private final SpecLoader loader = new SpecLoader();

    @Test
    void loadsOas30() throws Exception {
        SpecLoadResult result = loader.load(fixture("oas30.yaml"));

        assertThat(result.violations()).isEmpty();
        SpecFile spec = result.spec().orElseThrow();
        assertThat(spec.openApi().getPaths()).containsKey("/orders/{id}");
        assertThat(spec.raw().at("/info/title").asText()).isEqualTo("Orders");
        assertThat(spec.path()).endsWith("spec-loader/oas30.yaml");
    }

    @Test
    void loadsOas31() throws Exception {
        SpecLoadResult result = loader.load(fixture("oas31.json"));

        assertThat(result.violations()).isEmpty();
        SpecFile spec = result.spec().orElseThrow();
        assertThat(spec.openApi().getOpenapi()).isEqualTo("3.1.0");
        assertThat(spec.raw().at("/openapi").asText()).isEqualTo("3.1.0");
    }

    @Test
    void resolvesRelativeExternalReference() throws Exception {
        SpecLoadResult result = loader.load(fixture("external-ref.yaml"));

        assertThat(result.violations()).isEmpty();
        Schema<?> schema = result.spec().orElseThrow().openApi()
                .getPaths().get("/orders").getGet()
                .getResponses().get("500")
                .getContent().get("application/json").getSchema();
        assertThat(schema.get$ref()).isEqualTo("#/components/schemas/Error");
        assertThat(result.spec().orElseThrow().openApi().getComponents().getSchemas())
                .containsKey("Error");
        // The raw tree keeps the reference as it is in the file.
        assertThat(result.spec().orElseThrow().raw()
                .at("/paths/~1orders/get/responses/500/content/application~1json/schema/$ref").asText())
                .isEqualTo("./common.yaml#/components/schemas/Error");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "invalid-yaml.yaml",
        "swagger20.yaml",
        "missing-openapi.yaml",
        "unsupported-version.yaml",
        "unresolved-external-ref.yaml",
        "unresolved-internal-ref.yaml",
        "unresolved-external-ref-oas31.yaml",
        "unresolved-ref-oas31.yaml",
        "invalid-json.json",
        "missing-info.yaml"
    })
    void reportsOasParseViolation(String name) throws Exception {
        SpecLoadResult result = loader.load(fixture(name));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations()).isNotEmpty().allSatisfy(violation -> {
            assertThat(violation.ruleId()).isEqualTo("oas-parse");
            assertThat(violation.severity()).isEqualTo(Severity.ERROR);
            assertThat(violation.file()).endsWith(name);
        });
    }

    @Test
    void reportsInvalidYamlAtDocumentRoot() throws Exception {
        Violation violation = loader.load(fixture("invalid-yaml.yaml")).violations().getFirst();

        assertThat(violation.pointer()).isEmpty();
        assertThat(violation.message())
                .isEqualTo("Invalid YAML at line 4, column 12: mapping values are not allowed here");
    }

    @Test
    void reportsSwagger20AtSwaggerField() throws Exception {
        Violation violation = loader.load(fixture("swagger20.yaml")).violations().getFirst();

        assertThat(violation.pointer()).isEqualTo("/swagger");
        assertThat(violation.message()).isEqualTo("Unsupported OpenAPI version '2.0'");
    }

    @Test
    void reportsUnsupportedVersionAtOpenapiField() throws Exception {
        Violation violation = loader.load(fixture("unsupported-version.yaml")).violations().getFirst();

        assertThat(violation.pointer()).isEqualTo("/openapi");
        assertThat(violation.message()).isEqualTo("Unsupported OpenAPI version '4.0.0'");
    }

    @Test
    void reportsNonTextVersionAsItIs() {
        Violation violation = loader.load(new SpecSource("x.yaml", "openapi: [3]")).violations().getFirst();

        assertThat(violation.message()).isEqualTo("Unsupported OpenAPI version '[3]'");
    }

    @Test
    void reportsMissingOpenapiField() throws Exception {
        Violation violation = loader.load(fixture("missing-openapi.yaml")).violations().getFirst();

        assertThat(violation.pointer()).isEmpty();
        assertThat(violation.message()).isEqualTo("Missing field 'openapi'");
    }

    @Test
    void reportsInvalidJson() throws Exception {
        Violation violation = loader.load(fixture("invalid-json.json")).violations().getFirst();

        assertThat(violation.pointer()).isEmpty();
        assertThat(violation.message())
                .startsWith("Invalid JSON at line 6, column 1: Unexpected end-of-input")
                .doesNotContain("\n");
    }

    @Test
    void invalidJsonMessageDoesNotRepeatTheUnknownToken() {
        Violation violation = loader.load(new SpecSource("secret.json", "ghs_SECRETVALUE1234567890abcdef\n"))
                .violations().getFirst();

        assertThat(violation.message())
                .startsWith("Invalid JSON at line 1, column ")
                .contains("Unrecognized token")
                .doesNotContain("SECRET");
    }

    @Test
    void reportsSwaggerParserMessage() throws Exception {
        assertThat(loader.load(fixture("missing-info.yaml")).violations())
                .extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple("", "attribute info is missing"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unresolved-internal-ref.yaml", "unresolved-ref-oas31.yaml"})
    void reportsUnresolvedInternalReference(String name) throws Exception {
        assertThat(loader.load(fixture(name)).violations())
                .extracting(Violation::message)
                .contains("attribute paths.'/orders'(get).responses.200.content.'application/json'.schema."
                        + "#/components/schemas/DoesNotExist is missing");
    }

    @Test
    void reportsUnresolvedExternalReference() throws Exception {
        assertThat(loader.load(fixture("unresolved-external-ref.yaml")).violations())
                .extracting(Violation::message)
                .singleElement(as(STRING))
                .startsWith("Unable to load RELATIVE ref: ./does-not-exist.yaml path: ");
    }

    @Test
    void reportsUnresolvedExternalReferenceInOas31() throws Exception {
        assertThat(loader.load(fixture("unresolved-external-ref-oas31.yaml")).violations())
                .extracting(Violation::message)
                .singleElement(as(STRING))
                .contains("does-not-exist.yaml");
    }

    // SEC-66-1: swagger-parser keeps this reference and gives the path item no operations.
    @Test
    void rejectsPathItemReferenceToComponents() throws Exception {
        SpecLoadResult result = loader.load(fixture("path-item-component-ref.yaml"));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations())
                .extracting(Violation::severity, Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple(Severity.ERROR, "oas-parse", "/paths/~1orders~1admin",
                        notSupported("#/components/pathItems/Admin")));
    }

    /**
     * SEC-67-1: swagger-parser resolves {@code #/webhooks/Admin}, follows its {@code $ref} to {@code Inner}, and
     * drops the {@code get} next to it. Without the check, {@code GET /orders/admin} uses the public route
     * {@code /orders/{id}}.
     */
    @Test
    void rejectsPathItemReferenceChainThatDropsSiblingOperations() {
        SpecLoadResult result = loader.load(new SpecSource("orders.yaml", """
                openapi: 3.1.0
                info: { title: Orders, version: 1.0.0 }
                webhooks:
                  Admin:
                    $ref: "#/webhooks/Inner"
                    get:
                      security: [ { entra: [] } ]
                      responses: { "200": { description: OK } }
                  Inner:
                    post:
                      responses: { "200": { description: OK } }
                paths:
                  /orders/{id}:
                    get:
                      security: []
                      parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
                      responses: { "200": { description: OK } }
                  /orders/admin:
                    $ref: "#/webhooks/Admin"
                """));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations())
                .extracting(Violation::severity, Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(
                        tuple(Severity.ERROR, "oas-parse", "/paths/~1orders~1admin", notSupported("#/webhooks/Admin")),
                        tuple(Severity.ERROR, "oas-parse", "/webhooks/Admin", notSupported("#/webhooks/Inner")));
    }

    @Test
    void rejectsPathItemReferenceInWebhooksComponentsAndCallbacks() {
        SpecLoadResult result = loader.load(new SpecSource("hooks.yaml", """
                openapi: 3.1.0
                info: { title: Hooks, version: 1.0.0 }
                components:
                  pathItems:
                    Event:
                      post:
                        responses: { "200": { description: OK } }
                    Alias:
                      $ref: "#/components/pathItems/Event"
                  callbacks:
                    Done:
                      "{$request.body#/doneUrl}":
                        $ref: "#/components/pathItems/Event"
                webhooks:
                  orderCreated:
                    $ref: "#/components/pathItems/Event"
                  w:
                    post:
                      responses: { "200": { description: OK } }
                      callbacks:
                        cb:
                          "{$url}":
                            $ref: "#/components/pathItems/Event"
                        done:
                          $ref: "#/components/callbacks/Done"
                paths:
                  x-internal:
                    $ref: "#/components/pathItems/Event"
                  /subscriptions:
                    post:
                      responses: { "201": { description: Created } }
                      callbacks:
                        onEvent:
                          "{$request.body#/url}":
                            $ref: "#/components/pathItems/Event"
                """));

        String event = notSupported("#/components/pathItems/Event");
        assertThat(result.spec()).isEmpty();
        assertThat(result.violations())
                .extracting(Violation::severity, Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(
                        tuple(Severity.ERROR, "oas-parse", "/webhooks/orderCreated", event),
                        tuple(Severity.ERROR, "oas-parse", "/components/pathItems/Alias", event),
                        tuple(Severity.ERROR, "oas-parse", "/components/callbacks/Done/{$request.body#~1doneUrl}",
                                event),
                        tuple(Severity.ERROR, "oas-parse",
                                "/paths/~1subscriptions/post/callbacks/onEvent/{$request.body#~1url}", event),
                        tuple(Severity.ERROR, "oas-parse", "/webhooks/w/post/callbacks/cb/{$url}", event));
    }

    // The message quotes the $ref text of the file. It escapes unsafe characters and cuts a long text.
    @Test
    void escapesAndCutsTheReferenceText() {
        SpecLoadResult result = loader.load(new SpecSource("orders.json", """
                {"openapi": "3.1.0", "info": {"title": "Orders", "version": "1.0.0"}, "paths": {
                  "/a": {"$ref": "#/components/pathItems/A\\tB\\nC\\u202e"},
                  "/b": {"$ref": "#/components/pathItems/%s"}}}
                """.formatted("x".repeat(300))));

        String longText = ("#/components/pathItems/" + "x".repeat(300)).substring(0, 200) + "...";
        assertThat(result.violations())
                .extracting(Violation::pointer, Violation::message)
                .containsExactly(
                        tuple("/paths/~1a", notSupported("#/components/pathItems/A\\u0009B\\u000AC\\u202E")),
                        tuple("/paths/~1b", notSupported(longText)));
    }

    @Test
    void rejectsPathItemReferenceInOas30() {
        SpecLoadResult result = loader.load(new SpecSource("orders.yaml", """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                components:
                  pathItems:
                    Admin:
                      get:
                        responses: { "200": { description: OK } }
                paths:
                  /orders/admin:
                    $ref: "#/components/pathItems/Admin"
                """));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations())
                .extracting(Violation::severity, Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple(Severity.ERROR, "oas-parse", "/paths/~1orders~1admin",
                        notSupported("#/components/pathItems/Admin")));
    }

    @Test
    void rejectsPathItemReferenceToMissingComponent() {
        SpecLoadResult result = loader.load(new SpecSource("orders.yaml", """
                openapi: 3.1.0
                info: { title: Orders, version: 1.0.0 }
                paths:
                  /orders/admin:
                    $ref: "#/components/pathItems/DoesNotExist"
                """));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations())
                .extracting(Violation::severity, Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple(Severity.ERROR, "oas-parse", "/paths/~1orders~1admin",
                        notSupported("#/components/pathItems/DoesNotExist")));
    }

    // SEC-67 DECISION: swagger-parser resolves this alias, but every path item reference is rejected.
    @Test
    void rejectsPathItemReferenceToAnotherPath() {
        SpecLoadResult result = loader.load(new SpecSource("orders.yaml", """
                openapi: 3.1.0
                info: { title: Orders, version: 1.0.0 }
                paths:
                  /orders:
                    get:
                      responses: { "200": { description: OK } }
                  /orders-alias:
                    $ref: "#/paths/~1orders"
                """));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations())
                .extracting(Violation::severity, Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple(Severity.ERROR, "oas-parse", "/paths/~1orders-alias",
                        notSupported("#/paths/~1orders")));
    }

    // SEC-67 DECISION: swagger-parser resolves this file reference, but every path item reference is rejected.
    @Test
    void rejectsPathItemReferenceToAnotherFile() throws Exception {
        SpecLoadResult result = loader.load(fixture("path-item-file-ref/orders.yaml"));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations())
                .extracting(Violation::severity, Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple(Severity.ERROR, "oas-parse", "/paths/~1orders~1admin",
                        notSupported("./admin.yaml#/components/pathItems/Admin")));
    }

    /**
     * Defense in depth. No known input gives other operations in the model than in the raw tree, once the loader
     * rejects path item references, so the test builds the model.
     */
    @Test
    void reportsEachPathWithOtherOperationsInTheModel() throws Exception {
        JsonNode raw = RawDocuments.parse("orders.yaml", """
                paths:
                  /orders:
                    get: {}
                    post: {}
                  /payments:
                    get: {}
                  /same:
                    delete: {}
                  x-extension:
                    get: {}
                """);
        Paths paths = new Paths()
                .addPathItem("/orders", new PathItem().get(new Operation()))
                .addPathItem("/same", new PathItem().delete(new Operation()))
                .addPathItem("/refunds", new PathItem().get(new Operation()));

        assertThat(SpecLoader.operationDifferences("orders.yaml", raw, new OpenAPI().paths(paths)))
                .extracting(Violation::pointer)
                .containsExactly("/paths/~1orders", "/paths/~1payments", "/paths/~1refunds");
    }

    private static String notSupported(String reference) {
        return "Path item reference '" + reference + "' is not supported. Inline the operations.";
    }

    // The loader now rejects a second YAML document before swagger-parser runs (SEC-67-4). This SnakeYAML message
    // of swagger-parser had several lines.
    @Test
    void joinsTheLinesOfAParserMessage() {
        assertThat(SpecLoader.oneLine("""
                expected a single document in the stream
                 in 'reader', line 1, column 1:
                    openapi: 3.0.3
                    ^
                but found another document\r
                """)).isEqualTo("expected a single document in the stream in 'reader', line 1, column 1:"
                + " openapi: 3.0.3 ^ but found another document");
    }

    // SEC-67-5: a local callback $ref can point to any place of the file. The check follows it.
    @Test
    void rejectsPathItemReferenceBehindLocalCallbackReference() {
        SpecLoadResult result = loader.load(new SpecSource("hooks.yaml", """
                openapi: 3.1.0
                info: { title: Hooks, version: 1.0.0 }
                x-hooks:
                  OnEvent:
                    "{$request.body#/url}":
                      $ref: "#/x-admin"
                  Loop:
                    $ref: "#/x-hooks/Loop"
                  "On Event":
                    $ref: "#/x-hooks/OnEvent"
                x-admin:
                  get:
                    security: []
                    responses: { "200": { description: OK } }
                paths:
                  /subscriptions:
                    post:
                      responses: { "201": { description: Created } }
                      callbacks:
                        onEvent:
                          $ref: "#/x-hooks/OnEvent"
                        again:
                          $ref: "#/x-hooks/On%20Event"
                        loop:
                          $ref: "#/x-hooks/Loop"
                        missing:
                          $ref: "#/x-hooks/Missing"
                """));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations())
                .extracting(Violation::severity, Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple(Severity.ERROR, "oas-parse", "/x-hooks/OnEvent/{$request.body#~1url}",
                        notSupported("#/x-admin")));
    }

    // QA-69-1: the path item reference is only behind "#/x-hooks/On%20Event", so only the decoded pointer finds it.
    @Test
    void followsAPercentEncodedCallbackReference() {
        SpecLoadResult result = loader.load(new SpecSource("hooks.yaml", """
                openapi: 3.1.0
                info: { title: Hooks, version: 1.0.0 }
                x-hooks:
                  "On Event":
                    "{$url}":
                      $ref: "#/x-admin"
                x-admin:
                  get:
                    responses: { "200": { description: OK } }
                paths:
                  /subscriptions:
                    post:
                      responses: { "201": { description: Created } }
                      callbacks:
                        onEvent:
                          $ref: "#/x-hooks/On%20Event"
                """));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations())
                .extracting(Violation::severity, Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple(Severity.ERROR, "oas-parse", "/x-hooks/On Event/{$url}",
                        notSupported("#/x-admin")));
    }

    // SEC-67-5: a Callback Object with $ref next to expressions. The check reads the target and the expressions.
    @Test
    void rejectsPathItemReferencesInCallbackWithReferenceAndExpressions() {
        SpecLoadResult result = loader.load(new SpecSource("hooks.yaml", """
                openapi: 3.1.0
                info: { title: Hooks, version: 1.0.0 }
                components:
                  callbacks:
                    Done:
                      "{$request.body#/doneUrl}":
                        $ref: "#/x-done"
                paths:
                  /subscriptions:
                    post:
                      responses: { "201": { description: Created } }
                      callbacks:
                        onEvent:
                          $ref: "#/components/callbacks/Done"
                          "{$request.body#/url}":
                            $ref: "#/x-event"
                          x-note:
                            $ref: "#/x-ignored"
                """));

        assertThat(result.spec()).isEmpty();
        assertThat(result.violations())
                .extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(
                        tuple("oas-parse", "/components/callbacks/Done/{$request.body#~1doneUrl}",
                                notSupported("#/x-done")),
                        tuple("oas-parse", "/paths/~1subscriptions/post/callbacks/onEvent/{$request.body#~1url}",
                                notSupported("#/x-event")));
    }

    @Test
    void reportsNonTextSwaggerVersionAsJson() {
        Violation violation = loader.load(new SpecSource("x.yaml", "swagger: {a: 1}")).violations().getFirst();

        assertThat(violation.pointer()).isEqualTo("/swagger");
        assertThat(violation.message()).isEqualTo("Unsupported OpenAPI version '{\"a\":1}'");
    }

    @Test
    void reportsNullOpenapiValueAsMissing() {
        Violation violation = loader.load(new SpecSource("x.yaml", "openapi:\n")).violations().getFirst();

        assertThat(violation.pointer()).isEmpty();
        assertThat(violation.message()).isEqualTo("Missing field 'openapi'");
    }

    @Test
    void loadsSourceWithoutFile() {
        SpecLoadResult result = loader.load(new SpecSource("openapi/inline.yaml", """
                openapi: 3.0.3
                info: { title: Inline, version: 1.0.0 }
                paths: {}
                """));

        assertThat(result.violations()).isEmpty();
        assertThat(result.spec().orElseThrow().path()).isEqualTo("openapi/inline.yaml");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "[]", "42", "openapi: 3.0.3", "{", "openapi: [3]", "\u0000"})
    void neverThrowsForInvalidContent(String content) {
        assertThatNoException().isThrownBy(() -> {
            SpecLoadResult result = loader.load(new SpecSource("broken.yaml", content));
            assertThat(result.spec()).isEmpty();
            assertThat(result.violations()).isNotEmpty();
        });
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(SpecLoaderTest.class.getResource("/spec-loader/" + name).toURI());
    }
}
