package com.johnowl.gateway.core.validation;

import java.util.Objects;

/**
 * One problem that a rule finds in an OAS file.
 *
 * @param severity the severity after the rule configuration is applied
 * @param ruleId the id of the rule, for example {@code oas-parse}
 * @param file the location of the OAS file, as the caller gave it
 * @param pointer the JSON pointer of the problem; empty for the document root
 * @param message the description of the problem
 */
public record Violation(Severity severity, String ruleId, String file, String pointer, String message) {

    public Violation {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(message, "message");
    }
}
