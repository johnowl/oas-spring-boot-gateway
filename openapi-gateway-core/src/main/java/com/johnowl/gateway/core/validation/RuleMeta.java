package com.johnowl.gateway.core.validation;

import java.util.Objects;

/**
 * The description of a rule.
 *
 * @param id kebab-case id, unique in a rule set, for example {@code x-gateway-schema}
 * @param category the kind of problem
 * @param defaultSeverity the severity when no configuration changes it
 * @param configurable {@code false} if no configuration, suppression or baseline can change the
 *     result of the rule; always {@code false} for {@link Category#CONTRACT}
 * @param description what the rule checks
 * @param rationale why the rule exists
 */
public record RuleMeta(
        String id,
        Category category,
        Severity defaultSeverity,
        boolean configurable,
        String description,
        String rationale) {

    public RuleMeta {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(defaultSeverity, "defaultSeverity");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(rationale, "rationale");
        if (category == Category.CONTRACT && configurable) {
            throw new IllegalArgumentException("CONTRACT rule '" + id + "' must not be configurable");
        }
        if (category == Category.CONTRACT && defaultSeverity != Severity.ERROR) {
            throw new IllegalArgumentException("CONTRACT rule '" + id + "' must have severity ERROR");
        }
    }
}
