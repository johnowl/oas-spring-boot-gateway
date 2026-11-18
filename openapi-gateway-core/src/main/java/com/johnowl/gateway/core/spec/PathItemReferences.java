package com.johnowl.gateway.core.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.text.PrintableText;
import com.johnowl.gateway.core.validation.JsonPointers;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Finds each Path Item Object with a {@code $ref} in the raw trees. swagger-parser does not resolve some of these
 * references (SEC-66-1), and drops the operations next to a {@code $ref} that it resolves (SEC-67-1). In both cases
 * the model has other operations than the file, so no rule checks some operations and the gateway builds no route
 * for them. The loader therefore rejects every path item reference. The operations must be inline.
 *
 * <p>A Path Item Object can be a value of {@code paths}, {@code webhooks}, {@code components/pathItems}, and of a
 * Callback Object. A Callback Object can be a value of the {@code callbacks} of an operation and of
 * {@code components/callbacks}. A Callback Object with a local {@code $ref} ({@code #/...}) can point to any place
 * of the same document, so the walk also checks the target of that {@code $ref} (SEC-67-5). A Callback Object with
 * a {@code $ref} to another file can point to any place of that file too, and the walk does not follow it. The
 * loader therefore rejects it (SEC-69-2). The walk checks each Callback Object and each path item once, so a
 * reference cycle ends. The walk has no recursion, so a deep document cannot overflow the stack.
 */
final class PathItemReferences {

    private enum Kind {
        /** A map from names to path items, for example {@code webhooks}. */
        PATH_ITEMS,
        /** {@code paths}: a map from paths to path items, with extensions. */
        PATH_ITEMS_WITH_EXTENSIONS,
        PATH_ITEM,
        /** A map from names to Callback Objects. */
        CALLBACKS,
        /** A map from expressions to path items, with extensions, and with an optional {@code $ref}. */
        CALLBACK
    }

    private record Node(String pointer, JsonNode value, Kind kind) {
    }

    private PathItemReferences() {
    }

    /**
     * Returns one problem for each path item with {@code $ref}, and for each Callback Object with a {@code $ref} to
     * another file. In the root document, the pointer is the pointer of the path item or of the Callback Object. In
     * a referenced file, the pointer is the pointer of the reference in the root file that leads to that file, and
     * the message names the file and the pointer in it.
     */
    static List<ReferenceGuard.Problem> find(List<ReferenceGuard.Document> documents) {
        List<ReferenceGuard.Problem> problems = new ArrayList<>();
        for (ReferenceGuard.Document document : documents) {
            walk(document.raw(), (pointer, message) -> {
                problems.add(document.name() == null
                        ? new ReferenceGuard.Problem(pointer, message)
                        : new ReferenceGuard.Problem(document.rootPointer(), "In referenced file '"
                                + document.name() + "' at '" + PrintableText.escape(pointer) + "': " + message));
            });
        }
        return problems;
    }

    /** Calls the consumer with the pointer and the message of each problem. */
    static void walk(JsonNode raw, BiConsumer<String, String> consumer) {
        if (raw == null || !raw.isObject()) {
            return;
        }
        Deque<Node> queue = new ArrayDeque<>();
        // A callback $ref can point to a place that the walk also reaches in another way, or to itself.
        Set<String> visited = new HashSet<>();
        queue.add(new Node("/paths", raw.get("paths"), Kind.PATH_ITEMS_WITH_EXTENSIONS));
        queue.add(new Node("/webhooks", raw.get("webhooks"), Kind.PATH_ITEMS));
        JsonNode components = raw.path("components");
        queue.add(new Node("/components/pathItems", components.get("pathItems"), Kind.PATH_ITEMS));
        queue.add(new Node("/components/callbacks", components.get("callbacks"), Kind.CALLBACKS));
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.value() == null || !node.value().isObject()) {
                continue;
            }
            switch (node.kind()) {
                case PATH_ITEMS, PATH_ITEMS_WITH_EXTENSIONS -> {
                    for (Map.Entry<String, JsonNode> entry : node.value().properties()) {
                        if (node.kind() == Kind.PATH_ITEMS || !entry.getKey().startsWith("x-")) {
                            queue.add(child(node, entry, Kind.PATH_ITEM));
                        }
                    }
                }
                case PATH_ITEM -> {
                    if (!visited.add(node.kind() + node.pointer())) {
                        continue;
                    }
                    JsonNode reference = node.value().get("$ref");
                    if (reference != null) {
                        consumer.accept(node.pointer(), "Path item reference '" + PrintableText.printable(
                                reference.isTextual() ? reference.asText() : reference.toString())
                                + "' is not supported. Inline the operations.");
                    }
                    for (Map.Entry<String, JsonNode> entry : node.value().properties()) {
                        if (PathItemMethods.NAMES.contains(entry.getKey())) {
                            queue.add(new Node(node.pointer() + "/" + entry.getKey() + "/callbacks",
                                    entry.getValue().get("callbacks"), Kind.CALLBACKS));
                        }
                    }
                }
                case CALLBACKS -> {
                    for (Map.Entry<String, JsonNode> entry : node.value().properties()) {
                        queue.add(child(node, entry, Kind.CALLBACK));
                    }
                }
                case CALLBACK -> {
                    if (!visited.add(node.kind() + node.pointer())) {
                        continue;
                    }
                    JsonNode reference = node.value().get("$ref");
                    if (reference != null && reference.isTextual()) {
                        if (!ReferenceGuard.isLocal(reference.asText())) {
                            consumer.accept(node.pointer(), "Callback reference '"
                                    + PrintableText.printable(reference.asText())
                                    + "' to another file is not supported. Define the callback in this file.");
                        }
                        // A component name, for example "Done", is #/components/callbacks/Done: the walk reads it.
                        for (String target : localTargets(reference.asText())) {
                            queue.add(new Node(target, at(raw, target), Kind.CALLBACK));
                        }
                    }
                    // Fields next to the $ref: the parsers can read them, so the walk checks them too.
                    for (Map.Entry<String, JsonNode> entry : node.value().properties()) {
                        if (!entry.getKey().equals("$ref") && !entry.getKey().startsWith("x-")) {
                            queue.add(child(node, entry, Kind.PATH_ITEM));
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the JSON pointer of a local reference ({@code #/...}), as it is and percent-decoded, or nothing for
     * another reference. Both forms are checked, so that the walk does not depend on how a parser reads the text.
     */
    private static Set<String> localTargets(String reference) {
        if (!reference.startsWith("#/")) {
            return Set.of();
        }
        Set<String> targets = new LinkedHashSet<>();
        String pointer = reference.substring(1);
        targets.add(pointer);
        if (pointer.indexOf('%') >= 0) {
            try {
                targets.add(URLDecoder.decode(pointer.replace("+", "%2B"), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                // An invalid percent escape: swagger-parser does not find the target either.
            }
        }
        return targets;
    }

    /** The node at the pointer, or {@code null} if the pointer is invalid or the node does not exist. */
    private static JsonNode at(JsonNode raw, String pointer) {
        try {
            JsonNode node = raw.at(pointer);
            return node.isMissingNode() ? null : node;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Node child(Node parent, Map.Entry<String, JsonNode> entry, Kind kind) {
        return new Node(parent.pointer() + "/" + JsonPointers.escape(entry.getKey()), entry.getValue(), kind);
    }
}
