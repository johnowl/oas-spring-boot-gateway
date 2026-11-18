package com.johnowl.gateway.core.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.Violation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Parses OAS 3.0 and 3.1 files. Invalid content gives {@value #RULE_ID} violations, not exceptions.
 */
public final class SpecLoader {

    public static final String RULE_ID = "oas-parse";

    /**
     * Reads the file as UTF-8. Relative external references are resolved from the file directory. They must
     * stay inside that directory.
     */
    public SpecLoadResult load(Path file) throws IOException {
        String path = file.toString().replace('\\', '/');
        String content;
        try {
            content = RawDocuments.read(file);
        } catch (RawDocuments.InvalidDocumentException e) {
            return failed(path, e.pointer(), e.getMessage());
        }
        return load(new SpecSource(path, content), file);
    }

    /**
     * Parses content that does not come from a file, for example a classpath resource. Only local references
     * ({@code #/...}) are allowed.
     */
    public SpecLoadResult load(SpecSource source) {
        try {
            return load(source, null);
        } catch (IOException e) {
            // Without a file, the loader reads no other file.
            throw new IllegalStateException(e);
        }
    }

    private SpecLoadResult load(SpecSource source, Path file) throws IOException {
        String path = source.path();
        JsonNode raw;
        try {
            raw = RawDocuments.parse(path, source.content());
        } catch (RawDocuments.InvalidDocumentException e) {
            return failed(path, e.pointer(), e.getMessage());
        }

        JsonNode versionNode = raw == null || !raw.isObject() ? null : raw.get("openapi");
        if (versionNode == null || versionNode.isNull()) {
            if (raw != null && raw.isObject() && raw.has("swagger")) {
                return failed(path, "/swagger", "Unsupported OpenAPI version '" + text(raw.get("swagger")) + "'");
            }
            return failed(path, "", "Missing field 'openapi'");
        }
        String version = text(versionNode);
        if (!version.startsWith("3.0.") && !version.startsWith("3.1.")) {
            return failed(path, "/openapi", "Unsupported OpenAPI version '" + version + "'");
        }

        ReferenceGuard.Result guard = ReferenceGuard.check(raw, file);
        List<Violation> problems = new ArrayList<>();
        PathItemReferences.find(guard.documents()).forEach(problem ->
                problems.add(violation(path, problem.pointer(), problem.message())));
        guard.problems().forEach(problem -> problems.add(violation(path, problem.pointer(), problem.message())));
        if (!problems.isEmpty()) {
            return SpecLoadResult.failed(problems);
        }

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        String location = file == null ? null : file.toAbsolutePath().toString();
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(source.content(), null, options, location);

        List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();
        if (!messages.isEmpty()) {
            return SpecLoadResult.failed(messages.stream()
                    .map(message -> violation(path, "", oneLine(message)))
                    .toList());
        }
        if (result.getOpenAPI() == null) {
            return failed(path, "", "The OpenAPI parser returned no model");
        }
        List<Violation> differences = operationDifferences(path, raw, result.getOpenAPI());
        if (!differences.isEmpty()) {
            return SpecLoadResult.failed(differences);
        }
        return SpecLoadResult.loaded(new SpecFile(path, result.getOpenAPI(), raw));
    }

    /**
     * The rules read the operations of the raw tree, and the gateway routes the operations of the model. Both must
     * have the same paths and, for each path, the same HTTP methods. The loader already rejects each path item
     * reference, the known cause of a difference (SEC-66-1, SEC-67-1). This check also stops an unknown cause.
     */
    static List<Violation> operationDifferences(String path, JsonNode raw, OpenAPI openApi) {
        JsonNode rawPaths = raw.path("paths");
        Map<String, PathItem> modelPaths = openApi.getPaths() == null ? Map.of() : openApi.getPaths();
        Set<String> keys = new LinkedHashSet<>();
        rawPaths.properties().stream().map(Map.Entry::getKey).filter(key -> !key.startsWith("x-")).forEach(keys::add);
        keys.addAll(modelPaths.keySet());
        List<Violation> violations = new ArrayList<>();
        for (String key : keys) {
            JsonNode rawItem = rawPaths.get(key);
            PathItem modelItem = modelPaths.get(key);
            if (rawItem == null || modelItem == null || !rawMethods(rawItem).equals(modelMethods(modelItem))) {
                violations.add(violation(path, "/paths/" + escape(key),
                        "The operations of this path item differ from what the parser read."));
            }
        }
        return violations;
    }

    private static Set<String> rawMethods(JsonNode item) {
        Set<String> methods = new TreeSet<>();
        item.properties().stream().map(Map.Entry::getKey).filter(PathItemMethods.NAMES::contains).forEach(methods::add);
        return methods;
    }

    private static Set<String> modelMethods(PathItem item) {
        Set<String> methods = new TreeSet<>();
        item.readOperationsMap().keySet().forEach(method -> methods.add(method.name().toLowerCase(Locale.ROOT)));
        return methods;
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    /** Text values as they are, other values as JSON, for example {@code [3]}. */
    private static String text(JsonNode node) {
        return node.isTextual() ? node.asText() : node.toString();
    }

    /** Some swagger-parser messages contain several lines of SnakeYAML text. Every output format needs one line. */
    static String oneLine(String message) {
        return message.lines().map(String::strip).filter(line -> !line.isEmpty()).collect(Collectors.joining(" "));
    }

    private static SpecLoadResult failed(String path, String pointer, String message) {
        return SpecLoadResult.failed(List.of(violation(path, pointer, message)));
    }

    private static Violation violation(String path, String pointer, String message) {
        return new Violation(Severity.ERROR, RULE_ID, path, pointer, message);
    }
}
