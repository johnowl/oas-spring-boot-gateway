package com.johnowl.gateway.cli;

import com.johnowl.gateway.core.validation.Severity;

/** The lowest severity that makes the CLI return exit code 1. */
enum FailOn {
    INFO(Severity.INFO),
    WARN(Severity.WARN),
    ERROR(Severity.ERROR);

    private final Severity severity;

    FailOn(Severity severity) {
        this.severity = severity;
    }

    Severity severity() {
        return severity;
    }
}
