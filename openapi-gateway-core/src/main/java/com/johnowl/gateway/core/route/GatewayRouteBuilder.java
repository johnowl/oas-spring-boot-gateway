package com.johnowl.gateway.core.route;

import com.johnowl.gateway.core.spec.SpecFile;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Converts one OAS file into gateway routes. The file must have no CONTRACT violations. A file
 * without {@code x-gateway.upstream} gives no routes.
 *
 * <p>One route for each OAS path, with all methods of the path. If the operations of a path have
 * different response timeouts, one route for each operation.
 */
public final class GatewayRouteBuilder {

    public static final String RESPONSE_TIMEOUT_EXTENSION = "x-gateway-response-timeout";

    private static final Comparator<GatewayRoute> ORDER = Comparator.comparing(GatewayRoute::path)
            .thenComparing(route -> route.methods().iterator().next());

    public List<GatewayRoute> build(SpecFile spec) {
        Optional<XGatewaySettings> xGateway = XGatewaySettings.of(spec);
        if (xGateway.isEmpty()) {
            // The CONTRACT rule x-gateway-schema reports this file. The gateway always runs it.
            return List.of();
        }
        Settings settings = new Settings(fileName(spec.path()), xGateway.get());

        List<GatewayRoute> routes = new ArrayList<>();
        if (spec.openApi().getPaths() != null) {
            spec.openApi().getPaths().forEach((path, item) -> {
                requireResolved(spec, path, item);
                routes.addAll(routes(settings, path, item));
            });
        }
        routes.sort(ORDER);
        return routes;
    }

    /**
     * A path item that is still a {@code $ref} has no operations in the model. Without its routes, a request can
     * match a sibling template route and get its access rule (SEC-66-1). {@code SpecLoader} rejects such a file;
     * this check stops the gateway if a caller skips the loader.
     */
    private static void requireResolved(SpecFile spec, String oasPath, PathItem item) {
        if (item.get$ref() != null) {
            throw new IllegalStateException("The path item " + oasPath + " in " + spec.path()
                    + " is an unresolved reference");
        }
    }

    private static List<GatewayRoute> routes(Settings settings, String oasPath, PathItem item) {
        Map<PathItem.HttpMethod, Operation> operations = item.readOperationsMap();
        if (operations.isEmpty()) {
            return List.of();
        }
        long timeouts = operations.values().stream().map(GatewayRouteBuilder::timeout).distinct().count();
        if (timeouts == 1) {
            Set<String> methods = new TreeSet<>();
            operations.keySet().forEach(method -> methods.add(method.name()));
            Integer timeout = timeout(operations.values().iterator().next());
            return List.of(route(settings, id(settings.file(), null, oasPath), oasPath, methods, timeout));
        }
        List<GatewayRoute> split = new ArrayList<>();
        operations.forEach((method, operation) -> split.add(route(
                settings,
                id(settings.file(), method.name(), oasPath),
                oasPath,
                Set.of(method.name()),
                timeout(operation))));
        return split;
    }

    private static GatewayRoute route(
            Settings settings, String id, String oasPath, Set<String> methods, Integer timeout) {
        XGatewaySettings xGateway = settings.xGateway();
        String path = xGateway.publicPath(oasPath);
        return new GatewayRoute(
                id,
                xGateway.upstream(),
                path,
                methods,
                segments(xGateway.prefix()),
                xGateway.upstreamBasePath(),
                path.contains("{") ? GatewayRoute.TEMPLATE_ORDER : GatewayRoute.LITERAL_ORDER,
                timeout,
                xGateway.audience());
    }

    private static Integer timeout(Operation operation) {
        Map<String, Object> extensions = operation.getExtensions();
        Object value = extensions == null ? null : extensions.get(RESPONSE_TIMEOUT_EXTENSION);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static int segments(String prefix) {
        if (prefix == null) {
            return 0;
        }
        return (int) prefix.chars().filter(c -> c == '/').count();
    }

    /** {@code orders:/orders/{id}} for a grouped route, {@code orders:GET:/orders/export} for a split route. */
    private static String id(String file, String method, String oasPath) {
        return method == null ? file + ":" + oasPath : file + ":" + method + ":" + oasPath;
    }

    /** The file name without directory and extension: {@code openapi/orders.yaml} gives {@code orders}. */
    static String fileName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private record Settings(String file, XGatewaySettings xGateway) {
    }
}
