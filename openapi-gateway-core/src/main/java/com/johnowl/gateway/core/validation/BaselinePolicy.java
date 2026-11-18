package com.johnowl.gateway.core.validation;

/**
 * Removes known violations of configurable rules. The engine does not call the policy for rules
 * with {@code configurable=false}.
 */
@FunctionalInterface
public interface BaselinePolicy {

    boolean isKnown(Violation violation);

    /** Knows no violation. */
    static BaselinePolicy empty() {
        return violation -> false;
    }
}
