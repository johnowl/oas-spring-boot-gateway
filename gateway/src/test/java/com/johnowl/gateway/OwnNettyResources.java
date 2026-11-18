package com.johnowl.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ReactorResourceFactory;

/**
 * Netty resources of one application only, for a test that runs a second application itself. With the global
 * resources of Reactor Netty, the close of that application, or a startup that fails after the start of a server,
 * would also stop the servers of the test context and of the mocks, for example the mock identity provider.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OwnNettyResources {

    @Bean
    ReactorResourceFactory reactorResourceFactory() {
        ReactorResourceFactory factory = new ReactorResourceFactory();
        factory.setUseGlobalResources(false);
        return factory;
    }
}
