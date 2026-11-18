package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.spec.PathItemMethods;
import com.johnowl.gateway.core.spec.SpecFile;
import com.johnowl.gateway.core.validation.JsonPointers;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;

/** Finds the {@code security} arrays of a raw OAS document: the root array and each operation array. */
final class SecurityRequirements {

    private SecurityRequirements() {
    }

    /** Calls the consumer with the pointer and the node of each {@code security} field. */
    static void each(SpecFile spec, BiConsumer<String, JsonNode> consumer) {
        JsonNode root = spec.raw().get("security");
        if (root != null) {
            consumer.accept("/security", root);
        }
        JsonNode paths = spec.raw().get("paths");
        if (paths == null || !paths.isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields(); pathIterator.hasNext(); ) {
            Map.Entry<String, JsonNode> path = pathIterator.next();
            if (!path.getValue().isObject()) {
                continue;
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = path.getValue().fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> operation = it.next();
                JsonNode security = operation.getValue().get("security");
                if (PathItemMethods.NAMES.contains(operation.getKey()) && security != null) {
                    consumer.accept("/paths/" + JsonPointers.escape(path.getKey()) + "/" + operation.getKey() + "/security", security);
                }
            }
        }
    }
}
