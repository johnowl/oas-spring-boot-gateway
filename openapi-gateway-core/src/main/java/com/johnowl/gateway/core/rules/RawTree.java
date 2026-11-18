package com.johnowl.gateway.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.johnowl.gateway.core.validation.JsonPointers;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Walks a raw OAS tree (spec 030). Iterative, so that a deep document cannot overflow the stack. */
final class RawTree {

    private RawTree() {
    }

    /** Receives each node of the tree. */
    @FunctionalInterface
    interface Visitor {

        /**
         * @param pointer the RFC 6901 pointer of the node, for example {@code /paths/~1orders/get}
         * @param name the field name of the node, or {@code null} for the root and for array elements
         */
        void visit(String pointer, String name, JsonNode node);
    }

    private record Item(String pointer, String name, JsonNode node) {
    }

    /** Visits the root and each node below it, in document order. */
    static void walk(JsonNode root, String pointer, Visitor visitor) {
        Deque<Item> pending = new ArrayDeque<>();
        pending.push(new Item(pointer, null, root));
        while (!pending.isEmpty()) {
            Item item = pending.pop();
            visitor.visit(item.pointer(), item.name(), item.node());
            List<Item> children = new ArrayList<>();
            if (item.node().isArray()) {
                for (int i = 0; i < item.node().size(); i++) {
                    children.add(new Item(item.pointer() + "/" + i, null, item.node().get(i)));
                }
            } else if (item.node().isObject()) {
                for (Map.Entry<String, JsonNode> field : item.node().properties()) {
                    children.add(new Item(item.pointer() + "/" + JsonPointers.escape(field.getKey()),
                            field.getKey(), field.getValue()));
                }
            }
            for (int i = children.size() - 1; i >= 0; i--) {
                pending.push(children.get(i));
            }
        }
    }
}
