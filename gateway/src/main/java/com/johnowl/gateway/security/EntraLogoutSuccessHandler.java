package com.johnowl.gateway.security;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.ServerRedirectStrategy;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import reactor.core.publisher.Mono;

/**
 * Redirects the browser to the Entra ID logout endpoint with exactly two parameters:
 * {@code client_id} and {@code post_logout_redirect_uri}. It sends no {@code id_token_hint}, because
 * the ID token must not appear in a URL. Without the hint, Entra ID can show an account picker.
 */
final class EntraLogoutSuccessHandler implements ServerLogoutSuccessHandler {

    private final ServerRedirectStrategy redirectStrategy = new DefaultServerRedirectStrategy();

    private final URI logoutUri;

    /**
     * @param endSessionEndpoint the {@code end_session_endpoint} of the provider metadata, without a query
     * @param clientId the client id of {@code gateway-bff}
     * @param postLogoutRedirectUri the absolute post-logout redirect URI
     */
    EntraLogoutSuccessHandler(URI endSessionEndpoint, String clientId, URI postLogoutRedirectUri) {
        this.logoutUri = URI.create(endSessionEndpoint
                + "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&post_logout_redirect_uri=" + URLEncoder.encode(postLogoutRedirectUri.toString(), StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> onLogoutSuccess(WebFilterExchange exchange, Authentication authentication) {
        return redirectStrategy.sendRedirect(exchange.getExchange(), logoutUri);
    }

    URI logoutUri() {
        return logoutUri;
    }
}
