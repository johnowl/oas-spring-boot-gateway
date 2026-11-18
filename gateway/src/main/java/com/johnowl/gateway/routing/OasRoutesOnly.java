package com.johnowl.gateway.routing;

import com.johnowl.gateway.core.access.OperationAccess;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

/**
 * Stops the startup when a route does not come from an OAS file (spec 024, "Only OAS routes"). The
 * security index has an access rule only for the operations of the OAS files. A route from another
 * source, for example from {@code application.yaml}, could take a request that the index checked
 * against the rule of another operation. {@code RouteOperationFilter} also answers {@code 404} for such
 * a request, at runtime.
 *
 * <p>The check runs once, on the main thread, after all singletons exist and before the web server
 * starts. It reads the routes of the route locator; the OAS routes are in memory, so the call does not
 * wait for I/O.
 */
final class OasRoutesOnly implements SmartInitializingSingleton {

    private final ObjectProvider<RouteLocator> routeLocator;

    private final Set<String> oasRouteIds;

    private final Set<String> testRouteIds;

    OasRoutesOnly(ObjectProvider<RouteLocator> routeLocator, List<OperationAccess> operations, Set<String> testRouteIds) {
        this.routeLocator = routeLocator;
        this.oasRouteIds = operations.stream().map(OperationAccess::routeId).collect(Collectors.toUnmodifiableSet());
        this.testRouteIds = Set.copyOf(testRouteIds);
    }

    @Override
    public void afterSingletonsInstantiated() {
        RouteLocator locator = routeLocator.getIfAvailable();
        if (locator != null) {
            check(locator.getRoutes().collectList().block());
        }
    }

    /**
     * @throws IllegalStateException for the first route that is not an OAS route or a listed test route,
     *     and for a route id that more than one route uses
     */
    void check(List<Route> routes) {
        Set<String> seen = new HashSet<>();
        for (Route route : routes) {
            String id = route.getId();
            if (!seen.add(id)) {
                throw new IllegalStateException("The route id " + id + " is used by more than one route.");
            }
            if (!oasRouteIds.contains(id) && !testRouteIds.contains(id)) {
                throw new IllegalStateException("The route " + id + " does not come from an OAS file. The gateway"
                        + " accepts only the routes of the OAS files, because only they have an access rule (spec 024).");
            }
        }
    }
}
