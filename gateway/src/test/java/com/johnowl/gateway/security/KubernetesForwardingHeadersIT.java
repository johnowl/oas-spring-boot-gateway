package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.RecordingMockBackend;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Spring Boot turns on the forwarded header handling of Reactor Netty on Kubernetes unless
 * {@code server.forward-headers-strategy} is {@code none} (spec 025, QA-47-6). Forged forwarding headers
 * change neither the redirect URI of spec 022 nor the forwarding headers at the backend.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.cloud-platform=kubernetes")
class KubernetesForwardingHeadersIT {

    /**
     * With the native forwarded header handling of Reactor Netty, the connection peer would be
     * {@code 203.0.113.9} and the backend would receive it in {@code X-Forwarded-For} (QA-61-1).
     */
    private static final List<String> FORGED = List.of("X-Forwarded-Host: evil.example.com", "X-Forwarded-Proto: http",
            "X-Forwarded-For: 203.0.113.9", "Forwarded: for=203.0.113.9;host=evil.example.com;proto=http");

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.register(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @Test
    void forgedForwardingHeadersDoNotChangeTheRedirectUri() throws IOException {
        String plain = redirectUri(List.of());
        String forged = redirectUri(FORGED);

        assertThat(plain).isEqualTo("https://gateway.example.com/login/oauth2/code/entra");
        assertThat(forged).isEqualTo(plain);
    }

    @Test
    void forgedForwardingHeadersDoNotChangeTheHeadersAtTheBackend() throws IOException {
        TestBrowser browser = new TestBrowser(port);
        browser.login(idp);
        String session = "Cookie: " + TestBrowser.SESSION + "=" + browser.cookie(TestBrowser.SESSION);
        backend.clear();

        assertThat(HeaderHygieneIT.send(port, HeaderHygieneIT.PROTECTED, List.of(session))).startsWith("HTTP/1.1 200");
        List<String> forged = new ArrayList<>(FORGED);
        forged.add(session);
        assertThat(HeaderHygieneIT.send(port, HeaderHygieneIT.PROTECTED, forged)).startsWith("HTTP/1.1 200");

        assertThat(backend.headers()).hasSize(2);
        List<String> plain = HeaderHygieneIT.forwardingHeaders(backend.headers().get(0));
        assertThat(plain).containsExactly("X-Forwarded-For: 127.0.0.1", "X-Forwarded-Host: gateway.example.com",
                "X-Forwarded-Port: 443", "X-Forwarded-Proto: https");
        assertThat(HeaderHygieneIT.forwardingHeaders(backend.headers().get(1))).isEqualTo(plain);
        assertThat(backend.headers().get(1).get("X-Forwarded-For")).containsExactly("127.0.0.1");
    }

    private String redirectUri(List<String> headers) throws IOException {
        String response = HeaderHygieneIT.send(port, "/oauth2/authorization/entra", headers);
        assertThat(response).startsWith("HTTP/1.1 302");
        return TestBrowser.query(URI.create(RawHttp.header(response, "Location"))).getFirst("redirect_uri");
    }
}
