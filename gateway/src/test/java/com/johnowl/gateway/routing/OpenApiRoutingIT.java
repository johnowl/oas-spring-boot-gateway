package com.johnowl.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.Loopback;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Routes from OAS files, end to end. The OAS files point to a mock backend on a random port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiRoutingIT {

    private static RecordingMockBackend backend;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private OpenApiRouteDefinitionLocator locator;

    @DynamicPropertySource
    static void oasFiles(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        Path directory = writeSpecs(backend.port());
        registry.add("gateway.openapi.specs", () -> "file:" + directory + "/*.yaml");
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @Autowired
    private IndexedRoutePredicateHandlerMapping mapping;

    // The index is built after startup. Without the wait, a test could pass through the linear lookup.
    @BeforeEach
    void clearRequestsAndAwaitIndex() throws InterruptedException {
        backend.clear();
        RouteIndexAwait.awaitIndex(mapping);
    }

    @AfterEach
    void noFallbackToTheLinearLookup() {
        assertThat(mapping.fallbackCount()).as("fallbacks to the linear lookup").isZero();
    }

    @Test
    void forwardsDocumentedRequestWithPrefixAndBasePath() {
        String csrfToken = client().get().uri("/orders-api/orders/42").exchange().expectStatus().isOk()
                .returnResult(Void.class).getResponseCookies().getFirst("__Host-XSRF-TOKEN").getValue();
        // A state-changing request needs the CSRF token, also for a public operation (spec 022).
        client().delete().uri("/orders-api/orders/42")
                .cookie("__Host-XSRF-TOKEN", csrfToken).header("X-XSRF-TOKEN", csrfToken)
                .exchange().expectStatus().isOk();

        assertThat(backend.requests()).containsExactly("GET /v1/orders/42", "DELETE /v1/orders/42");
    }

    @Test
    void forwardsPathUnchangedWithoutPrefixAndBasePath() {
        client().get().uri("/users/7").exchange().expectStatus().isOk();

        assertThat(backend.requests()).containsExactly("GET /users/7");
    }

    @Test
    void literalPathWinsOverTemplate() {
        // Only the literal route /users/me has a 500 ms timeout; the backend answers "slow" after 1 s.
        client().get().uri("/users/me?slow=1").exchange().expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        client().get().uri("/users/42?slow=1").exchange().expectStatus().isOk();
    }

    // Spec 024: an undocumented request gets 404 before the security chain.
    @Test
    void undocumentedPathDoesNotReachTheBackend() {
        client().get().uri("/admin/secrets").exchange().expectStatus().isNotFound();

        assertThat(backend.requests()).isEmpty();
    }

    // SEC-59-1: a backend that ignores case can serve /users/me for /users/ME, so /users/{id} must not match.
    @ParameterizedTest
    @ValueSource(strings = {"/users/ME", "/users/Me"})
    void caseVariantOfALiteralPathDoesNotReachTheBackend(String path) {
        client().get().uri(path).exchange().expectStatus().isNotFound();

        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void undocumentedMethodDoesNotReachTheBackend() {
        client().post().uri("/users/7")
                .cookie("__Host-XSRF-TOKEN", "token").header("X-XSRF-TOKEN", "token")
                .exchange().expectStatus().isNotFound();

        assertThat(backend.requests()).isEmpty();
    }

    // The backend answers 200 after 1 second, and the global response timeout is 10 seconds. So a
    // 504 proves that the route timeout of 500 ms is used. No time bound: it only adds CI timing risk.
    @Test
    void operationResponseTimeoutIsUsed() {
        client().get().uri("/orders-api/orders/slow").exchange().expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    // Each path matches the route /orders/{id} as one segment. Without the firewall, the backend
    // receives the raw path and can read it as another path.
    @ParameterizedTest
    @ValueSource(strings = {
        "/orders-api/orders/..",
        "/orders-api/orders/.",
        "/orders-api/orders/%2e%2e",
        "/orders-api/orders/..%2F..%2Fadmin%2Fsecrets",
        "/orders-api/orders/a%2fb",
        "/orders-api/orders/a%5Cb",
        "/orders-api/orders/..;x",
        "/users/me;x=1",
        "/users/me%3Bx=1",
        "/users/m%65",
        "/users/%6De",
    })
    void ambiguousPathReturnsBadRequest(String rawPath) {
        client().get().uri(URI.create(Loopback.url(serverPort) + rawPath))
                .exchange().expectStatus().isBadRequest();

        assertThat(backend.requests()).isEmpty();
    }

    // java.net.URI cannot hold a raw backslash, so this test writes the request line itself.
    @Test
    void rawBackslashReturnsBadRequest() throws IOException {
        try (Socket socket = new Socket(Loopback.ADDRESS, serverPort)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(
                    "GET /orders-api/orders/a\\b HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            String statusLine = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();

            assertThat(statusLine).startsWith("HTTP/1.1 400");
        }
        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void dotInsideSegmentIsForwarded() {
        client().get().uri("/orders-api/orders/report.v2").exchange().expectStatus().isOk();

        assertThat(backend.requests()).containsExactly("GET /v1/orders/report.v2");
    }

    @Test
    void routeDefinitionsAreCachedAfterStartup() {
        assertThat(locator.getRouteDefinitions().collectList().block())
                .extracting(RouteDefinition::getId)
                .containsExactly(
                        "orders:/orders/export",
                        "orders:/orders/slow",
                        "orders:/orders/{id}",
                        "users:/users/me",
                        "users:/users/{id}");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl(Loopback.url(serverPort))
                .responseTimeout(Duration.ofSeconds(5)).build();
    }

    private static Path writeSpecs(int port) {
        try {
            Path directory = Files.createTempDirectory("openapi-routing-it");
            Files.writeString(directory.resolve("orders.yaml"), """
                    openapi: 3.0.3
                    info: { title: Orders, version: 1.0.0 }
                    x-gateway:
                      upstream: http://127.0.0.1:%d
                      prefix: /orders-api
                      upstream-base-path: /v1
                    paths:
                      /orders/{id}:
                        parameters:
                          - { name: id, in: path, required: true, schema: { type: string } }
                        get:
                          security: []
                          responses: { "200": { description: OK } }
                        delete:
                          security: []
                          responses: { "200": { description: OK } }
                      /orders/export:
                        get:
                          security: []
                          responses: { "200": { description: OK } }
                      /orders/slow:
                        get:
                          security: []
                          x-gateway-response-timeout: 500
                          responses: { "200": { description: OK } }
                    """.formatted(port));
            Files.writeString(directory.resolve("users.yaml"), """
                    openapi: 3.0.3
                    info: { title: Users, version: 1.0.0 }
                    x-gateway:
                      upstream: http://127.0.0.1:%d
                    paths:
                      /users/{id}:
                        get:
                          security: []
                          parameters:
                            - { name: id, in: path, required: true, schema: { type: string } }
                          responses: { "200": { description: OK } }
                      /users/me:
                        get:
                          security: []
                          x-gateway-response-timeout: 500
                          responses: { "200": { description: OK } }
                    """.formatted(port));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
