package com.johnowl.gateway.routing;

import java.util.Set;

/**
 * The ids of the routes without an OAS file that a test defines (spec 024, "Only OAS routes"). The
 * gateway does not start with such a route, unless a bean of this type lists its id. Only
 * {@code gateway/src/test} implements this interface; no production code and no property registers it.
 *
 * <p>The class of each bean MUST be in the package {@code com.johnowl.gateway} or a subpackage, and MUST
 * NOT be a proxy, the same rule as for {@link AudienceAuthorizationActive}.
 */
public interface TestRouteIds {

    /** The route ids. */
    Set<String> routeIds();
}
