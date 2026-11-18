package com.johnowl.gateway.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.ValidationReport;
import com.johnowl.gateway.core.validation.Violation;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SarifReportFormatterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE = "src/test/resources/source-locator/orders.yaml";

    @Test
    void writesResultsWithLevelsLocationsAndFingerprints() throws Exception {
        JsonNode sarif = write(List.of(
                new Violation(Severity.ERROR, "x-gateway-schema", FILE, "/x-gateway", "Bad."),
                new Violation(Severity.WARN, "route-collision", FILE, "/paths/~1orders~1{id}/get", "Collides."),
                new Violation(Severity.INFO, "x-gateway-schema", "missing.yaml", "", "Missing.")));

        JsonNode run = sarif.at("/runs/0");
        assertThat(run.at("/tool/driver/rules").findValuesAsText("id"))
                .containsExactly("route-collision", "x-gateway-schema");
        assertThat(run.at("/tool/driver/rules/1/shortDescription/text").asText()).startsWith("The root has an x-gateway object");
        assertThat(run.at("/tool/driver/rules/1/fullDescription/text").asText()).startsWith("The gateway builds the routes");
        assertThat(run.at("/tool/driver/rules/1/defaultConfiguration/level").asText()).isEqualTo("error");
        assertThat(run.at("/tool/driver/rules/1/properties/tags/0").asText()).isEqualTo("CONTRACT");

        JsonNode first = run.at("/results/0");
        assertThat(first.get("ruleId").asText()).isEqualTo("x-gateway-schema");
        assertThat(first.get("ruleIndex").asInt()).isEqualTo(1);
        assertThat(first.get("level").asText()).isEqualTo("error");
        assertThat(first.at("/message/text").asText()).isEqualTo("Bad.");
        assertThat(first.at("/locations/0/physicalLocation/artifactLocation/uri").asText()).isEqualTo(FILE);
        assertThat(first.at("/locations/0/physicalLocation/region/startLine").asInt()).isEqualTo(12);
        assertThat(first.at("/locations/0/logicalLocations/0/fullyQualifiedName").asText()).isEqualTo("/x-gateway");
        assertThat(first.at("/partialFingerprints/oasGatewayViolation~1v1").asText()).hasSize(64);

        assertThat(run.at("/results/1/level").asText()).isEqualTo("warning");
        assertThat(run.at("/results/2/level").asText()).isEqualTo("note");
        assertThat(run.at("/results/2/locations/0/physicalLocation/region").toString()).isEqualTo("{\"startLine\":1}");
    }

    @Test
    void violationWithoutFileHasUriDotAndFirstLine() throws Exception {
        JsonNode location = write(List.of(new Violation(Severity.ERROR, "route-collision", "", "", "Rule failed: x")))
                .at("/runs/0/results/0/locations/0/physicalLocation");

        assertThat(location.at("/artifactLocation/uri").asText()).isEqualTo(".");
        assertThat(location.at("/artifactLocation/uriBaseId").asText()).isEqualTo("%SRCROOT%");
        assertThat(location.at("/region").toString()).isEqualTo("{\"startLine\":1}");
    }

    @Test
    void uriIsAPercentEncodedRelativeReference() {
        assertThat(SarifReportFormatter.artifactLocation("api specs/orders v2#1%.yaml"))
                .isEqualTo(new SarifReportFormatter.ArtifactLocation("api%20specs/orders%20v2%231%25.yaml", "%SRCROOT%"));
        assertThat(SarifReportFormatter.artifactLocation("openapi\\c:d/caf\u00e9.yaml"))
                .isEqualTo(new SarifReportFormatter.ArtifactLocation("openapi/c%3Ad/caf%C3%A9.yaml", "%SRCROOT%"));
    }

    @Test
    void absoluteFileIsAFileUriWithoutBase(@TempDir Path dir) {
        Path file = dir.resolve("orders v2.yaml");

        SarifReportFormatter.ArtifactLocation location = SarifReportFormatter.artifactLocation(file.toString());

        assertThat(location.uriBaseId()).isNull();
        assertThat(location.uri()).startsWith("file:/").endsWith("/orders%20v2.yaml");
        assertThat(Path.of(URI.create(location.uri()))).isEqualTo(file);
    }

    @Test
    void fingerprintIsTheSameForWindowsAndLinuxSeparators() {
        assertThat(SarifReportFormatter.fingerprint(new Violation(Severity.ERROR, "rule", "a\\b.yaml", "/x", "M.")))
                .isEqualTo(SarifReportFormatter.fingerprint(new Violation(Severity.ERROR, "rule", "a/b.yaml", "/x", "M.")));
    }

    @Test
    void fingerprintDoesNotDependOnLineOrMessage() {
        Violation a = new Violation(Severity.ERROR, "rule", "a.yaml", "/paths/~1x/get", "Message one.");
        Violation b = new Violation(Severity.WARN, "rule", "a.yaml", "/paths/~1x/get", "Message two.");
        Violation c = new Violation(Severity.ERROR, "rule", "a.yaml", "/paths/~1y/get", "Message one.");

        assertThat(SarifReportFormatter.fingerprint(a)).isEqualTo(SarifReportFormatter.fingerprint(b));
        assertThat(SarifReportFormatter.fingerprint(a)).isNotEqualTo(SarifReportFormatter.fingerprint(c));
    }

    @Test
    void emptyReportHasEmptyResultsAndRules() throws Exception {
        JsonNode sarif = write(List.of());

        assertThat(sarif.at("/runs/0/results").isArray()).isTrue();
        assertThat(sarif.at("/runs/0/results")).isEmpty();
        assertThat(sarif.at("/runs/0/tool/driver/rules")).isEmpty();
    }

    @Test
    void outputIsValidSarif() throws Exception {
        Schema schema;
        try (InputStream in = getClass().getResourceAsStream("/sarif/sarif-schema-2.1.0.json")) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(in);
        }
        for (List<Violation> violations : List.of(
                List.<Violation>of(),
                List.of(new Violation(Severity.ERROR, "x-gateway-schema", FILE, "/x-gateway", "Bad."),
                        new Violation(Severity.INFO, "oas-parse", "missing.yaml", "", "Broken."),
                        new Violation(Severity.ERROR, "route-collision", "", "", "Rule failed: x"),
                        new Violation(Severity.WARN, "oas-parse", "/tmp/outside dir/a.yaml", "", "Outside.")))) {
            List<Error> errors = schema.validate(write(violations));
            assertThat(errors).isEmpty();
        }
        // The schema check is not empty: a wrong version fails.
        JsonNode wrong = ((ObjectNode) write(List.of())).put("version", "3.0");
        assertThat(schema.validate(wrong)).isNotEmpty();
    }

    private static JsonNode write(List<Violation> violations) throws Exception {
        StringWriter out = new StringWriter();
        new SarifReportFormatter(new SourceLocator(), "1.2.3", BuiltInRules.all())
                .write(new ValidationReport(violations), 1, new PrintWriter(out));
        return MAPPER.readTree(out.toString());
    }
}
