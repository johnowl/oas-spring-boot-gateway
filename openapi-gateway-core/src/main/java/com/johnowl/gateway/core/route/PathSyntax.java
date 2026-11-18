package com.johnowl.gateway.core.route;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks that an OAS path uses only the syntax that the gateway supports: literal segments and
 * whole-segment variables such as {@code /users/{id}}. This syntax is a subset of the Spring
 * {@code PathPattern} syntax, with the same meaning.
 *
 * <p>A literal segment contains only RFC 3986 unreserved characters {@code [A-Za-z0-9._~-]}, and is not
 * {@code .} or {@code ..}. Other characters have a different meaning in Spring Cloud Gateway or in
 * {@code PathPattern}: {@code ,} splits the {@code Path} predicate, {@code ?} matches any character,
 * {@code %} and {@code ;} are decoded or removed before matching.
 */
public final class PathSyntax {

    /** The variable names that PathPattern accepts, limited to ASCII. */
    private static final Pattern VARIABLE = Pattern.compile("^\\{[A-Za-z_][A-Za-z0-9_-]*}$");
    private static final Pattern ANY_WHOLE_VARIABLE = Pattern.compile("^\\{[^{}]*}$");
    private static final Pattern REGEX_VARIABLE = Pattern.compile("\\{[^}]*:[^}]*}");
    private static final Pattern PARTIAL_VARIABLE = Pattern.compile("\\{[^{}/]+}");
    private static final Pattern LITERAL = Pattern.compile("^[A-Za-z0-9._~-]+$");

    private PathSyntax() {
    }

    /** Returns the problem of the path, or empty if the gateway supports the path. */
    public static Optional<String> problem(String path) {
        if (!path.startsWith("/")) {
            return problem(path, "must start with '/'");
        }
        if (path.equals("/")) {
            return Optional.empty();
        }
        Set<String> variables = new HashSet<>();
        for (String segment : path.substring(1).split("/", -1)) {
            Optional<String> problem = segmentProblem(path, segment);
            if (problem.isPresent()) {
                return problem;
            }
            if (segment.startsWith("{") && !variables.add(segment.substring(1, segment.length() - 1))) {
                return problem(path, "uses the variable name '" + segment.substring(1, segment.length() - 1)
                        + "' twice");
            }
        }
        return Optional.empty();
    }

    /** Replaces each variable with {@code {}}: {@code /users/{id}} becomes {@code /users/{}}. */
    public static String normalize(String path) {
        return PARTIAL_VARIABLE.matcher(path).replaceAll("{}");
    }

    private static Optional<String> segmentProblem(String path, String segment) {
        if (segment.isEmpty()) {
            return problem(path, "has an empty segment. Remove the trailing or double slash");
        }
        if (segment.contains("**")) {
            return unsupported(path, "**");
        }
        if (segment.contains("{*")) {
            return unsupported(path, "{*...}");
        }
        if (REGEX_VARIABLE.matcher(segment).find()) {
            return unsupported(path, "{...:regex}");
        }
        if (segment.contains("*")) {
            return unsupported(path, "*");
        }
        if (segment.contains("{") || segment.contains("}")) {
            if (VARIABLE.matcher(segment).matches()) {
                return Optional.empty();
            }
            if (ANY_WHOLE_VARIABLE.matcher(segment).matches()) {
                return problem(path, "has an invalid variable name '" + segment.substring(1, segment.length() - 1)
                        + "'. Use letters, digits, '_' and '-', and do not start with a digit");
            }
            if (PARTIAL_VARIABLE.matcher(segment).replaceAll("").matches("[^{}]*")) {
                return problem(path, "uses unsupported syntax: a variable must be a whole segment");
            }
            return problem(path, "cannot be parsed: unbalanced '{' or '}'");
        }
        if (segment.equals(".") || segment.equals("..")) {
            return problem(path, "has the dot segment '" + segment + "'");
        }
        if (!LITERAL.matcher(segment).matches()) {
            return problem(path, "has the unsupported character '" + firstUnsupported(segment)
                    + "'. Literal segments use only letters, digits, '-', '.', '_' and '~'");
        }
        return Optional.empty();
    }

    /** The first character that is not unreserved, as Java escape {@code \\uXXXX} if it is not printable ASCII. */
    private static String firstUnsupported(String segment) {
        int c = segment.codePoints().filter(cp -> !LITERAL.matcher(Character.toString(cp)).matches())
                .findFirst().orElseThrow();
        return c > 0x20 && c < 0x7f ? Character.toString(c) : String.format("\\u%04X", c);
    }

    private static Optional<String> unsupported(String path, String syntax) {
        return Optional.of("Path '" + path + "' uses unsupported syntax '" + syntax + "'.");
    }

    private static Optional<String> problem(String path, String reason) {
        return Optional.of("Path '" + path + "' " + reason + ".");
    }
}
