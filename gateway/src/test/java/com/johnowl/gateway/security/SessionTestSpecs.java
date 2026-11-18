package com.johnowl.gateway.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * OAS files for the session tests. {@code site.yaml} has the public pages
 * {@code GET /} and {@code GET /signed-out}. {@code orders.yaml} has the public operation
 * {@code GET /orders-api/health} and the protected operations {@code GET /orders-api/orders/{id}}
 * and {@code POST /orders-api/orders}. A full application test needs no test bean: the authorization
 * manager of spec 024 is active. A test with a part of the configuration and without
 * {@link AuthorizationConfiguration} needs {@link com.johnowl.gateway.routing.TestAudienceAuthorization}.
 */
final class SessionTestSpecs {

    static final String AUDIENCE = "6f1c2e8a-0000-0000-0000-000000000000";

    /** The audience of {@code payments.yaml} (spec 023 tests only). */
    static final String PAYMENTS_AUDIENCE = "7a2d3f9b-0000-0000-0000-000000000000";

    private SessionTestSpecs() {
    }

    /** Writes the files and registers them as dynamic properties of a Spring Boot test. */
    static void register(DynamicPropertyRegistry registry, int backendPort) {
        for (String property : write(backendPort)) {
            String[] nameAndValue = property.split("=", 2);
            registry.add(nameAndValue[0], () -> nameAndValue[1]);
        }
    }

    /**
     * Writes the files and a second protected backend, {@code payments.yaml}, with the protected
     * operation {@code GET /payments-api/payments/{id}} (audience {@link #PAYMENTS_AUDIENCE}).
     */
    static void registerWithPayments(DynamicPropertyRegistry registry, int backendPort) {
        registerWith(registry, backendPort, "");
    }

    /**
     * Writes the files of {@link #registerWithPayments}. {@code orders.yaml} also has the public operation
     * {@code GET /orders-api/items/{id}} and the protected operation {@code DELETE /orders-api/items/{id}}
     * (permission {@code Orders.Write}) on the same path, so in one route.
     */
    static void registerWithPaymentsAndItems(DynamicPropertyRegistry registry, int backendPort) {
        registerWith(registry, backendPort, """
                  /items/{id}:
                    parameters:
                      - { name: id, in: path, required: true, schema: { type: string } }
                    get:
                      security: []
                      responses: { "200": { description: OK } }
                    delete:
                      x-gateway-authorization: { all-of: [Orders.Write] }
                      responses: { "200": { description: OK } }
                """);
    }

    private static void registerWith(DynamicPropertyRegistry registry, int backendPort, String moreOrdersPaths) {
        String[] nameAndValue = write(backendPort, moreOrdersPaths)[0].split("=", 2);
        Path directory = Path.of(nameAndValue[1].substring("file:".length())).getParent();
        try {
            Files.writeString(directory.resolve("payments.yaml"), """
                    openapi: 3.0.3
                    info: { title: Payments, version: 1.0.0 }
                    x-gateway: { upstream: "http://127.0.0.1:%d", prefix: /payments-api, audience: %s }
                    components:
                      securitySchemes:
                        entra: { type: openIdConnect, openIdConnectUrl: "https://login.example.com/.well-known/openid-configuration" }
                    security:
                      - entra: []
                    paths:
                      /payments/{id}:
                        get:
                          parameters:
                            - { name: id, in: path, required: true, schema: { type: string } }
                          x-gateway-authorization: { all-of: [Payments.Read] }
                          responses: { "200": { description: OK } }
                    """.formatted(backendPort, PAYMENTS_AUDIENCE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add(nameAndValue[0], () -> nameAndValue[1]);
    }

    /**
     * Writes the files into a new temporary directory.
     *
     * @param backendPort the port of the backend of all operations
     * @return the property value {@code gateway.openapi.specs}
     */
    static String[] write(int backendPort) {
        return write(backendPort, "");
    }

    /** @param moreOrdersPaths more path items of {@code orders.yaml}, indented by two spaces, or empty */
    private static String[] write(int backendPort, String moreOrdersPaths) {
        try {
            Path directory = Files.createTempDirectory("session-specs");
            Files.writeString(directory.resolve("site.yaml"), """
                    openapi: 3.0.3
                    info: { title: Site, version: 1.0.0 }
                    x-gateway: { upstream: "http://127.0.0.1:%d" }
                    paths:
                      /:
                        get:
                          security: []
                          responses: { "200": { description: OK } }
                      /signed-out:
                        get:
                          security: []
                          responses: { "200": { description: OK } }
                    """.formatted(backendPort));
            Files.writeString(directory.resolve("orders.yaml"), """
                    openapi: 3.0.3
                    info: { title: Orders, version: 1.0.0 }
                    x-gateway: { upstream: "http://127.0.0.1:%d", prefix: /orders-api, audience: %s }
                    components:
                      securitySchemes:
                        entra: { type: openIdConnect, openIdConnectUrl: "https://login.example.com/.well-known/openid-configuration" }
                    security:
                      - entra: []
                    paths:
                      /health:
                        get:
                          security: []
                          responses: { "200": { description: OK } }
                      /orders:
                        post:
                          x-gateway-authorization: { all-of: [Orders.Write] }
                          responses: { "200": { description: OK } }
                      /orders/{id}:
                        get:
                          parameters:
                            - { name: id, in: path, required: true, schema: { type: string } }
                          x-gateway-authorization: { all-of: [Orders.Read] }
                          responses: { "200": { description: OK } }
                    """.formatted(backendPort, AUDIENCE) + moreOrdersPaths);
            return new String[] {
                "gateway.openapi.specs=file:" + directory + "/*.yaml",
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
