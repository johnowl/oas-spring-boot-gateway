package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PublicBaseUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://gateway.example.com", "https://gateway.example.com:8443"})
    void acceptsHttpsWithAndWithoutLocalProfile(String value) {
        assertThat(PublicBaseUrl.parse(value, false).uri()).isEqualTo(URI.create(value));
        assertThat(PublicBaseUrl.parse(value, true).uri()).isEqualTo(URI.create(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080", "http://localhost"})
    void acceptsHttpForALocalHostOnlyWithLocalProfile(String value) {
        assertThat(PublicBaseUrl.parse(value, true).uri()).isEqualTo(URI.create(value));
        assertThatThrownBy(() -> PublicBaseUrl.parse(value, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.public-base-url")
                .hasMessageContaining("profile 'local'");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        " ",
        "http://gateway.example.com",
        "gateway.example.com",
        "https://gateway.example.com/",
        "https://gateway.example.com/app",
        "https://gateway.example.com?a=1",
        "https://gateway.example.com#a",
        "https://user@gateway.example.com",
        "ftp://gateway.example.com",
        "HTTPS://gateway.example.com",
        "https:gateway.example.com",
        "https://gate way.example.com",
    })
    void rejectsInvalidValues(String value) {
        assertThatThrownBy(() -> PublicBaseUrl.parse(value, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("gateway.public-base-url");
        assertThatThrownBy(() -> PublicBaseUrl.parse(value, false)).isInstanceOf(IllegalStateException.class);
    }

    // A string comparison such as "starts with http://localhost" would accept these values.
    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost.evil.example",
        "http://localhost@evil.example",
        "http://user@localhost:8080",
        "http://evil.example#@localhost",
        "http://127.0.0.1.nip.io",
        "http://LOCALHOST:8080",
        "http://localhost:8080/",
    })
    void rejectsLookalikeLocalHostsWithLocalProfile(String value) {
        assertThatThrownBy(() -> PublicBaseUrl.parse(value, true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolvesPathsOnTheBaseUrl() {
        PublicBaseUrl baseUrl = PublicBaseUrl.parse("https://gateway.example.com", false);

        assertThat(baseUrl.resolve("/?login=failed")).hasToString("https://gateway.example.com/?login=failed");
        assertThat(baseUrl.resolve("/login/oauth2/code/entra"))
                .hasToString("https://gateway.example.com/login/oauth2/code/entra");
    }
}
