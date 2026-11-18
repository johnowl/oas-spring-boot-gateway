package com.johnowl.gateway.core.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.spec.SpecFile;
import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.validation.ValidationEngine;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The comparison of spec 034: one test for each change id, the order of the checks, and each precise case. */
class AccessDiffTest {

    private static final String ORDERS = "6f1c2e8a-0000-0000-0000-000000000000";
    private static final String PAYMENTS = "7a2d3f9b-0000-0000-0000-000000000000";
    private static final String UPSTREAM = "http://orders-service:8080";
    private static final String PUBLIC_GET = "get: { security: [], responses: { '200': { description: OK } } }";

    private final AccessDiff diff = new AccessDiff();

    @Test
    void becamePublic() {
        AccessDiff.Result result = compare(
                "get: { x-gateway-authorization: { all-of: [Orders.Read] }, responses: { '200': { description: OK } } }",
                "get: { security: [], responses: { '200': { description: OK } } }");

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(AccessChange.Kind.BECAME_PUBLIC);
            assertThat(change.method()).isEqualTo("GET");
            assertThat(change.publicPath()).isEqualTo("/orders-api/orders/{id}");
            assertThat(change.file()).isEqualTo("head/orders.yaml");
            assertThat(change.pointer()).isEqualTo("/paths/~1orders~1{id}/get");
            assertThat(change.permission()).isNull();
            assertThat(change.message()).isEqualTo("base: audience=" + ORDERS + ", all-of=[Orders.Read]; head: public");
        });
    }

    @Test
    void protectedToPublicGivesOnlyBecamePublic() {
        AccessDiff.Result result = compare(
                operation("all-of: [Orders.Read, Orders.Delete], any-of: [Orders.Admin]"),
                "get: { security: [], responses: { '200': { description: OK } } }");

        assertThat(result.changes()).extracting(AccessChange::kind).containsExactly(AccessChange.Kind.BECAME_PUBLIC);
    }

    @Test
    void audienceChanged() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))),
                List.of(spec("head/orders.yaml", PAYMENTS, operation("all-of: [Orders.Read]"))));

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(AccessChange.Kind.AUDIENCE_CHANGED);
            assertThat(change.message()).isEqualTo("base: audience=" + ORDERS + ", all-of=[Orders.Read]; head: audience="
                    + PAYMENTS + ", all-of=[Orders.Read]");
        });
    }

    @Test
    void audienceChangedComesBeforeThePermissionChecks() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read, Orders.Delete]"))),
                List.of(spec("head/orders.yaml", PAYMENTS, operation("authenticated-only: true"))));

        assertThat(result.changes()).extracting(AccessChange::kind).containsExactly(AccessChange.Kind.AUDIENCE_CHANGED);
    }

    @Test
    void authenticatedOnlyReplacedPermissions() {
        AccessDiff.Result result = compare(operation("any-of: [Orders.Refund]"), operation("authenticated-only: true"));

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(AccessChange.Kind.AUTHENTICATED_ONLY_REPLACED_PERMISSIONS);
            assertThat(change.message()).isEqualTo("base: audience=" + ORDERS + ", any-of=[Orders.Refund]; head: audience="
                    + ORDERS + ", authenticated-only");
        });
    }

    @Test
    void authenticatedOnlyReplacedAllOfGivesNoPermissionChanges() {
        AccessDiff.Result result = compare(operation("all-of: [Orders.Read, Orders.Delete]"),
                operation("authenticated-only: true"));

        assertThat(result.changes()).extracting(AccessChange::kind)
                .containsExactly(AccessChange.Kind.AUTHENTICATED_ONLY_REPLACED_PERMISSIONS);
    }

    @Test
    void permissionRemovedFromAllOf() {
        AccessDiff.Result result = compare(operation("all-of: [Orders.Read, Orders.Delete]"),
                operation("all-of: [Orders.Read]"));

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::permission, AccessChange::message)
                .containsExactly(tuple(AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF, "Orders.Delete",
                        "Orders.Delete removed from all-of"));
    }

    @Test
    void twoRemovedPermissionsGiveTwoChanges() {
        AccessDiff.Result result = compare(operation("all-of: [Orders.Read, Orders.Delete, Orders.Write]"),
                operation("all-of: [Orders.Read]"));

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::permission).containsExactly(
                tuple(AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF, "Orders.Delete"),
                tuple(AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF, "Orders.Write"));
        assertThat(result.operationCount()).isEqualTo(1);
    }

    @Test
    void replacedAllOfPermissionIsReported() {
        AccessDiff.Result result = compare(operation("all-of: [Orders.Delete]"), operation("all-of: [Orders.Admin]"));

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::permission).containsExactly(
                tuple(AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF, "Orders.Delete"));
    }

    @Test
    void permissionAddedToAnyOf() {
        AccessDiff.Result result = compare(operation("any-of: [Orders.Refund]"),
                operation("any-of: [Orders.Refund, Finance.Admin]"));

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::permission, AccessChange::message)
                .containsExactly(tuple(AccessChange.Kind.PERMISSION_ADDED_TO_ANY_OF, "Finance.Admin",
                        "Finance.Admin added to any-of"));
    }

    @Test
    void checksFourAndFiveBothRun() {
        AccessDiff.Result result = compare(operation("all-of: [Orders.Read, Orders.Delete], any-of: [Orders.Refund]"),
                operation("all-of: [Orders.Read], any-of: [Orders.Refund, Finance.Admin]"));

        assertThat(result.changes()).extracting(AccessChange::kind).containsExactly(
                AccessChange.Kind.PERMISSION_ADDED_TO_ANY_OF, AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF);
    }

    /** QA-73-1, SEC-73-3: without any-of, a token needs only the all-of. */
    @Test
    void anyOfRemoved() {
        AccessDiff.Result result = compare(operation("all-of: [Orders.Read], any-of: [Orders.Refund, Orders.Admin]"),
                operation("all-of: [Orders.Read]"));

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(AccessChange.Kind.ANY_OF_REMOVED);
            assertThat(change.permission()).isNull();
            assertThat(change.message()).isEqualTo("base: audience=" + ORDERS
                    + ", all-of=[Orders.Read], any-of=[Orders.Admin, Orders.Refund]; head: audience=" + ORDERS
                    + ", all-of=[Orders.Read]");
        });
    }

    @Test
    void anyOfRemovedAndPermissionRemovedFromAllOfGiveBothChanges() {
        AccessDiff.Result result = compare(operation("all-of: [Orders.Read, Orders.Write], any-of: [Orders.Refund]"),
                operation("all-of: [Orders.Read]"));

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::permission).containsExactly(
                tuple(AccessChange.Kind.ANY_OF_REMOVED, null),
                tuple(AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF, "Orders.Write"));
    }

    @Test
    void anyOfPermissionMovedToAllOfIsTighter() {
        assertThat(compare(operation("all-of: [Orders.Read], any-of: [Orders.Refund, Orders.Admin]"),
                operation("all-of: [Orders.Read, Orders.Refund]")).changes()).isEmpty();
    }

    @Test
    void newAnyOfIsTighter() {
        assertThat(compare(operation("all-of: [Orders.Read]"), operation("all-of: [Orders.Read], any-of: [A.X]"))
                .changes()).isEmpty();
    }

    @Test
    void addedAllOfPermissionIsTighter() {
        assertThat(compare(operation("all-of: [Orders.Read]"), operation("all-of: [Orders.Read, Orders.Delete]"))
                .changes()).isEmpty();
    }

    @Test
    void permissionsReplacingAuthenticatedOnlyAreTighter() {
        assertThat(compare(operation("authenticated-only: true"), operation("all-of: [Orders.Read]")).changes())
                .isEmpty();
    }

    @Test
    void publicToProtectedIsTighter() {
        assertThat(compare("get: { security: [], responses: { '200': { description: OK } } }",
                operation("all-of: [Orders.Read]")).changes()).isEmpty();
    }

    @Test
    void orderOfPermissionsDoesNotCount() {
        assertThat(compare(operation("all-of: [Orders.Read, Orders.Delete], any-of: [A.X, B.Y]"),
                operation("all-of: [Orders.Delete, Orders.Read], any-of: [B.Y, A.X]")).changes()).isEmpty();
    }

    @Test
    void newAndRemovedOperationsGiveNoChange() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, "/orders/{id}", operation("all-of: [Orders.Read]"))),
                List.of(spec("head/orders.yaml", ORDERS, "/orders", operation("all-of: [Orders.Read]"))));

        assertThat(result.changes()).isEmpty();
    }

    @Test
    void renamedPathVariableIsTheSameOperation() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, "/orders/{id}", operation("all-of: [Orders.Read]"))),
                List.of(spec("head/orders.yaml", ORDERS, "/orders/{orderId}", operation("all-of: [Orders.Read]"))));

        assertThat(result.changes()).isEmpty();
        assertThat(result.bindings()).isEmpty();
    }

    @Test
    void renamedPathVariableStillReportsALooseningChange() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, "/orders/{id}", operation("all-of: [Orders.Read, Orders.Delete]"))),
                List.of(spec("head/orders.yaml", ORDERS, "/orders/{orderId}", operation("all-of: [Orders.Read]"))));

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.publicPath()).isEqualTo("/orders-api/orders/{orderId}");
            assertThat(change.pointer()).isEqualTo("/paths/~1orders~1{orderId}/get");
        });
    }

    @Test
    void movedOperationWithTheSamePrefixIsTheSameOperation() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))),
                List.of(spec("head/renamed.yaml", ORDERS, operation("all-of: [Orders.Read]"))));

        assertThat(result.changes()).isEmpty();
        assertThat(result.bindings()).isEmpty();
    }

    /** QA-73-2, decision 5: adding a public endpoint needs the label. */
    @Test
    void newPublicOperation() {
        AccessDiff.Result result = compareWithNewPaths("""
                  /status:
                    get: { security: [], responses: { '200': { description: OK } } }
                """);

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(AccessChange.Kind.NEW_PUBLIC_OPERATION);
            assertThat(change.publicPath()).isEqualTo("/orders-api/status");
            assertThat(change.pointer()).isEqualTo("/paths/~1status/get");
            assertThat(change.message()).isEqualTo("base: none; head: public");
        });
    }

    /** QA-73-2, SEC-73-2: GET /orders-api/orders/export took its requests from the template, with all-of. */
    @Test
    void newLiteralShadowsAProtectedTemplate() {
        AccessDiff.Result result = compareWithNewPaths("""
                  /orders/export:
                    get: { x-gateway-authorization: { authenticated-only: true }, responses: { '200': { description: OK } } }
                """);

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(AccessChange.Kind.SHADOWS_PROTECTED_TEMPLATE);
            assertThat(change.publicPath()).isEqualTo("/orders-api/orders/export");
            assertThat(change.permission()).isNull();
            assertThat(change.message()).isEqualTo("overlaps GET /orders-api/orders/{id} in the base; base: audience="
                    + ORDERS + ", all-of=[Orders.Read]; head: audience=" + ORDERS + ", authenticated-only");
        });
    }

    @Test
    void newPublicLiteralOverAProtectedTemplateGivesBothChanges() {
        AccessDiff.Result result = compareWithNewPaths("""
                  /orders/export:
                    get: { security: [], responses: { '200': { description: OK } } }
                """);

        assertThat(result.changes()).extracting(AccessChange::kind).containsExactly(
                AccessChange.Kind.NEW_PUBLIC_OPERATION, AccessChange.Kind.SHADOWS_PROTECTED_TEMPLATE);
    }

    @Test
    void newOperationWithoutAWeakerRuleThanAnOverlappedTemplateGivesNoChange() {
        AccessDiff.Result result = compareWithNewPaths("""
                  /orders/export:
                    get: { x-gateway-authorization: { all-of: [Orders.Read, Orders.Export] }, responses: { '200': { description: OK } } }
                  /orders/{id}/items:
                    parameters: [{ name: id, in: path, required: true, schema: { type: string } }]
                    get: { x-gateway-authorization: { authenticated-only: true }, responses: { '200': { description: OK } } }
                """);

        // /orders/{id}/items has one more segment, so it does not overlap the template.
        assertThat(result.changes()).isEmpty();
    }

    /** SEC-73-1: the operation moves to a new prefix and becomes public. Its backend target stays. */
    @Test
    void operationMovedToANewPrefixAndMadePublicIsReported() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))),
                List.of(spec("head/orders.yaml", ORDERS, "get: { security: [], responses: { '200': { description: OK } } }")
                        .withPrefix("/shop")));

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::publicPath, AccessChange::message)
                .containsExactly(
                        tuple(AccessChange.Kind.BECAME_PUBLIC, "/shop/orders/{id}", "base: audience=" + ORDERS
                                + ", all-of=[Orders.Read]; head: public (the same backend as GET"
                                + " /orders-api/orders/{id} in the base)"),
                        tuple(AccessChange.Kind.NEW_PUBLIC_OPERATION, "/shop/orders/{id}", "base: none; head: public"));
    }

    /** SEC-73-1, decision 8: the path moves into upstream-base-path, and the OAS path changes its case. */
    @Test
    void operationMovedToANewPathWithTheSameBackendTargetIsReported() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read, Orders.Delete]"))),
                List.of(spec("head/orders.yaml", ORDERS, "/{orderId}", operation("all-of: [Orders.Read]"))
                        .withPrefix("/shop").withUpstreamBasePath("/ORDERS")));

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::publicPath, AccessChange::message)
                .containsExactly(tuple(AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF, "/shop/{orderId}",
                        "Orders.Delete removed from all-of (the same backend as GET /orders-api/orders/{id} in the"
                                + " base)"));
    }

    @Test
    void otherBackendTargetIsNotMatched() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read, Orders.Delete]"))),
                List.of(spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]")).withPrefix("/shop")
                        .withUpstreamBasePath("/v2")));

        assertThat(result.changes()).isEmpty();
    }

    /**
     * The base reaches one backend through two public paths with different rules. The head keeps both: the weaker path
     * is not a new access to the backend.
     */
    @Test
    void unchangedPublicPathsToTheSameBackendGiveNoChange() {
        List<Spec> files = List.of(
                spec("orders.yaml", ORDERS, operation("all-of: [Orders.Read]")),
                spec("admin.yaml", PAYMENTS, operation("all-of: [Orders.Admin]")).withPrefix("/admin"));

        assertThat(compareSpecs(files, files).changes()).isEmpty();
    }

    /** QA-73-3: the prefix moves, but the public path stays. */
    @Test
    void prefixMoveWithTheSamePublicPathAndWeakerAccessIsReported() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read, Orders.Delete]"))),
                List.of(spec("head/orders.yaml", ORDERS, "/{id}", operation("all-of: [Orders.Read]"))
                        .withPrefix("/orders-api/orders")));

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::publicPath, AccessChange::message)
                .containsExactly(tuple(AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF, "/orders-api/orders/{id}",
                        "Orders.Delete removed from all-of"));
        assertThat(result.bindings()).containsExactly(new AudienceBinding(AudienceBinding.Kind.CHANGED, ORDERS,
                UPSTREAM, UPSTREAM, "/orders-api", "/orders-api/orders", null, null, "head/orders.yaml",
                "/x-gateway/prefix"));
    }

    @Test
    void changesAreSortedByPathMethodChangeIdAndPermission() {
        String base = """
                paths:
                  /b:
                    get: { x-gateway-authorization: { all-of: [Z.Z, A.A] }, responses: { '200': { description: OK } } }
                    delete: { x-gateway-authorization: { all-of: [A.A] }, responses: { '204': { description: OK } } }
                  /a:
                    get: { x-gateway-authorization: { all-of: [A.A] }, responses: { '200': { description: OK } } }
                """;
        String head = """
                paths:
                  /b:
                    get: { x-gateway-authorization: { authenticated-only: true }, responses: { '200': { description: OK } } }
                    delete: { security: [], responses: { '204': { description: OK } } }
                  /a:
                    get: { x-gateway-authorization: { all-of: [B.B] }, responses: { '200': { description: OK } } }
                """;
        AccessDiff.Result result = compareSpecs(List.of(file("base/orders.yaml", ORDERS, base)),
                List.of(file("head/orders.yaml", ORDERS, head)));

        assertThat(result.changes()).extracting(AccessChange::publicPath, AccessChange::method, AccessChange::kind)
                .containsExactly(
                        tuple("/orders-api/a", "GET", AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF),
                        tuple("/orders-api/b", "DELETE", AccessChange.Kind.BECAME_PUBLIC),
                        tuple("/orders-api/b", "GET", AccessChange.Kind.AUTHENTICATED_ONLY_REPLACED_PERMISSIONS));
        assertThat(result.operationCount()).isEqualTo(3);
    }

    @Test
    void newFileWithAnAudienceGivesBindingNew() {
        AccessDiff.Result result = compareSpecs(List.of(),
                List.of(spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))));

        assertThat(result.changes()).isEmpty();
        assertThat(result.bindings()).containsExactly(new AudienceBinding(AudienceBinding.Kind.NEW, ORDERS, null,
                UPSTREAM, null, "/orders-api", null, null, "head/orders.yaml", "/x-gateway/upstream"));
    }

    @Test
    void changedUpstreamGivesBindingChanged() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))),
                List.of(spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))
                        .withUpstream("http://reporting-service:8080")));

        assertThat(result.changes()).isEmpty();
        assertThat(result.bindings()).containsExactly(new AudienceBinding(AudienceBinding.Kind.CHANGED, ORDERS,
                UPSTREAM, "http://reporting-service:8080", "/orders-api", "/orders-api", null, null,
                "head/orders.yaml", "/x-gateway/upstream"));
    }

    /** QA-74-3: a binding without a prefix has no prefix on both sides. */
    @Test
    void changedUpstreamWithoutAPrefixGivesBindingChanged() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]")).withPrefix(null)),
                List.of(spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]")).withPrefix(null)
                        .withUpstream("http://reporting-service:8080")));

        assertThat(result.bindings()).containsExactly(new AudienceBinding(AudienceBinding.Kind.CHANGED, ORDERS,
                UPSTREAM, "http://reporting-service:8080", null, null, null, null, "head/orders.yaml",
                "/x-gateway/upstream"));
    }

    @Test
    void changedPrefixGivesBindingChanged() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read, Orders.Delete]"))),
                List.of(spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]")).withPrefix("/shop")));

        // The public path is new, but the backend target is the same, so the weaker rule is reported.
        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::permission)
                .containsExactly(tuple(AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF, "Orders.Delete"));
        assertThat(result.bindings()).containsExactly(new AudienceBinding(AudienceBinding.Kind.CHANGED, ORDERS,
                UPSTREAM, UPSTREAM, "/orders-api", "/shop", null, null, "head/orders.yaml", "/x-gateway/prefix"));
    }

    /** QA-73-4: the head file has no prefix field, so the pointer is the x-gateway object. */
    @Test
    void removedPrefixGivesBindingChangedAtXGateway() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))),
                List.of(spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]")).withPrefix(null)));

        assertThat(result.changes()).isEmpty();
        assertThat(result.bindings()).containsExactly(new AudienceBinding(AudienceBinding.Kind.CHANGED, ORDERS,
                UPSTREAM, UPSTREAM, "/orders-api", null, null, null, "head/orders.yaml", "/x-gateway"));
    }

    @Test
    void addedPrefixGivesBindingChangedAtThePrefix() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]")).withPrefix(null)),
                List.of(spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))));

        assertThat(result.changes()).isEmpty();
        assertThat(result.bindings()).containsExactly(new AudienceBinding(AudienceBinding.Kind.CHANGED, ORDERS,
                UPSTREAM, UPSTREAM, null, "/orders-api", null, null, "head/orders.yaml", "/x-gateway/prefix"));
    }

    /** QA-73-5, SEC-73-4: the gateway sends the token to the upstream and the upstream base path. */
    @Test
    void changedUpstreamBasePathGivesBindingChanged() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]")).withUpstreamBasePath("/v1")),
                List.of(spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))
                        .withUpstreamBasePath("/admin")));

        assertThat(result.bindings()).containsExactly(new AudienceBinding(AudienceBinding.Kind.CHANGED, ORDERS,
                UPSTREAM, UPSTREAM, "/orders-api", "/orders-api", "/v1", "/admin", "head/orders.yaml",
                "/x-gateway/upstream-base-path"));
    }

    @Test
    void addedAndRemovedUpstreamBasePathGiveBindingChanged() {
        Spec without = spec("orders.yaml", ORDERS, operation("all-of: [Orders.Read]"));
        Spec with = without.withUpstreamBasePath("/v1");

        assertThat(compareSpecs(List.of(without), List.of(with)).bindings())
                .extracting(AudienceBinding::baseUpstreamBasePath, AudienceBinding::headUpstreamBasePath,
                        AudienceBinding::pointer)
                .containsExactly(tuple(null, "/v1", "/x-gateway/upstream-base-path"));
        assertThat(compareSpecs(List.of(with), List.of(without)).bindings())
                .extracting(AudienceBinding::baseUpstreamBasePath, AudienceBinding::headUpstreamBasePath,
                        AudienceBinding::pointer)
                .containsExactly(tuple("/v1", null, "/x-gateway"));
    }

    @Test
    void changedAudienceGivesBindingNewForTheNewAudience() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))),
                List.of(spec("head/orders.yaml", PAYMENTS, operation("all-of: [Orders.Read]"))));

        assertThat(result.bindings()).extracting(AudienceBinding::kind, AudienceBinding::audience)
                .containsExactly(tuple(AudienceBinding.Kind.NEW, PAYMENTS));
    }

    /** SEC-73-6: a file with only public operations sends its requests to its upstream, so it has a binding too. */
    @Test
    void fileWithoutAudienceAndAChangedUpstreamGivesBindingChanged() {
        AccessDiff.Result result = compareSpecs(List.of(spec("base/docs.yaml", null, PUBLIC_GET).withPrefix("/docs")),
                List.of(spec("head/docs.yaml", null, PUBLIC_GET).withPrefix("/docs")
                        .withUpstream("http://169.254.169.254")));

        assertThat(result.changes()).isEmpty();
        assertThat(result.bindings()).containsExactly(new AudienceBinding(AudienceBinding.Kind.CHANGED, null,
                UPSTREAM, "http://169.254.169.254", "/docs", "/docs", null, null, "head/docs.yaml",
                "/x-gateway/upstream"));
    }

    @Test
    void newFileWithoutAudienceGivesBindingNew() {
        AccessDiff.Result result = compareSpecs(List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))),
                List.of(spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]")),
                        spec("head/docs.yaml", null, PUBLIC_GET).withPrefix("/docs").withUpstream("http://docs:8080")));

        assertThat(result.bindings()).containsExactly(new AudienceBinding(AudienceBinding.Kind.NEW, null, null,
                "http://docs:8080", null, "/docs", null, null, "head/docs.yaml", "/x-gateway/upstream"));
    }

    /** The public operations go to the upstream, prefix, and base path of a base binding: no new target. */
    @Test
    void fileWithoutAudienceWithTheTargetOfABaseBindingHasNoBinding() {
        AccessDiff.Result result = compareSpecs(List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))),
                List.of(spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]")),
                        spec("head/status.yaml", null, "/status", PUBLIC_GET)));

        assertThat(result.bindings()).isEmpty();
    }

    @Test
    void bindingsWithoutAudienceAreSortedLast() {
        AccessDiff.Result result = compareSpecs(List.of(), List.of(
                spec("head/docs.yaml", null, PUBLIC_GET).withPrefix("/docs"),
                spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))));

        assertThat(result.bindings()).extracting(AudienceBinding::audience).containsExactly(ORDERS, null);
    }

    /** SEC-73-5: the head removes a literal path, and the template of the same file takes its requests. */
    @Test
    void literalRemovedToAWeakerTemplate() {
        String template = """
                  /orders/{id}:
                    parameters: [{ name: id, in: path, required: true, schema: { type: string } }]
                    get: { x-gateway-authorization: { all-of: [Orders.Read] }, responses: { '200': { description: OK } } }
                """;
        AccessDiff.Result result = compareSpecs(List.of(file("base/orders.yaml", ORDERS, "paths:\n" + template + """
                          /orders/export:
                            get: { x-gateway-authorization: { all-of: [Orders.Export] }, responses: { '200': { description: OK } } }
                        """)), List.of(file("head/orders.yaml", ORDERS, "paths:\n" + template)));

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(AccessChange.Kind.LITERAL_REMOVED_TO_TEMPLATE);
            assertThat(change.publicPath()).isEqualTo("/orders-api/orders/{id}");
            assertThat(change.pointer()).isEqualTo("/paths/~1orders~1{id}/get");
            assertThat(change.message()).isEqualTo("takes the requests of the removed GET /orders-api/orders/export;"
                    + " base: audience=" + ORDERS + ", all-of=[Orders.Export]; head: audience=" + ORDERS
                    + ", all-of=[Orders.Read]");
        });
    }

    @Test
    void literalRemovedToANewWeakerTemplate() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, "/orders/export", operation("all-of: [Orders.Export]"))),
                List.of(spec("head/orders.yaml", ORDERS, "/orders/{id}", operation("authenticated-only: true"))));

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::publicPath).containsExactly(
                tuple(AccessChange.Kind.LITERAL_REMOVED_TO_TEMPLATE, "/orders-api/orders/{id}"));
    }

    @Test
    void literalRemovedToATemplateThatIsNotWeakerGivesNoChange() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, "/orders/export", operation("all-of: [Orders.Read]"))),
                List.of(spec("head/orders.yaml", ORDERS, "/orders/{id}", operation("all-of: [Orders.Read, Orders.X]"))));

        assertThat(result.changes()).isEmpty();
    }

    /** SEC-73-7: a backend often answers HEAD with the GET handler. */
    @Test
    void newHeadOperationWeakerThanTheBaseGet() {
        AccessDiff.Result result = compareWithNewPaths("", """
                    head: { x-gateway-authorization: { authenticated-only: true }, responses: { '200': { description: OK } } }
                """);

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(AccessChange.Kind.HEAD_WEAKER_THAN_GET);
            assertThat(change.method()).isEqualTo("HEAD");
            assertThat(change.pointer()).isEqualTo("/paths/~1orders~1{id}/head");
            assertThat(change.message()).isEqualTo("weaker than GET /orders-api/orders/{id} in the base; base: audience="
                    + ORDERS + ", all-of=[Orders.Read]; head: audience=" + ORDERS + ", authenticated-only");
        });
    }

    @Test
    void newPublicOptionsOperationIsWeakerThanTheBaseGet() {
        AccessDiff.Result result = compareWithNewPaths("", """
                    options: { security: [], responses: { '200': { description: OK } } }
                """);

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::method).containsExactly(
                tuple(AccessChange.Kind.HEAD_WEAKER_THAN_GET, "OPTIONS"),
                tuple(AccessChange.Kind.NEW_PUBLIC_OPERATION, "OPTIONS"));
    }

    @Test
    void newHeadOperationWithTheRuleOfTheGetGivesNoChange() {
        assertThat(compareWithNewPaths("", """
                    head: { x-gateway-authorization: { all-of: [Orders.Read] }, responses: { '200': { description: OK } } }
                """).changes()).isEmpty();
    }

    @Test
    void newHeadOperationIsComparedWithTheGetOfTheSameBackendTarget() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))),
                List.of(spec("head/orders.yaml", ORDERS,
                        "head: { x-gateway-authorization: { authenticated-only: true }, responses: { '200': { description: OK } } }")
                        .withPrefix("/shop")));

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::publicPath).containsExactly(
                tuple(AccessChange.Kind.HEAD_WEAKER_THAN_GET, "/shop/orders/{id}"));
    }

    /** SEC-73-8: the host case and the default port do not change the backend target. */
    @Test
    void upstreamIsCanonicalForTheBackendTarget() {
        AccessDiff.Result result = compareSpecs(
                List.of(spec("base/orders.yaml", ORDERS, operation("all-of: [Orders.Read, Orders.Delete]"))
                        .withUpstream("http://orders-service:80")),
                List.of(spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]")).withPrefix("/shop")
                        .withUpstream("http://Orders-Service")));

        assertThat(result.changes()).extracting(AccessChange::kind, AccessChange::permission).containsExactly(
                tuple(AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF, "Orders.Delete"));
    }

    @Test
    void canonicalUpstream() {
        assertThat(AccessDiff.canonical(URI.create("HTTP://Orders.Example:80"))).isEqualTo("http://orders.example");
        assertThat(AccessDiff.canonical(URI.create("https://orders:443"))).isEqualTo("https://orders");
        assertThat(AccessDiff.canonical(URI.create("https://orders:80"))).isEqualTo("https://orders:80");
        assertThat(AccessDiff.canonical(URI.create("http://orders:8080"))).isEqualTo("http://orders:8080");
        assertThat(AccessDiff.canonical(URI.create("http://user@orders:80/v1"))).isEqualTo("http://orders/v1");
    }

    @Test
    void bindingsAreSortedByAudienceThenFile() {
        AccessDiff.Result result = compareSpecs(List.of(), List.of(
                spec("head/payments.yaml", PAYMENTS, "/payments/{id}", operation("all-of: [Payments.Read]")),
                spec("head/orders.yaml", ORDERS, operation("all-of: [Orders.Read]"))));

        assertThat(result.bindings()).extracting(AudienceBinding::audience).containsExactly(ORDERS, PAYMENTS);
    }

    // Helpers

    private AccessDiff.Result compare(String baseOperation, String headOperation) {
        return compareSpecs(List.of(spec("base/orders.yaml", ORDERS, baseOperation)),
                List.of(spec("head/orders.yaml", ORDERS, headOperation)));
    }

    /** The base has GET /orders/{id} with all-of: [Orders.Read]. The head has it too, and the new paths. */
    private AccessDiff.Result compareWithNewPaths(String newPaths) {
        return compareWithNewPaths(newPaths, "");
    }

    /**
     * @param newPaths paths after /orders/{id} in the head
     * @param newOperations operations of /orders/{id} after its GET in the head
     */
    private AccessDiff.Result compareWithNewPaths(String newPaths, String newOperations) {
        String template = """
                paths:
                  /orders/{id}:
                    parameters: [{ name: id, in: path, required: true, schema: { type: string } }]
                    get: { x-gateway-authorization: { all-of: [Orders.Read] }, responses: { '200': { description: OK } } }
                """;
        return compareSpecs(List.of(file("base/orders.yaml", ORDERS, template)),
                List.of(file("head/orders.yaml", ORDERS, template + newOperations + newPaths)));
    }

    private AccessDiff.Result compareSpecs(List<Spec> base, List<Spec> head) {
        return diff.compare(base.stream().map(Spec::load).toList(), head.stream().map(Spec::load).toList());
    }

    /** A GET operation with the access rule. */
    private static String operation(String authorization) {
        return "get: { x-gateway-authorization: { " + authorization + " }, responses: { '200': { description: OK } } }";
    }

    private static Spec spec(String file, String audience, String operation) {
        return spec(file, audience, "/orders/{id}", operation);
    }

    private static Spec spec(String file, String audience, String path, String operation) {
        String parameters = path.contains("{")
                ? "    parameters: [{ name: " + path.substring(path.indexOf('{') + 1, path.indexOf('}'))
                        + ", in: path, required: true, schema: { type: string } }]\n"
                : "";
        return new Spec(file, audience, UPSTREAM, "/orders-api", null,
                "paths:\n  " + path + ":\n" + parameters + "    " + operation + "\n");
    }

    private static Spec file(String file, String audience, String paths) {
        return new Spec(file, audience, UPSTREAM, "/orders-api", null, paths);
    }

    /** A valid OAS file: the CONTRACT rules report nothing. A {@code null} prefix or base path is not in the file. */
    private record Spec(String file, String audience, String upstream, String prefix, String upstreamBasePath,
            String paths) {

        Spec withUpstream(String value) {
            return new Spec(file, audience, value, prefix, upstreamBasePath, paths);
        }

        Spec withPrefix(String value) {
            return new Spec(file, audience, upstream, value, upstreamBasePath, paths);
        }

        Spec withUpstreamBasePath(String value) {
            return new Spec(file, audience, upstream, prefix, value, paths);
        }

        SpecFile load() {
            String content = """
                    openapi: 3.0.3
                    info: { title: Orders, version: 1.0.0 }
                    x-gateway:
                      upstream: %s
                    %s%s%scomponents:
                      securitySchemes:
                        entra:
                          type: openIdConnect
                          openIdConnectUrl: https://login.microsoftonline.com/tenant/v2.0/.well-known/openid-configuration
                    security:
                      - entra: []
                    %s""".formatted(upstream, field("prefix", prefix), field("upstream-base-path", upstreamBasePath),
                    field("audience", audience), paths);
            SpecSource source = new SpecSource(file, content);
            assertThat(new ValidationEngine(BuiltInRules.contract()).validate(List.of(source)).violations())
                    .as(file).isEmpty();
            return new SpecLoader().load(source).spec().orElseThrow();
        }

        private static String field(String name, String value) {
            return value == null ? "" : "  " + name + ": " + value + "\n";
        }
    }
}
