package com.johnowl.gateway.core.spec;

import com.johnowl.gateway.core.validation.Violation;
import java.util.List;
import java.util.Optional;

/**
 * The result of loading one OAS file. The spec is present only if there are no violations.
 */
public record SpecLoadResult(Optional<SpecFile> spec, List<Violation> violations) {

    public SpecLoadResult {
        violations = List.copyOf(violations);
    }

    static SpecLoadResult loaded(SpecFile spec) {
        return new SpecLoadResult(Optional.of(spec), List.of());
    }

    static SpecLoadResult failed(List<Violation> violations) {
        return new SpecLoadResult(Optional.empty(), violations);
    }
}
