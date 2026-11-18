package com.johnowl.gateway.core.suppress;

import java.util.List;

/** The baseline file cannot be used. */
public class BaselineException extends RuntimeException {

    private final List<String> problems;

    public BaselineException(String file, List<String> problems) {
        super(file + ": " + String.join("\n" + file + ": ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
