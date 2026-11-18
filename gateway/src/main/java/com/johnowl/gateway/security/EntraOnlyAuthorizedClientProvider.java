package com.johnowl.gateway.security;

import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import reactor.core.publisher.Mono;

/**
 * Gives only the registration {@code entra} to a provider of Spring Security (spec 023, "Provider
 * chain"). The providers of Spring Security do not filter by registration: without this wrapper, the
 * authorization code provider would act on a per-audience registration.
 */
final class EntraOnlyAuthorizedClientProvider implements ReactiveOAuth2AuthorizedClientProvider {

    private final ReactiveOAuth2AuthorizedClientProvider delegate;

    EntraOnlyAuthorizedClientProvider(ReactiveOAuth2AuthorizedClientProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<OAuth2AuthorizedClient> authorize(OAuth2AuthorizationContext context) {
        return EntraAuthorizationRequestResolver.REGISTRATION_ID.equals(context.getClientRegistration().getRegistrationId())
                ? delegate.authorize(context)
                : Mono.empty();
    }
}
