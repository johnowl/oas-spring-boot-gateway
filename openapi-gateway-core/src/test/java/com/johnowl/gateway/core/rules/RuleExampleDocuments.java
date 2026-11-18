package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.johnowl.gateway.core.spec.PathItemMethods;
import com.johnowl.gateway.core.spec.SpecSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a rule example into complete OAS 3.1 documents, so that the meta-test can run the rule on it (spec 032).
 *
 * <p>An example is an excerpt. The wrapper adds only what the excerpt does not have: {@code openapi}, {@code info},
 * a default {@code x-gateway}, the security scheme {@code entra}, a {@code 200} response for an operation without
 * {@code responses}, and a path parameter for each path variable without one. It never changes or removes a field of
 * the excerpt. An example with the field {@code openapi} or {@code swagger} is a complete document; the wrapper does
 * not change it.
 *
 * <p>An example for several files has one YAML document for each file, separated by a {@code ---} line. Each
 * document starts with a comment that names the file, for example {@code # team-a/orders.yaml} or
 * {@code # payments.yaml: the same audience}. A single document is the file {@code example.yaml}.
 */
final class RuleExampleDocuments {

    static final String SINGLE_FILE = "example.yaml";

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Pattern SEPARATOR = Pattern.compile("(?m)^---[ \\t]*$\\R?");
    private static final Pattern FILE_COMMENT = Pattern.compile("^#\\s*([\\w./-]+\\.yaml)(:.*)?$");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");

    private RuleExampleDocuments() {
    }

    /** The excerpt could not be turned into documents. */
    static final class InvalidExampleException extends Exception {

        InvalidExampleException(String message) {
            super(message);
        }
    }

    /** One source for each YAML document of the example. */
    static List<SpecSource> wrap(String example) throws InvalidExampleException {
        String[] parts = SEPARATOR.split(example);
        List<SpecSource> sources = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String part : parts) {
            String name = parts.length == 1 ? SINGLE_FILE : fileName(part);
            if (!names.add(name)) {
                throw new InvalidExampleException("has two documents for the file " + name);
            }
            sources.add(new SpecSource(name, complete(part)));
        }
        return sources;
    }

    private static String fileName(String document) throws InvalidExampleException {
        String first = document.strip().lines().findFirst().orElse("");
        Matcher matcher = FILE_COMMENT.matcher(first);
        if (!matcher.matches()) {
            throw new InvalidExampleException("has a document that does not start with a comment like '# orders.yaml'");
        }
        return matcher.group(1);
    }

    private static String complete(String document) throws InvalidExampleException {
        JsonNode node;
        try {
            node = YAML.readTree(document);
        } catch (JacksonException e) {
            throw new InvalidExampleException("is not valid YAML");
        }
        if (!(node instanceof ObjectNode root)) {
            throw new InvalidExampleException("is not a YAML mapping");
        }
        if (root.has("openapi") || root.has("swagger")) {
            return document;
        }
        ObjectNode wrapped = YAML.createObjectNode();
        wrapped.put("openapi", "3.1.0");
        wrapped.putObject("info").put("title", "Example").put("version", "1.0.0");
        wrapped.setAll(root);
        if (!wrapped.has("x-gateway")) {
            wrapped.putObject("x-gateway").put("upstream", "http://example:8080");
        }
        ObjectNode schemes = object(object(wrapped, "components"), "securitySchemes");
        if (schemes != null && !schemes.has("entra")) {
            schemes.putObject("entra")
                    .put("type", "openIdConnect")
                    .put("openIdConnectUrl",
                            "https://login.microsoftonline.com/common/v2.0/.well-known/openid-configuration");
        }
        if (wrapped.get("paths") instanceof ObjectNode paths) {
            for (Map.Entry<String, JsonNode> path : paths.properties()) {
                if (path.getValue() instanceof ObjectNode item) {
                    completeOperations(path.getKey(), item);
                }
            }
        }
        try {
            return YAML.writeValueAsString(wrapped);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The object field {@code name} of {@code parent}; a new one if it is missing; null if it is not an object. */
    private static ObjectNode object(ObjectNode parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonNode child = parent.get(name);
        if (child == null) {
            return parent.putObject(name);
        }
        return child instanceof ObjectNode object ? object : null;
    }

    private static void completeOperations(String path, ObjectNode item) {
        Set<String> itemParameters = pathParameters(item);
        for (Map.Entry<String, JsonNode> entry : item.properties()) {
            if (!PathItemMethods.NAMES.contains(entry.getKey())
                    || !(entry.getValue() instanceof ObjectNode operation)) {
                continue;
            }
            if (!operation.has("responses")) {
                operation.putObject("responses").putObject("200").put("description", "OK.");
            }
            Set<String> declared = new HashSet<>(itemParameters);
            declared.addAll(pathParameters(operation));
            Matcher variable = PATH_VARIABLE.matcher(path);
            while (variable.find()) {
                String name = variable.group(1);
                if (declared.add(name) && !(operation.has("parameters") && !operation.get("parameters").isArray())) {
                    ArrayNode parameters = operation.has("parameters")
                            ? (ArrayNode) operation.get("parameters")
                            : operation.putArray("parameters");
                    parameters.addObject().put("name", name).put("in", "path").put("required", true)
                            .putObject("schema").put("type", "string");
                }
            }
        }
    }

    private static Set<String> pathParameters(ObjectNode node) {
        Set<String> names = new HashSet<>();
        for (JsonNode parameter : node.path("parameters")) {
            if ("path".equals(parameter.path("in").asText())) {
                names.add(parameter.path("name").asText());
            }
        }
        return names;
    }
}
