package com.johnowl.gateway.core.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The validated options of one rule. */
public final class RuleOptions {

    private static final RuleOptions NONE = new RuleOptions(Map.of());

    private final Map<String, Object> values;

    RuleOptions(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    public static RuleOptions none() {
        return NONE;
    }

    /** The value of a {@link OptionType#STRING_LIST} option, or empty if the file does not set it. */
    @SuppressWarnings("unchecked")
    public Optional<List<String>> stringList(String name) {
        return Optional.ofNullable((List<String>) values.get(name));
    }
}
