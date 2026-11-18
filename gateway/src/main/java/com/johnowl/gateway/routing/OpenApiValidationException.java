package com.johnowl.gateway.routing;

import com.johnowl.gateway.core.text.PrintableText;
import com.johnowl.gateway.core.validation.Violation;
import java.util.List;
import java.util.stream.Collectors;

/** The OAS files have ERROR violations, so the gateway does not start. */
public class OpenApiValidationException extends RuntimeException {

    private final List<Violation> violations;

    public OpenApiValidationException(List<Violation> violations) {
        super("The OAS files have " + violations.size() + " ERROR violation(s):\n" + violations.stream()
                .map(v -> "  " + line(v))
                .collect(Collectors.joining("\n")));
        this.violations = List.copyOf(violations);
    }

    /**
     * One log line for a violation. The file name, the pointer, and the message come from the OAS files, so each
     * unsafe character becomes a Java escape and cannot start a new log line (SEC-67-3).
     */
    static String line(Violation violation) {
        return PrintableText.escape(violation.ruleId() + " " + violation.file() + "#" + violation.pointer() + " "
                + violation.message());
    }

    public List<Violation> violations() {
        return violations;
    }
}
