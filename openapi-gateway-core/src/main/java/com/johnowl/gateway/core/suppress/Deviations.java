package com.johnowl.gateway.core.suppress;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.spec.PathItemMethods;
import com.johnowl.gateway.core.spec.SpecFile;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.ValidationEngine;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Reads the {@code x-deviation} entries of the operations of a raw OAS document. */
public final class Deviations {

    public static final String EXTENSION = "x-deviation";
    public static final String SCHEMA_RULE_ID = "deviation-schema";
    /** The engine reports unused entries with this id, so both use one constant. */
    public static final String UNUSED_RULE_ID = ValidationEngine.UNUSED_SUPPRESSION_RULE_ID;

    private Deviations() {
    }

    /**
     * One entry with a {@code rule} and a non-blank {@code reason}. The rule id is not checked here.
     *
     * @param operationPointer the operation that the entry covers, for example {@code /paths/~1orders/get}
     * @param pointer the entry, for example {@code /paths/~1orders/get/x-deviation/0}
     */
    public record Entry(String operationPointer, String pointer, String rule) {
    }

    /** Returns the well-formed entries of all operations. Malformed entries are skipped. */
    public static List<Entry> entries(SpecFile spec) {
        List<Entry> entries = new ArrayList<>();
        JsonNode paths = spec.raw().get("paths");
        if (paths == null || !paths.isObject()) {
            return entries;
        }
        for (Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields(); pathIterator.hasNext(); ) {
            Map.Entry<String, JsonNode> path = pathIterator.next();
            for (Iterator<Map.Entry<String, JsonNode>> it = path.getValue().fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> operation = it.next();
                JsonNode list = operation.getValue().get(EXTENSION);
                if (!PathItemMethods.NAMES.contains(operation.getKey()) || list == null || !list.isArray()) {
                    continue;
                }
                String operationPointer = "/paths/" + JsonPointers.escape(path.getKey()) + "/" + operation.getKey();
                for (int i = 0; i < list.size(); i++) {
                    JsonNode entry = list.get(i);
                    if (entry.isObject() && entry.path("rule").isTextual() && entry.path("reason").isTextual()
                            && !entry.get("reason").asText().isBlank()) {
                        entries.add(new Entry(operationPointer, operationPointer + "/" + EXTENSION + "/" + i,
                                entry.get("rule").asText()));
                    }
                }
            }
        }
        return entries;
    }
}
