package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.RecordingMockBackend;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The forwarding headers with a local public base URL (spec 025): {@code http} and an explicit port. The
 * forwarding headers do not depend on the session, so the test uses a public operation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.public-base-url=http://localhost:8080")
@ActiveProfiles("local")
class LocalForwardingHeadersIT {

    private static RecordingMockBackend backend;

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
    void forwardingHeadersComeFromTheLocalPublicBaseUrl() throws IOException {
        String response = HeaderHygieneIT.send(port, HeaderHygieneIT.PUBLIC + "?site-headers=1", List.of(
                "Host: other.example.com", "X-Forwarded-Host: evil.example.com", "X-Forwarded-Proto: https"));

        assertThat(response).startsWith("HTTP/1.1 200");
        // SEC-61-1: no Strict-Transport-Security for an http public base URL, and none from the backend.
        assertThat(HeaderHygieneIT.values(response, "Strict-Transport-Security")).isEmpty();

        assertThat(HeaderHygieneIT.forwardingHeaders(backend.headers().getFirst())).containsExactly(
                "X-Forwarded-For: 127.0.0.1", "X-Forwarded-Host: localhost:8080", "X-Forwarded-Port: 8080",
                "X-Forwarded-Proto: http");
    }
}
