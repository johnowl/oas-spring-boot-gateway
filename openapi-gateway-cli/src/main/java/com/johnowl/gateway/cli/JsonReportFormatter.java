package com.johnowl.gateway.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.ValidationReport;
import com.johnowl.gateway.core.validation.Violation;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Writes one JSON object with the format version, the tool, the summary and the violations. */
final class JsonReportFormatter implements ReportFormatter {

    /** Increment on a breaking change to the layout. */
    static final int FORMAT_VERSION = 1;

    /** Two spaces, {@code "key": value}, {@code []} for an empty array, and {@code \n} on every platform. */
    static final ObjectWriter WRITER = JsonMapper.builder().build().writer(
            new DefaultPrettyPrinter(Separators.createDefaultInstance()
                    .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                    .withArrayEmptySeparator("")
                    .withObjectEmptySeparator(""))
                    .withArrayIndenter(new DefaultIndenter("  ", "\n"))
                    .withObjectIndenter(new DefaultIndenter("  ", "\n")));

    private final SourceLocator locator;
    private final String version;

    JsonReportFormatter(SourceLocator locator, String version) {
        this.locator = locator;
        this.version = version;
    }

    @Override
    public void write(ValidationReport report, int fileCount, PrintWriter out) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("formatVersion", FORMAT_VERSION);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "oas-gateway-validate");
        tool.put("version", version);
        document.put("tool", tool);

        Map<Severity, Long> counts = report.summary();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("files", fileCount);
        summary.put("error", counts.get(Severity.ERROR));
        summary.put("warn", counts.get(Severity.WARN));
        summary.put("info", counts.get(Severity.INFO));
        document.put("summary", summary);

        List<Map<String, Object>> violations = new ArrayList<>();
        for (Violation violation : report.violations()) {
            Optional<SourcePosition> position = violation.file().isEmpty()
                    ? Optional.empty()
                    : locator.locate(Path.of(violation.file()), violation.pointer());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("severity", violation.severity().name());
            entry.put("ruleId", violation.ruleId());
            entry.put("file", violation.file());
            entry.put("pointer", violation.pointer());
            entry.put("line", position.map(SourcePosition::line).orElse(null));
            entry.put("column", position.map(SourcePosition::column).orElse(null));
            entry.put("message", violation.message());
            violations.add(entry);
        }
        document.put("violations", violations);

        try {
            out.print(WRITER.writeValueAsString(document) + "\n");
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        out.flush();
    }
}
