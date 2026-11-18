package com.johnowl.gateway.core.config;

import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import java.util.Locale;

/** A built-in profile: the base severity of each configurable rule. */
public enum Profile {

    /** The default severity of each rule. */
    RECOMMENDED,

    /** WARN becomes ERROR, INFO becomes WARN, ERROR stays ERROR. */
    STRICT;

    public Severity severity(RuleMeta rule) {
        if (this == RECOMMENDED) {
            return rule.defaultSeverity();
        }
        return switch (rule.defaultSeverity()) {
            case INFO -> Severity.WARN;
            case WARN, ERROR -> Severity.ERROR;
        };
    }

    /** The name in the configuration file, for example {@code recommended}. */
    public String fileName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
