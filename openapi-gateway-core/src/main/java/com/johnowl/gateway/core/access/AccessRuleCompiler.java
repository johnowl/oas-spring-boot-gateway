package com.johnowl.gateway.core.access;

import com.johnowl.gateway.core.route.GatewayRoute;
import com.johnowl.gateway.core.route.XGatewaySettings;
import com.johnowl.gateway.core.spec.SpecFile;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles the access rule of each operation of one OAS file. The file must have no CONTRACT
 * violations. A file without {@code x-gateway.upstream} gives no access rules, like it gives no routes.
 */
public final class AccessRuleCompiler {

    public static final String EXTENSION = "x-gateway-authorization";

    /**
     * @param routes the routes of the same file, from {@code GatewayRouteBuilder}
     */
    public List<OperationAccess> compile(SpecFile spec, List<GatewayRoute> routes) {
        XGatewaySettings xGateway = XGatewaySettings.of(spec).orElse(null);
        if (xGateway == null || spec.openApi().getPaths() == null) {
            return List.of();
        }
        List<OperationAccess> result = new ArrayList<>();
        spec.openApi().getPaths().forEach((oasPath, item) -> {
            if (item.get$ref() != null) {
                // SpecLoader rejects this file (SEC-66-1). The model has no operations for the reference.
                throw new IllegalStateException("The path item " + oasPath + " in " + spec.path()
                        + " is an unresolved reference");
            }
            String publicPath = xGateway.publicPath(oasPath);
            for (Map.Entry<PathItem.HttpMethod, Operation> entry : item.readOperationsMap().entrySet()) {
                String method = entry.getKey().name();
                String routeId = routes.stream()
                        .filter(route -> route.path().equals(publicPath) && route.methods().contains(method))
                        .map(GatewayRoute::id)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "No route for " + method + " " + publicPath + " in " + spec.path()));
                EffectiveSecurity security = EffectiveSecurity.of(spec, oasPath, method);
                AccessRule rule = security.isPublic()
                        ? AccessRule.publicAccess()
                        : rule(xGateway.audience(), entry.getValue());
                result.add(new OperationAccess(routeId, publicPath, method, rule));
            }
        });
        return result;
    }

    private static AccessRule rule(String audience, Operation operation) {
        Object extension = operation.getExtensions() == null ? null : operation.getExtensions().get(EXTENSION);
        if (!(extension instanceof Map<?, ?> authorization)) {
            return new AccessRule(false, audience, Set.of(), Set.of());
        }
        return new AccessRule(false, audience, strings(authorization.get("all-of")), strings(authorization.get("any-of")));
    }

    private static Set<String> strings(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Collection<?> values) {
            values.forEach(v -> result.add(String.valueOf(v)));
        }
        return result;
    }
}
