package com.johnowl.gateway.security;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps each authorized client of a session in its own session attribute (spec 023, "Storage of
 * authorized clients"), for example {@code gateway.oauth2.client.entra}. The repository of Spring
 * Security keeps all clients in one map attribute, so two parallel requests that each change the map
 * would lose the change of the other request. With one attribute for each client, the store
 * {@code redis} writes only the changed attribute.
 *
 * <p>A save or a removal changes the session of the request and also the {@link SessionTokenStore}
 * directly. The store {@code memory} keeps the stored token state when it saves a request copy, so
 * this write is the one that counts there. The store does not write to a session that no longer exists.
 */
final class SessionAuthorizedClientRepository implements ServerOAuth2AuthorizedClientRepository {

    /** The prefix of the session attributes. */
    static final String ATTRIBUTE_PREFIX = "gateway.oauth2.client.";

    private final SessionTokenStore store;

    SessionAuthorizedClientRepository(SessionTokenStore store) {
        this.store = store;
    }

    /** The session attribute of the authorized client of a registration. */
    static String attribute(String registrationId) {
        return ATTRIBUTE_PREFIX + registrationId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> Mono<T> loadAuthorizedClient(String clientRegistrationId,
            Authentication principal, ServerWebExchange exchange) {
        return exchange.getSession().mapNotNull(session -> session.getAttribute(attribute(clientRegistrationId)))
                .filter(OAuth2AuthorizedClient.class::isInstance)
                .map(client -> (T) client);
    }

    @Override
    public Mono<Void> saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
            ServerWebExchange exchange) {
        String name = attribute(authorizedClient.getClientRegistration().getRegistrationId());
        return exchange.getSession()
                .doOnNext(session -> session.getAttributes().put(name, authorizedClient))
                .flatMap(session -> store.save(session.getId(), authorizedClient))
                .then();
    }

    /**
     * Removes the per-audience clients of the session at login success (spec 023): they can belong to the
     * user of an earlier login in the same session. The client of {@code entra} of the new login stays.
     *
     * <p>The login gives the session a new id, and the store holds the token state under the old id until
     * the session is saved. The method therefore saves the session first, and then removes the stored
     * clients in the store under the new id.
     *
     * @param registrationIds the per-audience registrations; the clients in the session are also removed
     */
    Mono<Void> removeAudienceClients(WebSession session, Set<String> registrationIds) {
        Set<String> ids = new TreeSet<>(registrationIds);
        String prefix = attribute(AudienceRegistrationIds.PREFIX);
        for (String name : List.copyOf(session.getAttributes().keySet())) {
            if (name.startsWith(prefix)) {
                session.getAttributes().remove(name);
                ids.add(name.substring(ATTRIBUTE_PREFIX.length()));
            }
        }
        return session.save()
                .thenMany(Flux.fromIterable(ids).concatMap(id -> store.load(session.getId(), id)
                        .flatMap(stored -> store.remove(session.getId(), id))))
                .then();
    }

    @Override
    public Mono<Void> removeAuthorizedClient(String clientRegistrationId, Authentication principal,
            ServerWebExchange exchange) {
        return exchange.getSession()
                .doOnNext(session -> session.getAttributes().remove(attribute(clientRegistrationId)))
                .flatMap(session -> store.remove(session.getId(), clientRegistrationId));
    }
}
