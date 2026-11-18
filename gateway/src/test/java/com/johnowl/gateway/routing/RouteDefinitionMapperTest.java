package com.johnowl.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.benchmark.SyntheticSpecs;
import com.johnowl.gateway.core.route.GatewayRoute;
import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.validation.ValidationEngine;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.PrefixPathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RemoveRequestHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.support.Configurable;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.cloud.gateway.support.ShortcutConfigurable;
import org.springframework.core.convert.support.DefaultConversionService;

class RouteDefinitionMapperTest {

    private static final URI UPSTREAM = URI.create("http://orders-service:8080");

    private static final String AUDIENCE = "6f1c2e8a-0000-0000-0000-000000000000";

    private final RouteDefinitionMapper mapper = new RouteDefinitionMapper();

    @Test
    void mapsAllFields() {
        RouteDefinition definition = mapper.map(new GatewayRoute("orders:/orders/{id}", UPSTREAM,
                "/orders-api/orders/{id}", Set.of("GET", "DELETE"), 1, "/v1", 100, 2000, null));

        assertThat(definition.getId()).isEqualTo("orders:/orders/{id}");
        assertThat(definition.getUri()).isEqualTo(UPSTREAM);
        assertThat(definition.getOrder()).isEqualTo(100);
        assertThat(definition.getPredicates()).extracting(PredicateDefinition::getName, PredicateDefinition::getArgs)
                .containsExactly(
                        tuple("Path", Map.of("patterns", "/orders-api/orders/{id}")),
                        tuple("Method", Map.of("_genkey_0", "DELETE", "_genkey_1", "GET")));
        assertThat(definition.getFilters()).extracting(FilterDefinition::getName, FilterDefinition::getArgs)
                .containsExactly(
                        tuple("RemoveRequestHeader", Map.of("name", "Cookie")),
                        tuple("RemoveRequestHeader", Map.of("name", "Authorization")),
                        tuple("StripPrefix", Map.of("parts", "1")),
                        tuple("PrefixPath", Map.of("prefix", "/v1")));
        assertThat(definition.getMetadata()).isEqualTo(Map.of(
                "response-timeout", 2000,
                "oas-path", "/orders-api/orders/{id}",
                "oas-methods", List.of("DELETE", "GET"),
                "public-prefix", "/orders-api",
                "upstream-base-path", "/v1"));
    }

    // Spec 025: the backend response filter maps a backend Location back to the public path.
    @Test
    void thePublicPrefixIsThePartThatStripPrefixRemoves() {
        assertThat(RouteDefinitionMapper.publicPrefix("/orders-api/orders/{id}", 1)).isEqualTo("/orders-api");
        assertThat(RouteDefinitionMapper.publicPrefix("/shop/orders-api/orders", 2)).isEqualTo("/shop/orders-api");
        assertThat(RouteDefinitionMapper.publicPrefix("/orders-api", 1)).isEqualTo("/orders-api");
        assertThat(mapper.map(new GatewayRoute("orders:/orders", UPSTREAM, "/shop/orders-api/orders", Set.of("GET"), 2,
                null, 0, null, null)).getMetadata())
                .containsEntry("public-prefix", "/shop/orders-api")
                .doesNotContainKey("upstream-base-path");
    }

    @Test
    void omitsOptionalFiltersAndMetadata() {
        RouteDefinition definition = mapper.map(new GatewayRoute("orders:/orders/export", UPSTREAM,
                "/orders/export", Set.of("GET"), 0, null, 0, null, null));

        assertThat(definition.getOrder()).isZero();
        assertThat(definition.getFilters()).extracting(FilterDefinition::getName)
                .containsExactly("RemoveRequestHeader", "RemoveRequestHeader");
        assertThat(definition.getMetadata()).isEqualTo(Map.of("oas-path", "/orders/export", "oas-methods", List.of("GET")));
    }

    @Test
    void addsPrefixPathWithoutStripPrefix() {
        RouteDefinition definition = mapper.map(new GatewayRoute("orders:/orders", UPSTREAM,
                "/orders", Set.of("GET"), 0, "/v1", 0, null, null));

        assertThat(definition.getFilters()).extracting(FilterDefinition::getName)
                .containsExactly("RemoveRequestHeader", "RemoveRequestHeader", "PrefixPath");
    }

    // Spec 025: the relay replaces any client value, before the path filters.
    @Test
    void aRouteWithAnAudienceRelaysItsTokenAfterTheHeaderRemoval() {
        RouteDefinition definition = mapper.map(new GatewayRoute("orders:/orders/{id}", UPSTREAM,
                "/orders-api/orders/{id}", Set.of("GET", "DELETE"), 1, "/v1", 100, null, AUDIENCE));

        assertThat(definition.getFilters()).extracting(FilterDefinition::getName, FilterDefinition::getArgs)
                .containsExactly(
                        tuple("RemoveRequestHeader", Map.of("name", "Cookie")),
                        tuple("RemoveRequestHeader", Map.of("name", "Authorization")),
                        tuple("AudienceTokenRelay", Map.of("audience", AUDIENCE)),
                        tuple("StripPrefix", Map.of("parts", "1")),
                        tuple("PrefixPath", Map.of("prefix", "/v1")));
    }

    // Property-style test of spec 025: every generated route removes Cookie and Authorization first. The same
    // route with an audience also has the relay, before the path filters. The synthetic files have no audience.
    @Test
    void everyGeneratedRouteRemovesCookieAndAuthorization() {
        List<GatewayRoute> generated = new ValidationEngine(BuiltInRules.contract())
                .validate(SyntheticSpecs.generate(1000, 42, 8080)).routes();

        assertThat(generated).hasSize(1000);
        for (GatewayRoute route : generated) {
            GatewayRoute withAudience = new GatewayRoute(route.id(), route.upstream(), route.path(), route.methods(),
                    route.stripPrefix(), route.prefixPath(), route.order(), route.responseTimeoutMillis(), AUDIENCE);
            for (GatewayRoute candidate : List.of(route, withAudience)) {
                List<FilterDefinition> filters = mapper.map(candidate).getFilters();
                assertThat(filters.subList(0, 2)).extracting(FilterDefinition::getName, FilterDefinition::getArgs)
                        .containsExactly(
                                tuple("RemoveRequestHeader", Map.of("name", "Cookie")),
                                tuple("RemoveRequestHeader", Map.of("name", "Authorization")));
                assertThat(filters).extracting(FilterDefinition::getName).filteredOn("AudienceTokenRelay"::equals)
                        .hasSize(candidate.audience() == null ? 0 : 1);
            }
            assertThat(mapper.map(withAudience).getFilters().get(2))
                    .extracting(FilterDefinition::getName, FilterDefinition::getArgs)
                    .containsExactly("AudienceTokenRelay", Map.of("audience", AUDIENCE));
        }
    }

    // The shortcut text form "Path=/tags/a,b" gives the two patterns "/tags/a" and "b". PathSyntax
    // rejects commas, but the mapper must not depend on it.
    @Test
    void keepsCommasInPathAndPrefixAsOneValue() {
        RouteDefinition definition = mapper.map(new GatewayRoute("tags:/tags/a,b", UPSTREAM,
                "/tags/a,/internal/admin", Set.of("GET"), 1, "/v1,/v2", 0, null, null));

        PathRoutePredicateFactory.Config path = bind(new PathRoutePredicateFactory(new WebFluxProperties()),
                definition.getPredicates().getFirst().getArgs());
        PrefixPathGatewayFilterFactory.Config prefix = bind(new PrefixPathGatewayFilterFactory(),
                definition.getFilters().get(3).getArgs());
        StripPrefixGatewayFilterFactory.Config strip = bind(new StripPrefixGatewayFilterFactory(),
                definition.getFilters().get(2).getArgs());
        AbstractGatewayFilterFactory.NameConfig cookie = bind(new RemoveRequestHeaderGatewayFilterFactory(),
                definition.getFilters().getFirst().getArgs());

        assertThat(path.getPatterns()).containsExactly("/tags/a,/internal/admin");
        assertThat(prefix.getPrefix()).isEqualTo("/v1,/v2");
        assertThat(strip.getParts()).isEqualTo(1);
        assertThat(cookie.getName()).isEqualTo("Cookie");
    }

    @Test
    void neverChangesBodies() {
        RouteDefinition definition = mapper.map(new GatewayRoute("orders:/orders", UPSTREAM,
                "/a/orders", Set.of("POST"), 1, "/v1", 0, 1000, null));

        assertThat(definition.getFilters()).extracting(FilterDefinition::getName)
                .doesNotContain("ModifyRequestBody", "ModifyResponseBody", "CacheRequestBody");
    }

    /** Binds the arguments in the same way as the route locator of Spring Cloud Gateway. */
    private static <C, F extends Configurable<C> & ShortcutConfigurable> C bind(F factory, Map<String, String> args) {
        ConfigurationService service = new ConfigurationService(new DefaultListableBeanFactory(),
                DefaultConversionService::new, () -> null);
        return service.with(factory).name("test").properties(args).bind();
    }
}
