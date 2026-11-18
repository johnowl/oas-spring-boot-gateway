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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.gateway.server.webflux.httpclient.pool.max-connections=1",
            "spring.cloud.gateway.server.webflux.httpclient.pool.acquire-timeout=200"
        })
@ActiveProfiles("upstream-test")
@Import(OpenTestRoutesConfiguration.class)
class UpstreamPoolIT {

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
    void acquireTimeoutReturnsInternalServerErrorWhenPoolIsFull() {
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl(Loopback.url(serverPort))
                .responseTimeout(Duration.ofSeconds(5))
                .build();

        // The slow request holds the only connection of the pool for 2 seconds (route timeout: 3 seconds).
        int before = backend.received();
        Mono<HttpStatus> slow = Mono.fromCallable(() -> HttpStatus.valueOf(
                        client.get().uri("/long/slow?delay=2000").exchange()
                                .returnResult(String.class).getStatus().value()))
                .subscribeOn(Schedulers.boundedElastic())
                .cache();
        slow.subscribe();

        // Wait until the backend has the slow request, so that the connection is in use. A fixed
        // sleep is not enough on a slow CI machine.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (backend.received() == before && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(backend.received()).as("slow request reached the backend").isGreaterThan(before);
        client.get().uri("/long/fast")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        StepVerifier.create(slow).expectNext(HttpStatus.OK).verifyComplete();
    }
}
