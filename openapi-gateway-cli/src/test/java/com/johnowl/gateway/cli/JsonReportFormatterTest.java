package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.ValidationReport;
import com.johnowl.gateway.core.validation.Violation;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonReportFormatterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writesExpectedDocument() throws Exception {
        JsonNode actual = write(List.of(
                new Violation(Severity.ERROR, "rule-a", "src/test/resources/source-locator/orders.yaml",
                        "/paths/~1orders~1{id}/get", "Error."),
                new Violation(Severity.WARN, "oas-parse", "missing.yaml", "", "Broken.")), 2);

        assertThat(actual).isEqualTo(MAPPER.readTree("""
                {
                  "formatVersion": 1,
                  "tool": { "name": "oas-gateway-validate", "version": "1.2.3" },
                  "summary": { "files": 2, "error": 1, "warn": 1, "info": 0 },
                  "violations": [
                    {
                      "severity": "ERROR",
                      "ruleId": "rule-a",
                      "file": "src/test/resources/source-locator/orders.yaml",
                      "pointer": "/paths/~1orders~1{id}/get",
                      "line": 5,
                      "column": 5,
                      "message": "Error."
                    },
                    {
                      "severity": "WARN",
                      "ruleId": "oas-parse",
                      "file": "missing.yaml",
                      "pointer": "",
                      "line": null,
                      "column": null,
                      "message": "Broken."
                    }
                  ]
                }
                """));
    }

    @Test
    void violationWithoutFileHasNoPosition() throws Exception {
        JsonNode violation = write(List.of(
                new Violation(Severity.ERROR, "route-collision", "", "", "Rule failed: boom")), 1).at("/violations/0");

        assertThat(violation.get("file").asText()).isEmpty();
        assertThat(violation.get("line").isNull()).isTrue();
        assertThat(violation.get("column").isNull()).isTrue();
    }

    @Test
    void layoutUsesTwoSpacesColonSpaceAndLineFeeds() {
        StringWriter out = new StringWriter();
        new JsonReportFormatter(new SourceLocator(), "1.2.3")
                .write(new ValidationReport(List.of()), 1, new PrintWriter(out));

        assertThat(out.toString()).isEqualTo("""
                {
                  "formatVersion": 1,
                  "tool": {
                    "name": "oas-gateway-validate",
                    "version": "1.2.3"
                  },
                  "summary": {
                    "files": 1,
                    "error": 0,
                    "warn": 0,
                    "info": 0
                  },
                  "violations": []
                }
                """);
    }

    @Test
    void writesEmptyViolationArray() throws Exception {
        JsonNode actual = write(List.of(), 1);

        assertThat(actual.get("violations").isArray()).isTrue();
        assertThat(actual.get("violations")).isEmpty();
    }

    private static JsonNode write(List<Violation> violations, int files) throws Exception {
        StringWriter out = new StringWriter();
        new JsonReportFormatter(new SourceLocator(), "1.2.3")
                .write(new ValidationReport(violations), files, new PrintWriter(out));
        return MAPPER.readTree(out.toString());
    }
}
