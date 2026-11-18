package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RedirectPathValidatorTest {

    private static final String PROPERTY = "gateway.security.login.failure-path";

    @ParameterizedTest
    @ValueSource(strings = {"/?login=failed", "/signed-out", "/", "/app/login", "/?next=/a?b", "/oauth2"})
    void acceptsValidPaths(String value) {
        assertThatCode(() -> RedirectPathValidator.validate(PROPERTY, value, path -> false)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "//evil.example.com/",
        "/\\evil.example.com",
        "/%2F%2Fevil.example.com",
        "https://evil.example.com/",
        "evil.example.com",
        "?login=failed",
        "/a#b",
        "/oauth2/authorization/entra",
        "/login/oauth2/code/entra",
        "/login",
        "/logout",
        "/logout/x",
        "/a b",
        "/a\tb",
        "/a\nb",
        "/a\u0000b",
        "/a\u007fb",
        "/café",
        "/../oauth2/authorization/entra",
        "/a/./b",
        "/oauth2;x/authorization/entra",
        "/login;x",
        "/logout;jsessionid=1",
        "/?login=failed;x",
    })
    void rejectsInvalidPaths(String value) {
        assertThatThrownBy(() -> RedirectPathValidator.validate(PROPERTY, value, path -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(PROPERTY);
    }

    @Test
    void rejectsAPathOfANonPublicOperation() {
        assertThatThrownBy(() -> RedirectPathValidator.validate(PROPERTY, "/orders-api/orders/1?x=y",
                        path -> path.equals("/orders-api/orders/1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-public operation");
    }
}
