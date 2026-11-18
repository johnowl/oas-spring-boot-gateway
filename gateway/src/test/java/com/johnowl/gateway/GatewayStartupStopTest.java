package com.johnowl.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.johnowl.gateway.routing.AudienceAuthorizationActive;
import com.johnowl.gateway.security.TestLocalIdentityProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.NestedExceptionUtils;
import reactor.netty.ChannelBindException;

/**
 * Starts the full application with a protected operation. An XML bean definition of the marker of spec 024
 * stops the startup, also next to the production marker. The check that no property turns off the stop is
 * in {@code OpenApiRoutingStartupTest}: since PR 2 of spec 024, the full application has the production
 * marker.
 */
class GatewayStartupStopTest {

    private static final String PROTECTED = """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            x-gateway: { upstream: "http://orders:8080", audience: 6f1c2e8a-0000-0000-0000-000000000000 }
            components:
              securitySchemes:
                entra: { type: openIdConnect, openIdConnectUrl: "https://login.microsoftonline.com/t/v2.0/.well-known/openid-configuration" }
            security:
              - entra: []
            paths:
              /orders:
                get:
                  x-gateway-authorization: { all-of: [Orders.Read] }
                  responses: { "200": { description: OK } }
            """;

    private static final String PUBLIC = """
            openapi: 3.0.3
            info: { title: Site, version: 1.0.0 }
            x-gateway: { upstream: "http://site:8080" }
            paths:
              /:
                get:
                  security: []
                  responses: { "200": { description: OK } }
            """;

    @TempDir
    Path directory;

    // SEC-59-2: a random management port is not covered by the network policy; the same port puts the actuator on the main port.
    @Test
    void managementPortZeroStopsTheStartup() throws IOException {
        Files.writeString(directory.resolve("site.yaml"), PUBLIC);

        assertThatThrownBy(() -> run("--management.server.port=0"))
                .satisfies(failure -> assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("management.server.port must not be 0. The actuator needs a fixed port"
                                + " that the network policy restricts (spec 002)."));
    }

    // SEC-59-3: only routes from OAS files have an access rule.
    @Test
    void routeFromTheConfigurationStopsTheStartup() throws IOException {
        Files.writeString(directory.resolve("site.yaml"), PUBLIC);
        String route = "--spring.cloud.gateway.server.webflux.routes[0].";

        assertThatThrownBy(() -> run(route + "id=yaml-route", route + "uri=http://localhost:9",
                        route + "predicates[0]=Path=/admin/**"))
                .satisfies(failure -> assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("The route yaml-route does not come from an OAS file. The gateway accepts only the"
                                + " routes of the OAS files, because only they have an access rule (spec 024)."));
    }

    @Test
    void managementPortEqualToServerPortStopsTheStartup() throws IOException {
        Files.writeString(directory.resolve("site.yaml"), PUBLIC);
        int port = FreePort.next();

        assertThatThrownBy(() -> run("--server.port=" + port, "--management.server.port=" + port))
                .satisfies(failure -> assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("management.server.port must differ from server.port (" + port
                                + "). The actuator needs its own port (spec 002)."));
    }

    // A reproduction of the flaky 426 of TokenLoggingIT on macOS: there, a wildcard bind with SO_REUSEADDR shares a
    // port with a process on 127.0.0.1, and that process then answers the requests of the test. The test servers
    // therefore bind 127.0.0.1 (Loopback), and the bind fails. Linux refuses the wildcard bind too, so the test
    // proves the fix only on macOS. GatewayBootstrapIT checks the bound addresses on every system.
    @EnabledOnOs(OS.MAC)
    @ParameterizedTest
    @ValueSource(strings = {"--server.port=", "--management.server.port="})
    void portOfAnotherProcessOnTheLoopbackAddressStopsTheStartup(String portArgument) throws IOException {
        Files.writeString(directory.resolve("site.yaml"), PUBLIC);
        try (ServerSocket other = new ServerSocket()) {
            other.setReuseAddress(true);
            other.bind(new InetSocketAddress(Loopback.ADDRESS, 0));

            assertThatThrownBy(() -> run(portArgument + other.getLocalPort()))
                    .satisfies(failure -> assertThat(Stream.iterate(failure, Objects::nonNull, Throwable::getCause))
                            .filteredOn(ChannelBindException.class::isInstance)
                            .singleElement()
                            .satisfies(bind -> assertThat(((ChannelBindException) bind).localPort())
                                    .isEqualTo(other.getLocalPort())));
        }
    }

    @Test
    void xmlProxyBeanDoesNotTurnOffTheStartupStop() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), PROTECTED);
        Path xml = Files.writeString(directory.resolve("marker.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:schemaLocation="http://www.springframework.org/schema/beans
                           https://www.springframework.org/schema/beans/spring-beans.xsd">
                    <bean id="marker" class="org.springframework.aop.framework.ProxyFactoryBean">
                        <property name="proxyInterfaces" value="%s"/>
                    </bean>
                </beans>
                """.formatted(AudienceAuthorizationActive.class.getName()));

        assertThatThrownBy(() -> run("--spring.main.sources=file:" + xml))
                .satisfies(failure -> assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageMatching("\\Qjdk.proxy\\E\\d+\\Q.$Proxy\\E\\d+\\Q is not a valid bean of type"
                                + " AudienceAuthorizationActive. The bean must be a class in the package"
                                + " com.johnowl.gateway, and not a proxy.\\E"));
    }

    /** Runs the application. An argument for {@code server.port} or {@code management.server.port} replaces the default. */
    private void run(String... arguments) {
        List<String> args = new ArrayList<>(List.of("--gateway.openapi.specs=file:" + directory + "/*.yaml"));
        args.addAll(List.of(Loopback.serverAddressArguments()));
        args.addAll(List.of(arguments));
        if (args.stream().noneMatch(arg -> arg.startsWith("--server.port="))) {
            args.add("--server.port=0");
        }
        if (args.stream().noneMatch(arg -> arg.startsWith("--management.server.port="))) {
            args.add("--management.server.port=" + FreePort.next());
        }
        // The local identity provider lets the full application start up to the check of each test.
        new SpringApplicationBuilder(GatewayApplication.class, TestLocalIdentityProvider.class, OwnNettyResources.class)
                .run(args.toArray(String[]::new)).close();
    }
}
