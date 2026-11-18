package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Each component is reachable with local references from a place outside {@code components}. The rule reads the
 * raw document, because swagger-parser replaces resolved references. A {@code discriminator.mapping} value counts
 * as a reference. A used schema with a {@code discriminator} also uses each schema that references it directly in
 * its {@code allOf} (the implicit mapping of OAS). References to other files are ignored. {@code securitySchemes} are not checked, because
 * {@code security} references them by name.
 */
public final class ComponentsUnusedRule implements SpecRule {

    static final List<String> TYPES = List.of("schemas", "responses", "parameters", "examples", "requestBodies",
            "headers", "links", "callbacks", "pathItems");

    private static final String PREFIX = "#/components/";

    private static final RuleMeta META = BestPracticeRule.meta(
            "components-unused", Category.DESIGN, Severity.WARN,
            "Each component (except securitySchemes) is reachable with local $ref or discriminator.mapping"
                    + " references from outside components, also through other components. A used schema with a"
                    + " discriminator also uses each schema that references it in allOf.",
            "Unused components make a spec longer and harder to review, and they often show a forgotten change.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(SpecContext context, Reporter reporter) {
        for (String pointer : unused(context.spec().raw())) {
            reporter.report(pointer, "Component '" + pointer.substring("/components/".length())
                    + "' is not used. Reference it from an operation, or remove it.");
        }
    }

    /** The pointers of the unused components, in document order, for example {@code /components/schemas/Order}. */
    static List<String> unused(JsonNode raw) {
        JsonNode components = raw.get("components");
        if (components == null || !components.isObject()) {
            return List.of();
        }
        Set<String> declared = new LinkedHashSet<>();
        for (String type : TYPES) {
            JsonNode entries = components.get(type);
            if (entries != null && entries.isObject()) {
                entries.properties().forEach(entry ->
                        declared.add("/components/" + type + "/" + JsonPointers.escape(entry.getKey())));
            }
        }
        if (declared.isEmpty()) {
            return List.of();
        }

        Map<String, List<String>> subschemas = subschemas(components.get("schemas"));
        Set<String> used = new HashSet<>();
        Deque<String> reached = new ArrayDeque<>();
        for (Map.Entry<String, JsonNode> field : raw.properties()) {
            if (!field.getKey().equals("components")) {
                references(field.getValue(), reached);
            }
        }
        while (!reached.isEmpty()) {
            String component = reached.pop();
            if (declared.contains(component) && used.add(component)) {
                JsonNode node = raw.at(component);
                references(node, reached);
                if (node.path("discriminator").isObject()) {
                    subschemas.getOrDefault(component, List.of()).forEach(reached::push);
                }
            }
        }

        List<String> unused = new ArrayList<>();
        for (String component : declared) {
            if (!used.contains(component)) {
                unused.add(component);
            }
        }
        return unused;
    }

    /**
     * Adds the component of each local reference in the tree, for example {@code /components/schemas/Order} for
     * {@code #/components/schemas/Order/properties/id}.
     */
    private static void references(JsonNode root, Deque<String> reached) {
        RawTree.walk(root, "", (pointer, name, node) -> {
            if ("$ref".equals(name) && node.isTextual()) {
                component(node.asText()).ifPresent(reached::push);
            } else if ("discriminator".equals(name) && node.isObject()) {
                mapping(node.get("mapping"), reached);
            }
        });
    }

    /**
     * For each schema, the schemas that reference exactly this schema in their {@code allOf}. A discriminator
     * maps these schemas implicitly (OAS, Discriminator Object), so they are used when the schema is used.
     */
    private static Map<String, List<String>> subschemas(JsonNode schemas) {
        Map<String, List<String>> subschemas = new HashMap<>();
        if (schemas == null || !schemas.isObject()) {
            return subschemas;
        }
        for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
            JsonNode allOf = schema.getValue().path("allOf");
            if (!allOf.isArray()) {
                continue;
            }
            String child = "/components/schemas/" + JsonPointers.escape(schema.getKey());
            for (JsonNode part : allOf) {
                JsonNode ref = part.path("$ref");
                if (ref.isTextual()) {
                    component(ref.asText())
                            .filter(parent -> ref.asText().equals("#" + parent))
                            .ifPresent(parent -> subschemas.computeIfAbsent(parent, key -> new ArrayList<>()).add(child));
                }
            }
        }
        return subschemas;
    }

    /** A mapping value is a reference, or the name of a schema (OAS, Discriminator Object). */
    private static void mapping(JsonNode mapping, Deque<String> reached) {
        if (mapping == null || !mapping.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : mapping.properties()) {
            if (!entry.getValue().isTextual()) {
                continue;
            }
            String value = entry.getValue().asText();
            if (value.contains("#") || value.contains("/")) {
                component(value).ifPresent(reached::push);
            } else {
                reached.push("/components/schemas/" + JsonPointers.escape(value));
            }
        }
    }

    /** The component pointer of a local reference, or empty for a reference to another file. */
    private static Optional<String> component(String ref) {
        if (!ref.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] tokens = ref.substring(PREFIX.length()).split("/", 3);
        if (tokens.length < 2 || tokens[1].isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("/components/" + tokens[0] + "/" + tokens[1]);
    }
}
