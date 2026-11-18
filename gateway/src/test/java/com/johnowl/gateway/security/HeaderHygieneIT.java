package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.RecordingMockBackend;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The header hygiene of spec 025 on a real port: the test sends raw HTTP with forged headers, with the
 * session of a real login against the mock identity provider, and a mock backend records the headers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "gateway.security.remove-request-headers.names=X-Tenant-Id,X-Other-Name",
    "gateway.security.remove-request-headers.prefixes=X-Internal-"})
class HeaderHygieneIT {

    static final String PROTECTED = "/orders-api/orders/42";

    static final String PUBLIC = "/orders-api/health";

    /** Each built-in name, and a header for each built-in prefix, in mixed letter case. */
    private static final List<String> BUILT_IN = List.of(
            "x-forwarded-user: alice", "X-REMOTE-USER: alice", "Remote-User: alice",
            "X-HTTP-Method-Override: DELETE", "x-http-method: DELETE", "X-Method-Override: DELETE",
            "X-Original-URL: /admin", "X-Rewrite-URL: /admin", "X-Original-Host: admin.example.com",
            "X-Real-IP: 203.0.113.9", "X-Client-IP: 203.0.113.9", "True-Client-IP: 203.0.113.9",
            "X-XSRF-TOKEN: forged-csrf",
            "X-User-Id: 42", "x-authenticated-user: alice", "X-MS-CLIENT-PRINCIPAL-NAME: alice",
            "X-MS-TOKEN-AAD-ACCESS-TOKEN: eyJforged", "X-Forwarded-Prefix: /evil",
            "X-User: alice", "X-UserId: 42", "x-username: alice", "X-Principal: alice", "X-Roles: admin",
            "X-Groups: admins", "X-Remote-Group: admins", "X-Webauth-User: alice", "X-Id-Token: eyJforged",
            "X-Access-Token: eyJforged", "X-Auth-Token: forged", "X-Api-Key: forged", "X-ARR-ClientCert: MIIforged",
            "X-Client-Cert: MIIforged", "Ssl-Client-Cert: MIIforged", "X-WAWS-Unencoded-URL: /admin",
            "X-Envoy-Original-Path: /admin", "X-Override-URL: /admin", "X-Host: admin.example.com",
            "X-HTTP-Host-Override: admin.example.com", "CF-Connecting-IP: 203.0.113.9",
            "X-Cluster-Client-IP: 203.0.113.9", "Client-IP: 203.0.113.9", "X-Auth-Request-Email: alice@example.com",
            "X-Amzn-Oidc-Identity: alice", "X-Goog-Authenticated-User-Email: alice@example.com");

    private static final List<String> CONFIGURED = List.of("x-tenant-id: 7", "X-INTERNAL-ROLE: admin");

    private static final List<String> UNDERSCORE = List.of("X_User_Id: 42", "x_custom: 1", "Accept_Language: de");

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @LocalServerPort
    private int port;

    private TestBrowser browser;

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
    void login() {
        browser = new TestBrowser(port);
        browser.login(idp);
        backend.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {PROTECTED, PUBLIC})
    void backendReceivesNoCookieAndNoSpoofableHeader(String path) throws IOException {
        List<String> headers = new ArrayList<>();
        headers.add("Cookie: " + TestBrowser.SESSION + "=" + browser.cookie(TestBrowser.SESSION)
                + "; " + TestBrowser.XSRF + "=" + browser.cookie(TestBrowser.XSRF) + "; other=1");
        headers.add("Cookie: second=2");
        headers.addAll(BUILT_IN);
        headers.addAll(CONFIGURED);
        headers.addAll(UNDERSCORE);
        headers.add("Content-Type: application/json; charset=utf-8");
        headers.add("X-Request-Id: r-1");

        assertThat(send(port, path, headers)).startsWith("HTTP/1.1 200");

        HttpHeaders received = backend.headers().getFirst();
        assertThat(received.containsHeader(HttpHeaders.COOKIE)).isFalse();
        Stream.of(BUILT_IN, CONFIGURED, UNDERSCORE).flatMap(List::stream).map(HeaderHygieneIT::name)
                .forEach(name -> assertThat(received.containsHeader(name)).as(name).isFalse());
        assertThat(received.get("Accept")).containsExactly("application/json");
        assertThat(received.get("Content-Type")).containsExactly("application/json; charset=utf-8");
        assertThat(received.get("X-Request-Id")).containsExactly("r-1");
    }

    // QA-62-1: Spring Cloud Gateway would remove each header that the Connection header of the client names.
    @ParameterizedTest
    @ValueSource(strings = {PROTECTED, PUBLIC})
    void theConnectionHeaderOfTheClientRemovesNoHeader(String path) throws IOException {
        assertThat(send(port, path, List.of(session(), "Connection: X-Request-Id, X-Forwarded-For",
                "X-Request-Id: r-1"))).startsWith("HTTP/1.1 200");

        HttpHeaders received = backend.headers().getFirst();
        assertThat(received.get("X-Request-Id")).containsExactly("r-1");
        assertThat(received.get("X-Forwarded-For")).containsExactly("127.0.0.1");
        assertThat(received.getConnection()).doesNotContain("X-Request-Id", "X-Forwarded-For");
    }

    @Test
    void backendReceivesThePeerAddressAndNoForwardedHeader() throws IOException {
        assertThat(send(port, PROTECTED, List.of(session(),
                "Forwarded: for=203.0.113.9;host=evil.example.com;proto=http",
                "X-Forwarded-For: 203.0.113.9"))).startsWith("HTTP/1.1 200");

        HttpHeaders received = backend.headers().getFirst();
        assertThat(received.containsHeader("Forwarded")).isFalse();
        assertThat(received.get("X-Forwarded-For")).containsExactly("127.0.0.1");
    }

    @Test
    void forwardingHeadersComeFromThePublicBaseUrl() throws IOException {
        assertThat(send(port, PROTECTED, List.of("Host: other.example.com", session(),
                "X-Forwarded-Host: evil.example.com", "X-Forwarded-Proto: http", "X-Forwarded-Port: 80",
                "X-Forwarded-Prefix: /evil"))).startsWith("HTTP/1.1 200");

        assertThat(forwardingHeaders(backend.headers().getFirst())).containsExactly(
                "X-Forwarded-For: 127.0.0.1", "X-Forwarded-Host: gateway.example.com", "X-Forwarded-Port: 443",
                "X-Forwarded-Proto: https");
    }

    // A request without the CSRF cookie: the gateway writes its __Host-XSRF-TOKEN cookie (spec 022).
    @ParameterizedTest
    @ValueSource(strings = {PUBLIC, PROTECTED})
    void responseHasNoCookieAndNoCorsHeaderOfTheBackend(String path) throws IOException {
        List<String> headers = path.equals(PUBLIC) ? List.of()
                : List.of("Cookie: " + TestBrowser.SESSION + "=" + browser.cookie(TestBrowser.SESSION));

        String response = send(port, path + "?set-cookie=1&cors=1", headers);

        assertThat(response).startsWith("HTTP/1.1 200");
        List<String> names = response.lines().skip(1).filter(line -> line.contains(":"))
                .map(line -> line.substring(0, line.indexOf(':')).toLowerCase(Locale.ROOT)).toList();
        assertThat(names).doesNotContain("set-cookie2", "clear-site-data")
                .noneMatch(name -> name.startsWith("access-control-"));
        assertThat(response.lines().filter(line -> line.regionMatches(true, 0, "Set-Cookie:", 0, 11)))
                .singleElement().asString().contains(TestBrowser.XSRF + "=").doesNotContain(RecordingMockBackend.BACKEND_COOKIE);
        assertThat(RawHttp.header(response, "Cache-Control")).isEqualTo("no-store");
        assertThat(RawHttp.header(response, "Content-Type")).isEqualTo("text/plain;charset=UTF-8");
    }

    // SEC-61-1, SEC-61-2, SEC-61-3: the backend cannot set browser policies of the gateway site, a cache
    // policy for an authenticated response, or a sign-in challenge.
    @Test
    void backendCannotSetSitePoliciesOrACachePolicyForAnAuthenticatedResponse() throws IOException {
        String response = send(port, PROTECTED + "?site-headers=1", List.of(session()));

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(values(response, "Strict-Transport-Security")).containsExactly("max-age=31536000");
        RecordingMockBackend.SITE_RESPONSE_HEADERS.keySet().stream()
                .filter(name -> !List.of("Strict-Transport-Security", "X-Frame-Options", "X-Content-Type-Options",
                        "Referrer-Policy", "Cache-Control").contains(name))
                .forEach(name -> assertThat(values(response, name)).as(name).isEmpty());
        assertThat(values(response, "X-Frame-Options")).containsExactly("DENY");
        assertThat(values(response, "X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(values(response, "Referrer-Policy")).containsExactly("no-referrer");
        assertThat(values(response, "Cache-Control")).containsExactly("no-store");
        assertThat(values(response, "Pragma")).containsExactly("no-cache");
    }

    @Test
    void aPublicResponseWithAGatewayCookieGetsNoStore() throws IOException {
        String response = send(port, PUBLIC + "?site-headers=1", List.of());

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(values(response, "Set-Cookie")).singleElement().asString().startsWith(TestBrowser.XSRF + "=");
        assertThat(values(response, "Cache-Control")).containsExactly("no-store");
        assertThat(values(response, "Pragma")).containsExactly("no-cache");
    }

    @Test
    void aPublicResponseWithoutSessionAndGatewayCookieKeepsTheCachePolicyOfTheBackend() throws IOException {
        String response = send(port, PUBLIC + "?site-headers=1",
                List.of("Cookie: " + TestBrowser.XSRF + "=" + browser.cookie(TestBrowser.XSRF)));

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(values(response, "Set-Cookie")).isEmpty();
        assertThat(values(response, "Cache-Control")).containsExactly("public, max-age=3600");
        assertThat(values(response, "Strict-Transport-Security")).containsExactly("max-age=31536000");
        assertThat(values(response, "WWW-Authenticate")).isEmpty();
    }

    @Test
    void aLocationOfTheUpstreamPointsAtThePublicUrl() throws IOException {
        String upstream = send(port, PROTECTED + "?location=upstream", List.of(session()));
        String other = send(port, PROTECTED + "?location=other", List.of(session()));
        String relative = send(port, PROTECTED + "?location=relative", List.of(session()));

        assertThat(values(upstream, "Location")).containsExactly("https://gateway.example.com/orders-api/orders/43?page=2");
        assertThat(other).startsWith("HTTP/1.1 200");
        assertThat(values(other, "Location")).isEmpty();
        assertThat(values(relative, "Location")).containsExactly(RecordingMockBackend.RELATIVE_LOCATION);
    }

    /** All values of a response header, in any letter case of the name. */
    static List<String> values(String response, String name) {
        return response.lines().skip(1)
                .filter(line -> line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                .map(line -> line.substring(name.length() + 1).trim())
                .toList();
    }

    private String session() {
        return "Cookie: " + TestBrowser.SESSION + "=" + browser.cookie(TestBrowser.SESSION);
    }

    /**
     * Sends {@code GET} with {@code Accept: application/json} and the headers. Without a {@code Host} in the
     * headers, the request has {@code Host: localhost:<port>}.
     */
    static String send(int port, String path, List<String> headers) throws IOException {
        StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\n");
        if (headers.stream().noneMatch(header -> header.regionMatches(true, 0, "Host:", 0, 5))) {
            request.append("Host: localhost:").append(port).append("\r\n");
        }
        request.append("Accept: application/json\r\n");
        headers.forEach(header -> request.append(header).append("\r\n"));
        return RawHttp.send(port, request.append("Connection: close\r\n\r\n").toString());
    }

    /** The {@code Forwarded} and {@code X-Forwarded-*} headers, as sorted {@code name: value} lines. */
    static List<String> forwardingHeaders(HttpHeaders received) {
        return received.headerSet().stream()
                .filter(header -> header.getKey().equalsIgnoreCase("Forwarded")
                        || header.getKey().toLowerCase(Locale.ROOT).startsWith("x-forwarded-"))
                .map(header -> header.getKey() + ": " + String.join(",", header.getValue()))
                .sorted()
                .toList();
    }

    private static String name(String header) {
        return header.substring(0, header.indexOf(':'));
    }
}
