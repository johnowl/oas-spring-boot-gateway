package com.johnowl.gateway.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers the test bean of {@link LocalIdentityProvider}: the decoder of spec 023 then accepts the
 * issuer of {@link MockIdentityProvider} on localhost. {@link LocalIdentityProviderContextCustomizerFactory}
 * adds it to each Spring Boot test context. A test that starts a context in another way imports it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestLocalIdentityProvider {

    @Bean
    LocalIdentityProvider localIdentityProvider() {
        return new Marker();
    }

    /** The marker. A plain class of the package of {@link LocalIdentityProvider}, not a proxy. */
    static final class Marker implements LocalIdentityProvider {
    }
}
