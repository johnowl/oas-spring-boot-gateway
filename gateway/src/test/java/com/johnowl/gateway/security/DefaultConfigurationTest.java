package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The defaults of the main application.yaml. The secrets of the session login come from the
 * environment, never from this file.
 */
class DefaultConfigurationTest {

    // The decision of 2026-11-13: sessions in memory by default, Redis only when enabled.
    @Test
    void sessionsAreInMemoryWithALimitByDefault() throws IOException {
        PropertySource<?> yaml = mainApplicationYaml();

        assertThat(yaml.getProperty("gateway.session.store")).hasToString("memory");
        assertThat(yaml.getProperty("gateway.session.memory.max-sessions")).hasToString("100000");
    }

    @Test
    void redisUsesTlsAndCredentialsFromTheEnvironment() throws IOException {
        PropertySource<?> yaml = mainApplicationYaml();

        assertThat(yaml.getProperty("spring.data.redis.ssl.enabled")).hasToString("true");
        assertThat(yaml.getProperty("spring.data.redis.username")).hasToString("${REDIS_USERNAME}");
        assertThat(yaml.getProperty("spring.data.redis.password")).hasToString("${REDIS_PASSWORD}");
    }

    @Test
    void clientSecretComesFromTheEnvironment() throws IOException {
        PropertySource<?> yaml = mainApplicationYaml();

        assertThat(yaml.getProperty("spring.security.oauth2.client.registration.entra.client-secret"))
                .hasToString("${GATEWAY_BFF_CLIENT_SECRET:}");
        assertThat(yaml.getProperty("spring.security.oauth2.client.registration.entra.redirect-uri"))
                .hasToString("${gateway.public-base-url}/login/oauth2/code/{registrationId}");
    }

    // Spring Boot trusts forwarding headers on some cloud platforms when the strategy is not set.
    @Test
    void forwardingHeadersAreNotTrusted() throws IOException {
        assertThat(mainApplicationYaml().getProperty("server.forward-headers-strategy")).hasToString("none");
    }

    private static PropertySource<?> mainApplicationYaml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        return sources.getFirst();
    }
}
