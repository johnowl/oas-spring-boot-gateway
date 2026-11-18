package com.johnowl.gateway.core.validation;

/** Receives the problems that a rule finds in one file. */
@FunctionalInterface
public interface Reporter {

    /**
     * @param pointer the JSON pointer (RFC 6901) of the problem; empty for the document root
     * @param message the description of the problem
     */
    void report(String pointer, String message);

    /** Receives the problems that a {@link CrossSpecRule} finds. Each problem names its file. */
    @FunctionalInterface
    interface ForFile {

        void report(String file, String pointer, String message);
    }
}
