package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.RecordingMockBackend;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * A token endpoint that refuses the connection (spec 023, "Token endpoint errors"). The test context has its
 * own mock identity provider. The test stops it after the login, so the refresh token grant finds no server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AudienceTokenRefusedConnectionIT {

    private static final MockIdentityProvider IDP = new MockIdentityProvider();

    private static RecordingMockBackend backend;

    @LocalServerPort
    private int port;

    @Autowired
    private MemorySessionRepository sessions;

    @Autowired
    private SessionRedemptions redemptions;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.register(registry, backend.port());
        registry.add("spring.security.oauth2.client.provider.entra.issuer-uri", IDP::issuer);
    }

    @AfterAll
    static void stop() {
        backend.close();
        IDP.close();
    }

    @Test
    void aRefusedConnectionGives502AndKeepsTheSession() {
        TestBrowser browser = new TestBrowser(port);
        browser.login(IDP);
        backend.clear();
        IDP.close();
        assertThat(refusesConnections(URI.create(IDP.tokenEndpoint()))).as("the token endpoint refuses connections").isTrue();

        EntityExchangeResult<byte[]> result = browser.fetch(HttpMethod.GET, "/orders-api/orders/42");

        assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"type\":\"about:blank\",\"title\":\"Bad Gateway\",\"status\":502,\"detail\":\"Bad gateway.\"}");
        assertThat(backend.requests()).isEmpty();
        assertThat(sessions.contains(browser.cookie(TestBrowser.SESSION))).isTrue();
        assertThat(redemptions.running()).isZero();
    }

    private static boolean refusesConnections(URI endpoint) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.getHost(), endpoint.getPort()), 1_000);
            return false;
        } catch (IOException refused) {
            return true;
        }
    }
}
