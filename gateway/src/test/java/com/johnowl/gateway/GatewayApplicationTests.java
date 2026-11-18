package com.johnowl.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.AudienceAuthorizationActive;
import com.johnowl.gateway.routing.TestRouteIds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class GatewayApplicationTests {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
    }

    @Test
    void productionCodeActivatesTheAudienceAuthorizationOnce() {
        // PR 2 of spec 024 registers exactly one marker, a class of gateway/src/main.
        assertThat(context.getBeansOfType(AudienceAuthorizationActive.class).values()).singleElement()
                .satisfies(marker -> assertThat(marker.getClass().getName())
                        .isEqualTo("com.johnowl.gateway.security.GatewayAudienceAuthorization"));
    }

    @Test
    void productionCodeAllowsNoRouteWithoutAnOasFile() {
        // Only test configurations in gateway/src/test list the ids of routes without an OAS file (spec 024).
        assertThat(context.getBeansOfType(TestRouteIds.class)).isEmpty();
    }

}
