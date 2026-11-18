package com.johnowl.gateway.core.validation;

import com.johnowl.gateway.core.spec.SpecFile;
import java.util.List;

/**
 * Removes accepted violations of configurable rules. The engine does not call the policy for
 * rules with {@code configurable=false}.
 */
@FunctionalInterface
public interface SuppressionPolicy {

    /**
     * @param violation the violation, with its effective severity
     * @param spec the file of the violation
     */
    boolean isSuppressed(Violation violation, SpecFile spec);

    /**
     * Returns the suppressions that covered no violation. The engine calls this after all rules.
     * The default has no suppressions.
     */
    default List<UnusedSuppression> unused(List<SpecFile> specs) {
        return List.of();
    }

    /** Suppresses nothing. */
    static SuppressionPolicy none() {
        return (violation, spec) -> false;
    }
}
