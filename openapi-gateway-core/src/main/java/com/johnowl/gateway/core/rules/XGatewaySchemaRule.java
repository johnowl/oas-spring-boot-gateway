package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Validates the root-level {@code x-gateway} object against {@code x-gateway.schema.json}. */
public final class XGatewaySchemaRule implements SpecRule {

    static final String EXTENSION = "x-gateway";

    private static final RuleMeta META = new RuleMeta(
            "x-gateway-schema",
            Category.CONTRACT,
            Severity.ERROR,
            false,
            "The root has an x-gateway object, and the object is valid against x-gateway.schema.json.",
            "The gateway builds the routes of the file from this object. A wrong value sends traffic to"
                    + " the wrong backend or creates a wrong public path.");

    /** Loaded once for all files and all runs. */
    private static final Schema SCHEMA = loadSchema();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(SpecContext context, Reporter reporter) {
        JsonNode object = context.spec().raw().get(EXTENSION);
        if (object == null) {
            reporter.report("", "Missing required object 'x-gateway'.");
            return;
        }
        for (Error error : SCHEMA.validate(object)) {
            reporter.report(pointer(error.getInstanceLocation()), message(error));
        }
    }

    private static String message(Error error) {
        String name = lastName(error.getInstanceLocation());
        return switch (error.getKeyword()) {
            case "additionalProperties" -> "Unknown property '" + error.getProperty() + "'.";
            case "required" -> "Missing required property '" + error.getProperty() + "'.";
            case "type" -> name == null
                    ? "'x-gateway' must be an object."
                    : "'" + name + "' must be a string.";
            case "pattern" -> "upstream".equals(name)
                    ? "'upstream' must be scheme://host[:port] without a path."
                            + " Use 'upstream-base-path' for a backend path."
                    : "'" + name + "' does not match " + error.getSchemaNode().asText() + ".";
            default -> (name == null ? "'x-gateway'" : "'" + name + "'") + ": " + error.getMessage();
        };
    }

    private static String pointer(NodePath location) {
        StringBuilder pointer = new StringBuilder("/").append(EXTENSION);
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
        try (InputStream schema = XGatewaySchemaRule.class.getResourceAsStream("/x-gateway.schema.json")) {
            if (schema == null) {
                throw new IllegalStateException("x-gateway.schema.json is not on the classpath");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
