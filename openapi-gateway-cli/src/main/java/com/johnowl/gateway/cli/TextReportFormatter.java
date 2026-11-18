package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.ValidationReport;
import com.johnowl.gateway.core.validation.Violation;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Writes each violation on two lines, then a summary:
 *
 * <pre>
 * ERROR route-collision openapi/orders.yaml#/paths/~1orders~1{id}/get
 *   GET /orders-api/orders/{} is also declared in payments.yaml at /paths/~1orders~1{orderId}/get.
 *
 * 2 files checked: 1 error, 0 warnings, 0 info.
 * </pre>
 *
 * The engine sorts the violations by file, pointer and rule id. Control characters in file names, pointers and
 * messages are escaped (see {@link ConsoleText}).
 */
final class TextReportFormatter implements ReportFormatter {

    @Override
    public void write(ValidationReport report, int fileCount, PrintWriter out) {
        for (Violation violation : report.violations()) {
            out.printf("%-5s %s %s#%s%n", violation.severity(), violation.ruleId(),
                    ConsoleText.printable(violation.file()), ConsoleText.printable(violation.pointer()));
            out.printf("  %s%n", ConsoleText.printable(violation.message()));
        }
        if (!report.violations().isEmpty()) {
            out.println();
        }
        out.println(summary(report, fileCount));
        out.flush();
    }

    /** {@code 2 files checked: 1 error, 0 warnings, 0 info.} */
    static String summary(ValidationReport report, int fileCount) {
        Map<Severity, Long> summary = report.summary();
        return "%s checked: %s, %s, %d info.".formatted(
                plural(fileCount, "file"),
                plural(summary.get(Severity.ERROR), "error"),
                plural(summary.get(Severity.WARN), "warning"),
                summary.get(Severity.INFO));
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
