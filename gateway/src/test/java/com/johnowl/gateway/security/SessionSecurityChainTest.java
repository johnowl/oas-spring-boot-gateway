package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import com.johnowl.gateway.routing.RecordingMockBackend;
import com.johnowl.gateway.routing.RequestPathFirewall;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.server.WebFilter;
import org.springframework.web.util.UriComponentsBuilder;

/** The session security chain with a mock-bound client and a mock OIDC user (spec 022). */
@SpringBootTest
@Import(StubPermissions.class)
class SessionSecurityChainTest {

    private static final String FORBIDDEN_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Access denied.\"}";

    private static RecordingMockBackend backend;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private StubTokenPermissionsResolver permissions;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.register(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @BeforeEach
    void clearBackend() {
        backend.clear();
        permissions.reset();
        permissions.permissions(SessionTestSpecs.AUDIENCE, "Orders.Read", "Orders.Write");
    }

    @Test
    void publicOperationReachesTheBackendWithoutSession() {
        client().get().uri("/orders-api/health").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isOk()
                .expectCookie().doesNotExist("__Host-SESSION");

        assertThat(backend.requests()).containsExactly("GET /health");
    }

    @Test
    void fetchWithoutSessionGets401WithoutSession() {
        client().get().uri("/orders-api/orders/42").accept(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().doesNotExist(HttpHeaders.LOCATION)
                .expectCookie().doesNotExist("__Host-SESSION")
                .expectBody(String.class).isEqualTo(SessionAuthenticationEntryPointTest.UNAUTHORIZED_BODY);

        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void htmlNavigationWithoutSessionRedirectsToTheLogin() {
        client().get().uri("/orders-api/orders/42").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isFound()
                .expectHeader().location("/oauth2/authorization/entra")
                .expectCookie().exists("__Host-SESSION");
    }

    @Test
    void longHtmlNavigationIsNotSavedAndStartsNoSession() {
        client().get().uri("/orders-api/orders/42?q=" + "x".repeat(1100)).accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isFound()
                .expectHeader().location("/oauth2/authorization/entra")
                .expectCookie().doesNotExist("__Host-SESSION");
    }

    @Test
    void authenticatedUserReachesAProtectedOperation() {
        client().mutateWith(mockOidcLogin()).get().uri("/orders-api/orders/42").exchange()
                .expectStatus().isOk();

        assertThat(backend.requests()).containsExactly("GET /orders/42");
    }

    @Test
    void postNeedsTheCsrfTokenInTheHeader() {
        String token = csrfCookie().getValue();

        authenticated().post().uri("/orders-api/orders").exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).isEqualTo(FORBIDDEN_BODY);
        authenticated().post().uri("/orders-api/orders")
                .cookie("__Host-XSRF-TOKEN", token).header("X-XSRF-TOKEN", "wrong").exchange()
                .expectStatus().isForbidden()
                .expectBody(String.class).isEqualTo(FORBIDDEN_BODY);
        authenticated().post().uri("/orders-api/orders")
                .cookie("__Host-XSRF-TOKEN", token).header("X-XSRF-TOKEN", token).exchange()
                .expectStatus().isOk();

        assertThat(backend.requests()).containsExactly("POST /orders");
    }

    @Test
    void csrfTokenInAFormFieldOrQueryDoesNotCount() {
        String token = csrfCookie().getValue();

        authenticated().post().uri("/orders-api/orders?_csrf=" + token)
                .cookie("__Host-XSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("_csrf", token))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(String.class).isEqualTo(FORBIDDEN_BODY);

        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void csrfCookieIsAReadableHostCookie() {
        ResponseCookie cookie = csrfCookie();

        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
    }

    @Test
    void logoutRedirectsToEntraWithTwoParameters() {
        URI location = authenticated().mutateWith(csrf()).post().uri("/logout").exchange()
                .expectStatus().isFound()
                .returnResult(Void.class).getResponseHeaders().getLocation();

        assertThat(location.toString()).startsWith(MockIdentityProviderSession.provider().logoutEndpoint() + "?");
        var query = UriComponentsBuilder.fromUri(location).build().getQueryParams();
        assertThat(query.keySet()).containsExactly("client_id", "post_logout_redirect_uri");
        assertThat(query.getFirst("client_id")).isEqualTo(MockIdentityProvider.CLIENT_ID);
        assertThat(query.getFirst("post_logout_redirect_uri")).isEqualTo("https%3A%2F%2Fgateway.example.com%2F");
    }

    @Test
    void logoutNeedsTheCsrfToken() {
        authenticated().post().uri("/logout").exchange()
                .expectStatus().isForbidden()
                .expectBody(String.class).isEqualTo(FORBIDDEN_BODY);
    }

    // Spring Security does not handle these paths, and no OAS operation documents them (spec 024, row 1).
    @ParameterizedTest
    @ValueSource(strings = {"/login", "/logout", "/oauth2/authorization/entra-" + SessionTestSpecs.AUDIENCE,
        "/oauth2/authorization/unknown", "/admin/secrets", "/actuator/health"})
    void pathsWithoutAccessRuleGet404WithAndWithoutSession(String path) {
        for (WebTestClient client : new WebTestClient[] {client(), authenticated()}) {
            for (MediaType accept : new MediaType[] {MediaType.TEXT_HTML, MediaType.APPLICATION_JSON}) {
                client.get().uri(path).accept(accept).exchange()
                        .expectStatus().isNotFound()
                        .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .expectHeader().doesNotExist(HttpHeaders.LOCATION)
                        .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                        .expectCookie().doesNotExist("__Host-SESSION")
                        .expectBody(String.class).isEqualTo(SessionAuthenticationEntryPointTest.NOT_FOUND_BODY);
            }
        }
        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void undocumentedMethodGets404AndNot405() {
        for (WebTestClient client : new WebTestClient[] {client(), authenticated().mutateWith(csrf())}) {
            client.put().uri("/orders-api/orders/42").exchange()
                    .expectStatus().isNotFound()
                    .expectBody(String.class).isEqualTo(SessionAuthenticationEntryPointTest.NOT_FOUND_BODY);
            client.delete().uri("/orders-api/health").exchange()
                    .expectStatus().isNotFound();
        }
        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void corsPreflightGets404() {
        client().options().uri("/orders-api/orders/42")
                .header(HttpHeaders.ORIGIN, "https://spa.example.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .expectBody(String.class).isEqualTo(SessionAuthenticationEntryPointTest.NOT_FOUND_BODY);
    }

    @Test
    void endpointsOfTheSessionChainAreNotAnsweredByTheFilter() {
        client().get().uri("/oauth2/authorization/entra").exchange()
                .expectStatus().isFound();
        client().get().uri("/login/oauth2/code/entra?code=x&state=y").exchange()
                .expectStatus().isFound()
                .expectHeader().location("https://gateway.example.com/?login=failed");
        authenticated().mutateWith(csrf()).post().uri("/logout").exchange()
                .expectStatus().isFound();
    }

    // QA-59-1: another method or another registration id on a path of the session chain gets 404, not 403 or 302.
    @ParameterizedTest
    @ValueSource(strings = {"PUT /logout", "DELETE /logout", "POST /oauth2/authorization/unknown",
        "POST /oauth2/authorization/entra", "POST /login/oauth2/code/entra", "GET /login/oauth2/code/unknown"})
    void otherRequestsToTheSessionChainPathsGet404(String request) {
        String[] methodAndPath = request.split(" ");
        for (WebTestClient client : new WebTestClient[] {client(), authenticated()}) {
            client.method(HttpMethod.valueOf(methodAndPath[0])).uri(methodAndPath[1] + "?code=x&state=y")
                    .accept(MediaType.TEXT_HTML).exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectHeader().doesNotExist(HttpHeaders.LOCATION)
                    .expectCookie().doesNotExist("__Host-SESSION")
                    .expectBody(String.class).isEqualTo(SessionAuthenticationEntryPointTest.NOT_FOUND_BODY);
        }
        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void undocumentedOperationFilterRunsAfterTheFirewallAndBeforeSpringSecurity() {
        List<WebFilter> filters = context.getBeanProvider(WebFilter.class).orderedStream().toList();
        int firewall = indexOf(filters, RequestPathFirewall.class);
        int undocumented = indexOf(filters, UndocumentedOperationWebFilter.class);
        int security = indexOf(filters, WebFilterChainProxy.class);

        assertThat(firewall).isNotNegative().isLessThan(undocumented);
        assertThat(undocumented).isLessThan(security);
    }

    private static int indexOf(List<WebFilter> filters, Class<?> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Gets a CSRF cookie from a public operation, as the single-page application does. */
    private ResponseCookie csrfCookie() {
        ResponseCookie cookie = client().get().uri("/orders-api/health").exchange()
                .expectStatus().isOk()
                .returnResult(Void.class).getResponseCookies().getFirst("__Host-XSRF-TOKEN");
        backend.clear();
        return cookie;
    }

    private WebTestClient authenticated() {
        return client().mutateWith(mockOidcLogin());
    }

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
    }
}
