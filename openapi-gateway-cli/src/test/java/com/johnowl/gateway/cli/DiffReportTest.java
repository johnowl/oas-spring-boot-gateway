package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.johnowl.gateway.core.access.AccessChange;
import com.johnowl.gateway.core.access.AccessDiff;
import com.johnowl.gateway.core.access.AudienceBinding;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The escapes of the output of {@code diff} (spec 034): the values come from the files under review. */
class DiffReportTest {

    private static final Path WORKFLOW = Path.of("../.github/workflows/openapi-change-control.yml");

    /** A file name and an upstream that try to start a workflow command, and a bidirectional control. */
    private static final String FILE = "openapi/x\n::error file=a::boom‮.yaml";
    private static final String UPSTREAM = "http://orders‮:8080/##[group]";

    private static final AccessDiff.Result RESULT = new AccessDiff.Result(
            List.of(new AccessChange(AccessChange.Kind.BECAME_PUBLIC, "GET", "/a", FILE, "/paths/~1a/get", null,
                    "base: audience=x; head: public\r\n::warning::x")),
            List.of(new AudienceBinding(AudienceBinding.Kind.CHANGED, "6f1c2e8a-0000-0000-0000-000000000000",
                    "::notice::base", UPSTREAM, null, "/p", null, "/v1##[x]", "::error::" + FILE,
                    "/x-gateway/upstream")));

    @Test
    void textFormatStartsNoWorkflowCommand() {
        String text = write(out -> DiffReport.writeText(RESULT, out));

        assertThat(text.lines()).hasSize(8).noneMatch(line -> line.stripLeading().startsWith("::"))
                .noneMatch(line -> line.contains("##[")).noneMatch(line -> line.contains("‮"));
        assertThat(text).contains("  openapi/x\\n::error file=a::boom\\u202E.yaml#/paths/~1a/get\n",
                "  base: upstream=::notice::base; head: upstream=http://orders\\u202E:8080/#\\u0023[group],"
                        + " upstream-base-path=/v1#\\u0023[x], prefix=/p\n");
    }

    @Test
    void githubFormatKeepsEachAnnotationOnOneLine() {
        String github = write(out -> DiffReport.writeGithub(RESULT, new SourceLocator(), out));

        assertThat(github.lines()).hasSize(3);
        assertThat(github.lines().toList().get(0)).startsWith("::error file=openapi/x\\n%3A%3Aerror file=a%3A%3Aboom"
                + "\\u202E.yaml::Access loosening (became-public) of GET /a: base: audience=x; head: public\\r\\n");
        assertThat(github.lines().toList().get(1)).startsWith("::error file=%3A%3Aerror%3A%3Aopenapi/x");
    }

    @Test
    void jsonFormatEscapesUnsafeCharactersAndKeepsTheValues() throws IOException {
        String json = write(out -> DiffReport.writeJson(RESULT, out));

        assertThat(json).doesNotContain("‮").contains("\\u202E");
        assertThat(new ObjectMapper().readTree(json).get("changes").get(0).get("file").asText()).isEqualTo(FILE);
        assertThat(new ObjectMapper().readTree(json).get("bindings").get(0).get("headUpstream").asText())
                .isEqualTo(UPSTREAM);
    }

    @Test
    void codeSpanUsesALongerDelimiterThanTheLongestBacktickRun() {
        assertThat(JobSummary.code("orders.yaml")).isEqualTo("` orders.yaml `");
        assertThat(JobSummary.code("a`b")).isEqualTo("`` a`b ``");
        assertThat(JobSummary.code("a``b`c")).isEqualTo("``` a``b`c ```");
        assertThat(JobSummary.code("`")).isEqualTo("`` ` ``");
    }

    @Test
    void codeSpanKeepsTheValueInItsCell() {
        assertThat(JobSummary.code("a|b")).isEqualTo("` a\\|b `");
        assertThat(JobSummary.code("line\nfeed\r x‮")).isEqualTo("` line\\nfeed\\r\\u2028x\\u202E `");
        assertThat(JobSummary.code("[click](https://evil.example) <img src=x>"))
                .isEqualTo("` [click](https://evil.example) <img src=x> `");
    }

    @Test
    void summaryHasOneRowForEachBindingAndNoUntrustedTextOutsideCodeSpans() {
        AudienceBinding added = new AudienceBinding(AudienceBinding.Kind.NEW, "6f1c2e8a-0000-0000-0000-000000000000",
                null, "http://a:8080", null, null, null, null, "| x | y `z`\n| injected |", "/x-gateway/upstream");
        String markdown = JobSummary.bindings(List.of(RESULT.bindings().getFirst(), added));

        List<String> rows = markdown.lines().filter(line -> line.startsWith("| binding-")).toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).isEqualTo("| binding-new | ` 6f1c2e8a-0000-0000-0000-000000000000 ` | none"
                + " | ` http://a:8080 ` | none | none | none | none | `` \\| x \\| y `z`\\n\\| injected \\| `` |");
        assertThat(rows.get(0)).contains(" | ` /p ` | none | ` /v1##[x] ` | ");
        assertThat(markdown.lines().filter(line -> line.startsWith("|"))).hasSize(4);
    }

    /** SEC-75-3: the table of the changes escapes each value like the table of the bindings. */
    @Test
    void summaryHasOneRowForEachChangeAndNoUntrustedTextOutsideCodeSpans() {
        String markdown = JobSummary.changes(RESULT.changes());

        List<String> rows = markdown.lines().filter(line -> line.startsWith("| became-public")).toList();
        assertThat(rows).singleElement().isEqualTo("| became-public | ` GET ` | ` /a `"
                + " | ` base: audience=x; head: public\\r\\n::warning::x `"
                + " | ` openapi/x\\n::error file=a::boom\\u202E.yaml ` | ` /paths/~1a/get ` |");
        assertThat(markdown.lines().filter(line -> line.startsWith("|"))).hasSize(3);
    }

    /**
     * QA-75-2: the job access-changes of .github/workflows/openapi-change-control.yml (step "Access diff") accepts
     * exit code 1 only with this summary line. The job runs the CLI of the base commit, so a change of the line
     * takes two pull requests: first widen the pattern in the workflow, then change the line (spec 034, "Output").
     */
    @Test
    void summaryLineMatchesThePatternOfTheWorkflow() throws IOException {
        List<String> lines = Files.readAllLines(WORKFLOW).stream().map(String::strip)
                .filter(text -> text.startsWith("summary='")).toList();
        assertThat(lines).hasSize(1);
        String pattern = lines.getFirst().substring("summary='".length(), lines.getFirst().length() - 1);
        assertThat(pattern).isEqualTo("^[0-9]+ access loosening changes? in [0-9]+ operations?\\."
                + "( [0-9]+ new or changed audience bindings? to review\\.)?$");
        Pattern workflow = Pattern.compile(pattern);
        AccessChange change = RESULT.changes().getFirst();
        AudienceBinding binding = RESULT.bindings().getFirst();

        for (AccessDiff.Result result : List.of(new AccessDiff.Result(List.of(), List.of()),
                new AccessDiff.Result(List.of(change), List.of()),
                new AccessDiff.Result(List.of(change, change), List.of(binding)),
                new AccessDiff.Result(List.of(), List.of(binding, binding)))) {
            assertThat(DiffReport.summary(result)).matches(workflow);
        }
        assertThat(DiffReport.summary(new AccessDiff.Result(List.of(change), List.of(binding))))
                .isEqualTo("1 access loosening change in 1 operation. 1 new or changed audience binding to review.");
    }

    /** SEC-73-6: a binding without an audience. */
    @Test
    void bindingWithoutAudienceInEachFormat() throws IOException {
        AccessDiff.Result result = new AccessDiff.Result(List.of(), List.of(new AudienceBinding(
                AudienceBinding.Kind.NEW, null, null, "http://docs:8080", null, "/docs", null, null, "docs.yaml",
                "/x-gateway/upstream")));

        assertThat(write(out -> DiffReport.writeText(result, out))).startsWith("BINDING binding-new without an"
                + " audience\n");
        assertThat(write(out -> DiffReport.writeGithub(result, new SourceLocator(), out))).startsWith("::error"
                + " file=docs.yaml::New binding without an audience: the public operations go to http://docs:8080 for"
                + " the prefix /docs. Check that this upstream is the right backend.\n");
        assertThat(new ObjectMapper().readTree(write(out -> DiffReport.writeJson(result, out))).get("bindings").get(0)
                .get("audience").isNull()).isTrue();
        assertThat(JobSummary.bindings(result.bindings())).contains("| binding-new | none | none | ` http://docs:8080 ` |");
    }

    private static String write(Consumer<PrintWriter> writer) {
        StringWriter out = new StringWriter();
        try (PrintWriter printWriter = new PrintWriter(out)) {
            writer.accept(printWriter);
        }
        return out.toString();
    }
}
