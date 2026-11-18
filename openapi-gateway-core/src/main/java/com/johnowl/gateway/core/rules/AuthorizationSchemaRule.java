package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.johnowl.gateway.core.access.AccessRuleCompiler;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.OperationContext;
import com.johnowl.gateway.core.validation.OperationRule;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/** {@code x-gateway-authorization} matches {@code $defs/authorization} of {@code x-gateway.schema.json}. */
public final class AuthorizationSchemaRule implements OperationRule {

    private static final RuleMeta META = ContractRule.meta(
            "authorization-schema",
            "x-gateway-authorization matches $defs/authorization of x-gateway.schema.json: all-of, any-of"
                    + " (non-empty lists), or authenticated-only: true alone.",
            "The gateway compiles the extension into the access rule of the operation. A typo or an"
                    + " empty condition would change who can call the operation.");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Loaded once for all files and all runs. */
    private static final Schema SCHEMA = loadSchema();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(OperationContext context, Reporter reporter) {
        Map<String, Object> extensions = context.operation().getExtensions();
        if (extensions == null || !extensions.containsKey(AccessRuleCompiler.EXTENSION)) {
            return;
        }
        String base = context.pointer() + "/" + AccessRuleCompiler.EXTENSION;
        JsonNode value = MAPPER.valueToTree(extensions.get(AccessRuleCompiler.EXTENSION));
        for (Error error : SCHEMA.validate(value)) {
            reporter.report(pointer(base, error.getInstanceLocation()), message(error));
        }
    }

    private static String message(Error error) {
        String name = lastName(error.getInstanceLocation());
        return switch (error.getKeyword()) {
            case "additionalProperties" -> "Unknown property '" + error.getProperty()
                    + "'. Allowed: all-of, any-of, authenticated-only.";
            case "minProperties" -> "The object is empty. Declare all-of, any-of, or authenticated-only: true.";
            case "minItems" -> "'" + name + "' must not be empty.";
            case "const" -> "'authenticated-only' must be true. To require permissions, use all-of or any-of.";
            case "not" -> "'authenticated-only' cannot be used with 'all-of' or 'any-of'.";
            case "type" -> name == null
                    ? "x-gateway-authorization must be an object."
                    : "'" + name + "' has the wrong type: " + error.getMessage();
            default -> error.getMessage();
        };
    }

    private static String pointer(String base, NodePath location) {
        StringBuilder pointer = new StringBuilder(base);
        for (int i = 0; i < location.getNameCount(); i++) {
            pointer.append('/').append(JsonPointers.escape(String.valueOf(location.getElement(i))));
        }
        return pointer.toString();
    }

    private static String lastName(NodePath location) {
        int count = location.getNameCount();
        return count == 0 ? null : String.valueOf(location.getElement(count - 1));
    }

    private static Schema loadSchema() {
        try (InputStream in = AuthorizationSchemaRule.class.getResourceAsStream("/x-gateway.schema.json")) {
            JsonNode definition = MAPPER.readTree(in).at("/$defs/authorization");
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(definition);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
