package com.johnowl.gateway.routing;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.accept.ApiVersionStrategy;

/**
 * Replaces the route lookup of Spring Cloud Gateway with the indexed lookup (spec 017). The
 * auto-configuration declares its {@link RoutePredicateHandlerMapping} with
 * {@code @ConditionalOnMissingBean}, so this bean replaces it. If a {@link MeterRegistry} exists,
 * the counter {@code gateway.route.index.fallback} shows the fallbacks to the linear lookup.
 */
@Configuration(proxyBeanMethods = false)
public class IndexedRouteLookupConfiguration {

    @Bean
    RoutePredicateHandlerMapping routePredicateHandlerMapping(FilteringWebHandler webHandler,
            RouteLocator routeLocator, GlobalCorsProperties globalCorsProperties, Environment environment,
            ObjectProvider<ApiVersionStrategy> apiVersionStrategy, ObjectProvider<WebFluxProperties> webFluxProperties,
            ObjectProvider<MeterRegistry> meterRegistry) {
        // The same base path that PathRoutePredicateFactory adds before each pattern.
        WebFluxProperties properties = webFluxProperties.getIfAvailable();
        IndexedRoutePredicateHandlerMapping handlerMapping = new IndexedRoutePredicateHandlerMapping(webHandler,
                routeLocator, globalCorsProperties, environment, properties == null ? null : properties.getBasePath());
        apiVersionStrategy.ifAvailable(handlerMapping::setApiVersionStrategy);
        meterRegistry.ifAvailable(registry -> FunctionCounter
                .builder("gateway.route.index.fallback", handlerMapping,
                        IndexedRoutePredicateHandlerMapping::fallbackCount)
                .description("Lookups where the route index candidate did not match and the linear lookup ran")
                .register(registry));
        return handlerMapping;
    }
}
