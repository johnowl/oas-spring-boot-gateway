package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Values from Microsoft Graph or a PR on stderr: no control characters and no workflow commands. */
class ConsoleTextTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', quoteCharacter = '"', value = {
        "orders-api;              orders-api",
        "a::b;                    a::b",
        "::error::x;              \\u003A:error::x",
        "\"  ::error::x\";        \"  \\u003A:error::x\"",
        "##[error]x;              #\\u0023[error]x",
        "a ##[group]b ###[c;      a #\\u0023[group]b ##\\u0023[c",
        "##;                      ##",
        "\"x\n::error::y\";       x\\n::error::y"
    })
    void untrustedNeutralizesWorkflowCommands(String value, String expected) {
        assertThat(ConsoleText.untrusted(value)).isEqualTo(expected);
    }

    @Test
    void printableEscapesC1ControlsLineSeparatorsAndBidirectionalControls() {
        assertThat(ConsoleText.printable("a\u0085b\u2028c\u202Ed\u2066e\u200Ff"))
                .isEqualTo("a\\u0085b\\u2028c\\u202Ed\\u2066e\\u200Ff");
    }
}
