package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.suppress.DeviationSuppressions;
import com.johnowl.gateway.core.validation.BaselinePolicy;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.SeverityPolicy;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.Violation;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The rules for audiences of spec 018: audience-unique and audience-not-first-party. */
class AudienceRulesTest {

    private static final String ORDERS = "6f1c2e8a-0000-0000-0000-000000000000";
    private static final String GRAPH = "00000003-0000-0000-c000-000000000000";

    @Test
    void repeatedAudienceIsReportedInEachFileAndNamesTheOtherFile() {
        List<Violation> violations = run(new AudienceUniqueRule(),
                spec("orders.yaml", ORDERS), spec("payments.yaml", ORDERS));

        assertThat(violations).extracting(Violation::file, Violation::pointer, Violation::message).containsExactly(
                tuple("orders.yaml", "/x-gateway/audience", "Audience '" + ORDERS + "' is also declared in"
                        + " payments.yaml. An audience can appear in one OAS file only."),
                tuple("payments.yaml", "/x-gateway/audience", "Audience '" + ORDERS + "' is also declared in"
                        + " orders.yaml. An audience can appear in one OAS file only."));
    }

    @Test
    void threeFilesWithTheSameAudienceCountTheOtherFiles() {
        List<Violation> violations = run(new AudienceUniqueRule(),
                spec("a.yaml", ORDERS), spec("b.yaml", ORDERS), spec("c.yaml", ORDERS));

        assertThat(violations).extracting(Violation::message).containsExactly(
                "Audience '" + ORDERS + "' is also declared in b.yaml and in 1 more file(s). An audience can appear"
                        + " in one OAS file only.",
                "Audience '" + ORDERS + "' is also declared in a.yaml and in 1 more file(s). An audience can appear"
                        + " in one OAS file only.",
                "Audience '" + ORDERS + "' is also declared in a.yaml and in 1 more file(s). An audience can appear"
                        + " in one OAS file only.");
    }

    @Test
    void differentAudiencesAndFilesWithoutAudienceAreValid() {
        assertThat(run(new AudienceUniqueRule(),
                spec("orders.yaml", ORDERS), spec("payments.yaml", "0b7d3f10-0000-0000-0000-000000000000"),
                spec("health.yaml", null), spec("status.yaml", null))).isEmpty();
    }

    @Test
    void fileWithoutValidXGatewayIsSkipped() {
        // Only a textual audience counts: an array, a number, or an object is not an audience.
        SpecSource array = spec("array.yaml", "[" + ORDERS + "]");
        SpecSource otherArray = spec("other-array.yaml", "[" + ORDERS + "]");
        SpecSource number = spec("number.yaml", "42");
        SpecSource otherNumber = spec("other-number.yaml", "42");
        SpecSource object = spec("object.yaml", "{ id: " + ORDERS + " }");
        SpecSource notAnObject = new SpecSource("text.yaml", """
                openapi: 3.0.3
                info: { title: text, version: 1.0.0 }
                x-gateway: %s
                paths:
                  /items:
                    get:
                      security: []
                      responses: { "200": { description: OK } }
                """.formatted(ORDERS));

        assertThat(run(new AudienceUniqueRule(), spec("orders.yaml", ORDERS), array, otherArray, number, otherNumber,
                object, notAnObject)).isEmpty();
    }

    @Test
    void fileWithInvalidUpstreamStillCountsItsAudience() {
        // x-gateway-schema reports the upstream. The audience is still a textual value, so the rule compares it.
        SpecSource broken = new SpecSource("broken.yaml", spec("broken.yaml", ORDERS).content()
                .replace("upstream: http://service:8080", "upstream: not a uri"));

        assertThat(run(new AudienceUniqueRule(), spec("orders.yaml", ORDERS), broken))
                .extracting(Violation::file).containsExactly("broken.yaml", "orders.yaml");
    }

    @Test
    void microsoftGraphIsNotAnAudience() {
        List<Violation> violations = run(new AudienceNotFirstPartyRule(), spec("graph.yaml", GRAPH));

        assertThat(violations).extracting(Violation::pointer, Violation::message).containsExactly(tuple(
                "/x-gateway/audience", "Audience '" + GRAPH + "' is the Microsoft first-party app 'Microsoft Graph'."
                        + " The gateway does not request tokens for Microsoft APIs."));
    }

    @Test
    void eachListedFirstPartyAppIsNotAnAudience() {
        assertThat(FirstPartyApps.all()).hasSize(7);
        FirstPartyApps.all().forEach((appId, name) -> {
            String message = "Audience '" + appId + "' is the Microsoft first-party app '" + name + "'."
                    + " The gateway does not request tokens for Microsoft APIs.";
            assertThat(run(new AudienceNotFirstPartyRule(), spec("api.yaml", appId)))
                    .extracting(Violation::ruleId, Violation::pointer, Violation::message)
                    .containsExactly(tuple("audience-not-first-party", "/x-gateway/audience", message));
            assertThat(FirstPartyApps.name(appId.toUpperCase(Locale.ROOT))).contains(name);
        });
    }

    @Test
    void backendAudienceAndMissingAudienceAreNotFirstParty() {
        assertThat(run(new AudienceNotFirstPartyRule(), spec("orders.yaml", ORDERS), spec("health.yaml", null)))
                .isEmpty();
    }

    @Test
    void bothRulesAreContractRules() {
        assertThat(BuiltInRules.contract()).extracting(rule -> rule.meta().id())
                .contains("audience-unique", "audience-not-first-party");
    }

    @Test
    void deviationDoesNotHideTheAudienceRules() {
        String deviation = """
                      x-deviation:
                        - { rule: audience-unique, reason: Two files for one backend. }
                        - { rule: audience-not-first-party, reason: We need Graph. }
                """;
        SpecSource a = new SpecSource("a.yaml", spec("a.yaml", GRAPH).content().replace("      security: []\n",
                "      security: []\n" + deviation));
        SpecSource b = spec("b.yaml", GRAPH);
        List<Rule> rules = BuiltInRules.all();

        List<Violation> violations = new ValidationEngine(rules, SeverityPolicy.defaults(),
                new DeviationSuppressions(rules.stream().map(Rule::meta).toList()), BaselinePolicy.empty())
                .validate(List.of(a, b)).violations();

        assertThat(violations).extracting(Violation::ruleId, Violation::file).contains(
                tuple("audience-unique", "a.yaml"), tuple("audience-unique", "b.yaml"),
                tuple("audience-not-first-party", "a.yaml"), tuple("audience-not-first-party", "b.yaml"));
        assertThat(violations).filteredOn(violation -> violation.ruleId().equals("deviation-schema"))
                .extracting(Violation::file, Violation::pointer, Violation::message).containsExactly(
                        tuple("a.yaml", "/paths/~1items/get/x-deviation/0/rule",
                                "Rule 'audience-unique' cannot have a deviation, because it is a CONTRACT rule."),
                        tuple("a.yaml", "/paths/~1items/get/x-deviation/1/rule",
                                "Rule 'audience-not-first-party' cannot have a deviation, because it is a CONTRACT rule."));
    }

    @Test
    void severityOfTheAudienceRulesIsNotConfigurable() {
        assertThat(List.of(new AudienceUniqueRule(), new AudienceNotFirstPartyRule()))
                .extracting(rule -> rule.meta().id(), rule -> rule.meta().configurable())
                .containsExactly(tuple("audience-unique", false), tuple("audience-not-first-party", false));
    }

    private static List<Violation> run(Rule rule, SpecSource... sources) {
        return new ValidationEngine(List.of(rule)).validate(Arrays.asList(sources)).violations();
    }

    /** A file with one public operation, and the audience if it is not {@code null}. */
    private static SpecSource spec(String file, String audience) {
        String name = file.replace(".yaml", "");
        return new SpecSource(file, """
                openapi: 3.0.3
                info: { title: %s, version: 1.0.0 }
                x-gateway:
                  upstream: http://service:8080
                  prefix: /%s
                %s
                paths:
                  /items:
                    get:
                      security: []
                      responses: { "200": { description: OK } }
                """.formatted(name, name, audience == null ? "" : "  audience: " + audience));
    }
}
