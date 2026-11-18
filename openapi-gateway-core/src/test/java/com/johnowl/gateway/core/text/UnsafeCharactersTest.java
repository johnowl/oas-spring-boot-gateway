package com.johnowl.gateway.core.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UnsafeCharactersTest {

    @ParameterizedTest
    @ValueSource(chars = {
        '\u0000', '\t', '\n', '\r', '\u001f', '\u007f', '\u0080', '\u0085', '\u009f', ' ', ' ',
        '؜', '‎', '‏', '‪', '‮', '⁦', '⁩'
    })
    void unsafeCharacters(char c) {
        assertThat(UnsafeCharacters.isUnsafe(c)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(chars = {' ', 'a', '~', ' ', 'é', '​', '‧', ' ', '⁥', '⁪'})
    void safeCharacters(char c) {
        assertThat(UnsafeCharacters.isUnsafe(c)).isFalse();
    }
}
