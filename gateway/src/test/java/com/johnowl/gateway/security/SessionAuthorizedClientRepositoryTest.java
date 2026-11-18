package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.server.WebSession;

/** Each authorized client in its own session attribute (spec 023, "Storage of authorized clients"). */
class SessionAuthorizedClientRepositoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    // The sessions of the mock exchange are not in this store; the store writes nothing for them.
    private final SessionAuthorizedClientRepository repository = new SessionAuthorizedClientRepository(
            new MemorySessionTokenStore(new MemorySessionRepository(new DrbgSessionIdGenerator(), Duration.ofMinutes(5), 10),
                    Clock.systemUTC()));

    private final MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));

    @Test
    void keepsEachClientInItsOwnAttribute() {
        OAuth2AuthorizedClient entra = client(AudienceClientRegistrationsTest.entra("bff"));
        OAuth2AuthorizedClient orders = client(ClientRegistration.withClientRegistration(entra.getClientRegistration())
                .registrationId("entra-6f1c2e8a-0000-0000-0000-000000000000").build());

        repository.saveAuthorizedClient(entra, null, exchange).block(TIMEOUT);
        repository.saveAuthorizedClient(orders, null, exchange).block(TIMEOUT);

        WebSession session = exchange.getSession().block(TIMEOUT);
        assertThat(session.getAttributes()).containsOnlyKeys(
                "gateway.oauth2.client.entra", "gateway.oauth2.client.entra-6f1c2e8a-0000-0000-0000-000000000000");
        assertThat(repository.<OAuth2AuthorizedClient>loadAuthorizedClient("entra", null, exchange).block(TIMEOUT))
                .isSameAs(entra);

        repository.removeAuthorizedClient("entra", null, exchange).block(TIMEOUT);

        assertThat(session.getAttributes()).containsOnlyKeys("gateway.oauth2.client.entra-6f1c2e8a-0000-0000-0000-000000000000");
        assertThat(repository.loadAuthorizedClient("entra", null, exchange).block(TIMEOUT)).isNull();
    }

    @Test
    void ignoresAValueOfAnotherType() {
        exchange.getSession().block(TIMEOUT).getAttributes().put("gateway.oauth2.client.entra", "not a client");

        assertThat(repository.loadAuthorizedClient("entra", null, exchange).block(TIMEOUT)).isNull();
    }

    private static OAuth2AuthorizedClient client(ClientRegistration registration) {
        Instant now = Instant.now();
        return new OAuth2AuthorizedClient(registration, "oid-1",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access", now, now.plusSeconds(3600)));
    }
}
