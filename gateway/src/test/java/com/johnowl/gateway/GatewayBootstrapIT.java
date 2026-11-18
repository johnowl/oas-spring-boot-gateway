package com.johnowl.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

// ManagementPortContextCustomizerFactory gives the management context a free port.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({OpenTestRoutesConfiguration.class, GatewayBootstrapIT.BoundServers.class})
class GatewayBootstrapIT {

    private static final AtomicReference<String> receivedPath = new AtomicReference<>();

    private static DisposableServer mockBackend;

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void mockBackendPort(DynamicPropertyRegistry registry) {
        mockBackend = HttpServer.create()
                .host(Loopback.ADDRESS)
                .port(0)
                .handle((request, response) -> {
                    receivedPath.set(request.uri());
                    return response.status(201)
                            .header("X-Mock-Backend", "yes")
                            .sendString(Mono.just("echo from mock backend"));
                })
                .bindNow();
        registry.add("mock.backend.port", mockBackend::port);
    }

    @AfterAll
    static void stopMockBackend() {
        mockBackend.disposeNow();
    }

    // See Loopback: a server on the wildcard address can share its port with another process on 127.0.0.1.
    // The test checks the address that each server really binds, not only the property.
    @Test
    void testServersListenOnTheLoopbackAddress() {
        assertThat(BoundServers.SERVERS).containsOnlyKeys("server", "management");
        assertThat(BoundServers.SERVERS.get("server").getPort()).isEqualTo(serverPort);
        assertThat(BoundServers.SERVERS.get("management").getPort()).isEqualTo(managementPort);
        assertThat(BoundServers.SERVERS.values()).allSatisfy(server -> assertThat(boundAddress(server))
                .isEqualTo(new InetSocketAddress(Loopback.ADDRESS, server.getPort())));
        assertThat(mockBackend.address()).isEqualTo(new InetSocketAddress(Loopback.ADDRESS, mockBackend.port()));
    }

    @Test
    void forwardsTestRouteToMockBackend() {
        client(serverPort).get().uri("/test/echo")
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectHeader().valueEquals("X-Mock-Backend", "yes")
                .expectBody(String.class).isEqualTo("echo from mock backend");

        assertThat(receivedPath.get()).isEqualTo("/test/echo");
    }

    @Test
    void healthIsUpOnManagementPort() {
        client(managementPort).get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    // Spec 024: the main port answers 404 for every path without an access rule.
    @Test
    void healthIsNotOnMainServerPort() {
        client(serverPort).get().uri("/actuator/health")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() {
        client(managementPort).get().uri("/actuator/env")
                .exchange()
                .expectStatus().isNotFound();
    }

    // SEC-59-2: the management port lets only the actuator base path pass.
    @ParameterizedTest
    @ValueSource(strings = {"/", "/admin/secrets", "/actuatorx", "/test/echo"})
    void managementPortAnswers404OutsideTheActuatorBasePath(String path) {
        client(managementPort).get().uri(path)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).isEqualTo(
                        "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"Not found.\"}");
    }

    @Test
    void contextIsReactiveWebApplicationContext() {
        assertThat(context).isInstanceOf(ReactiveWebApplicationContext.class);
    }

    @Test
    void blockHoundIsActive() {
        Mono<Void> blocking = Mono.<Void>fromRunnable(() -> {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .subscribeOn(Schedulers.parallel());

        assertThatThrownBy(() -> blocking.block(Duration.ofSeconds(5)))
                .hasCauseInstanceOf(BlockingOperationError.class);
    }

    private static WebTestClient client(int port) {
        return WebTestClient.bindToServer().baseUrl(Loopback.url(port)).build();
    }

    /** NettyWebServer does not expose its address, so the test reads the address of its DisposableServer. */
    private static SocketAddress boundAddress(WebServer server) {
        return ((DisposableServer) ReflectionTestUtils.getField(server, "disposableServer")).address();
    }

    /** Records the web server of the application ("server") and of the management context ("management"). */
    @TestConfiguration(proxyBeanMethods = false)
    static class BoundServers {

        static final Map<String, WebServer> SERVERS = new ConcurrentHashMap<>();

        @Bean
        ApplicationListener<WebServerInitializedEvent> boundServerRecorder() {
            return event -> SERVERS.put(Objects.requireNonNullElse(
                    event.getApplicationContext().getServerNamespace(), "server"), event.getWebServer());
        }
    }
}
