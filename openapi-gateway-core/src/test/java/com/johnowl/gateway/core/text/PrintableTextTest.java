package com.johnowl.gateway.core.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrintableTextTest {

    @Test
    void escapesEachUnsafeCharacter() {
        assertThat(PrintableText.escape("a\tb\nc\u007fd\u0085e f‮g"))
                .isEqualTo("a\\u0009b\\u000Ac\\u007Fd\\u0085e\\u2028f\\u202Eg");
    }

    @Test
    void printableEscapesLineSeparatorsAndBidirectionalControls() {
        assertThat(PrintableText.printable("a\u2028b\u2029c\u061Cd\u2066e"))
                .isEqualTo("a\\u2028b\\u2029c\\u061Cd\\u2066e");
    }

    @Test
    void keepsSafeTextAsItIs() {
        String text = "#/components/pathItems/Admin é 😀";

        assertThat(PrintableText.escape(text)).isSameAs(text);
        assertThat(PrintableText.printable(text)).isEqualTo(text);
    }

    @Test
    void escapeKeepsTheFullText() {
        assertThat(PrintableText.escape("x".repeat(500))).hasSize(500);
    }

    @Test
    void printableCutsLongText() {
        assertThat(PrintableText.printable("x".repeat(PrintableText.MAX_LENGTH)))
                .isEqualTo("x".repeat(PrintableText.MAX_LENGTH));
        assertThat(PrintableText.printable("x".repeat(PrintableText.MAX_LENGTH + 1)))
                .isEqualTo("x".repeat(PrintableText.MAX_LENGTH) + "...");
    }

    @Test
    void printableNeverCutsAnEscapeOrASurrogatePair() {
        String escape = PrintableText.printable("x".repeat(PrintableText.MAX_LENGTH - 3) + "\n");
        String surrogate = PrintableText.printable("x".repeat(PrintableText.MAX_LENGTH - 1) + "😀");

        assertThat(escape).isEqualTo("x".repeat(PrintableText.MAX_LENGTH - 3) + "...");
        assertThat(surrogate).isEqualTo("x".repeat(PrintableText.MAX_LENGTH - 1) + "...");
    }
}
