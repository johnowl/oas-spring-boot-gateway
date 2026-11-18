package com.johnowl.gateway.routing;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

/**
 * Rejects request paths that a backend can read as a different path than the route does. A route
 * variable such as {@code {id}} matches one segment, and the gateway forwards the raw path. A
 * backend that removes dot segments or decodes {@code %2F} could then serve an undocumented path.
 * The route lookup and the security index (spec 024) remove matrix parameters and decode the path,
 * but a backend may not: {@code /users/me;x=1} and {@code /users/m%65} match {@code /users/me}, and a
 * backend can read them as {@code /users/{id}}.
 *
 * <p>The filter runs first, before the route lookup, and returns 400 with no body for a path that
 * contains:
 *
 * <ul>
 *   <li>{@code %2F} or {@code %5C}, in any case (an encoded {@code /} or {@code \});
 *   <li>a {@code ;} (matrix parameters), also when it is percent-encoded ({@code %3B}, in any case). A
 *       backend that decodes the path before it removes matrix parameters reads {@code %3B} as {@code ;};
 *   <li>a percent-encoded unreserved character of RFC 3986 ({@code ALPHA}, {@code DIGIT}, {@code -},
 *       {@code .}, {@code _}, {@code ~}), for example {@code %65} for {@code e}. A client never needs
 *       to encode these characters;
 *   <li>a backslash;
 *   <li>a {@code .} or {@code ..} segment, also when it is percent-encoded ({@code %2e}) or has
 *       matrix parameters ({@code ..;x});
 *   <li>an invalid percent-encoding.
 * </ul>
 *
 * <p>It reads only the request line. It never reads or buffers the body.
 */
@Component
public final class RequestPathFirewall implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isRejected(exchange.getRequest().getURI().getRawPath())) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** {@code true} if the raw (still percent-encoded) path must not reach a route. */
    static boolean isRejected(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        String lowerCase = rawPath.toLowerCase(Locale.ROOT);
        if (rawPath.indexOf('\\') >= 0 || rawPath.indexOf(';') >= 0
                || lowerCase.contains("%2f") || lowerCase.contains("%5c") || lowerCase.contains("%3b")
                || hasEncodedUnreserved(rawPath)) {
            return true;
        }
        for (String segment : rawPath.split("/", -1)) {
            String decoded;
            try {
                decoded = UriUtils.decode(segment, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return true;
            }
            int semicolon = decoded.indexOf(';');
            String name = semicolon < 0 ? decoded : decoded.substring(0, semicolon);
            if (name.equals(".") || name.equals("..")) {
                return true;
            }
        }
        return false;
    }

    /** {@code true} if the path has a valid percent-encoding of an unreserved character. */
    private static boolean hasEncodedUnreserved(String rawPath) {
        for (int i = rawPath.indexOf('%'); i >= 0 && i + 2 < rawPath.length(); i = rawPath.indexOf('%', i + 1)) {
            int high = Character.digit(rawPath.charAt(i + 1), 16);
            int low = Character.digit(rawPath.charAt(i + 2), 16);
            if (high >= 0 && low >= 0 && isUnreserved((char) (high * 16 + low))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
