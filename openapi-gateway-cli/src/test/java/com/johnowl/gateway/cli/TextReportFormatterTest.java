package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.ValidationReport;
import com.johnowl.gateway.core.validation.Violation;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextReportFormatterTest {

    @Test
    void writesTwoLinesForEachViolationAndSummary() {
        ValidationReport report = new ValidationReport(List.of(
                new Violation(Severity.ERROR, "route-collision", "openapi/orders.yaml", "/paths/~1orders/get", "Collides."),
                new Violation(Severity.WARN, "summary-required", "openapi/orders.yaml", "/paths/~1orders/post", "No summary.")));

        assertThat(write(report, 2)).isEqualTo("""
                ERROR route-collision openapi/orders.yaml#/paths/~1orders/get
                  Collides.
                WARN  summary-required openapi/orders.yaml#/paths/~1orders/post
                  No summary.

                2 files checked: 1 error, 1 warning, 0 info.
                """);
    }

    @Test
    void escapesControlCharactersSoThatNoLineCanBeInjected() {
        ValidationReport report = new ValidationReport(List.of(new Violation(Severity.ERROR, "route-collision",
                "a\r\nb.yaml", "/paths/~1a~1\n::error file=README.md::x/get", "Path '/a/\n::error::x\u0007'.")));

        assertThat(write(report, 1)).isEqualTo("""
                ERROR route-collision a\\r\\nb.yaml#/paths/~1a~1\\n::error file=README.md::x/get
                  Path '/a/\\n::error::x\\u0007'.

                1 file checked: 1 error, 0 warnings, 0 info.
                """);
    }

    @Test
    void writesOnlySummaryWithoutViolations() {
        assertThat(write(new ValidationReport(List.of()), 1))
                .isEqualTo("1 file checked: 0 errors, 0 warnings, 0 info.\n");
    }

    private static String write(ValidationReport report, int files) {
        StringWriter out = new StringWriter();
        new TextReportFormatter().write(report, files, new PrintWriter(out));
        return out.toString().replace(System.lineSeparator(), "\n");
    }
}
