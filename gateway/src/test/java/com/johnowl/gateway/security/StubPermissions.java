package com.johnowl.gateway.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Gives the authorization manager the {@link StubTokenPermissionsResolver}, for tests with {@code mockOidcLogin()}. */
@TestConfiguration(proxyBeanMethods = false)
class StubPermissions {

    @Bean
    @Primary
    StubTokenPermissionsResolver stubTokenPermissionsResolver() {
        return new StubTokenPermissionsResolver();
    }
}
