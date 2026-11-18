package com.johnowl.gateway.routing;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.johnowl.gateway.core.access.AccessRule;
import com.johnowl.gateway.core.access.OperationAccess;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

class OasRoutesOnlyTest {

    private static final List<OperationAccess> OPERATIONS = List.of(
            new OperationAccess("orders-get", "/orders/{id}", "GET", AccessRule.publicAccess()),
            new OperationAccess("orders-get", "/orders/{id}", "HEAD", AccessRule.publicAccess()));

    private final OasRoutesOnly check = new OasRoutesOnly(
            new StaticListableBeanFactory().getBeanProvider(RouteLocator.class), OPERATIONS, Set.of("test-route"));

    @Test
    void acceptsOasRoutesAndListedTestRoutes() {
        assertThatNoException().isThrownBy(() -> check.check(List.of(route("orders-get"), route("test-route"))));
    }

    // SEC-59-3: a route from application.yaml has no access rule in the security index.
    @Test
    void otherRouteStopsTheStartup() {
        assertThatIllegalStateException().isThrownBy(() -> check.check(List.of(route("orders-get"), route("yaml-route"))))
                .withMessage("The route yaml-route does not come from an OAS file. The gateway accepts only the"
                        + " routes of the OAS files, because only they have an access rule (spec 024).");
    }

    @Test
    void secondRouteWithTheIdOfAnOasRouteStopsTheStartup() {
        assertThatIllegalStateException().isThrownBy(() -> check.check(List.of(route("orders-get"), route("orders-get"))))
                .withMessage("The route id orders-get is used by more than one route.");
    }

    @Test
    void withoutRouteLocatorNothingIsChecked() {
        assertThatNoException().isThrownBy(check::afterSingletonsInstantiated);
    }

    @Test
    void testRouteIdsMustComeFromGatewayCode() {
        TestRouteIds proxy = (TestRouteIds) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {TestRouteIds.class}, (target, method, args) -> Set.of("yaml-route"));

        assertThatIllegalStateException().isThrownBy(() -> OpenApiRoutingConfiguration.testRouteIds(List.of(proxy)))
                .withMessage(proxy.getClass().getName() + " is not a valid bean of type TestRouteIds."
                        + " The bean must be a class in the package com.johnowl.gateway, and not a proxy.");
    }

    private static Route route(String id) {
        return Route.async().id(id).uri("http://localhost:9").predicate(exchange -> true).build();
    }
}
