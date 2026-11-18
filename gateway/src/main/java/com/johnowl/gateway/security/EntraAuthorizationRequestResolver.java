package com.johnowl.gateway.security;

import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Starts the login only for the registration {@code entra}, always with PKCE ({@code S256}), state
 * and nonce. For another registration id, for example a per-audience registration of spec 023, it
 * returns an empty result. The request then continues the filter chain and starts no login.
 */
final class EntraAuthorizationRequestResolver implements ServerOAuth2AuthorizationRequestResolver {

    static final String REGISTRATION_ID = "entra";

    private final ServerWebExchangeMatcher matcher = new PathPatternParserServerWebExchangeMatcher(
            DefaultServerOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_PATTERN);

    private final DefaultServerOAuth2AuthorizationRequestResolver delegate;

    EntraAuthorizationRequestResolver(ReactiveClientRegistrationRepository registrations) {
        delegate = new DefaultServerOAuth2AuthorizationRequestResolver(registrations, matcher);
        delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange) {
        return matcher.matches(exchange)
                .filter(ServerWebExchangeMatcher.MatchResult::isMatch)
                .map(match -> match.getVariables().get(
                        DefaultServerOAuth2AuthorizationRequestResolver.DEFAULT_REGISTRATION_ID_URI_VARIABLE_NAME))
                .flatMap(registrationId -> resolve(exchange, (String) registrationId));
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange, String clientRegistrationId) {
        if (!REGISTRATION_ID.equals(clientRegistrationId)) {
            return Mono.empty();
        }
        // State, nonce and code verifier come from SecureRandom, which can read /dev/urandom: not on
        // the event loop. Session ids do not come from here; the session store creates them.
        return delegate.resolve(exchange, clientRegistrationId).subscribeOn(Schedulers.boundedElastic());
    }
}
