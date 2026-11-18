package com.johnowl.gateway.routing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers a test bean of {@link AudienceAuthorizationActive}, so that a test can load OAS files with
 * protected operations before the authorization manager of spec 024 exists. Only tests use it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAudienceAuthorization {

    @Bean
    AudienceAuthorizationActive testAudienceAuthorizationActive() {
        return new AudienceAuthorizationActive() {
        };
    }
}
