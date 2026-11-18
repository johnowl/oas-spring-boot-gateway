package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;

/**
 * Metadata of the {@code oas-parse} violations. {@link SpecLoader} creates these violations; this
 * rule never reports. It exists so that the rule sets, the fixture harness and the CLI know it.
 */
public final class OasParseRule implements SpecRule {

    private static final RuleMeta META = new RuleMeta(
            SpecLoader.RULE_ID,
            Category.CONTRACT,
            Severity.ERROR,
            false,
            "The file is valid YAML or JSON, is OpenAPI 3.0 or 3.1, and the OpenAPI parser reads it"
                    + " without messages.",
            "The gateway cannot build routes from a file that it cannot parse.");

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public void check(SpecContext context, Reporter reporter) {
        // A file with a parse problem never reaches the rules. See SpecLoader.
    }
}
