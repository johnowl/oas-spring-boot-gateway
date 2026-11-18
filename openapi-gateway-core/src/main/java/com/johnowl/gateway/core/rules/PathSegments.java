package com.johnowl.gateway.core.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Splits OAS paths into literal segments, and paths and segments into words (specs 029 and 030). */
final class PathSegments {

    private static final Pattern VARIABLE = Pattern.compile("\\{[^}]*}");

    private PathSegments() {
    }

    /**
     * The literal segments of an OAS path: the segments, split on {@code /}, that are not empty and do not
     * contain a variable. Example: {@code /orders/{id}/items} gives {@code orders} and {@code items}.
     */
    static List<String> literal(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty() && !segment.contains("{")) {
                segments.add(segment);
            }
        }
        return segments;
    }

    /**
     * The words of an OAS path outside its variables. Each {@code {...}} part is removed, also inside a segment,
     * and the rest is split as in {@link #words(String)}. Example: {@code /orders/{id}:delete/getAll.{format}}
     * gives {@code orders}, {@code delete}, {@code get}, and {@code All}.
     */
    static List<String> wordsOutsideVariables(String path) {
        return words(VARIABLE.matcher(path).replaceAll("/"));
    }

    /**
     * The words of a segment. The split is on each character that is not a letter or a digit, for example
     * {@code -}, {@code _}, {@code .}, and {@code :}, and on each change from a lowercase letter to an uppercase
     * letter. Example: {@code createOrder_v2.json} gives {@code create}, {@code Order}, {@code v2}, and
     * {@code json}.
     */
    static List<String> words(String segment) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                add(words, word);
            } else {
                if (Character.isLowerCase(previous) && Character.isUpperCase(c)) {
                    add(words, word);
                }
                word.append(c);
            }
            previous = c;
        }
        add(words, word);
        return words;
    }

    private static void add(List<String> words, StringBuilder word) {
        if (!word.isEmpty()) {
            words.add(word.toString());
            word.setLength(0);
        }
    }
}
