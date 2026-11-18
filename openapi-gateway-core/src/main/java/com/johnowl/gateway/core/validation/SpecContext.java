package com.johnowl.gateway.core.validation;

import com.johnowl.gateway.core.spec.SpecFile;
import java.util.Objects;

/** One OAS file that a rule checks. */
public record SpecContext(SpecFile spec) {

    public SpecContext {
        Objects.requireNonNull(spec, "spec");
    }

    /** The location of the file, as violations show it. */
    public String file() {
        return spec.path();
    }
}
