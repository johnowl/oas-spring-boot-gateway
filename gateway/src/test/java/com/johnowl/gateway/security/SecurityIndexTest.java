package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.access.AccessRule;
import com.johnowl.gateway.core.access.OperationAccess;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class SecurityIndexTest {

    private static final AccessRule PROTECTED = new AccessRule(false, "aud", Set.of("Orders.Read"), Set.of());

    private static final OperationAccess HEALTH =
            new OperationAccess("orders:/health", "/orders-api/health", "GET", AccessRule.publicAccess());

    private static final OperationAccess GET_ORDER =
            new OperationAccess("orders:/orders/{id}", "/orders-api/orders/{id}", "GET", PROTECTED);

    private static final OperationAccess DELETE_ORDER =
            new OperationAccess("orders:/orders/{id}", "/orders-api/orders/{id}", "DELETE", PROTECTED);

    private static final OperationAccess EXPORT =
            new OperationAccess("orders:/orders/export", "/orders-api/orders/export", "GET", AccessRule.publicAccess());

    private static final OperationAccess ME =
            new OperationAccess("users:/users/me", "/users-api/users/me", "GET", AccessRule.publicAccess());

    private static final OperationAccess USER =
            new OperationAccess("users:/users/{id}", "/users-api/users/{id}", "GET", PROTECTED);

    private final SecurityIndex index = SecurityIndex.build(List.of(HEALTH, GET_ORDER, DELETE_ORDER, EXPORT, ME, USER));

    @Test
    void findsTheOperationOfMethodAndPath() {
        assertThat(find(HttpMethod.GET, "/orders-api/health")).contains(HEALTH);
        assertThat(find(HttpMethod.GET, "/orders-api/orders/42")).contains(GET_ORDER);
        assertThat(find(HttpMethod.DELETE, "/orders-api/orders/42")).contains(DELETE_ORDER);
    }

    @Test
    void literalSegmentComesBeforeTheVariable() {
        assertThat(find(HttpMethod.GET, "/users-api/users/me")).contains(ME);
        assertThat(find(HttpMethod.GET, "/users-api/users/42")).contains(USER);
    }

    @Test
    void backtracksToTheVariableForAnotherMethod() {
        // GET /orders-api/orders/export is a literal; DELETE exists only on the variable.
        assertThat(find(HttpMethod.GET, "/orders-api/orders/export")).contains(EXPORT);
        assertThat(find(HttpMethod.DELETE, "/orders-api/orders/export")).contains(DELETE_ORDER);
    }

    // SEC-59-1: a backend that ignores case serves the literal operation, so the variable rule must not apply.
    @Test
    void caseVariantOfALiteralFindsNothing() {
        assertThat(find(HttpMethod.GET, "/orders-api/orders/EXPORT")).isEmpty();
        assertThat(find(HttpMethod.DELETE, "/orders-api/orders/Export")).isEmpty();
        assertThat(find(HttpMethod.GET, "/users-api/users/ME")).isEmpty();
    }

    @Test
    void findsNothingForUndocumentedRequests() {
        assertThat(find(HttpMethod.POST, "/orders-api/health")).isEmpty();
        assertThat(find(HttpMethod.PUT, "/orders-api/orders/42")).isEmpty();
        assertThat(find(HttpMethod.OPTIONS, "/orders-api/orders/42")).isEmpty();
        assertThat(find(HttpMethod.GET, "/orders-api/health/")).isEmpty();
        assertThat(find(HttpMethod.GET, "/orders-api//health")).isEmpty();
        assertThat(find(HttpMethod.GET, "/other/health")).isEmpty();
        assertThat(find(HttpMethod.GET, "/")).isEmpty();
    }

    // RequestPathFirewall rejects %68 (an encoded unreserved character) before the index; the index still decodes.
    @Test
    void decodesSegmentsLikeTheRoutes() {
        assertThat(find(HttpMethod.GET, "/orders-api/%68ealth")).contains(HEALTH);
        assertThat(find(HttpMethod.GET, "/orders-api/orders/a%20b")).contains(GET_ORDER);
    }

    @Test
    void addsTheBasePath() {
        SecurityIndex withBasePath = SecurityIndex.build(List.of(HEALTH), "/api");

        assertThat(withBasePath.find(exchange(HttpMethod.GET, "/api/orders-api/health"))).contains(HEALTH);
        assertThat(withBasePath.find(exchange(HttpMethod.GET, "/orders-api/health"))).isEmpty();
    }

    @Test
    void findsNonPublicGetOperations() {
        assertThat(index.isNonPublicGet("/orders-api/orders/42")).isTrue();
        assertThat(index.isNonPublicGet("/orders-api/orders/export")).isFalse();
        assertThat(index.isNonPublicGet("/orders-api/health")).isFalse();
        assertThat(index.isNonPublicGet("/")).isFalse();
    }

    // Built once at startup: no public method changes the index after build.
    @Test
    void noRequestCanChangeTheIndex() {
        List<String> instanceMethods = Arrays.stream(SecurityIndex.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList();

        assertThat(instanceMethods).containsExactly("find", "isNonPublicGet");
    }

    private Optional<OperationAccess> find(HttpMethod method, String path) {
        return index.find(exchange(method, path));
    }

    private static MockServerWebExchange exchange(HttpMethod method, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, URI.create(path)));
    }
}
