package com.johnowl.gateway.cli;

/** Exit codes of the CLI. */
final class ExitCodes {

    /** No violation at or above the {@code --fail-on} severity. */
    static final int OK = 0;

    /** At least one violation at or above the {@code --fail-on} severity. */
    static final int VIOLATIONS = 1;

    /** Usage error, I/O error, or unexpected error. */
    static final int ERROR = 2;

    private ExitCodes() {
    }
}
