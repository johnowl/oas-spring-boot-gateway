package com.johnowl.gateway.routing;

import com.johnowl.gateway.core.rules.BuiltInRules;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.util.ClassUtils;

/** Creates the routes from the OAS files. The gateway runs only the CONTRACT rules. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GatewayOpenApiProperties.class, GatewaySecurityProperties.class})
public class OpenApiRoutingConfiguration {

    private static final String GATEWAY_PACKAGE = "com.johnowl.gateway";

    @Bean
    RouteDefinitionMapper routeDefinitionMapper() {
        return new RouteDefinitionMapper();
    }

    /** Removes spoofable request headers on every route (spec 025, "Request header removal"). */
    @Bean
    IdentityHeaderRemovalFilter identityHeaderRemovalFilter(GatewaySecurityProperties properties) {
        return new IdentityHeaderRemovalFilter(properties.removeRequestHeaders());
    }

    @Bean
    OpenApiRouteDefinitionLocator openApiRouteDefinitionLocator(
            GatewayOpenApiProperties properties,
            ResourceLoader resourceLoader,
            RouteDefinitionMapper mapper,
            ObjectProvider<AudienceAuthorizationActive> authorization) {
        return new OpenApiRouteDefinitionLocator(
                properties,
                ResourcePatternUtils.getResourcePatternResolver(resourceLoader),
                BuiltInRules.contract(),
                mapper,
                authorizationActive(authorization.stream().toList()));
    }

    /** Stops the startup when a route does not come from an OAS file (spec 024, "Only OAS routes"). */
    @Bean
    OasRoutesOnly oasRoutesOnly(ObjectProvider<RouteLocator> routeLocator, OpenApiRouteDefinitionLocator locator,
            ObjectProvider<TestRouteIds> testRoutes) {
        return new OasRoutesOnly(routeLocator, locator.accessRules(), testRouteIds(testRoutes.stream().toList()));
    }

    /**
     * Accepts a marker bean only if gateway code creates it. A bean definition from outside the code, for
     * example an XML file in {@code spring.main.sources} with a {@code ProxyFactoryBean}, gives a proxy or a
     * class of another package, and stops the startup.
     */
    static boolean authorizationActive(List<AudienceAuthorizationActive> markers) {
        markers.forEach(marker -> requireGatewayClass(marker, AudienceAuthorizationActive.class));
        return !markers.isEmpty();
    }

    /** The ids of all beans of {@link TestRouteIds}, with the same check as for {@link AudienceAuthorizationActive}. */
    static Set<String> testRouteIds(List<TestRouteIds> beans) {
        Set<String> ids = new HashSet<>();
        for (TestRouteIds bean : beans) {
            requireGatewayClass(bean, TestRouteIds.class);
            ids.addAll(bean.routeIds());
        }
        return ids;
    }

    private static void requireGatewayClass(Object bean, Class<?> beanType) {
        Class<?> type = bean.getClass();
        boolean proxy = Proxy.isProxyClass(type) || ClassUtils.getUserClass(type) != type;
        if (proxy || !(type.getPackageName() + ".").startsWith(GATEWAY_PACKAGE + ".")) {
            throw new IllegalStateException(type.getName() + " is not a valid bean of type "
                    + beanType.getSimpleName() + ". The bean must be a class in the package "
                    + GATEWAY_PACKAGE + ", and not a proxy.");
        }
    }
}
