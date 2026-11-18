package com.johnowl.gateway.core.config;

import java.util.List;

/** The configuration file is not valid. Each problem names its location, for example {@code rules.x}. */
public class RuleConfigException extends RuntimeException {

    private final String file;
    private final List<String> problems;

    public RuleConfigException(String file, List<String> problems) {
        super(file + ": " + String.join("\n" + file + ": ", problems));
        this.file = file;
        this.problems = List.copyOf(problems);
    }

    public String file() {
        return file;
    }

    public List<String> problems() {
        return problems;
    }
}
