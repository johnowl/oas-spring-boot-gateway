package com.johnowl.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class IdentityHeaderRemovalFilterTest {

    private static final GatewaySecurityProperties.RemoveRequestHeaders NONE =
            new GatewaySecurityProperties.RemoveRequestHeaders(List.of(), List.of());

    private static final GatewaySecurityProperties.RemoveRequestHeaders CONFIGURED =
            new GatewaySecurityProperties.RemoveRequestHeaders(List.of("X-Tenant-Id"), List.of("X-Internal-"));

    @Test
    void removesEachBuiltInNameInAnyLetterCase() {
        IdentityHeaderRemovalFilter filter = new IdentityHeaderRemovalFilter(NONE);

        for (String name : IdentityHeaderRemovalFilter.BUILT_IN_NAMES) {
            assertThat(filter.removes(name)).as(name).isTrue();
            assertThat(filter.removes(name.toLowerCase(Locale.ROOT))).as(name).isTrue();
            assertThat(filter.removes(name.toUpperCase(Locale.ROOT))).as(name).isTrue();
        }
    }

    @Test
    void removesAHeaderForEachBuiltInPrefixInAnyLetterCase() {
        IdentityHeaderRemovalFilter filter = new IdentityHeaderRemovalFilter(NONE);

        for (String prefix : IdentityHeaderRemovalFilter.BUILT_IN_PREFIXES) {
            assertThat(filter.removes(prefix + "Name")).as(prefix).isTrue();
            assertThat(filter.removes((prefix + "Name").toUpperCase(Locale.ROOT))).as(prefix).isTrue();
        }
    }

    // The examples of the spec, with the prefixes of the decision after SEC-47-4.
    @ParameterizedTest
    @ValueSource(strings = {"X-User-Id", "X-Authenticated-User", "X-MS-CLIENT-PRINCIPAL-NAME", "x-ms-client-principal",
        "X-MS-TOKEN-AAD-ACCESS-TOKEN", "Remote-User", "X-HTTP-Method-Override", "X-Original-URL", "X-Original-Host",
        "X-Rewrite-URL", "True-Client-IP", "X-Real-IP", "X-Client-IP", "X-XSRF-TOKEN", "x-xsrf-token", "Forwarded",
        "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Prefix",
        // SEC-61-4
        "X-User", "X-UserId", "X-Username", "X-Principal", "X-Roles", "X-Groups", "X-Remote-Group", "X-Webauth-User",
        "X-Id-Token", "X-Access-Token", "X-Auth-Token", "X-Api-Key", "X-ARR-ClientCert", "X-Client-Cert",
        "Ssl-Client-Cert", "X-WAWS-Unencoded-URL", "X-Envoy-Original-Path", "X-Override-URL", "X-Host",
        "X-HTTP-Host-Override", "CF-Connecting-IP", "X-Cluster-Client-IP", "Client-IP", "X-Auth-Request-Email",
        "X-Amzn-Oidc-Data", "X-Goog-Authenticated-User-Email", "X-ARR-SSL", "X-WAWS-Other",
        // QA-62-1
        "Connection", "connection"})
    void removesTheHeadersOfTheSpec(String name) {
        assertThat(new IdentityHeaderRemovalFilter(NONE).removes(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"X_User_Id", "x_custom", "Accept_Language", "_", "X-Request_Id"})
    void removesEveryHeaderWithAnUnderscore(String name) {
        assertThat(new IdentityHeaderRemovalFilter(NONE).removes(name)).isTrue();
        assertThat(new IdentityHeaderRemovalFilter(CONFIGURED).removes(name)).isTrue();
    }

    @Test
    void removesConfiguredNamesAndPrefixesInAnyLetterCase() {
        IdentityHeaderRemovalFilter filter = new IdentityHeaderRemovalFilter(CONFIGURED);

        assertThat(filter.removes("X-Tenant-Id")).isTrue();
        assertThat(filter.removes("x-tenant-id")).isTrue();
        assertThat(filter.removes("X-INTERNAL-ROLE")).isTrue();
        assertThat(filter.removes("X-Tenant-Ids")).isFalse();
    }

    @Test
    void theConfigurationDoesNotRemoveABuiltInEntry() {
        IdentityHeaderRemovalFilter filter = new IdentityHeaderRemovalFilter(CONFIGURED);

        assertThat(IdentityHeaderRemovalFilter.BUILT_IN_NAMES).allMatch(filter::removes);
        assertThat(IdentityHeaderRemovalFilter.BUILT_IN_PREFIXES).allMatch(prefix -> filter.removes(prefix + "Name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Accept", "Content-Type", "Accept-Language", "X-Request-Id", "User-Agent", "X-Users",
        "X-Originals", "Authorization", "Cookie", "X-Hosts", "X-Api-Version", "X-Auth-Requested"})
    void keepsOtherHeaders(String name) {
        assertThat(new IdentityHeaderRemovalFilter(CONFIGURED).removes(name)).isFalse();
    }

    @Test
    void doesNotStartWithABlankConfiguredValue() {
        assertThatThrownBy(() -> new IdentityHeaderRemovalFilter(
                new GatewaySecurityProperties.RemoveRequestHeaders(List.of(), List.of(" "))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("gateway.security.remove-request-headers.prefixes must not contain a blank value.");
        assertThatThrownBy(() -> new IdentityHeaderRemovalFilter(
                new GatewaySecurityProperties.RemoveRequestHeaders(List.of(""), List.of())))
                .hasMessage("gateway.security.remove-request-headers.names must not contain a blank value.");
    }

    @Test
    void filterRemovesTheHeadersAndKeepsTheOthersUnchanged() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders-api/orders/42")
                .header("X-User-Id", "42")
                .header("X_User_Id", "42")
                .header("X-Forwarded-For", "203.0.113.9")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8"));

        HttpHeaders forwarded = filter(new IdentityHeaderRemovalFilter(NONE), exchange).getRequest().getHeaders();

        assertThat(forwarded.headerNames()).containsExactlyInAnyOrder("Accept", "Content-Type");
        assertThat(forwarded.getFirst("Content-Type")).isEqualTo("application/json; charset=utf-8");
    }

    // QA-62-1: Spring Cloud Gateway removes each header that Connection names, after the route filters.
    @Test
    void filterRemovesTheConnectionHeaderAndKeepsTheHeadersThatItNames() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders-api/orders/42")
                .header("Connection", "close, Authorization, X-Request-Id")
                .header("X-Request-Id", "r-1"));

        HttpHeaders forwarded = filter(new IdentityHeaderRemovalFilter(NONE), exchange).getRequest().getHeaders();

        assertThat(forwarded.containsHeader(HttpHeaders.CONNECTION)).isFalse();
        assertThat(forwarded.getConnection()).isEmpty();
        assertThat(forwarded.get("X-Request-Id")).containsExactly("r-1");
    }

    @Test
    void filterKeepsTheExchangeWithoutHeadersToRemove() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a").header("Accept", "*/*"));

        assertThat(filter(new IdentityHeaderRemovalFilter(NONE), exchange)).isSameAs(exchange);
    }

    @Test
    void runsDirectlyAfterTheRouteOperationFilter() {
        assertThat(new IdentityHeaderRemovalFilter(NONE).getOrder()).isEqualTo(Integer.MIN_VALUE + 1);
    }

    private static ServerWebExchange filter(IdentityHeaderRemovalFilter filter, ServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> next = new AtomicReference<>();
        filter.filter(exchange, forwarded -> {
            next.set(forwarded);
            return Mono.empty();
        }).block();
        return next.get();
    }
}
