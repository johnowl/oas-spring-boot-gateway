package com.johnowl.gateway.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.server.PathContainer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The actuator of the management context (spec 002), as the security chain sees it (spec 024,
 * "Management port"). The management chain and {@link UndocumentedOperationWebFilter} let a request of
 * the management context pass only under the actuator base path. Every other path gets {@code 404}.
 *
 * <p>The checks at startup keep the actuator on its own fixed port, which the network policy restricts.
 * With {@code management.server.port=0}, the port is random, so no network policy can restrict it.
 */
public final class ManagementEndpoints {

    static final String MANAGEMENT_NAMESPACE = "management";

    /** The defaults of Spring Boot: the base path {@code /actuator}. */
    public static final ManagementEndpoints DEFAULT = new ManagementEndpoints("/actuator");

    private final String[] patterns;

    private final List<PathPattern> parsed;

    /**
     * @param basePath {@code management.endpoints.web.base-path} without a trailing {@code /}, for example
     *     {@code /actuator}, or the empty text for the root
     */
    ManagementEndpoints(String basePath) {
        this.patterns = basePath.isEmpty() ? new String[] {"/**"} : new String[] {basePath, basePath + "/**"};
        this.parsed = Arrays.stream(patterns).map(PathPatternParser.defaultInstance::parse).toList();
    }

    /**
     * Reads and checks the settings. Startup only.
     *
     * @throws IllegalStateException if {@code management.server.port} is {@code 0} or equals {@code server.port}
     */
    public static ManagementEndpoints of(Environment environment) {
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        int serverPort = environment.getProperty("server.port", Integer.class, 8080);
        if (managementPort != null && managementPort == 0) {
            throw new IllegalStateException("management.server.port must not be 0. The actuator needs a fixed port"
                    + " that the network policy restricts (spec 002).");
        }
        if (managementPort != null && managementPort == serverPort) {
            throw new IllegalStateException("management.server.port must differ from server.port (" + serverPort
                    + "). The actuator needs its own port (spec 002).");
        }
        String basePath = environment.getProperty("management.endpoints.web.base-path", "/actuator");
        while (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        return new ManagementEndpoints(basePath);
    }

    /** The patterns of the actuator paths, for the matchers of Spring Security. */
    String[] patterns() {
        return patterns.clone();
    }

    /** {@code true} if the request comes from the management context (server namespace {@code management}). */
    static boolean isManagementRequest(ServerWebExchange exchange) {
        return WebServerApplicationContext.hasServerNamespace(exchange.getApplicationContext(), MANAGEMENT_NAMESPACE);
    }

    /** {@code true} if the path is the actuator base path or below it. The same path as the matchers of Spring Security. */
    boolean isActuatorPath(ServerWebExchange exchange) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        for (PathPattern pattern : parsed) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }
}
