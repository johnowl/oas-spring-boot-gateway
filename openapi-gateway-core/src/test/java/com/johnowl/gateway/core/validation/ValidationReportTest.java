package com.johnowl.gateway.core.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationReportTest {

    @Test
    void hasAtLeastComparesSeverities() {
        ValidationReport report = new ValidationReport(List.of(violation(Severity.ERROR)));

        assertThat(report.hasAtLeast(Severity.INFO)).isTrue();
        assertThat(report.hasAtLeast(Severity.WARN)).isTrue();
        assertThat(report.hasAtLeast(Severity.ERROR)).isTrue();
    }

    @Test
    void hasAtLeastIsFalseForLowerSeverities() {
        ValidationReport report = new ValidationReport(List.of(violation(Severity.INFO)));

        assertThat(report.hasAtLeast(Severity.WARN)).isFalse();
        assertThat(new ValidationReport(List.of()).hasAtLeast(Severity.INFO)).isFalse();
    }

    @Test
    void summaryCountsEachSeverity() {
        ValidationReport report = new ValidationReport(
                List.of(violation(Severity.WARN), violation(Severity.WARN), violation(Severity.ERROR)));

        assertThat(report.summary()).isEqualTo(Map.of(Severity.INFO, 0L, Severity.WARN, 2L, Severity.ERROR, 1L));
    }

    private static Violation violation(Severity severity) {
        return new Violation(severity, "rule", "file.yaml", "", "message");
    }
}
