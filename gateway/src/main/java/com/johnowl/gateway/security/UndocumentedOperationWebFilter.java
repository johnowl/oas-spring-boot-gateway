package com.johnowl.gateway.security;

import com.johnowl.gateway.core.access.OperationAccess;
import java.util.List;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Answers {@code 404} for each request without an access rule, before the security chain reads the
 * session (spec 024, "Undocumented operation filter"). An undocumented path and an undocumented method
 * on a documented path get the same generic {@code 404}, for every client. The request causes no
 * access to the session store.
 *
 * <p>For a documented operation, the filter stores the {@link OperationAccess} in the exchange attribute
 * {@link GatewaySecurityAttributes#OPERATION_ACCESS}, so the authorization manager does not search the
 * security index again.
 *
 * <p>The filter lets three endpoints of the session chain of spec 022 pass, each only with its method:
 * {@code GET /oauth2/authorization/entra}, {@code GET /login/oauth2/code/entra}, and {@code POST /logout}.
 * Every other method and registration id on these paths gets the generic {@code 404}. In the management
 * context (actuator, spec 002), the filter lets only the paths under the actuator base path pass. It reads
 * only the request line, never the body.
 */
public final class UndocumentedOperationWebFilter implements WebFilter, Ordered {

    /**
     * Before the {@code WebFilterChainProxy} of Spring Security, which has the order {@code -100}
     * ({@code WebFluxSecurityConfiguration.WEB_FILTER_CHAIN_FILTER_ORDER}, not public). A test checks the order
     * of both filters in the application context.
     */
    public static final int ORDER = -101;

    /** The endpoints that the session chain owns (spec 022, "Endpoints of the security chain"). */
    private static final List<Endpoint> EXCLUDED = List.of(
            new Endpoint(HttpMethod.GET, "/oauth2/authorization/" + EntraAuthorizationRequestResolver.REGISTRATION_ID),
            new Endpoint(HttpMethod.GET, "/login/oauth2/code/" + EntraAuthorizationRequestResolver.REGISTRATION_ID),
            new Endpoint(HttpMethod.POST, "/logout"));

    private final SecurityIndex index;

    private final ManagementEndpoints management;

    private final AuthorizationDenials denials;

    public UndocumentedOperationWebFilter(SecurityIndex index, ManagementEndpoints management,
            AuthorizationDenials denials) {
        this.index = index;
        this.management = management;
        this.denials = denials;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // The management context uses the web filters of the main context (spec 022).
        if (ManagementEndpoints.isManagementRequest(exchange)) {
            return management.isActuatorPath(exchange)
                    ? chain.filter(exchange)
                    : ProblemResponses.write(exchange, HttpStatus.NOT_FOUND);
        }
        if (isExcluded(exchange)) {
            return chain.filter(exchange);
        }
        Optional<OperationAccess> operation = index.find(exchange);
        if (operation.isEmpty()) {
            // A NO_MATCH denial: one DEBUG line and the counter, without the principal (the session is not read).
            denials.denied(exchange, DenyReason.NO_MATCH, null, null);
            return ProblemResponses.write(exchange, HttpStatus.NOT_FOUND);
        }
        exchange.getAttributes().put(GatewaySecurityAttributes.OPERATION_ACCESS, operation.get());
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private static boolean isExcluded(ServerWebExchange exchange) {
        // The same path as the matchers of Spring Security.
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        HttpMethod method = exchange.getRequest().getMethod();
        for (Endpoint endpoint : EXCLUDED) {
            if (endpoint.method().equals(method) && endpoint.pattern().matches(path)) {
                return true;
            }
        }
        return false;
    }

    /** One endpoint of the session chain: a method and a path without variables. */
    private record Endpoint(HttpMethod method, PathPattern pattern) {

        Endpoint(HttpMethod method, String path) {
            this(method, PathPatternParser.defaultInstance.parse(path));
        }
    }
}
