package com.johnowl.gateway.core.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.spec.SpecFile;
import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.spec.SpecSource;
import io.swagger.v3.oas.models.PathItem;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GatewayRouteBuilderTest {

    private static final String ORDERS = """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            x-gateway:
              upstream: http://orders-service:8080
              prefix: /orders-api
              upstream-base-path: /v1
              audience: 6f1c2e8a-0000-0000-0000-000000000000
            paths:
              /orders/{id}:
                parameters:
                  - { name: id, in: path, required: true, schema: { type: string } }
                get:
                  responses: { "200": { description: OK } }
                delete:
                  responses: { "204": { description: Deleted } }
              /orders/export:
                get:
                  x-gateway-response-timeout: 30000
                  responses: { "200": { description: OK } }
            """;

    private final GatewayRouteBuilder builder = new GatewayRouteBuilder();

    @Test
    void buildsRoutesOfTheExample() {
        URI upstream = URI.create("http://orders-service:8080");
        String audience = "6f1c2e8a-0000-0000-0000-000000000000";

        assertThat(build("openapi/orders.yaml", ORDERS)).containsExactly(
                new GatewayRoute("orders:/orders/export", upstream, "/orders-api/orders/export",
                        Set.of("GET"), 1, "/v1", 0, 30000, audience),
                new GatewayRoute("orders:/orders/{id}", upstream, "/orders-api/orders/{id}",
                        Set.of("DELETE", "GET"), 1, "/v1", 100, null, audience));
    }

    @Test
    void splitsPathWhenTimeoutsDiffer() {
        List<GatewayRoute> routes = build("reports.yaml", """
                openapi: 3.0.3
                info: { title: Reports, version: 1.0.0 }
                x-gateway: { upstream: http://reports:8080 }
                paths:
                  /reports:
                    get:
                      x-gateway-response-timeout: 30000
                      responses: { "200": { description: OK } }
                    delete:
                      responses: { "204": { description: Deleted } }
                """);

        assertThat(routes).extracting(GatewayRoute::id, GatewayRoute::methods, GatewayRoute::responseTimeoutMillis)
                .containsExactly(
                        tuple("reports:DELETE:/reports", Set.of("DELETE"), null),
                        tuple("reports:GET:/reports", Set.of("GET"), 30000));
    }

    @Test
    void groupsMethodsWithTheSameTimeout() {
        List<GatewayRoute> routes = build("reports.yaml", """
                openapi: 3.0.3
                info: { title: Reports, version: 1.0.0 }
                x-gateway: { upstream: http://reports:8080 }
                paths:
                  /reports:
                    get:
                      x-gateway-response-timeout: 5000
                      responses: { "200": { description: OK } }
                    post:
                      x-gateway-response-timeout: 5000
                      responses: { "201": { description: Created } }
                """);

        assertThat(routes).singleElement().satisfies(route -> {
            assertThat(route.id()).isEqualTo("reports:/reports");
            assertThat(route.methods()).containsExactlyInAnyOrder("GET", "POST");
            assertThat(route.responseTimeoutMillis()).isEqualTo(5000);
        });
    }

    @Test
    void literalPathsHaveLowerOrderThanTemplates() {
        List<GatewayRoute> routes = build("users.yaml", """
                openapi: 3.0.3
                info: { title: Users, version: 1.0.0 }
                x-gateway: { upstream: http://users:8080 }
                paths:
                  /users/me:
                    get:
                      responses: { "200": { description: OK } }
                  /users/{id}:
                    get:
                      parameters:
                        - { name: id, in: path, required: true, schema: { type: string } }
                      responses: { "200": { description: OK } }
                """);

        assertThat(routes).extracting(GatewayRoute::path, GatewayRoute::order)
                .containsExactly(
                        tuple("/users/me", 0),
                        tuple("/users/{id}", 100));
    }

    @Test
    void optionalFieldsHaveDefaults() {
        GatewayRoute route = build("users.yaml", """
                openapi: 3.0.3
                info: { title: Users, version: 1.0.0 }
                x-gateway: { upstream: http://users:8080 }
                paths:
                  /users:
                    get:
                      responses: { "200": { description: OK } }
                """).getFirst();

        assertThat(route.path()).isEqualTo("/users");
        assertThat(route.stripPrefix()).isZero();
        assertThat(route.prefixPath()).isNull();
        assertThat(route.audience()).isNull();
        assertThat(route.responseTimeoutMillis()).isNull();
    }

    @Test
    void countsPrefixSegments() {
        GatewayRoute route = build("users.yaml", """
                openapi: 3.0.3
                info: { title: Users, version: 1.0.0 }
                x-gateway: { upstream: http://users:8080, prefix: /a/b }
                paths:
                  /users:
                    get:
                      responses: { "200": { description: OK } }
                """).getFirst();

        assertThat(route.path()).isEqualTo("/a/b/users");
        assertThat(route.stripPrefix()).isEqualTo(2);
    }

    @Test
    void rootPathWithPrefixHasThePrefixAsPublicPath() {
        List<GatewayRoute> routes = build("orders.yaml", """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: http://orders:8080, prefix: /orders-api }
                paths:
                  /:
                    get:
                      responses: { "200": { description: OK } }
                """);

        assertThat(routes).extracting(GatewayRoute::id, GatewayRoute::path, GatewayRoute::stripPrefix)
                .containsExactly(tuple("orders:/", "/orders-api", 1));
    }

    @Test
    void buildIsDeterministic() {
        assertThat(build("orders.yaml", ORDERS)).isEqualTo(build("orders.yaml", ORDERS));
    }

    @Test
    void fileWithoutUpstreamHasNoRoutes() {
        assertThat(build("users.yaml", """
                openapi: 3.0.3
                info: { title: Users, version: 1.0.0 }
                paths: {}
                """)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"http://orders service:8080\"", "\"{{ORDERS_URL}}\"", "orders-service", "8080"})
    void fileWithInvalidUpstreamHasNoRoutesAndDoesNotThrow(String upstream) {
        assertThat(build("orders.yaml", """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: %s }
                paths:
                  /orders:
                    get:
                      responses: { "200": { description: OK } }
                """.formatted(upstream))).isEmpty();
    }

    @Test
    void routeIdUsesFileNameWithoutDirectoryAndExtension() {
        assertThat(GatewayRouteBuilder.fileName("gateway/src/main/resources/openapi/orders.v2.yaml"))
                .isEqualTo("orders.v2");
        assertThat(GatewayRouteBuilder.fileName("orders")).isEqualTo("orders");
    }

    // SEC-66-1: defense in depth. SpecLoader rejects this model, but the builder must not drop the path item.
    @Test
    void unresolvedPathItemReferenceStopsTheBuild() {
        SpecFile spec = new SpecLoader().load(new SpecSource("orders.yaml", ORDERS)).spec().orElseThrow();
        spec.openApi().getPaths().addPathItem("/orders/admin", new PathItem().$ref("#/components/pathItems/Admin"));

        assertThatThrownBy(() -> builder.build(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The path item /orders/admin in orders.yaml is an unresolved reference");
    }

    private List<GatewayRoute> build(String path, String yaml) {
        SpecFile spec = new SpecLoader().load(new SpecSource(path, yaml)).spec().orElseThrow();
        return builder.build(spec);
    }
}
