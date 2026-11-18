package com.johnowl.gateway.security;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint.DelegateEntry;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.savedrequest.ServerRequestCache;
import org.springframework.security.web.server.util.matcher.MediaTypeServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The response to a request without an authenticated user (spec 022, "Entry point"):
 *
 * <ol>
 *   <li>The deny reason is {@link DenyReason#NO_MATCH}: {@code 404} with the generic body.
 *   <li>HTML navigation ({@code Accept} contains {@code text/html}): {@code 302} to the login of
 *       {@code entra}. The request cache saves the request.
 *   <li>Any other request: {@code 401} with the generic problem body. No redirect, no session.
 * </ol>
 */
public final class SessionAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    /** The start of the login flow. */
    public static final String LOGIN_PATH = "/oauth2/authorization/entra";

    private final ServerAuthenticationEntryPoint delegate;

    public SessionAuthenticationEntryPoint(ServerRequestCache requestCache) {
        RedirectServerAuthenticationEntryPoint login = new RedirectServerAuthenticationEntryPoint(LOGIN_PATH);
        login.setRequestCache(requestCache);
        DelegatingServerAuthenticationEntryPoint entryPoint = new DelegatingServerAuthenticationEntryPoint(
                new DelegateEntry(denyReason(DenyReason.NO_MATCH),
                        (exchange, e) -> ProblemResponses.write(exchange, HttpStatus.NOT_FOUND)),
                new DelegateEntry(htmlNavigation(), login));
        entryPoint.setDefaultEntryPoint((exchange, e) -> ProblemResponses.write(exchange, HttpStatus.UNAUTHORIZED));
        this.delegate = entryPoint;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return delegate.commence(exchange, ex);
    }

    /** Matches a request whose {@code Accept} header contains {@code text/html}. {@code *}{@code /*} does not count. */
    static ServerWebExchangeMatcher htmlNavigation() {
        MediaTypeServerWebExchangeMatcher html = new MediaTypeServerWebExchangeMatcher(MediaType.TEXT_HTML);
        html.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return html;
    }

    /** Matches a request with one of the deny reasons. */
    static ServerWebExchangeMatcher denyReason(DenyReason... reasons) {
        Set<DenyReason> set = Set.of(reasons);
        return exchange -> {
            DenyReason reason = exchange.getAttribute(GatewaySecurityAttributes.DENY_REASON);
            return reason != null && set.contains(reason)
                    ? ServerWebExchangeMatcher.MatchResult.match()
                    : ServerWebExchangeMatcher.MatchResult.notMatch();
        };
    }
}
