package com.johnowl.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("upstream-test")
@Import(OpenTestRoutesConfiguration.class)
class UpstreamTimeoutIT {

    private static DelayingMockBackend backend;

    @LocalServerPort
    private int serverPort;

    @DynamicPropertySource
    static void mockBackendPort(DynamicPropertyRegistry registry) {
        backend = DelayingMockBackend.start();
        registry.add("mock.backend.port", backend::port);
    }

    @AfterAll
    static void stopMockBackend() {
        backend.close();
    }

    @Test
    void globalResponseTimeoutReturnsGatewayTimeout() {
        client().get().uri("/global/x?delay=1500")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void routeResponseTimeoutLowerThanGlobalIsUsed() {
        long start = System.nanoTime();

        client().get().uri("/short/x?delay=1000")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT);

        // The global timeout is 1 second. The route timeout is 500 ms.
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(900));
    }

    @Test
    void routeResponseTimeoutHigherThanGlobalIsUsed() {
        client().get().uri("/long/x?delay=1000")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ok");
    }

    @Test
    void connectTimeoutReturnsInternalServerError() {
        long start = System.nanoTime();

        client().get().uri("/unreachable/x")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // The connect timeout (300 ms) ends the request, not the response timeout (1 s).
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(900));
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl(Loopback.url(serverPort))
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }
}
