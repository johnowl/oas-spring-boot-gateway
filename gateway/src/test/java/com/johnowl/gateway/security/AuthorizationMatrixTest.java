package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import com.johnowl.gateway.routing.AudienceAuthorizationActive;
import com.johnowl.gateway.routing.RecordingMockBackend;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The decision table of spec 024 through the full security chain: a mock OIDC user, and a stub permission
 * resolver that gives chosen {@code aud} values and permissions, or token errors of spec 023.
 */
@SpringBootTest(properties = "logging.level.com.johnowl.gateway.security.AuthorizationDenials=DEBUG")
@Import(StubPermissions.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthorizationMatrixTest {

    private static final String ORDERS = SessionTestSpecs.AUDIENCE;

    private static final String PAYMENTS = SessionTestSpecs.PAYMENTS_AUDIENCE;

    private static final List<String> PERMISSION_NAMES =
            List.of("Orders.", "Reports.", "Refunds.", "Api.", "insufficient_scope");

    private static RecordingMockBackend backend;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private StubTokenPermissionsResolver permissions;

    @Autowired
    private MeterRegistry meters;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) throws IOException {
        backend = new RecordingMockBackend();
        Path directory = Files.createTempDirectory("matrix-specs");
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
                  /orders/{id}:
                    get:
                      parameters:
                        - { name: id, in: path, required: true, schema: { type: string } }
                      x-gateway-authorization: { all-of: [Orders.Read, Orders.Export] }
                      responses: { "200": { description: OK } }
                  /reports:
                    get:
                      x-gateway-authorization: { any-of: [Reports.Read, Reports.Admin] }
                      responses: { "200": { description: OK } }
                  /refunds:
                    get:
                      x-gateway-authorization: { all-of: [Orders.Read], any-of: [Refunds.Approve, Refunds.Admin] }
                      responses: { "200": { description: OK } }
                  /profile:
                    get:
                      x-gateway-authorization: { authenticated-only: true }
                      responses: { "200": { description: OK } }
                  /admin:
                    get:
                      x-gateway-authorization: { all-of: [Api.Admin] }
                      responses: { "200": { description: OK } }
                """.formatted(backend.port(), ORDERS));
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
                  /admin:
                    get:
                      x-gateway-authorization: { all-of: [Api.Admin] }
                      responses: { "200": { description: OK } }
                """.formatted(backend.port(), PAYMENTS));
        registry.add("gateway.openapi.specs", () -> "file:" + directory + "/*.yaml");
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @BeforeEach
    void reset() {
        backend.clear();
        permissions.reset();
    }

    @Test
    void theApplicationHasExactlyTheProductionMarker() {
        Map<String, AudienceAuthorizationActive> markers = context.getBeansOfType(AudienceAuthorizationActive.class);

        assertThat(markers.values()).singleElement().isInstanceOf(GatewayAudienceAuthorization.class);
        assertThat(GatewayAudienceAuthorization.class.getProtectionDomain().getCodeSource().getLocation().getPath())
                .endsWith("/target/classes/");
    }

    @Test
    void publicOperationWithoutSessionReachesTheBackend() {
        client().get().uri("/orders-api/health").exchange().expectStatus().isOk();

        assertThat(backend.requests()).containsExactly("GET /health");
    }

    @Test
    void protectedOperationWithoutSessionGoesToTheEntryPoint() {
        client().get().uri("/orders-api/profile").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isFound()
                .expectHeader().location(SessionAuthenticationEntryPoint.LOGIN_PATH);
        client().get().uri("/orders-api/profile").accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class).isEqualTo(SessionAuthenticationEntryPointTest.UNAUTHORIZED_BODY);
        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void allOfNeedsEveryPermission() {
        permissions.permissions(ORDERS, "Orders.Read");
        assertForbidden(fetch("/orders-api/orders/42"));

        permissions.permissions(ORDERS, "Orders.Read", "Orders.Export");
        assertThat(fetch("/orders-api/orders/42").getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(backend.requests()).containsExactly("GET /orders/42");
    }

    @Test
    void anyOfNeedsOnePermission() {
        permissions.permissions(ORDERS, "Orders.Read");
        assertForbidden(fetch("/orders-api/reports"));

        permissions.permissions(ORDERS, "Reports.Read");
        assertThat(fetch("/orders-api/reports").getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void allOfAndAnyOfGrantOnlyWhenBothHold() {
        permissions.permissions(ORDERS, "Orders.Read");
        assertForbidden(fetch("/orders-api/refunds"));
        permissions.permissions(ORDERS, "Refunds.Admin");
        assertForbidden(fetch("/orders-api/refunds"));

        permissions.permissions(ORDERS, "Orders.Read", "Refunds.Admin");
        assertThat(fetch("/orders-api/refunds").getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void authenticatedOnlyGrantsAnyAuthenticatedUser() {
        permissions.permissions(ORDERS);

        assertThat(fetch("/orders-api/profile").getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rolesInTheIdTokenHaveNoEffect() {
        permissions.permissions(ORDERS);

        EntityExchangeResult<String> result = client()
                .mutateWith(mockOidcLogin().idToken(token -> token.claim("roles", List.of("Api.Admin", "Orders.Read"))))
                .get().uri("/orders-api/admin").accept(MediaType.APPLICATION_JSON).exchange()
                .expectBody(String.class).returnResult();

        assertForbidden(result);
    }

    // Both backends have a permission named Api.Admin. A token of orders-api does not pass a payments-api route.
    @Test
    void aPermissionOfAnotherAudienceDoesNotPass() {
        permissions.permissions(ORDERS, "Api.Admin");
        permissions.token(PAYMENTS, ORDERS, "Api.Admin");
        assertThat(fetch("/orders-api/admin").getStatus()).isEqualTo(HttpStatus.OK);

        assertInternalError(fetch("/payments-api/admin"));
        assertThat(backend.requests()).containsExactly("GET /admin");
    }

    // Spec 025: the backend receives the token that the manager checked, never the client value.
    @Test
    void aGrantRelaysTheCheckedTokenOfTheRouteAudience() {
        permissions.permissions(ORDERS, "Api.Admin");
        permissions.permissions(PAYMENTS, "Api.Admin");

        for (String path : new String[] {"/orders-api/admin", "/payments-api/admin"}) {
            assertThat(authenticated().get().uri(path).accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer forged").exchange()
                    .expectBody(String.class).returnResult().getStatus()).isEqualTo(HttpStatus.OK);
        }

        assertThat(backend.headers()).extracting(headers -> headers.get(HttpHeaders.AUTHORIZATION)).containsExactly(
                List.of("Bearer " + StubTokenPermissionsResolver.tokenValue(ORDERS)),
                List.of("Bearer " + StubTokenPermissionsResolver.tokenValue(PAYMENTS)));
    }

    // Spec 025, "no token at relay": 500 and not 401, so the single-page application does not loop.
    @Test
    void aGrantWithoutATokenAtTheRelayGives500AndNoBackendRequest(CapturedOutput output) {
        permissions.permissionsWithoutRelayToken(ORDERS, "Api.Admin");
        int start = output.getOut().length();

        assertInternalError(fetch("/orders-api/admin"));

        assertThat(backend.requests()).isEmpty();
        // QA-62-3: all ERROR lines of the request, not only the expected one. The denial log of spec 024 and the
        // error handler of WebFlux write no other ERROR line.
        assertThat(output.getOut().substring(start).lines().filter(line -> line.contains(" ERROR ")))
                .singleElement().asString()
                .contains("no access token of the audience " + ORDERS, "the route orders:/admin", "the user user");
    }

    @Test
    void aWrongAudienceGives500AndNever401() {
        double before = mismatches();
        permissions.token(ORDERS, "other-client-id", "Orders.Read", "Orders.Export");

        for (MediaType accept : new MediaType[] {MediaType.APPLICATION_JSON, MediaType.TEXT_HTML}) {
            EntityExchangeResult<String> result = authenticated().get().uri("/orders-api/orders/42").accept(accept)
                    .exchange().expectBody(String.class).returnResult();
            assertInternalError(result);
        }
        assertThat(mismatches()).isEqualTo(before + 2);
        assertThat(backend.requests()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = DenyReason.class, names = {"SESSION_INVALID", "REAUTHENTICATION_REQUIRED"})
    void anEndedSessionGoesToTheEntryPoint(DenyReason reason) {
        permissions.fails(ORDERS, reason);

        EntityExchangeResult<String> fetch = fetch("/orders-api/profile");
        assertThat(fetch.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(fetch.getResponseBody()).isEqualTo(SessionAuthenticationEntryPointTest.UNAUTHORIZED_BODY);
        authenticated().get().uri("/orders-api/profile").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isFound()
                .expectHeader().location(SessionAuthenticationEntryPoint.LOGIN_PATH);
    }

    @ParameterizedTest
    @EnumSource(value = DenyReason.class, names = {"CONSENT_REQUIRED", "TOKEN_REQUEST_REJECTED", "TOKEN_GRANT_LIMIT",
        "TOKEN_ENDPOINT_TIMEOUT", "TOKEN_ENDPOINT_ERROR", "TOKEN_ENDPOINT_THROTTLED", "INVALID_AUDIENCE_TOKEN"})
    void aTokenErrorGetsItsStatusAndProblemBody(DenyReason reason) {
        double before = denied(reason);
        permissions.fails(ORDERS, reason);

        EntityExchangeResult<String> result = fetch("/orders-api/profile");

        int status = switch (reason) {
            case CONSENT_REQUIRED -> 403;
            case TOKEN_GRANT_LIMIT -> 429;
            case TOKEN_ENDPOINT_TIMEOUT -> 504;
            case TOKEN_ENDPOINT_ERROR -> 502;
            case TOKEN_ENDPOINT_THROTTLED -> 503;
            default -> 500;
        };
        // QA-60-4: the whole body, not only a part of it.
        String body = switch (reason) {
            case CONSENT_REQUIRED -> GatewayAccessDeniedHandlerTest.CONSENT_BODY;
            case TOKEN_GRANT_LIMIT ->
                    "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Too many requests.\"}";
            case TOKEN_ENDPOINT_TIMEOUT ->
                    "{\"type\":\"about:blank\",\"title\":\"Gateway Timeout\",\"status\":504,\"detail\":\"Gateway timeout.\"}";
            case TOKEN_ENDPOINT_ERROR ->
                    "{\"type\":\"about:blank\",\"title\":\"Bad Gateway\",\"status\":502,\"detail\":\"Bad gateway.\"}";
            case TOKEN_ENDPOINT_THROTTLED ->
                    "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\"Try again later.\"}";
            default -> GatewayAccessDeniedHandlerTest.INTERNAL_ERROR_BODY;
        };
        assertThat(result.getStatus().value()).isEqualTo(status);
        assertThat(result.getResponseBody()).isEqualTo(body);
        assertProblem(result);
        assertThat(denied(reason)).isEqualTo(before + 1);
        assertThat(backend.requests()).isEmpty();
    }

    // SEC-60-2, QA-60-1: another error of the resolver gives the generic 500, for a fetch and for an HTML navigation.
    @Test
    void anotherErrorOfTheResolverGives500(CapturedOutput output) {
        double before = denied(DenyReason.INTERNAL_ERROR);
        permissions.breaks(ORDERS, new IllegalStateException("defect"));

        EntityExchangeResult<String> fetch = fetch("/orders-api/profile");
        EntityExchangeResult<String> navigation = authenticated().get().uri("/orders-api/profile")
                .accept(MediaType.TEXT_HTML).exchange().expectBody(String.class).returnResult();

        for (EntityExchangeResult<String> result : List.of(fetch, navigation)) {
            assertInternalError(result);
        }
        assertThat(denied(DenyReason.INTERNAL_ERROR)).isEqualTo(before + 2);
        assertThat(output.getOut().lines().filter(line -> line.contains("reason=INTERNAL_ERROR")).toList())
                .hasSize(2)
                .allSatisfy(line -> assertThat(line).contains(" ERROR ", "exception=java.lang.IllegalStateException")
                        .doesNotContain("defect"));
        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void theDenialLogHasTheRawPath(CapturedOutput output) {
        client().get().uri(URI.create("/orders-api/%0Aorders")).exchange().expectStatus().isNotFound();

        assertThat(output).contains("Access denied: reason=NO_MATCH method=GET path=/orders-api/%0Aorders principal=-");
    }

    @Test
    void aMissingPermissionWritesOneInfoLineWithoutToken(CapturedOutput output) {
        permissions.permissions(ORDERS, "Orders.Read");
        int before = lines(output, "reason=MISSING_PERMISSION");

        assertForbidden(fetch("/orders-api/orders/7"));

        assertThat(lines(output, "reason=MISSING_PERMISSION")).isEqualTo(before + 1);
        assertThat(output.getOut().lines().filter(line -> line.contains("reason=MISSING_PERMISSION")).toList().getLast())
                .contains(" INFO ").contains("path=/orders-api/orders/7").contains("route=");
    }

    private static int lines(CapturedOutput output, String text) {
        return (int) output.getOut().lines().filter(line -> line.contains(text)).count();
    }

    private double denied(DenyReason reason) {
        var counter = meters.find(AuthorizationDenials.DENIED_METRIC).tag("reason", reason.name()).counter();
        return counter == null ? 0 : counter.count();
    }

    /** The mismatches of the route of {@code GET /orders-api/orders/{id}}. */
    private double mismatches() {
        String route = context.getBean(SecurityIndex.class).find(MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders-api/orders/42"))).orElseThrow().routeId();
        var counter = meters.find(AuthorizationDenials.AUDIENCE_MISMATCH_METRIC).tag("route", route).counter();
        return counter == null ? 0 : counter.count();
    }

    private static void assertForbidden(EntityExchangeResult<String> result) {
        assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.getResponseBody()).isEqualTo(GatewayAccessDeniedHandlerTest.FORBIDDEN_BODY);
        assertProblem(result);
    }

    private static void assertInternalError(EntityExchangeResult<String> result) {
        assertThat(result.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getResponseBody()).isEqualTo(GatewayAccessDeniedHandlerTest.INTERNAL_ERROR_BODY);
        assertProblem(result);
    }

    private static void assertProblem(EntityExchangeResult<String> result) {
        assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getResponseHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(result.getResponseHeaders().getLocation()).isNull();
        assertThat(result.getResponseBody()).contains("\"detail\":\"");
        for (String name : PERMISSION_NAMES) {
            assertThat(result.getResponseBody()).doesNotContain(name);
        }
    }

    private EntityExchangeResult<String> fetch(String path) {
        return authenticated().get().uri(path).accept(MediaType.APPLICATION_JSON).exchange()
                .expectBody(String.class).returnResult();
    }

    private WebTestClient authenticated() {
        return client().mutateWith(mockOidcLogin());
    }

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
    }
}
