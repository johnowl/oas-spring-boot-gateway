package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A scheme with {@code $ref} has no type in the raw document. The rule must check the resolved type. */
class SecuritySchemeTypeRuleTest {

    private static final String COMMON = """
            components:
              securitySchemes:
                key: { type: apiKey, in: header, name: X-Key }
                entra:
                  type: openIdConnect
                  openIdConnectUrl: https://login.microsoftonline.com/tenant/v2.0/.well-known/openid-configuration
            """;

    private final SpecLoader loader = new SpecLoader();

    @TempDir
    Path dir;

    @Test
    void externalReferenceToApiKeySchemeIsReported() throws IOException {
        List<Violation> violations = run("./common.yaml#/components/securitySchemes/key");

        assertThat(violations).extracting(Violation::ruleId, Violation::pointer, Violation::message).containsExactly(
                tuple("security-scheme-type", "/components/securitySchemes/entra",
                        "Security scheme 'entra' refers to a scheme with type 'apiKey'. The gateway supports only"
                                + " openIdConnect and oauth2 (Microsoft Entra ID)."));
    }

    @Test
    void externalReferenceToOpenIdConnectSchemeIsAllowed() throws IOException {
        assertThat(run("./common.yaml#/components/securitySchemes/entra")).isEmpty();
    }

    @Test
    void internalReferenceToApiKeySchemeIsReported() throws IOException {
        List<Violation> violations = run("#/x-legacy/key");

        assertThat(violations).extracting(Violation::ruleId, Violation::pointer)
                .containsExactly(tuple("security-scheme-type", "/components/securitySchemes/entra"));
    }

    @Test
    void mutualTlsSchemeOfOpenApi31IsReported() throws IOException {
        Path orders = Files.writeString(dir.resolve("orders.yaml"), """
                openapi: 3.1.0
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: http://orders-service:8080 }
                components:
                  securitySchemes:
                    mtls: { type: mutualTLS }
                paths:
                  /orders:
                    get:
                      security: []
                      responses:
                        "200": { description: OK }
                """);

        assertThat(contract(orders)).extracting(Violation::ruleId, Violation::pointer, Violation::message)
                .containsExactly(tuple("security-scheme-type", "/components/securitySchemes/mtls/type",
                        "Security scheme 'mtls' has type 'mutualTLS'. The gateway supports only openIdConnect and"
                                + " oauth2 (Microsoft Entra ID)."));
    }

    /** Runs all CONTRACT rules on orders.yaml, whose scheme {@code entra} is a {@code $ref}. */
    private List<Violation> run(String ref) throws IOException {
        Files.writeString(dir.resolve("common.yaml"), COMMON);
        Path orders = Files.writeString(dir.resolve("orders.yaml"), """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: http://orders-service:8080 }
                x-legacy:
                  key: { type: apiKey, in: header, name: X-API-Key }
                components:
                  securitySchemes:
                    entra: { $ref: '%s' }
                paths:
                  /orders:
                    get:
                      security: []
                      responses:
                        "200": { description: OK }
                """.formatted(ref));
        return contract(orders);
    }

    private List<Violation> contract(Path file) throws IOException {
        return new ValidationEngine(BuiltInRules.contract())
                .validateLoaded(List.of(loader.load(file)))
                .violations();
    }
}
