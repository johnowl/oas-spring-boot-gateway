package com.johnowl.gateway.routing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Removes spoofable request headers before the gateway sends a request to a backend (spec 025, "Request
 * header removal"): identity headers, method and URL override headers, client address headers, the CSRF
 * header, and the forwarding headers of the client. It also removes every header whose name contains
 * {@code _}, because some servers read {@code X_User_Id} as {@code X-User-Id}. The comparison is
 * case-insensitive.
 *
 * <p>It removes the {@code Connection} header of the client. The header is hop-by-hop, and Netty sets its
 * own value for the connection to the backend. Spring Cloud Gateway removes each header that
 * {@code Connection} names, after the route filters: with {@code Connection: Authorization}, the backend
 * would not receive the relayed token.
 *
 * <p>The configuration adds names and prefixes to the built-in list. It cannot remove a built-in entry or
 * the rule for {@code _}. The route filters remove {@code Cookie} and {@code Authorization}.
 *
 * <p>The filter runs on every route, directly after {@code RouteOperationFilter} and before the route
 * filters. It reads and changes only the request headers, never the body.
 */
public final class IdentityHeaderRemovalFilter implements GlobalFilter, Ordered {

    /** The built-in exact names. */
    static final List<String> BUILT_IN_NAMES = List.of(
            "X-Forwarded-User", "X-Remote-User", "Remote-User",
            "X-User", "X-UserId", "X-Username", "X-Principal", "X-Roles", "X-Groups", "X-Remote-Group",
            "X-Webauth-User",
            "X-Id-Token", "X-Access-Token", "X-Auth-Token", "X-Api-Key",
            "X-Client-Cert", "Ssl-Client-Cert",
            "X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override",
            "X-Original-URL", "X-Rewrite-URL", "X-Envoy-Original-Path", "X-Override-URL",
            "X-Host", "X-HTTP-Host-Override",
            "X-Real-IP", "X-Client-IP", "True-Client-IP", "CF-Connecting-IP", "X-Cluster-Client-IP", "Client-IP",
            "X-XSRF-TOKEN",
            "Forwarded",
            "Connection");

    /** The built-in prefixes. */
    static final List<String> BUILT_IN_PREFIXES = List.of(
            "X-User-", "X-Authenticated-", "X-Ms-Client-Principal", "X-Ms-Token-",
            "X-Auth-Request-", "X-Amzn-Oidc-", "X-Goog-Authenticated-", "X-Arr-", "X-Waws-",
            "X-Original-",
            "X-Forwarded-");

    /** Directly after {@code RouteOperationFilter}, which has the highest precedence. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    private final Set<String> names = new HashSet<>();

    private final List<String> prefixes = new ArrayList<>();

    /**
     * @throws IllegalStateException if a configured name or prefix is blank
     */
    public IdentityHeaderRemovalFilter(GatewaySecurityProperties.RemoveRequestHeaders configured) {
        BUILT_IN_NAMES.forEach(name -> names.add(lowerCase(name)));
        BUILT_IN_PREFIXES.forEach(prefix -> prefixes.add(lowerCase(prefix)));
        configured.names().forEach(name -> names.add(checked("names", name)));
        configured.prefixes().forEach(prefix -> prefixes.add(checked("prefixes", prefix)));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        List<String> removed = exchange.getRequest().getHeaders().headerNames().stream().filter(this::removes).toList();
        if (removed.isEmpty()) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange.mutate()
                .request(request -> request.headers(headers -> removed.forEach(headers::remove)))
                .build());
    }

    /** {@code true} if the gateway removes the request header with this name. */
    boolean removes(String name) {
        String lowerCase = lowerCase(name);
        if (lowerCase.indexOf('_') >= 0 || names.contains(lowerCase)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (lowerCase.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /** A blank prefix would remove every header, so the gateway does not start with a blank value. */
    private static String checked(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("gateway.security.remove-request-headers." + property
                    + " must not contain a blank value.");
        }
        return lowerCase(value.strip());
    }

    private static String lowerCase(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
