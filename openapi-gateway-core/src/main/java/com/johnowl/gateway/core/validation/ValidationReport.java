package com.johnowl.gateway.core.validation;

import com.johnowl.gateway.core.access.OperationAccess;
import com.johnowl.gateway.core.route.GatewayRoute;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The result of one validation run.
 *
 * @param violations sorted by file, pointer and rule id
 * @param routes the routes of all files, sorted by path; empty if there is an ERROR violation
 * @param accessRules the access rule of each operation of all files; empty if there is an ERROR violation
 */
public record ValidationReport(List<Violation> violations, List<GatewayRoute> routes, List<OperationAccess> accessRules) {

    public ValidationReport {
        violations = List.copyOf(violations);
        routes = List.copyOf(routes);
        accessRules = List.copyOf(accessRules);
    }

    /** A report without routes and access rules. */
    public ValidationReport(List<Violation> violations) {
        this(violations, List.of(), List.of());
    }

    /** Returns {@code true} if at least one violation has this severity or a higher one. */
    public boolean hasAtLeast(Severity severity) {
        return violations.stream().anyMatch(v -> v.severity().compareTo(severity) >= 0);
    }

    /** Returns the number of violations for each severity, including severities with zero. */
    public Map<Severity, Long> summary() {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0L);
        }
        violations.forEach(v -> counts.merge(v.severity(), 1L, Long::sum));
        return counts;
    }
}
