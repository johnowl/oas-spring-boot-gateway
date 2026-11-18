package com.johnowl.gateway.security;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Decides which requests the request cache saves (spec 022, "Anonymous sessions"). It saves only an
 * HTML {@code GET} navigation, and only if:
 *
 * <ul>
 *   <li>the deny reason is not {@link DenyReason#NO_MATCH}, {@link DenyReason#SESSION_INVALID}, or
 *       {@link DenyReason#REAUTHENTICATION_REQUIRED};
 *   <li>the path and the query have at most {@value #MAX_BYTES} bytes;
 *   <li>the path does not start with {@code //}. After login, the browser goes to the saved path, and
 *       {@code //host} is a URL of another host.
 * </ul>
 *
 * A request that is not saved does not start a session. After login, the browser then goes to {@code /}.
 */
final class SavedRequestMatcher implements ServerWebExchangeMatcher {

    static final int MAX_BYTES = 1024;

    private final ServerWebExchangeMatcher html = SessionAuthenticationEntryPoint.htmlNavigation();

    private final ServerWebExchangeMatcher excludedReasons = SessionAuthenticationEntryPoint.denyReason(
            DenyReason.NO_MATCH, DenyReason.SESSION_INVALID, DenyReason.REAUTHENTICATION_REQUIRED);

    @Override
    public Mono<MatchResult> matches(ServerWebExchange exchange) {
        if (!HttpMethod.GET.equals(exchange.getRequest().getMethod()) || !isSafeToSave(exchange.getRequest())) {
            return MatchResult.notMatch();
        }
        return excludedReasons.matches(exchange)
                .flatMap(excluded -> excluded.isMatch() ? MatchResult.notMatch() : html.matches(exchange));
    }

    private static boolean isSafeToSave(ServerHttpRequest request) {
        // The same value that WebSessionServerRequestCache saves.
        String path = request.getPath().pathWithinApplication().value();
        String query = request.getURI().getRawQuery();
        String saved = query == null ? path : path + "?" + query;
        return path.startsWith("/") && !path.startsWith("//")
                && saved.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
