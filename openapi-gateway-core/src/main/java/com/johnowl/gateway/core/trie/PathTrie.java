package com.johnowl.gateway.core.trie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps path patterns such as {@code /users/{id}} and HTTP methods to values. A lookup costs
 * O(number of path segments), not O(number of patterns).
 *
 * <p>Matching: at each segment, a literal child has priority over the variable child. If the
 * literal branch has no value for the path and the method, the search goes back and tries the
 * variable child. This gives the same result as the route order of the gateway: literal routes
 * first, and a {@code Method} predicate on each route.
 *
 * <p>Case variants: a segment that is not a literal child, but equals a literal child when the case is
 * ignored, never goes to the variable child. For example, {@code /orders/EXPORT} matches neither
 * {@code /orders/export} nor {@code /orders/{id}}. A backend that compares paths without case could
 * otherwise serve {@code /orders/export} under the access rule of {@code /orders/{id}}.
 *
 * <p>Thread safety: {@link #insert} is not thread-safe. Build the trie once, then publish it safely
 * (for example with a {@code volatile} field). After that, {@link #match} is safe for concurrent use.
 *
 * @param <T> the value type
 */
public final class PathTrie<T> {

    private final Node<T> root = new Node<>();

    /**
     * Adds a value for a path pattern and a set of uppercase HTTP methods.
     *
     * @throws IllegalArgumentException if a segment uses unsupported syntax, or if the pattern
     *     already has a value for one of the methods. Variable names do not count:
     *     {@code /users/{id}} and {@code /users/{userId}} are the same pattern. A failed insert
     *     changes nothing: it checks all segments and all methods before it creates a node or stores a value.
     */
    public void insert(String pattern, Set<String> methods, T value) {
        List<String> segments = segments(pattern);
        List<Boolean> variables = new ArrayList<>(segments.size());
        for (String segment : segments) {
            variables.add(isVariable(segment, pattern));
        }
        Node<T> existing = existingNode(root, segments, variables, 0);
        for (String method : methods) {
            if (existing != null && existing.valuesByMethod.containsKey(method)) {
                throw new IllegalArgumentException(
                        "Pattern '" + pattern + "' already has a value for " + method);
            }
        }
        Node<T> node = root;
        for (int i = 0; i < segments.size(); i++) {
            node = variables.get(i) ? node.variable() : node.literal(segments.get(i));
        }
        for (String method : methods) {
            node.valuesByMethod.put(method, value);
        }
    }

    /**
     * Returns the value for the path segments and the uppercase HTTP method, or empty.
     *
     * <p>A path with an empty, {@code .}, or {@code ..} segment never matches (fail closed). A variable in Spring
     * {@code PathPattern} does not match an empty segment, and a backend can resolve dot segments to another path.
     * A segment that equals a literal sibling when the case is ignored does not match the variable sibling.
     *
     * @param segments the output of {@link #segments(String)} for the request path, without the query
     */
    public Optional<T> match(List<String> segments, String method) {
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(find(root, segments, 0, method));
    }

    /**
     * Splits a path into segments. One trailing {@code /} is removed: {@code /users/} gives
     * {@code [users]}, and {@code /} gives {@code []}. Empty segments inside the path stay.
     */
    public static List<String> segments(String path) {
        String trimmed = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        List<String> segments = new ArrayList<>();
        int start = trimmed.startsWith("/") ? 1 : 0;
        if (start >= trimmed.length()) {
            return segments;
        }
        int slash;
        while ((slash = trimmed.indexOf('/', start)) >= 0) {
            segments.add(trimmed.substring(start, slash));
            start = slash + 1;
        }
        segments.add(trimmed.substring(start));
        return segments;
    }

    private static <T> T find(Node<T> node, List<String> segments, int index, String method) {
        if (index == segments.size()) {
            return node.valuesByMethod.get(method);
        }
        String segment = segments.get(index);
        Node<T> literal = node.literals.get(segment);
        if (literal != null) {
            T found = find(literal, segments, index + 1, method);
            if (found != null) {
                return found;
            }
        }
        if (node.variable == null) {
            return null;
        }
        // A case variant of a literal sibling is denied: a backend that ignores case serves the literal.
        if (literal == null && !node.foldedLiterals.isEmpty() && node.foldedLiterals.contains(fold(segment))) {
            return null;
        }
        return find(node.variable, segments, index + 1, method);
    }

    /**
     * The segment with each character folded to one case, as {@link String#equalsIgnoreCase} compares
     * characters. Also folds characters outside ASCII that a backend can read as ASCII, for example the
     * Kelvin sign to {@code k}.
     */
    private static String fold(String segment) {
        StringBuilder folded = new StringBuilder(segment.length());
        segment.codePoints().forEach(c -> folded.appendCodePoint(Character.toLowerCase(Character.toUpperCase(c))));
        return folded.toString();
    }

    /** The number of nodes, the root included. Package-private, for tests. */
    int nodeCount() {
        return root.count();
    }

    /** Returns the node of a pattern without creating nodes, or {@code null} if it does not exist. */
    private static <T> Node<T> existingNode(
            Node<T> node, List<String> segments, List<Boolean> variables, int index) {
        if (node == null || index == segments.size()) {
            return node;
        }
        Node<T> child = variables.get(index) ? node.variable : node.literals.get(segments.get(index));
        return existingNode(child, segments, variables, index + 1);
    }

    /** Returns true for {@code {name}}, false for a literal, and throws for unsupported syntax. */
    private static boolean isVariable(String segment, String pattern) {
        if (segment.contains("*")) {
            throw unsupported(segment, pattern);
        }
        boolean braces = segment.indexOf('{') >= 0 || segment.indexOf('}') >= 0;
        if (!braces) {
            return false;
        }
        String name = segment.length() > 2 && segment.startsWith("{") && segment.endsWith("}")
                ? segment.substring(1, segment.length() - 1)
                : null;
        if (name == null || name.indexOf('{') >= 0 || name.indexOf('}') >= 0 || name.indexOf(':') >= 0) {
            throw unsupported(segment, pattern);
        }
        return true;
    }

    private static IllegalArgumentException unsupported(String segment, String pattern) {
        return new IllegalArgumentException(
                "Segment '" + segment + "' of pattern '" + pattern + "' is not a literal or a whole variable");
    }

    private static final class Node<T> {

        private final Map<String, Node<T>> literals = new HashMap<>();
        /** The {@link #fold folded} keys of {@link #literals}. */
        private final Set<String> foldedLiterals = new HashSet<>();
        private final Map<String, T> valuesByMethod = new HashMap<>();
        private Node<T> variable;

        private Node<T> literal(String segment) {
            foldedLiterals.add(fold(segment));
            return literals.computeIfAbsent(segment, s -> new Node<>());
        }

        private Node<T> variable() {
            if (variable == null) {
                variable = new Node<>();
            }
            return variable;
        }

        private int count() {
            int count = 1 + (variable == null ? 0 : variable.count());
            for (Node<T> literal : literals.values()) {
                count += literal.count();
            }
            return count;
        }
    }
}
