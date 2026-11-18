package com.johnowl.gateway.core.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The bad and the good example of a rule, from the classpath resources {@code rule-examples/<rule-id>/bad.yaml} and
 * {@code rule-examples/<rule-id>/good.yaml}. An example is a short excerpt of an OAS file, not a complete spec.
 * See specs/032-cli-rules-and-explain.md.
 */
public final class RuleExamples {

    /** The resource directory of the examples. */
    public static final String ROOT = "rule-examples";

    /** A rule id is kebab-case, so it can never leave the resource directory. */
    private static final Pattern RULE_ID = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /**
     * @param bad an excerpt that the rule reports
     * @param good the same excerpt, corrected
     */
    public record Examples(String bad, String good) {
    }

    private RuleExamples() {
    }

    /**
     * @throws IllegalArgumentException if the rule id is not kebab-case
     * @throws IllegalStateException if a resource is missing, for example
     *     {@code Rule 'x' has no example rule-examples/x/bad.yaml}
     */
    public static Examples of(String ruleId) {
        return of(ruleId, ROOT);
    }

    static Examples of(String ruleId, String root) {
        if (!RULE_ID.matcher(ruleId).matches()) {
            throw new IllegalArgumentException("Rule id is not kebab-case: " + ruleId);
        }
        return new Examples(read(ruleId, root + "/" + ruleId + "/bad.yaml"),
                read(ruleId, root + "/" + ruleId + "/good.yaml"));
    }

    private static String read(String ruleId, String resource) {
        try (InputStream in = RuleExamples.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Rule '" + ruleId + "' has no example " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }
}
