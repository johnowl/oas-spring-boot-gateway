package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogPathFormatterTest {

    @Test
    void keepsTheRawPath() {
        assertThat(LogPathFormatter.format("/orders-api/%0Aorders")).isEqualTo("/orders-api/%0Aorders");
    }

    @Test
    void escapesControlCharactersOnOneLine() {
        String formatted = LogPathFormatter.format("/a\nb\rc\u0007d e");

        assertThat(formatted).isEqualTo("/a\\nb\\rc\\u0007d\\u2028e");
        assertThat(formatted).doesNotContain("\n", "\r", "\u0007", " ");
    }

    @Test
    void escapesBidirectionalControls() {
        assertThat(LogPathFormatter.format("/a\u202Eb\u2066c")).isEqualTo("/a\\u202Eb\\u2066c");
    }

    @Test
    void cutsAfter512Characters() {
        String path = "/" + "a".repeat(1999);

        assertThat(LogPathFormatter.format(path)).isEqualTo(path.substring(0, 512) + "...");
        assertThat(LogPathFormatter.format(path.substring(0, 512))).isEqualTo(path.substring(0, 512));
    }

    @Test
    void cutsTheEscapedPath() {
        String formatted = LogPathFormatter.format("/" + "\n".repeat(600));

        assertThat(formatted).hasSize(515).endsWith("...").doesNotContain("\n");
    }
}
