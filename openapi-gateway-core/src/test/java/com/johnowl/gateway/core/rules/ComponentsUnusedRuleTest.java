package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.spec.SpecLoadResult;
import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The reachability walk of components-unused (spec 030). */
class ComponentsUnusedRuleTest {

    private static final String SPEC = """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            paths:
              /orders:
                get:
                  operationId: listOrders
                  responses:
                    "200":
                      description: The orders.
                      content: { application/json: { schema: %s } }
            components:
            %s
            """;

    @Test
    void schemaUsedOnlyByAnUnusedSchemaIsUnusedToo() {
        String components = """
                  schemas:
                    OrderList: { type: array, items: { $ref: "#/components/schemas/Order" } }
                    Order: { type: object }
                """;

        assertThat(run("{ type: object }", components)).extracting(Violation::pointer, Violation::message)
                .containsExactly(
                        tuple("/components/schemas/Order",
                                "Component 'schemas/Order' is not used. Reference it from an operation, or remove it."),
                        tuple("/components/schemas/OrderList", "Component 'schemas/OrderList' is not used."
                                + " Reference it from an operation, or remove it."));
    }

    @Test
    void followsReferencesTransitivelyAndThroughOtherComponentTypes() {
        String components = """
                  responses:
                    Orders: { description: The orders., content: { application/json: { schema: { $ref: "#/components/schemas/OrderList" } } } }
                  schemas:
                    Wrapper: { type: object, properties: { orders: { $ref: "#/components/schemas/OrderList/items" } } }
                    OrderList: { type: array, items: { $ref: "#/components/schemas/Order" } }
                    Order: { type: object, properties: { lines: { $ref: "#/components/schemas/Line" } } }
                    Line: { type: object }
                """;

        // Wrapper is used. A reference into a component (OrderList/items) uses the component.
        assertThat(run("{ $ref: \"#/components/schemas/Wrapper\" }", components)).extracting(Violation::pointer)
                .containsExactly("/components/responses/Orders");
    }

    @Test
    void cyclesEndAndAreUnusedWithoutAReferenceFromOutside() {
        String components = """
                  schemas:
                    Self: { type: object, properties: { next: { $ref: "#/components/schemas/Self" } } }
                    A: { type: object, properties: { b: { $ref: "#/components/schemas/B" } } }
                    B: { type: object, properties: { a: { $ref: "#/components/schemas/A" } } }
                    C: { type: object, properties: { d: { $ref: "#/components/schemas/D" } } }
                    D: { type: object, properties: { c: { $ref: "#/components/schemas/C" } } }
                """;

        assertThat(run("{ $ref: \"#/components/schemas/C\" }", components)).extracting(Violation::pointer)
                .containsExactlyInAnyOrder("/components/schemas/Self", "/components/schemas/A", "/components/schemas/B");
    }

    @Test
    void discriminatorMappingCountsAsAReference() {
        String schema = """
                { type: object, properties: { kind: { type: string } }, discriminator: { propertyName: kind,\
                 mapping: { cat: "#/components/schemas/Cat", dog: Dog } } }""";
        String components = """
                  schemas:
                    Cat: { type: object }
                    Dog: { type: object }
                    Bird: { type: object }
                """;

        assertThat(run(schema, components)).extracting(Violation::pointer)
                .containsExactly("/components/schemas/Bird");
    }

    @Test
    void schemaWithAllOfToAUsedDiscriminatorSchemaIsUsed() {
        String components = """
                  schemas:
                    Pet:
                      type: object
                      required: [kind]
                      properties: { kind: { type: string } }
                      discriminator: { propertyName: kind }
                    Cat:
                      allOf: [{ $ref: "#/components/schemas/Pet" }, { properties: { toy: { $ref: "#/components/schemas/Toy" } } }]
                    Toy: { type: object }
                    Animal: { type: object, properties: { name: { type: string } } }
                    Dog:
                      allOf: [{ $ref: "#/components/schemas/Animal" }]
                    Fish:
                      allOf: [{ $ref: "#/components/schemas/Pet/properties/kind" }]
                    Lizard:
                      allOf: [{ $ref: "#/components/schemas/Bird" }]
                    Bird: { type: object, discriminator: { propertyName: kind } }
                """;

        // Cat and its Toy are used through the implicit mapping of Pet. Animal has no discriminator, Fish does not
        // reference Pet itself, and Bird is not used.
        assertThat(run("{ $ref: \"#/components/schemas/Pet\" }", components)).extracting(Violation::pointer)
                .containsExactly("/components/schemas/Animal", "/components/schemas/Bird", "/components/schemas/Dog",
                        "/components/schemas/Fish", "/components/schemas/Lizard");
    }

    @Test
    void securitySchemesAreNotChecked() {
        String components = """
                  securitySchemes:
                    entra: { type: openIdConnect, openIdConnectUrl: https://login.example.com/.well-known/openid-configuration }
                """;

        assertThat(run("{ type: object }", components)).isEmpty();
    }

    @Test
    void referencesToOtherFilesAreIgnored(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("common.yaml"), """
                Shared: { type: object }
                """);
        Path orders = Files.writeString(directory.resolve("orders.yaml"), SPEC.formatted(
                "{ $ref: \"common.yaml#/Shared\" }", """
                  schemas:
                    Local: { type: object, properties: { shared: { $ref: "common.yaml#/Shared" } } }
                """));

        SpecLoadResult loaded = new SpecLoader().load(orders);

        assertThat(loaded.violations()).isEmpty();
        assertThat(new ValidationEngine(List.of(new ComponentsUnusedRule())).validateLoaded(List.of(loaded))
                .violations()).extracting(Violation::pointer).containsExactly("/components/schemas/Local");
    }

    private static List<Violation> run(String schema, String components) {
        return RuleRunner.run(new ComponentsUnusedRule(), SPEC.formatted(schema, components));
    }
}
