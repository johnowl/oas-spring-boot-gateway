package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ManagementEndpointsTest {

    @Test
    void managementPortZeroStopsTheStartup() {
        MockEnvironment environment = new MockEnvironment().withProperty("management.server.port", "0");

        assertThatIllegalStateException().isThrownBy(() -> ManagementEndpoints.of(environment))
                .withMessage("management.server.port must not be 0. The actuator needs a fixed port"
                        + " that the network policy restricts (spec 002).");
    }

    @Test
    void managementPortEqualToServerPortStopsTheStartup() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.port", "9000").withProperty("management.server.port", "9000");

        assertThatIllegalStateException().isThrownBy(() -> ManagementEndpoints.of(environment))
                .withMessage("management.server.port must differ from server.port (9000)."
                        + " The actuator needs its own port (spec 002).");
    }

    @Test
    void managementPortEqualToTheDefaultServerPortStopsTheStartup() {
        MockEnvironment environment = new MockEnvironment().withProperty("management.server.port", "8080");

        assertThatIllegalStateException().isThrownBy(() -> ManagementEndpoints.of(environment))
                .withMessageStartingWith("management.server.port must differ from server.port (8080).");
    }

    @Test
    void fixedOwnPortStarts() {
        MockEnvironment environment = new MockEnvironment().withProperty("management.server.port", "8081");

        assertThat(ManagementEndpoints.of(environment).patterns()).containsExactly("/actuator", "/actuator/**");
    }

    @Test
    void usesTheActuatorBasePath() {
        MockEnvironment environment = new MockEnvironment().withProperty("management.server.port", "8081")
                .withProperty("management.endpoints.web.base-path", "/manage/");

        assertThat(ManagementEndpoints.of(environment).patterns()).containsExactly("/manage", "/manage/**");
    }

    @Test
    void rootBasePathCoversAllPaths() {
        MockEnvironment environment = new MockEnvironment().withProperty("management.server.port", "8081")
                .withProperty("management.endpoints.web.base-path", "/");

        assertThat(ManagementEndpoints.of(environment).patterns()).containsExactly("/**");
    }
}
