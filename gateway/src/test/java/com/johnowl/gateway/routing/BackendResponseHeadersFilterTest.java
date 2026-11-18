package com.johnowl.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class BackendResponseHeadersFilterTest {

    private static final URI PUBLIC_BASE_URL = URI.create("https://gateway.example.com");

    private final BackendResponseHeadersFilter filter = new BackendResponseHeadersFilter(PUBLIC_BASE_URL, null);

    private final MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a"));

    @Test
    void removesSetCookieAndSetCookie2InAnyCase() {
        HttpHeaders backend = new HttpHeaders();
        backend.add("Set-Cookie", "tracking=1");
        backend.add("set-cookie", "other=2");
        backend.add("SET-COOKIE2", "legacy=1");
        backend.add("Content-Type", "application/json");
        backend.add("X-Request-Id", "42");

        HttpHeaders filtered = filter.filter(backend, exchange);

        assertThat(filtered.headerNames()).containsExactlyInAnyOrder("Content-Type", "X-Request-Id", "X-Frame-Options",
                "X-Content-Type-Options", "Referrer-Policy");
        assertThat(filtered.getFirst("X-Request-Id")).isEqualTo("42");
    }

    @Test
    void removesCorsHeadersAndClearSiteDataInAnyCase() {
        HttpHeaders backend = new HttpHeaders();
        backend.add("Access-Control-Allow-Origin", "*");
        backend.add("access-control-allow-credentials", "true");
        backend.add("ACCESS-CONTROL-EXPOSE-HEADERS", "X-Request-Id");
        backend.add("Access-Control-Max-Age", "600");
        backend.add("clear-site-data", "\"cookies\"");
        backend.add("Cache-Control", "no-store");
        backend.add("Content-Type", "application/json");

        HttpHeaders filtered = filter.filter(backend, exchange);

        assertThat(filtered.headerNames()).containsExactlyInAnyOrder("Cache-Control", "Content-Type", "X-Frame-Options",
                "X-Content-Type-Options", "Referrer-Policy");
        assertThat(filtered.getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(filtered.getFirst("Content-Type")).isEqualTo("application/json");
    }

    // SEC-61-1 and SEC-61-3: headers that act on the whole gateway site, and the sign-in challenge of the backend.
    @ParameterizedTest
    @ValueSource(strings = {"Strict-Transport-Security", "strict-transport-security", "NEL", "Report-To",
        "Reporting-Endpoints", "Service-Worker-Allowed", "Alt-Svc", "Origin-Agent-Cluster", "Accept-CH",
        "CRITICAL-CH", "WWW-Authenticate", "www-authenticate"})
    void removesHeadersThatActOnTheWholeSite(String name) {
        HttpHeaders backend = new HttpHeaders();
        backend.add(name, "value");
        backend.add("Content-Type", "application/json");

        HttpHeaders filtered = filter.filter(backend, exchange);

        assertThat(filtered.containsHeader(name)).isFalse();
        assertThat(filtered.getFirst("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void overwritesTheBrowserPoliciesOfTheBackend() {
        HttpHeaders backend = new HttpHeaders();
        backend.add("x-frame-options", "ALLOW-FROM https://evil.example.com");
        backend.add("X-Content-Type-Options", "none");
        backend.add("REFERRER-POLICY", "unsafe-url");

        HttpHeaders filtered = filter.filter(backend, exchange);

        assertThat(filtered.get("X-Frame-Options")).containsExactly("DENY");
        assertThat(filtered.get("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(filtered.get("Referrer-Policy")).containsExactly("no-referrer");
        assertThat(filtered.headerNames()).hasSize(3);
    }

    @Test
    void keepsOtherResponseHeadersUnchanged() {
        HttpHeaders backend = new HttpHeaders();
        backend.add("Content-Type", "text/plain");
        backend.add("Vary", "Access-Control-Request-Method");
        backend.add("Cache-Control", "max-age=60");

        HttpHeaders filtered = filter.filter(backend, exchange);

        assertThat(filtered.get("Content-Type")).containsExactly("text/plain");
        assertThat(filtered.get("Vary")).containsExactly("Access-Control-Request-Method");
        assertThat(filtered.get("Cache-Control")).containsExactly("max-age=60");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            http://orders-service:8080/v1/orders/43                | https://gateway.example.com/orders-api/orders/43
            http://ORDERS-SERVICE:8080/v1/orders/43?page=2#top     | https://gateway.example.com/orders-api/orders/43?page=2#top
            HTTP://orders-service:8080/v1                          | https://gateway.example.com/orders-api
            http://orders-service:8080/v1/                         | https://gateway.example.com/orders-api/
            //orders-service:8080/v1/orders/43                     | https://gateway.example.com/orders-api/orders/43
            """)
    void rewritesALocationOfTheUpstreamToThePublicUrl(String location, String expected) {
        assertThat(location(filter, ordersRoute("http://orders-service:8080"), location)).isEqualTo(expected);
    }

    @Test
    void rewritesALocationOfAnUpstreamWithTheDefaultPort() {
        Route route = ordersRoute("http://orders-service");

        assertThat(location(filter, route, "http://orders-service:80/v1/orders/43"))
                .isEqualTo("https://gateway.example.com/orders-api/orders/43");
        assertThat(location(filter, route, "http://orders-service/v1/orders/43"))
                .isEqualTo("https://gateway.example.com/orders-api/orders/43");
    }

    @Test
    void rewritesALocationOfARouteWithoutPrefixAndBasePath() {
        Route route = route("http://orders-service:8080", Map.of());

        assertThat(location(filter, route, "http://orders-service:8080/orders/43"))
                .isEqualTo("https://gateway.example.com/orders/43");
        assertThat(location(filter, route, "http://orders-service:8080"))
                .isEqualTo("https://gateway.example.com/");
    }

    @Test
    void addsTheWebFluxBasePath() {
        BackendResponseHeadersFilter withBasePath = new BackendResponseHeadersFilter(PUBLIC_BASE_URL, "/api");

        assertThat(location(withBasePath, ordersRoute("http://orders-service:8080"), "http://orders-service:8080/v1/orders/43"))
                .isEqualTo("https://gateway.example.com/api/orders-api/orders/43");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://evil.example.com/orders/43",
        "//evil.example.com/orders/43",
        "https://orders-service:8080/v1/orders/43",
        "http://orders-service:8081/v1/orders/43",
        "http://user@orders-service:8080/v1/orders/43",
        "http://orders-service:8080/admin",
        "http://orders-service:8080/v10/orders",
        "javascript:alert(1)",
        "http://orders-service:8080/a b"})
    void removesAnyOtherAbsoluteLocation(String location) {
        assertThat(location(filter, ordersRoute("http://orders-service:8080"), location)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/orders/43", "orders/43", "?page=2"})
    void keepsARelativeLocation(String location) {
        assertThat(location(filter, ordersRoute("http://orders-service:8080"), location)).isEqualTo(location);
    }

    @Test
    void removesAnAbsoluteLocationWithoutARoute() {
        HttpHeaders backend = new HttpHeaders();
        backend.add("Location", "http://orders-service:8080/v1/orders/43");

        assertThat(filter.filter(backend, exchange).containsHeader("Location")).isFalse();
    }

    @Test
    void appliesOnlyToResponses() {
        assertThat(filter.supports(HttpHeadersFilter.Type.RESPONSE)).isTrue();
        assertThat(filter.supports(HttpHeadersFilter.Type.REQUEST)).isFalse();
    }

    /** The {@code Location} header of the client response, or {@code null}. */
    private static String location(BackendResponseHeadersFilter filter, Route route, String location) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders-api/orders"));
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        HttpHeaders backend = new HttpHeaders();
        backend.add("location", location);
        return filter.filter(backend, exchange).getFirst("Location");
    }

    /** The route of {@code prefix: /orders-api} and {@code upstream-base-path: /v1}. */
    private static Route ordersRoute(String upstream) {
        return route(upstream, Map.of(RouteDefinitionMapper.PUBLIC_PREFIX, "/orders-api",
                RouteDefinitionMapper.UPSTREAM_BASE_PATH, "/v1"));
    }

    private static Route route(String upstream, Map<String, Object> metadata) {
        return Route.async()
                .id("orders:/orders")
                .uri(URI.create(upstream))
                .predicate(exchange -> true)
                .metadata(new LinkedHashMap<>(metadata))
                .build();
    }
}
