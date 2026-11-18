package com.johnowl.gateway.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.ValidationReport;
import com.johnowl.gateway.core.validation.Violation;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes a SARIF 2.1.0 log with one run, for GitHub code scanning. The rule list contains the
 * rules that have at least one result. Each result has a fingerprint that does not depend on the
 * line, so that an alert keeps its identity when lines move.
 */
final class SarifReportFormatter implements ReportFormatter {

    static final String FINGERPRINT_KEY = "oasGatewayViolation/v1";

    private static final String SRCROOT = "%SRCROOT%";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private final SourceLocator locator;
    private final String version;
    private final Map<String, RuleMeta> rules;

    SarifReportFormatter(SourceLocator locator, String version, List<Rule> rules) {
        this.locator = locator;
        this.version = version;
        this.rules = rules.stream().map(Rule::meta).collect(Collectors.toMap(RuleMeta::id, Function.identity()));
    }

    @Override
    public void write(ValidationReport report, int fileCount, PrintWriter out) {
        Map<String, RuleMeta> used = new TreeMap<>();
        for (Violation violation : report.violations()) {
            used.put(violation.ruleId(), rules.get(violation.ruleId()));
        }
        List<String> ids = new ArrayList<>(used.keySet());

        List<SarifRule> sarifRules = used.entrySet().stream().map(e -> rule(e.getKey(), e.getValue())).toList();
        List<Result> results = report.violations().stream().map(v -> result(v, ids.indexOf(v.ruleId()))).toList();
        Log log = new Log(
                "https://json.schemastore.org/sarif-2.1.0.json",
                "2.1.0",
                List.of(new Run(new Tool(new Driver("oas-gateway-validate", version, sarifRules)), results)));
        try {
            out.println(MAPPER.writeValueAsString(log));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        out.flush();
    }

    private static SarifRule rule(String id, RuleMeta meta) {
        if (meta == null) {
            return new SarifRule(id, new Text(id), null, null, null);
        }
        return new SarifRule(
                id,
                new Text(meta.description()),
                new Text(meta.rationale()),
                new Configuration(level(meta.defaultSeverity())),
                new Properties(List.of(meta.category().name())));
    }

    private Result result(Violation violation, int ruleIndex) {
        Region region = (violation.file().isEmpty()
                        ? Optional.<SourcePosition>empty()
                        : locator.locate(Path.of(violation.file()), violation.pointer()))
                .map(p -> new Region(p.line(), p.column()))
                .orElse(new Region(1, null));
        return new Result(
                violation.ruleId(),
                ruleIndex,
                level(violation.severity()),
                new Text(violation.message()),
                List.of(new Location(
                        new PhysicalLocation(artifactLocation(violation.file()), region),
                        List.of(new LogicalLocation(violation.pointer())))),
                Map.of(FINGERPRINT_KEY, fingerprint(violation)));
    }

    /**
     * A relative file gives a relative URI reference to {@code %SRCROOT%}, with each segment percent-encoded. An
     * absolute file (outside the working directory) gives a {@code file:} URI without a base. An empty file (a
     * failing CrossSpecRule) gives {@code .}.
     */
    static ArtifactLocation artifactLocation(String file) {
        if (file.isEmpty()) {
            return new ArtifactLocation(".", SRCROOT);
        }
        Path path = Path.of(file);
        if (path.isAbsolute()) {
            return new ArtifactLocation(path.toUri().toASCIIString(), null);
        }
        return new ArtifactLocation(encodePath(file.replace('\\', '/')), SRCROOT);
    }

    /** Percent-encodes each byte that is not allowed in a path segment of RFC 3986, and also {@code :}. */
    private static String encodePath(String path) {
        StringBuilder result = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c < 0x80 && (Character.isLetterOrDigit(c) || "/-._~!$&'()*+,;=@".indexOf(c) >= 0)) {
                result.append((char) c);
            } else {
                result.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) c));
            }
        }
        return result.toString();
    }

    static String level(Severity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARN -> "warning";
            case INFO -> "note";
        };
    }

    /**
     * SHA-256 of rule id, file and pointer. The line is not part of it. The file uses {@code /} separators, so that
     * Windows and Linux give the same fingerprint.
     */
    static String fingerprint(Violation violation) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String identity = violation.ruleId() + "\n" + violation.file().replace('\\', '/') + "\n"
                    + violation.pointer();
            return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record Log(@JsonProperty("$schema") String schema, String version, List<Run> runs) {
    }

    record Run(Tool tool, List<Result> results) {
    }

    record Tool(Driver driver) {
    }

    record Driver(String name, String version, List<SarifRule> rules) {
    }

    record SarifRule(
            String id,
            Text shortDescription,
            Text fullDescription,
            Configuration defaultConfiguration,
            Properties properties) {
    }

    record Text(String text) {
    }

    record Configuration(String level) {
    }

    record Properties(List<String> tags) {
    }

    record Result(
            String ruleId,
            int ruleIndex,
            String level,
            Text message,
            List<Location> locations,
            Map<String, String> partialFingerprints) {
    }

    record Location(PhysicalLocation physicalLocation, List<LogicalLocation> logicalLocations) {
    }

    record PhysicalLocation(ArtifactLocation artifactLocation, Region region) {
    }

    record ArtifactLocation(String uri, String uriBaseId) {
    }

    record Region(int startLine, Integer startColumn) {
    }

    record LogicalLocation(String fullyQualifiedName) {
    }
}
