package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.ValidationReport;
import com.johnowl.gateway.core.validation.Violation;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubReportFormatterTest {

    private static final String FILE = "src/test/resources/source-locator/orders.yaml";

    @Test
    void mapsSeveritiesToCommandsWithPosition() {
        String output = write(
                new Violation(Severity.ERROR, "rule-a", FILE, "/paths/~1orders~1{id}/get", "Error."),
                new Violation(Severity.WARN, "rule-b", FILE, "/x-gateway", "Warning."),
                new Violation(Severity.INFO, "rule-c", FILE, "", "Info."));

        assertThat(output).isEqualTo("""
                ::error file=src/test/resources/source-locator/orders.yaml,line=5,col=5,title=rule-a::Error.
                ::warning file=src/test/resources/source-locator/orders.yaml,line=12,col=1,title=rule-b::Warning.
                ::notice file=src/test/resources/source-locator/orders.yaml,line=1,col=1,title=rule-c::Info.
                1 file checked: 1 error, 1 warning, 1 info.
                """);
    }

    @Test
    void omitsLineAndColumnWhenPositionIsUnknown() {
        String output = write(new Violation(Severity.ERROR, "oas-parse", "missing.yaml", "", "Broken."));

        assertThat(output).startsWith("::error file=missing.yaml,title=oas-parse::Broken.\n");
    }

    @Test
    void omitsFileWhenTheViolationHasNoFile() {
        String output = write(new Violation(Severity.ERROR, "route-collision", "", "", "Rule failed: boom"));

        assertThat(output).startsWith("::error title=route-collision::Rule failed: boom\n");
    }

    @Test
    void escapesMessageAndProperties() {
        assertThat(GithubReportFormatter.escapeData("100% done:\r\nnext, line"))
                .isEqualTo("100%25 done:%0D%0Anext, line");
        assertThat(GithubReportFormatter.escapeProperty("a:b,c%d\ne")).isEqualTo("a%3Ab%2Cc%25d%0Ae");
    }

    @Test
    void escapesInsideTheCommand() {
        String output = write(new Violation(Severity.ERROR, "rule", "dir,1/x:y.yaml", "", "50% of\nlines"));

        assertThat(output).startsWith("::error file=dir%2C1/x%3Ay.yaml,title=rule::50%25 of%0Alines\n");
    }

    private static String write(Violation... violations) {
        StringWriter out = new StringWriter();
        new GithubReportFormatter(new SourceLocator())
                .write(new ValidationReport(List.of(violations)), 1, new PrintWriter(out));
        return out.toString().replace(System.lineSeparator(), "\n");
    }
}
