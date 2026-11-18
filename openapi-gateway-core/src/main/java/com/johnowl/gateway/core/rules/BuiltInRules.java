package com.johnowl.gateway.core.rules;

import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The rule sets of the CLI and of the gateway. */
public final class BuiltInRules {

    private static final List<Rule> BASE = List.of(
            new OasParseRule(),
            new XGatewaySchemaRule(),
            new XGatewayUnknownExtensionRule(),
            new XGatewayResponseTimeoutRule(),
            new PathPatternSupportedRule(),
            new RouteCollisionRule(),
            new RouteIdUniqueRule(),
            new SecurityExplicitRule(),
            new SecurityNoScopesRule(),
            new SecurityNoEmptyRequirementRule(),
            new SecuritySchemeExistsRule(),
            new SecuritySchemeTypeRule(),
            new AuthorizationRequiredRule(),
            new AuthorizationSchemaRule(),
            new AuthorizationNotOnPublicRule(),
            new PermissionFormatRule(),
            new AudienceRequiredRule(),
            new AudienceUniqueRule(),
            new AudienceNotFirstPartyRule(),
            new OperationIdRequiredRule(),
            new OperationIdCamelCaseRule(),
            new PathKebabCaseRule(),
            new PathNoTrailingSlashRule(),
            new PathNoVerbsRule(),
            new OperationSuccessResponseRule(),
            new GetNoRequestBodyRule(),
            new Post201LocationHeaderRule(),
            new ComponentsUnusedRule(),
            new ErrorResponseProblemJsonRule(),
            new ServersHttpsOnlyRule(),
            new OperationSummaryRequiredRule(),
            new OperationTagsDefinedRule(),
            new SchemaExamplesPresentRule());

    private BuiltInRules() {
    }

    /** All built-in rules. The CLI runs these. */
    public static List<Rule> all() {
        return withSuppressionRules(BASE);
    }

    /** The CONTRACT rules. The gateway runs these at startup. */
    public static List<Rule> contract() {
        return all().stream().filter(rule -> rule.meta().category() == Category.CONTRACT).toList();
    }

    /** Adds the x-deviation rules. deviation-schema knows the ids of all built-in rules. */
    private static List<Rule> withSuppressionRules(List<Rule> base) {
        Map<String, RuleMeta> metas = new LinkedHashMap<>();
        base.forEach(rule -> metas.put(rule.meta().id(), rule.meta()));
        metas.put(DeviationSchemaRule.META.id(), DeviationSchemaRule.META);
        metas.put(DeviationUnusedRule.META.id(), DeviationUnusedRule.META);
        List<Rule> rules = new ArrayList<>(base);
        rules.add(new DeviationSchemaRule(metas));
        rules.add(new DeviationUnusedRule());
        return List.copyOf(rules);
    }
}
