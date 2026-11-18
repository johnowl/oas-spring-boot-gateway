package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.validation.ValidationReport;
import com.johnowl.gateway.core.validation.Violation;
import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * Writes GitHub Actions workflow commands, so that each violation is an annotation on the pull
 * request, then the summary line of the text format:
 *
 * <pre>
 * ::error file=openapi/orders.yaml,line=5,col=5,title=route-collision::GET /orders collides ...
 * </pre>
 */
final class GithubReportFormatter implements ReportFormatter {

    private final SourceLocator locator;

    GithubReportFormatter(SourceLocator locator) {
        this.locator = locator;
    }

    @Override
    public void write(ValidationReport report, int fileCount, PrintWriter out) {
        for (Violation violation : report.violations()) {
            // A violation without a file (a failing CrossSpecRule) has no file and no position.
            StringBuilder command = new StringBuilder("::").append(command(violation)).append(' ');
            if (!violation.file().isEmpty()) {
                command.append("file=").append(escapeProperty(violation.file())).append(',');
                locator.locate(Path.of(violation.file()), violation.pointer()).ifPresent(p ->
                        command.append("line=").append(p.line()).append(",col=").append(p.column()).append(','));
            }
            command.append("title=").append(escapeProperty(violation.ruleId()))
                    .append("::").append(escapeData(violation.message()));
            out.println(command);
        }
        out.println(TextReportFormatter.summary(report, fileCount));
        out.flush();
    }

    private static String command(Violation violation) {
        return switch (violation.severity()) {
            case ERROR -> "error";
            case WARN -> "warning";
            case INFO -> "notice";
        };
    }

    /** Escapes a message: {@code %}, carriage return and line feed. */
    static String escapeData(String value) {
        return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    /** Escapes a property value: the message escapes, and {@code :} and {@code ,}. */
    static String escapeProperty(String value) {
        return escapeData(value).replace(":", "%3A").replace(",", "%2C");
    }
}
