package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The masking of tokens and secrets in log lines (spec 022, "Log guard"). */
class LogMaskingTest {

    private static final String JWT = "eyJraWQiOiJ0ZXN0In0.eyJzdWIiOiJhIn0.c2lnbmF0dXJl-_x";

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Authorization: Bearer abcdefgh.ijk-lm_no~p+q/r=                 | Authorization: Bearer ***",
        "Authorization=[Basic dGVzdC1nYXRld2F5LWJmZjpzZWNyZXQ=]          | Authorization=[Basic ***]",
        "grant_type=refresh_token&refresh_token=0.AXo-abc&scope=x       | grant_type=refresh_token&refresh_token=***&scope=x",
        "{access_token=graph-access-1, expires_in=3600}                  | {access_token=***, expires_in=3600}",
        "{\"id_token\":\"abc.def\",\"token_type\":\"Bearer\"}            | {\"id_token\":\"***\",\"token_type\":\"Bearer\"}",
        "{\\\"refresh_token\\\":\\\"refresh-1\\\"}                       | {\\\"refresh_token\\\":\\\"***\\\"}",
        "client_secret: test-only-dummy-secret                           | client_secret: ***",
        "code_verifier=Zm9vYmFy&code=abc                                 | code_verifier=***&code=***",
        "GET /login/oauth2/code/entra?code=0.AXo-1&state=s1              | GET /login/oauth2/code/entra?code=***&state=s1",
    })
    void masksTheValueAndKeepsTheName(String line, String masked) {
        assertThat(LogMasking.mask(line)).isEqualTo(masked);
    }

    @Test
    void masksEachJsonWebToken() {
        assertThat(LogMasking.mask("token " + JWT + " and (" + JWT + ")")).isEqualTo("token *** and (***)");
        assertThat(LogMasking.mask("eyJhbGciOiJub25lIn0.eyJzdWIiOiJhIn0.")).isEqualTo("***");
    }

    @Test
    void masksAJsonWebTokenAfterBearerOnce() {
        assertThat(LogMasking.mask("Bearer " + JWT)).isEqualTo("Bearer ***");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "reason=INVALID_AUDIENCE_TOKEN registration=entra-6f1c2e8a",
        "error_code=50076 statusCode=400 error_codes=[65001]",
        "Bearer error=\"invalid_token\"",
        "The gateway started in 2.1 seconds.",
        "eyJ alone is not a token",
    })
    void keepsALineWithoutASecret(String line) {
        assertThat(LogMasking.mask(line)).isSameAs(line);
    }

    @Test
    void keepsTheOtherBytesOfAUtf8Line() {
        byte[] line = ("Grüße 日本 access_token=ä€x " + JWT + " ✓\n").getBytes(StandardCharsets.UTF_8);

        assertThat(new String(LogMasking.mask(line), StandardCharsets.UTF_8)).isEqualTo("Grüße 日本 access_token=*** *** ✓\n");
    }

    @Test
    void returnsTheSameBytesWithoutASecret() {
        byte[] line = "Started GatewayApplication\n".getBytes(StandardCharsets.UTF_8);

        assertThat(LogMasking.mask(line)).isSameAs(line);
    }
}
