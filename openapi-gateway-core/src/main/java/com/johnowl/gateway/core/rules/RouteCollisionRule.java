package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.route.PathSyntax;
import com.johnowl.gateway.core.route.XGatewaySettings;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.CrossSpecRule;
import com.johnowl.gateway.core.validation.JsonPointers;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * No request can match two operations. Two operations with the same method collide when they have
 * the same normalized public path, or when they overlap: at each segment, the literals are equal or
 * one of the two segments is a variable. One exception: in the same file, a path without variables
 * may specialize a template ({@code /users/me} and {@code /users/{id}}). That path has route order 0,
 * so it wins in Spring Cloud Gateway and in the path trie. Two templates that overlap always collide,
 * because both have route order 100.
 */
public final class RouteCollisionRule implements CrossSpecRule {

    private static final String VARIABLE = "{}";

    private static final RuleMeta META = new RuleMeta(
            "route-collision",
            Category.CONTRACT,
            Severity.ERROR,
            false,
            "No request can match two operations with the same method, in the same file or in different"
                    + " files. Variable names do not count: /a/{id} and /a/{key} collide, and /a/{x}/c overlaps"
                    + " /a/b/{y}. In one file, a path without variables may specialize a template: /users/me and"
                    + " /users/{id}. Two templates that overlap always collide.",
            "Only one route can win. The result depends on the load order of the files, and the security"
                    + " index and the router can choose different operations.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(List<SpecContext> specs, Reporter.ForFile reporter) {
        Map<String, List<Declaration>> byMethodAndLength = new LinkedHashMap<>();
        for (SpecContext spec : specs) {
            XGatewaySettings xGateway = XGatewaySettings.of(spec.spec()).orElse(null);
            if (xGateway == null || spec.spec().openApi().getPaths() == null) {
                continue;
            }
            spec.spec().openApi().getPaths().forEach((path, item) -> {
                if (PathSyntax.problem(path).isPresent()) {
                    return;
                }
                String publicPath = PathSyntax.normalize(xGateway.publicPath(path));
                List<String> segments = segments(publicPath);
                item.readOperationsMap().keySet().forEach(method -> {
                    String pointer = "/paths/" + JsonPointers.escape(path) + "/" + method.name().toLowerCase(Locale.ROOT);
                    byMethodAndLength.computeIfAbsent(method.name() + " " + segments.size(), k -> new ArrayList<>())
                            .add(new Declaration(spec.file(), pointer, method.name(), publicPath, segments));
                });
            });
        }
        byMethodAndLength.values().forEach(declarations -> report(declarations, reporter));
    }

    /**
     * Compares each operation only with operations that can share its first segment: the same
     * literal (usually the prefix of the file), or a variable. This keeps the rule fast for many
     * files with different prefixes.
     */
    private static void report(List<Declaration> declarations, Reporter.ForFile reporter) {
        Map<String, List<Declaration>> byFirstSegment = new LinkedHashMap<>();
        for (Declaration declaration : declarations) {
            byFirstSegment.computeIfAbsent(firstSegment(declaration), k -> new ArrayList<>()).add(declaration);
        }
        List<Declaration> variableFirst = byFirstSegment.getOrDefault(VARIABLE, List.of());
        for (Declaration declaration : declarations) {
            String segment = firstSegment(declaration);
            List<Declaration> candidates = segment.equals(VARIABLE)
                    ? declarations
                    : concat(byFirstSegment.get(segment), variableFirst);
            List<Declaration> conflicts = new ArrayList<>();
            for (Declaration other : candidates) {
                if (other != declaration && conflict(declaration, other)) {
                    conflicts.add(other);
                }
            }
            if (conflicts.isEmpty()) {
                continue;
            }
            List<Declaration> duplicates = new ArrayList<>();
            List<Declaration> overlaps = new ArrayList<>();
            for (Declaration other : conflicts) {
                (other.publicPath().equals(declaration.publicPath()) ? duplicates : overlaps).add(other);
            }
            String key = declaration.method() + " " + declaration.publicPath();
            String message;
            if (!duplicates.isEmpty()) {
                message = key + " is also declared in " + where(duplicates) + "."
                        + (overlaps.isEmpty() ? "" : " It also overlaps " + overlaps.size() + " other operation(s).");
            } else {
                Declaration first = overlaps.getFirst();
                message = key + " overlaps " + first.method() + " " + first.publicPath() + " in " + where(overlaps)
                        + ". Both match " + example(declaration, first) + ".";
            }
            reporter.report(declaration.file(), declaration.pointer(), message);
        }
    }

    /** The file and pointer of the first declaration, and the number of the other declarations. */
    private static String where(List<Declaration> declarations) {
        Declaration first = declarations.getFirst();
        int more = declarations.size() - 1;
        return first.file() + " at " + first.pointer() + (more > 0 ? " and in " + more + " more place(s)" : "");
    }

    private static String firstSegment(Declaration declaration) {
        return declaration.segments().isEmpty() ? "" : declaration.segments().getFirst();
    }

    private static List<Declaration> concat(List<Declaration> a, List<Declaration> b) {
        if (b.isEmpty() || a == b) {
            return a;
        }
        List<Declaration> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /**
     * Both match at least one request, and it is not a path without variables (route order 0) that
     * specializes a template (route order 100) in one file. Two templates have the same route order,
     * so Spring Cloud Gateway and the path trie can select different operations.
     */
    private static boolean conflict(Declaration a, Declaration b) {
        for (int i = 0; i < a.segments().size(); i++) {
            String x = a.segments().get(i);
            String y = b.segments().get(i);
            if (!x.equals(VARIABLE) && !y.equals(VARIABLE) && !x.equals(y)) {
                return false;
            }
        }
        boolean literalSpecialization = isLiteral(a) != isLiteral(b);
        return !(literalSpecialization && a.file().equals(b.file()));
    }

    private static boolean isLiteral(Declaration declaration) {
        return !declaration.segments().contains(VARIABLE);
    }

    /** A request path that both operations match, for example {@code /a/b/c}. */
    private static String example(Declaration a, Declaration b) {
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < a.segments().size(); i++) {
            String x = a.segments().get(i);
            String segment = !x.equals(VARIABLE) ? x : !b.segments().get(i).equals(VARIABLE) ? b.segments().get(i) : "x";
            path.append('/').append(segment);
        }
        return path.isEmpty() ? "/" : path.toString();
    }

    private static List<String> segments(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private record Declaration(String file, String pointer, String method, String publicPath, List<String> segments) {
    }
}
