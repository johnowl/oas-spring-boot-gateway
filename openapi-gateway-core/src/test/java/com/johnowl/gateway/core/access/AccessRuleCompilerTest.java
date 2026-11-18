package com.johnowl.gateway.core.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.route.GatewayRoute;
import com.johnowl.gateway.core.route.GatewayRouteBuilder;
import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.spec.SpecFile;
import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.ValidationReport;
import com.johnowl.gateway.core.validation.Violation;
import io.swagger.v3.oas.models.PathItem;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AccessRuleCompilerTest {

    private static final String AUDIENCE = "6f1c2e8a-0000-0000-0000-000000000000";

    /** The example of spec 018. */
    static final String ORDERS = """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            x-gateway:
              upstream: http://orders-service:8080
              prefix: /orders-api
              audience: 6f1c2e8a-0000-0000-0000-000000000000
            components:
              securitySchemes:
                entra:
                  type: openIdConnect
                  openIdConnectUrl: https://login.microsoftonline.com/tenant/v2.0/.well-known/openid-configuration
            security:
              - entra: []
            paths:
              /orders/{id}:
                parameters:
                  - { name: id, in: path, required: true, schema: { type: string } }
                get:
                  x-gateway-authorization:
                    all-of: [Orders.Read]
                  responses: { "200": { description: OK } }
                delete:
                  x-gateway-authorization:
                    all-of: [Orders.Read, Orders.Delete]
                  responses: { "204": { description: Deleted } }
              /orders/{id}/refund:
                parameters:
                  - { name: id, in: path, required: true, schema: { type: string } }
                post:
                  x-gateway-authorization:
                    any-of: [Orders.Refund, Finance.Admin]
                  responses: { "200": { description: OK } }
              /me:
                get:
                  x-gateway-authorization:
                    authenticated-only: true
                  responses: { "200": { description: OK } }
              /health:
                get:
                  security: []
                  responses: { "200": { description: OK } }
            """;

    @Test
    void compilesTheExampleSpec() {
        ValidationReport report = new ValidationEngine(List.of()).validate(List.of(new SpecSource("orders.yaml", ORDERS)));

        assertThat(report.accessRules()).containsExactlyInAnyOrder(
                new OperationAccess("orders:/orders/{id}", "/orders-api/orders/{id}", "GET",
                        new AccessRule(false, AUDIENCE, Set.of("Orders.Read"), Set.of())),
                new OperationAccess("orders:/orders/{id}", "/orders-api/orders/{id}", "DELETE",
                        new AccessRule(false, AUDIENCE, Set.of("Orders.Read", "Orders.Delete"), Set.of())),
                new OperationAccess("orders:/orders/{id}/refund", "/orders-api/orders/{id}/refund", "POST",
                        new AccessRule(false, AUDIENCE, Set.of(), Set.of("Orders.Refund", "Finance.Admin"))),
                new OperationAccess("orders:/me", "/orders-api/me", "GET",
                        new AccessRule(false, AUDIENCE, Set.of(), Set.of())),
                new OperationAccess("orders:/health", "/orders-api/health", "GET", AccessRule.publicAccess()));
    }

    @Test
    void splitRoutesGiveTheOperationRouteId() {
        ValidationReport report = new ValidationEngine(List.of()).validate(List.of(new SpecSource("reports.yaml", """
                openapi: 3.0.3
                info: { title: Reports, version: 1.0.0 }
                x-gateway: { upstream: "http://reports:8080" }
                paths:
                  /reports:
                    get:
                      security: []
                      x-gateway-response-timeout: 30000
                      responses: { "200": { description: OK } }
                    post:
                      security: []
                      responses: { "201": { description: Created } }
                """)));

        assertThat(report.accessRules()).extracting(OperationAccess::method, OperationAccess::routeId)
                .containsExactlyInAnyOrder(
                        tuple("GET", "reports:GET:/reports"),
                        tuple("POST", "reports:POST:/reports"));
    }

    @Test
    void fileWithoutUpstreamGivesNoAccessRules() {
        ValidationReport report = new ValidationEngine(List.of()).validate(List.of(new SpecSource("plain.yaml", """
                openapi: 3.0.3
                info: { title: Plain, version: 1.0.0 }
                paths:
                  /health:
                    get:
                      security: []
                      responses: { "200": { description: OK } }
                """)));

        assertThat(report.violations()).isEmpty();
        assertThat(report.accessRules()).isEmpty();
    }

    // SEC-66-1: defense in depth. SpecLoader rejects this model, but the compiler must not skip the path item.
    @Test
    void unresolvedPathItemReferenceStopsTheCompiler() {
        SpecFile spec = new SpecLoader().load(new SpecSource("orders.yaml", ORDERS)).spec().orElseThrow();
        List<GatewayRoute> routes = new GatewayRouteBuilder().build(spec);
        spec.openApi().getPaths().addPathItem("/orders/admin", new PathItem().$ref("#/components/pathItems/Admin"));

        assertThatThrownBy(() -> new AccessRuleCompiler().compile(spec, routes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The path item /orders/admin in orders.yaml is an unresolved reference");
    }

    /**
     * SEC-66-1: {@code /orders/admin} references a path item in {@code components}. swagger-parser does not resolve
     * it, so without the loader check {@code GET /orders/admin} matches the public route {@code /orders/{id}}.
     */
    @Test
    void pathItemReferenceNextToAPublicTemplateGivesNoRoutes() {
        ValidationReport report = new ValidationEngine(BuiltInRules.contract())
                .validate(List.of(new SpecSource("orders.yaml", """
                        openapi: 3.1.0
                        info: { title: Orders, version: 1.0.0 }
                        x-gateway: { upstream: "http://orders:8080", audience: %s }
                        components:
                          securitySchemes:
                            entra: { type: openIdConnect, openIdConnectUrl: "https://login.microsoftonline.com/t/v2.0/.well-known/openid-configuration" }
                          pathItems:
                            Admin:
                              get:
                                security: [ { entra: [] } ]
                                x-gateway-authorization: { all-of: [Orders.Admin] }
                                responses: { "200": { description: OK } }
                        paths:
                          /orders/{id}:
                            get:
                              security: []
                              parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
                              responses: { "200": { description: OK } }
                          /orders/admin:
                            $ref: "#/components/pathItems/Admin"
                        """.formatted(AUDIENCE))));

        assertThat(report.violations())
                .extracting(Violation::ruleId, Violation::pointer)
                .containsExactly(tuple("oas-parse", "/paths/~1orders~1admin"));
        assertThat(report.routes()).isEmpty();
        assertThat(report.accessRules()).isEmpty();
    }
}
