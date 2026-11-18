package com.johnowl.gateway.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.spec.SpecSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ValidationEngineTest {

    private static final SpecSource USERS = new SpecSource("users.yaml", """
            openapi: 3.0.3
            info: { title: Users, version: 1.0.0 }
            paths:
              /users/{id}:
                parameters:
                  - { name: id, in: path, required: true, schema: { type: string } }
                get:
                  responses: { "200": { description: OK } }
                delete:
                  responses: { "204": { description: Deleted } }
              /users:
                post:
                  responses: { "201": { description: Created } }
            """);

    private static final SpecSource ORDERS = new SpecSource("orders.yaml", """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            paths:
              /orders:
                get:
                  responses: { "200": { description: OK } }
            """);

    private static final SpecSource BROKEN = new SpecSource("broken.yaml", "swagger: '2.0'");

    @Test
    void callsEachRuleKindTheExpectedNumberOfTimes() {
        AtomicInteger specCalls = new AtomicInteger();
        AtomicInteger operationCalls = new AtomicInteger();
        AtomicInteger crossCalls = new AtomicInteger();
        List<Rule> rules = List.of(
                specRule("spec-rule", Category.DESIGN, (c, r) -> specCalls.incrementAndGet()),
                operationRule("operation-rule", Category.DESIGN, (c, r) -> operationCalls.incrementAndGet()),
                crossRule("cross-rule", (specs, r) -> crossCalls.incrementAndGet()));

        new ValidationEngine(rules).validate(List.of(USERS, ORDERS));

        assertThat(specCalls).hasValue(2);
        assertThat(operationCalls).hasValue(4);
        assertThat(crossCalls).hasValue(1);
    }

    @Test
    void operationContextHasEscapedPointer() {
        List<String> pointers = new ArrayList<>();

        new ValidationEngine(List.of(operationRule("operation-rule", Category.DESIGN,
                (c, r) -> pointers.add(c.method() + " " + c.path() + " " + c.pointer()))))
                .validate(List.of(USERS));

        assertThat(pointers).contains("GET /users/{id} /paths/~1users~1{id}/get");
        assertThat(JsonPointers.escape("/a~b")).isEqualTo("~1a~0b");
    }

    @Test
    void violationHasRuleIdFilePointerMessageAndSeverity() {
        ValidationReport report = new ValidationEngine(List.of(
                specRule("spec-rule", Category.DESIGN, Severity.WARN, true,
                        (c, r) -> r.report("/info", "Bad info."))))
                .validate(List.of(USERS));

        assertThat(report.violations())
                .containsExactly(new Violation(Severity.WARN, "spec-rule", "users.yaml", "/info", "Bad info."));
    }

    @Test
    void severityPolicyChangesConfigurableRules() {
        ValidationReport report = engine(
                List.of(specRule("spec-rule", Category.DESIGN, Severity.WARN, true, (c, r) -> r.report("", "x"))),
                (rule, file) -> Optional.of(Severity.ERROR))
                .validate(List.of(USERS));

        assertThat(report.violations()).extracting(Violation::severity).containsExactly(Severity.ERROR);
    }

    @Test
    void ruleThatIsOffDoesNotRun() {
        AtomicInteger calls = new AtomicInteger();

        ValidationReport report = engine(
                List.of(specRule("spec-rule", Category.DESIGN, Severity.WARN, true, (c, r) -> {
                    calls.incrementAndGet();
                    r.report("", "x");
                })),
                (rule, file) -> file.equals("orders.yaml") ? Optional.empty() : Optional.of(Severity.WARN))
                .validate(List.of(USERS, ORDERS));

        assertThat(calls).hasValue(1);
        assertThat(report.violations()).extracting(Violation::file).containsExactly("users.yaml");
    }

    @Test
    void crossSpecRuleThatIsOffDoesNotRun() {
        AtomicInteger calls = new AtomicInteger();

        ValidationReport report = engine(
                List.of(crossRule("cross-rule", (specs, r) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("boom");
                })),
                (rule, file) -> Optional.empty())
                .validate(List.of(USERS, ORDERS));

        assertThat(calls).hasValue(0);
        assertThat(report.violations()).isEmpty();
    }

    @Test
    void severityPolicyHasNoEffectOnContractRule() {
        ValidationReport report = engine(
                List.of(contractRule((c, r) -> r.report("", "x"))),
                (rule, file) -> Optional.of(Severity.WARN))
                .validate(List.of(USERS));

        assertThat(report.violations()).extracting(Violation::severity).containsExactly(Severity.ERROR);
    }

    @Test
    void severityPolicyHasNoEffectOnNonConfigurableBestPracticeRule() {
        ValidationReport report = engine(
                List.of(specRule("fixed-rule", Category.DESIGN, Severity.ERROR, false, (c, r) -> r.report("", "x"))),
                (rule, file) -> Optional.of(Severity.WARN))
                .validate(List.of(USERS));

        assertThat(report.violations()).extracting(Violation::severity).containsExactly(Severity.ERROR);
    }

    @Test
    void suppressionPolicyRemovesConfigurableViolations() {
        ValidationReport report = new ValidationEngine(
                List.of(specRule("spec-rule", Category.DESIGN, Severity.WARN, true, (c, r) -> r.report("", "x"))),
                SeverityPolicy.defaults(), (violation, spec) -> true, BaselinePolicy.empty())
                .validate(List.of(USERS));

        assertThat(report.violations()).isEmpty();
    }

    @Test
    void suppressionPolicyHasNoEffectOnContractRule() {
        ValidationReport report = new ValidationEngine(
                List.of(contractRule((c, r) -> r.report("", "x"))),
                SeverityPolicy.defaults(), (violation, spec) -> true, BaselinePolicy.empty())
                .validate(List.of(USERS));

        assertThat(report.violations()).hasSize(1);
    }

    @Test
    void baselinePolicyRemovesOnlyConfigurableViolations() {
        ValidationReport report = new ValidationEngine(
                List.of(
                        specRule("spec-rule", Category.DESIGN, Severity.WARN, true, (c, r) -> r.report("", "x")),
                        contractRule((c, r) -> r.report("", "y"))),
                SeverityPolicy.defaults(), SuppressionPolicy.none(), violation -> true)
                .validate(List.of(USERS));

        assertThat(report.violations()).extracting(Violation::ruleId).containsExactly("contract-rule");
    }

    @Test
    void failingRuleGivesOneErrorAndOtherRulesStillRun() {
        ValidationReport report = new ValidationEngine(List.of(
                specRule("failing-rule", Category.DESIGN, Severity.INFO, true, (c, r) -> {
                    throw new IllegalStateException("boom");
                }),
                specRule("working-rule", Category.DESIGN, Severity.WARN, true, (c, r) -> r.report("", "x"))))
                .validate(List.of(USERS));

        assertThat(report.violations()).containsExactly(
                new Violation(Severity.ERROR, "failing-rule", "users.yaml", "", "Rule failed: boom"),
                new Violation(Severity.WARN, "working-rule", "users.yaml", "", "x"));
    }

    @Test
    void failingRuleWithErrorOrWithoutMessageGivesOneError() {
        ValidationReport report = new ValidationEngine(List.of(
                specRule("stack-rule", Category.DESIGN, (c, r) -> {
                    throw new StackOverflowError();
                }),
                specRule("assert-rule", Category.DESIGN, (c, r) -> {
                    throw new AssertionError("x");
                }),
                specRule("linkage-rule", Category.DESIGN, (c, r) -> {
                    throw new NoClassDefFoundError("a/B");
                }),
                specRule("npe-rule", Category.DESIGN, (c, r) -> {
                    throw new NullPointerException();
                })))
                .validate(List.of(USERS));

        assertThat(report.violations()).extracting(Violation::ruleId, Violation::message).containsExactly(
                tuple("assert-rule", "Rule failed: x"),
                tuple("linkage-rule", "Rule failed: a/B"),
                tuple("npe-rule", "Rule failed: NullPointerException"),
                tuple("stack-rule", "Rule failed: StackOverflowError"));
    }

    @Test
    void failingOperationRuleReportsAtOperationPointer() {
        ValidationReport report = new ValidationEngine(List.of(operationRule("operation-rule", Category.DESIGN,
                (c, r) -> {
                    if (c.path().equals("/users")) {
                        throw new IllegalStateException("boom");
                    }
                })))
                .validate(List.of(USERS));

        assertThat(report.violations()).containsExactly(
                new Violation(Severity.ERROR, "operation-rule", "users.yaml", "/paths/~1users/post", "Rule failed: boom"));
    }

    @Test
    void failingCrossSpecRuleReportsWithEmptyFileAndKeepsEarlierViolations() {
        ValidationReport report = new ValidationEngine(List.of(crossRule("cross-rule", (specs, r) -> {
            r.report("users.yaml", "/info", "First.");
            throw new IllegalStateException("boom");
        })))
                .validate(List.of(USERS, ORDERS));

        assertThat(report.violations()).containsExactly(
                new Violation(Severity.ERROR, "cross-rule", "", "", "Rule failed: boom"),
                new Violation(Severity.WARN, "cross-rule", "users.yaml", "/info", "First."));
    }

    @Test
    void violationsBeforeFailureStay() {
        ValidationReport report = new ValidationEngine(List.of(specRule("spec-rule", Category.DESIGN, (c, r) -> {
            r.report("/info", "First.");
            throw new IllegalStateException("boom");
        })))
                .validate(List.of(USERS));

        assertThat(report.violations()).containsExactly(
                new Violation(Severity.ERROR, "spec-rule", "users.yaml", "", "Rule failed: boom"),
                new Violation(Severity.WARN, "spec-rule", "users.yaml", "/info", "First."));
    }

    @Test
    void crossSpecRuleCanReportFileThatIsNotAnOasFile() {
        ValidationReport report = new ValidationEngine(List.of(crossRule("cross-rule",
                (specs, r) -> r.report("owners.yaml", "/teams/0", "x"))))
                .validate(List.of(USERS));

        assertThat(report.violations()).containsExactly(
                new Violation(Severity.WARN, "cross-rule", "owners.yaml", "/teams/0", "x"));
    }

    @Test
    void policiesAreNotCalledForNonConfigurableRule() {
        AtomicInteger policyCalls = new AtomicInteger();
        ValidationReport report = new ValidationEngine(
                List.of(contractRule((c, r) -> r.report("", "x"))),
                (rule, file) -> {
                    policyCalls.incrementAndGet();
                    throw new AssertionError("severity policy called");
                },
                (violation, spec) -> {
                    policyCalls.incrementAndGet();
                    throw new AssertionError("suppression policy called");
                },
                violation -> {
                    policyCalls.incrementAndGet();
                    throw new AssertionError("baseline policy called");
                })
                .validate(List.of(USERS));

        assertThat(policyCalls).hasValue(0);
        assertThat(report.violations()).containsExactly(
                new Violation(Severity.ERROR, "contract-rule", "users.yaml", "", "x"));
    }

    @Test
    void rejectsTwoLoadedFilesWithTheSamePath() {
        SpecSource otherUsers = new SpecSource("users.yaml", ORDERS.content());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ValidationEngine(List.of()).validate(List.of(USERS, otherUsers)))
                .withMessage("Two loaded files have the same path 'users.yaml'");
    }

    @Test
    void fileWithParseViolationDoesNotGoToRules() {
        AtomicInteger calls = new AtomicInteger();

        ValidationReport report = new ValidationEngine(
                List.of(specRule("spec-rule", Category.DESIGN, (c, r) -> calls.incrementAndGet())))
                .validate(List.of(BROKEN));

        assertThat(calls).hasValue(0);
        assertThat(report.violations()).extracting(Violation::ruleId).containsExactly("oas-parse");
    }

    @Test
    void crossSpecRuleReportsForAnyFile() {
        ValidationReport report = new ValidationEngine(List.of(crossRule("cross-rule",
                (specs, r) -> specs.forEach(s -> r.report(s.file(), "/info", "Seen " + s.file())))))
                .validate(List.of(USERS, ORDERS));

        assertThat(report.violations()).extracting(Violation::file).containsExactly("orders.yaml", "users.yaml");
    }

    @Test
    void reportContainsRoutesWhenThereIsNoError() {
        SpecSource gateway = new SpecSource("gateway.yaml", """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: http://orders:8080 }
                paths:
                  /orders:
                    get:
                      responses: { "200": { description: OK } }
                """);

        ValidationReport report = new ValidationEngine(List.of(
                specRule("spec-rule", Category.DESIGN, Severity.WARN, true, (c, r) -> r.report("", "x"))))
                .validate(List.of(gateway));

        assertThat(report.routes()).extracting(route -> route.id()).containsExactly("gateway:/orders");
    }

    @Test
    void invalidUpstreamWithoutContractRulesGivesReportWithoutRoutes() {
        SpecSource gateway = new SpecSource("gateway.yaml", """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: "http://orders service:8080" }
                paths:
                  /orders:
                    get:
                      responses: { "200": { description: OK } }
                """);

        ValidationReport report = new ValidationEngine(List.of(
                specRule("spec-rule", Category.DESIGN, Severity.WARN, true, (c, r) -> r.report("", "x"))))
                .validate(List.of(gateway));

        assertThat(report.violations()).extracting(Violation::ruleId).containsExactly("spec-rule");
        assertThat(report.routes()).isEmpty();
    }

    @Test
    void reportHasNoRoutesWhenThereIsAnError() {
        SpecSource gateway = new SpecSource("gateway.yaml", """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: http://orders:8080 }
                paths:
                  /orders:
                    get:
                      responses: { "200": { description: OK } }
                """);

        ValidationReport report = new ValidationEngine(List.of(contractRule((c, r) -> r.report("", "x"))))
                .validate(List.of(gateway));

        assertThat(report.routes()).isEmpty();
        assertThat(report.accessRules()).isEmpty();
    }

    @Test
    void reportHasAccessRulesWithoutAnError() {
        SpecSource gateway = new SpecSource("gateway.yaml", """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: http://orders:8080 }
                paths:
                  /orders:
                    get:
                      responses: { "200": { description: OK } }
                """);

        ValidationReport report = new ValidationEngine(List.of()).validate(List.of(gateway));

        assertThat(report.accessRules()).hasSize(1);
    }

    @Test
    void crossSpecRuleCanReportInAnotherFile() {
        ValidationReport report = new ValidationEngine(
                List.of(crossRule("cross-rule", (specs, r) -> r.report("owners.yaml", "/teams/0", "x"))),
                SeverityPolicy.defaults(), (violation, spec) -> true, BaselinePolicy.empty())
                .validate(List.of(USERS));

        // The suppression policy reads OAS files only, so it does not apply here.
        assertThat(report.violations())
                .containsExactly(new Violation(Severity.WARN, "cross-rule", "owners.yaml", "/teams/0", "x"));
    }

    @Test
    void rejectsDuplicateRuleIds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ValidationEngine(List.of(
                        specRule("same-id", Category.DESIGN, (c, r) -> { }),
                        operationRule("same-id", Category.DESIGN, (c, r) -> { }))))
                .withMessageContaining("same-id");
    }

    @Test
    void sortsViolationsByFilePointerAndRuleId() {
        ValidationReport report = new ValidationEngine(List.of(
                specRule("b-rule", Category.DESIGN, (c, r) -> {
                    r.report("/paths", "x");
                    r.report("/info", "x");
                }),
                specRule("a-rule", Category.DESIGN, (c, r) -> r.report("/info", "x"))))
                .validate(List.of(USERS, ORDERS));

        assertThat(report.violations())
                .extracting(v -> v.file() + " " + v.pointer() + " " + v.ruleId())
                .containsExactly(
                        "orders.yaml /info a-rule",
                        "orders.yaml /info b-rule",
                        "orders.yaml /paths b-rule",
                        "users.yaml /info a-rule",
                        "users.yaml /info b-rule",
                        "users.yaml /paths b-rule");
    }

    @Test
    void reportsAtMostTheLimitOfEachRuleInEachFileAndTheNumberOfTheOthers() {
        ValidationReport report = new ValidationEngine(List.of(
                specRule("many-rule", Category.DESIGN, (c, r) -> report(r, 150)),
                specRule("few-rule", Category.DESIGN, (c, r) -> report(r, 3))))
                .validate(List.of(USERS, ORDERS));

        for (String file : List.of("users.yaml", "orders.yaml")) {
            assertThat(report.violations()).filteredOn(v -> v.file().equals(file) && v.ruleId().equals("many-rule"))
                    .hasSize(ValidationEngine.VIOLATION_LIMIT + 1)
                    .first()
                    .isEqualTo(new Violation(Severity.WARN, "many-rule", file, "", "50 more violations of this rule"
                            + " in this file are not reported. The report shows at most 100 violations of each rule"
                            + " in each file."));
            assertThat(report.violations()).filteredOn(v -> v.file().equals(file) && v.ruleId().equals("many-rule"))
                    .extracting(Violation::pointer)
                    .last()
                    .isEqualTo("/x/099");
            assertThat(report.violations()).filteredOn(v -> v.file().equals(file) && v.ruleId().equals("few-rule"))
                    .hasSize(3);
        }
    }

    @Test
    void theLimitItselfIsNotExceededAndOneMoreViolationIsSingular() {
        assertThat(new ValidationEngine(List.of(specRule("many-rule", Category.DESIGN, (c, r) -> report(r, 100))))
                .validate(List.of(ORDERS)).violations()).hasSize(100);
        assertThat(new ValidationEngine(List.of(specRule("many-rule", Category.DESIGN, (c, r) -> report(r, 101))))
                .validate(List.of(ORDERS)).violations())
                .hasSize(101)
                .extracting(Violation::message)
                .contains("1 more violation of this rule in this file is not reported. The report shows at most 100"
                        + " violations of each rule in each file.");
    }

    /** Suppressed and known violations do not count, so the suppression policy and the baseline see every one. */
    @Test
    void theLimitAppliesAfterSuppressionsAndTheBaseline() {
        List<String> seen = new ArrayList<>();
        ValidationReport report = new ValidationEngine(
                List.of(specRule("many-rule", Category.DESIGN, (c, r) -> report(r, 150))),
                SeverityPolicy.defaults(),
                (violation, spec) -> seen.add(violation.pointer()) && violation.pointer().compareTo("/x/030") < 0,
                violation -> violation.pointer().compareTo("/x/130") >= 0)
                .validate(List.of(ORDERS));

        assertThat(seen).hasSize(150);
        assertThat(report.violations()).hasSize(100)
                .extracting(Violation::pointer)
                .startsWith("/x/030")
                .endsWith("/x/129");
    }

    /** The extra violation has the highest severity of the others, so that an ERROR still stops the routes. */
    @Test
    void theExtraViolationKeepsTheErrorOfAContractRule() {
        ValidationReport report = new ValidationEngine(List.of(contractRule((c, r) -> report(r, 120))))
                .validate(List.of(ORDERS));

        assertThat(report.violations()).first()
                .extracting(Violation::severity, Violation::ruleId, Violation::pointer)
                .containsExactly(Severity.ERROR, "contract-rule", "");
        assertThat(report.hasAtLeast(Severity.ERROR)).isTrue();
        assertThat(report.routes()).isEmpty();
    }

    @Test
    void noLimitReportsEveryViolation() {
        ValidationReport report = new ValidationEngine(
                List.of(specRule("many-rule", Category.DESIGN, (c, r) -> report(r, 150))),
                SeverityPolicy.defaults(), SuppressionPolicy.none(), BaselinePolicy.empty(),
                ValidationEngine.NO_LIMIT)
                .validate(List.of(ORDERS));

        assertThat(report.violations()).hasSize(150);
    }

    @Test
    void rejectsALimitBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ValidationEngine(List.of(),
                SeverityPolicy.defaults(), SuppressionPolicy.none(), BaselinePolicy.empty(), 0));
    }

    private static void report(Reporter reporter, int count) {
        for (int i = 0; i < count; i++) {
            reporter.report("/x/%03d".formatted(i), "Problem " + i + ".");
        }
    }

    private static ValidationEngine engine(List<Rule> rules, SeverityPolicy severity) {
        return new ValidationEngine(rules, severity, SuppressionPolicy.none(), BaselinePolicy.empty());
    }

    private static RuleMeta meta(String id, Category category, Severity severity, boolean configurable) {
        return new RuleMeta(id, category, severity, configurable, "Test rule.", "Test.");
    }

    private interface SpecCheck {
        void check(SpecContext context, Reporter reporter);
    }

    private interface OperationCheck {
        void check(OperationContext context, Reporter reporter);
    }

    private interface CrossCheck {
        void check(List<SpecContext> specs, Reporter.ForFile reporter);
    }

    private static SpecRule specRule(String id, Category category, SpecCheck check) {
        return specRule(id, category, Severity.WARN, true, check);
    }

    private static SpecRule specRule(
            String id, Category category, Severity severity, boolean configurable, SpecCheck check) {
        RuleMeta meta = meta(id, category, severity, configurable);
        return new SpecRule() {
            @Override
            public RuleMeta meta() {
                return meta;
            }

            @Override
            public void check(SpecContext context, Reporter reporter) {
                check.check(context, reporter);
            }
        };
    }

    private static SpecRule contractRule(SpecCheck check) {
        return specRule("contract-rule", Category.CONTRACT, Severity.ERROR, false, check);
    }

    private static OperationRule operationRule(String id, Category category, OperationCheck check) {
        RuleMeta meta = meta(id, category, Severity.WARN, true);
        return new OperationRule() {
            @Override
            public RuleMeta meta() {
                return meta;
            }

            @Override
            public void check(OperationContext context, Reporter reporter) {
                check.check(context, reporter);
            }
        };
    }

    private static CrossSpecRule crossRule(String id, CrossCheck check) {
        RuleMeta meta = meta(id, Category.DESIGN, Severity.WARN, true);
        return new CrossSpecRule() {
            @Override
            public RuleMeta meta() {
                return meta;
            }

            @Override
            public void check(List<SpecContext> specs, Reporter.ForFile reporter) {
                check.check(specs, reporter);
            }
        };
    }
}
