package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.johnowl.gateway.core.access.AccessRule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.mock.web.server.MockWebSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.session.MapSession;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The steps of the provider of spec 023, with a stub token endpoint (a {@link WebClient} without a
 * network), a stub decoder, and the session store {@code memory}.
 */
class AudienceRefreshTokenAuthorizedClientProviderTest {

    private static final String ORDERS = "6f1c2e8a-0000-0000-0000-000000000000";

    private static final String PAYMENTS = "9b2d3f4a-0000-0000-0000-000000000000";

    private static final String ORDERS_ID = "entra-" + ORDERS;

    private static final String PAYMENTS_ID = "entra-" + PAYMENTS;

    private static final String USER = "oid-1";

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final ClientRegistration entra = AudienceClientRegistrationsTest.entra(MockIdentityProvider.CLIENT_ID);

    private final AudienceClientRegistrations audiences = AudienceClientRegistrations.of(entra, List.of(
            AudienceClientRegistrationsTest.access("GET", new AccessRule(false, ORDERS, Set.of(), Set.of())),
            AudienceClientRegistrationsTest.access("POST", new AccessRule(false, PAYMENTS, Set.of(), Set.of()))));

    private final InMemoryReactiveClientRegistrationRepository registrations = repository();

    private final TestClock clock = new TestClock();

    private final MemorySessionRepository sessions = new MemorySessionRepository(new DrbgSessionIdGenerator(),
            Duration.ofMinutes(5), 10);

    private final MemorySessionTokenStore store = new MemorySessionTokenStore(sessions, clock);

    private final SessionRedemptions redemptions = new SessionRedemptions();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final AtomicLong ticker = new AtomicLong();

    /** The requests that reached the token endpoint. */
    private final List<ClientRequest> grants = new CopyOnWriteArrayList<>();

    /** The answer of the token endpoint. */
    private final AtomicReference<Function<ClientRequest, ClientResponse>> answer = new AtomicReference<>(request -> token(3600));

    /** The claims of the decoded token: by default a valid token of the audience of the grant. */
    private final AtomicReference<Function<String, Mono<Jwt>>> decoding = new AtomicReference<>(
            token -> Mono.just(jwt(token, ORDERS, MockIdentityProvider.CLIENT_ID, Instant.now().plusSeconds(3600))));

    private final MockWebSession session = new MockWebSession();

    private final ServerWebExchange exchange = MockServerWebExchange.builder(MockServerHttpRequest.get("/orders-api/orders/42"))
            .session(session).build();

    private SessionTokenStore tokenStore = store;

    /** The token endpoint client of {@link #provider}. */
    private TokenEndpointClient endpoint;

    private AudienceRefreshTokenAuthorizedClientProvider provider = provider(new GrantRateLimiter(50, clock), WAIT);

    @Test
    void withoutATokenTheProviderRedeemsAndStoresTheTokenWithoutARefreshToken() {
        login("refresh-1");

        OAuth2AuthorizedClient client = authorize(ORDERS_ID, null, USER).block(WAIT);

        assertThat(client.getPrincipalName()).isEqualTo(USER);
        assertThat(client.getRefreshToken()).isNull();
        assertThat(stored(ORDERS_ID).getAccessToken().getTokenValue()).isEqualTo(client.getAccessToken().getTokenValue());
        assertThat(grants).hasSize(1);
        assertThat(count("success")).isOne();
        assertThat(stored("entra").getRefreshToken().getTokenValue()).isEqualTo("refresh-new");
    }

    @Test
    void aValidTokenOfTheUserStaysInUse() {
        login("refresh-1");

        assertThat(authorize(ORDERS_ID, client(ORDERS_ID, USER, 3600), USER).blockOptional(WAIT)).isEmpty();

        assertThat(grants).isEmpty();
    }

    @Test
    void aValidTokenOfAnotherUserIsTreatedAsMissing() {
        login("refresh-1");

        OAuth2AuthorizedClient client = authorize(ORDERS_ID, client(ORDERS_ID, "oid-2", 3600), USER).block(WAIT);

        assertThat(client.getPrincipalName()).isEqualTo(USER);
        assertThat(grants).hasSize(1);
    }

    @Test
    void step4UsesAValidTokenOfTheUserThatAnotherRequestStored() {
        login("refresh-1");
        store.save(session.getId(), client(ORDERS_ID, USER, 3600)).block(WAIT);

        OAuth2AuthorizedClient client = authorize(ORDERS_ID, null, USER).block(WAIT);

        assertThat(client.getAccessToken().getTokenValue()).isEqualTo("stored-" + ORDERS_ID);
        assertThat(grants).isEmpty();
    }

    @Test
    void step4IgnoresAStoredTokenOfAnotherUser() {
        login("refresh-1");
        store.save(session.getId(), client(ORDERS_ID, "oid-2", 3600)).block(WAIT);

        OAuth2AuthorizedClient client = authorize(ORDERS_ID, null, USER).block(WAIT);

        assertThat(client.getPrincipalName()).isEqualTo(USER);
        assertThat(grants).hasSize(1);
    }

    @Test
    void anEntraClientOfAnotherUserEndsTheSession() {
        login("refresh-1");

        assertDenied(authorize(ORDERS_ID, null, "oid-2"), DenyReason.SESSION_INVALID);

        assertThat(grants).isEmpty();
        assertThat(sessions.contains(session.getId())).isFalse();
    }

    @Test
    void anEntraClientWithoutARefreshTokenEndsTheSession() {
        login(null);

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.SESSION_INVALID);

        assertThat(grants).isEmpty();
        assertThat(sessions.contains(session.getId())).isFalse();
    }

    // A parallel request ended the session after this request loaded it: no client, no session, and no defect.
    @Test
    void aSessionThatAParallelRequestEndedEndsAsSessionInvalid() {
        login("refresh-1");
        sessions.deleteById(session.getId()).block(WAIT);

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.SESSION_INVALID);

        assertThat(grants).isEmpty();
        assertThat(sessions.contains(session.getId())).isFalse();
    }

    // A parallel request removed the clients of the session: the session ends as for a missing session.
    @Test
    void aSessionWithoutAnEntraClientEndsAsSessionInvalid() {
        sessions.save(new MapSession(session.getId())).block(WAIT);

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.SESSION_INVALID);

        assertThat(grants).isEmpty();
        assertThat(sessions.contains(session.getId())).isFalse();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "400 | {\"error\":\"invalid_grant\",\"suberror\":\"consent_required\",\"error_codes\":[65001]} | CONSENT_REQUIRED | true",
        "400 | {\"error\":\"interaction_required\",\"error_codes\":[65001]}                           | CONSENT_REQUIRED | true",
        "400 | {\"error\":\"interaction_required\",\"error_codes\":[50076]}                           | REAUTHENTICATION_REQUIRED | false",
        "400 | {\"error\":\"invalid_grant\",\"error_codes\":[700082]}                                 | SESSION_INVALID | false",
        "400 | {\"error\":\"invalid_scope\"}                                                          | TOKEN_REQUEST_REJECTED | true",
        "500 | {}                                                                                     | TOKEN_ENDPOINT_ERROR | true",
        "429 | {}                                                                                     | TOKEN_ENDPOINT_THROTTLED | true",
    })
    void eachRowOfTheTokenEndpointErrorsInOrder(int status, String body, DenyReason reason, boolean sessionStays) {
        login("refresh-1");
        answer.set(request -> ClientResponse.create(HttpStatus.valueOf(status))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build());

        assertDenied(authorize(ORDERS_ID, null, USER), reason);

        assertThat(exchange.<DenyReason>getAttribute(GatewaySecurityAttributes.DENY_REASON)).isEqualTo(reason);
        assertThat(exchange.<TokenError>getAttribute(GatewaySecurityAttributes.TOKEN_ERROR).status()).isEqualTo(status);
        assertThat(sessions.contains(session.getId())).isEqualTo(sessionStays);
    }

    @Test
    void aFailedGrantIsRememberedForThirtySecondsForTheSessionAndTheAudience() {
        login("refresh-1");
        answer.set(request -> error(400, "{\"error\":\"invalid_scope\"}"));
        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.TOKEN_REQUEST_REJECTED);

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.TOKEN_REQUEST_REJECTED);
        assertThat(grants).hasSize(1);

        answer.set(request -> token(3600));
        decoding.set(token -> Mono.just(jwt(token, PAYMENTS, MockIdentityProvider.CLIENT_ID, Instant.now().plusSeconds(3600))));
        assertThat(authorize(PAYMENTS_ID, null, USER).block(WAIT)).isNotNull();
        assertThat(grants).hasSize(2);

        ticker.addAndGet(AudienceRefreshTokenAuthorizedClientProvider.FAILURE_LIFETIME.toNanos());
        decoding.set(token -> Mono.just(jwt(token, ORDERS, MockIdentityProvider.CLIENT_ID, Instant.now().plusSeconds(3600))));
        assertThat(authorize(ORDERS_ID, null, USER).block(WAIT)).isNotNull();
        assertThat(grants).hasSize(3);
    }

    @Test
    void anEmptyGrantBucketOfTheInstanceStopsTheGrantWith503() {
        provider = provider(new GrantRateLimiter(1, clock), WAIT);
        login("refresh-1");
        assertThat(authorize(ORDERS_ID, null, USER).block(WAIT)).isNotNull();

        decoding.set(token -> Mono.just(jwt(token, PAYMENTS, MockIdentityProvider.CLIENT_ID, Instant.now().plusSeconds(3600))));
        assertDenied(authorize(PAYMENTS_ID, null, USER), DenyReason.TOKEN_ENDPOINT_THROTTLED);

        assertThat(grants).hasSize(1);
        assertThat(exchange.<TokenError>getAttribute(GatewaySecurityAttributes.TOKEN_ERROR).status()).isZero();
        assertThat(count("limited")).isOne();
        clock.advance(Duration.ofSeconds(1));
        assertThat(authorize(PAYMENTS_ID, null, USER).block(WAIT)).isNotNull();
    }

    @Test
    void theGrantLimitOfTheSessionCountsEachStoppedRequestAlsoAWaitingOne() {
        tokenStore = new GrantCounterHook(store, count -> count.delayElement(Duration.ofMillis(200)));
        provider = provider(new GrantRateLimiter(50, clock), WAIT);
        login("refresh-1");
        for (int i = 0; i < SessionTokenStore.GRANT_LIMIT; i++) {
            store.countGrant(session.getId()).block(WAIT);
        }

        // The second request waits for the redemption of the first request and gets its result.
        List<Throwable> errors = Flux.merge(
                        authorize(ORDERS_ID, null, USER).materialize(), authorize(ORDERS_ID, null, USER).materialize())
                .map(signal -> signal.getThrowable()).collectList().block(WAIT);

        assertThat(errors).hasSize(2).allSatisfy(e -> assertThat(((AudienceTokenException) e).reason())
                .isEqualTo(DenyReason.TOKEN_GRANT_LIMIT));
        assertThat(grants).isEmpty();
        assertThat(count("limited")).isEqualTo(2);
    }

    @Test
    void successCountsOnlyAStoredToken() {
        login("refresh-1");
        decoding.set(token -> sessions.deleteById(session.getId())
                .then(Mono.just(jwt(token, ORDERS, MockIdentityProvider.CLIENT_ID, Instant.now().plusSeconds(3600)))));

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.SESSION_INVALID);

        assertThat(grants).hasSize(1);
        assertThat(count("success")).isZero();
    }

    // Each sent grant increments gateway.token.grants exactly once, with its result.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "200 | {}                                                                   | success",
        "400 | {\"error\":\"invalid_grant\",\"suberror\":\"consent_required\"}   | consent",
        "400 | {\"error\":\"interaction_required\",\"error_codes\":[50076]}     | reauthentication",
        "400 | {\"error\":\"invalid_grant\"}                                     | invalid_grant",
        "400 | {\"error\":\"invalid_scope\"}                                     | rejected",
        "500 | {}                                                                   | error",
        "429 | {}                                                                   | throttled",
    })
    void eachSentGrantIncrementsTheGrantCounterOnceWithItsResult(int status, String body, String result) {
        login("refresh-1");
        if (status != 200) {
            answer.set(request -> error(status, body));
        }

        authorize(ORDERS_ID, null, USER).onErrorResume(AudienceTokenException.class, e -> Mono.empty()).block(WAIT);

        assertGrantCounted(result);
    }

    @Test
    void aNewTokenThatFailsTheValidationCountsAsInvalidToken() {
        login("refresh-1");
        decoding.set(token -> Mono.just(jwt(token, PAYMENTS, MockIdentityProvider.CLIENT_ID, Instant.now().plusSeconds(3600))));

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.INVALID_AUDIENCE_TOKEN);

        assertGrantCounted("invalid_token");
    }

    @ParameterizedTest
    @CsvSource({"true, timeout", "false, error"})
    void aKeySetFailureAfterTheGrantCountsTheGrant(boolean timeout, String result) {
        login("refresh-1");
        decoding.set(token -> Mono.error(new KeySetUnavailableException(timeout)));

        authorize(ORDERS_ID, null, USER).onErrorResume(AudienceTokenException.class, e -> Mono.empty()).block(WAIT);

        assertGrantCounted(result);
    }

    @Test
    void aGrantThatTheRedemptionTimeoutEndsCountsAsTimeout() {
        provider = provider(new GrantRateLimiter(50, clock), Duration.ofMillis(200));
        login("refresh-1");
        decoding.set(token -> Mono.never());

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.TOKEN_ENDPOINT_TIMEOUT);

        assertGrantCounted("timeout");
    }

    @Test
    void aGrantWhoseTokenTheGatewayCannotStoreCountsAsInvalidGrant() {
        login("refresh-1");
        decoding.set(token -> sessions.deleteById(session.getId())
                .then(Mono.just(jwt(token, ORDERS, MockIdentityProvider.CLIENT_ID, Instant.now().plusSeconds(3600)))));

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.SESSION_INVALID);

        assertGrantCounted("invalid_grant");
    }

    @Test
    void aGrantThatIsNotSentIsNotAGrantResult() {
        provider = provider(new GrantRateLimiter(1, clock), WAIT);
        login("refresh-1");
        assertThat(authorize(ORDERS_ID, null, USER).block(WAIT)).isNotNull();
        decoding.set(token -> Mono.just(jwt(token, PAYMENTS, MockIdentityProvider.CLIENT_ID, Instant.now().plusSeconds(3600))));

        assertDenied(authorize(PAYMENTS_ID, null, USER), DenyReason.TOKEN_ENDPOINT_THROTTLED);

        assertThat(grants).hasSize(1);
        assertThat(countedGrants()).isOne();
        assertThat(count("limited")).isOne();
    }

    // QA-63-1: another request of the instance gets 429 after the check of step 6 and before this grant is sent.
    @Test
    void aPauseThatStartsAfterStep6StopsTheGrantAndCountsAsLimited() {
        tokenStore = new GrantCounterHook(store, count -> throttledGrantOfAnotherRequest().then(count));
        provider = provider(new GrantRateLimiter(50, clock), WAIT);
        login("refresh-1");

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.TOKEN_ENDPOINT_THROTTLED);

        assertThat(endpoint.paused()).isTrue();
        assertThat(grants).as("grants of this request").isEmpty();
        assertThat(count("limited")).isOne();
        assertThat(countedGrants()).isZero();
        assertThat(exchange.<TokenError>getAttribute(GatewaySecurityAttributes.TOKEN_ERROR).status()).isZero();
    }

    // QA-63-2: the decoder answers when the redemption timeout cancels the grant. The first signal counts.
    @Test
    void aGrantThatSucceedsAtTheTimeoutInstantCountsOnce() {
        provider = provider(new GrantRateLimiter(50, clock), Duration.ofMillis(200));
        login("refresh-1");
        // Without a new meter, the cancel does not wait for the lock of the registry, which the blocked success holds.
        meters.counter(AudienceRefreshTokenAuthorizedClientProvider.GRANTS_METRIC, "result", "timeout");
        // The success of the grant waits until the timeout ended the redemption: both signals of one grant.
        meters.config().onMeterAdded(meter -> {
            if ("success".equals(meter.getId().getTag("result"))) {
                await().atMost(WAIT).until(() -> redemptions.running() == 0);
            }
        });

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.TOKEN_ENDPOINT_TIMEOUT);

        assertGrantCounted("success");
        assertThat(count("timeout")).isZero();
    }

    @Test
    void aDecoderThatDoesNotAnswerEndsTheRedemptionWithATimeout() {
        provider = provider(new GrantRateLimiter(50, clock), Duration.ofMillis(200));
        login("refresh-1");
        decoding.set(token -> Mono.never());

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.TOKEN_ENDPOINT_TIMEOUT);

        assertThat(redemptions.running()).isZero();
        assertThat(sessions.contains(session.getId())).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"true, TOKEN_ENDPOINT_TIMEOUT", "false, TOKEN_ENDPOINT_ERROR"})
    void aKeySetThatDoesNotLoadIsATokenEndpointFailure(boolean timeout, DenyReason reason) {
        login("refresh-1");
        decoding.set(token -> Mono.error(new KeySetUnavailableException(timeout)));

        assertDenied(authorize(ORDERS_ID, null, USER), reason);

        assertThat(stored(ORDERS_ID)).isNull();
        assertThat(stored("entra").getRefreshToken().getTokenValue()).isEqualTo("refresh-new");
    }

    @ParameterizedTest
    @CsvSource({"9b2d3f4a-0000-0000-0000-000000000000, test-gateway-bff, aud", "6f1c2e8a-0000-0000-0000-000000000000, other, azp"})
    void aNewTokenWithAnotherAudOrAzpIsNotStored(String aud, String azp, String check) {
        login("refresh-1");
        decoding.set(token -> Mono.just(jwt(token, aud, azp, Instant.now().plusSeconds(3600))));

        assertDenied(authorize(ORDERS_ID, null, USER), DenyReason.INVALID_AUDIENCE_TOKEN);

        assertThat(exchange.<TokenError>getAttribute(GatewaySecurityAttributes.TOKEN_ERROR).failedCheck()).isEqualTo(check);
        assertThat(stored(ORDERS_ID)).isNull();
        assertThat(stored("entra").getRefreshToken().getTokenValue()).isEqualTo("refresh-new");
    }

    @Test
    void theStoredExpiryIsTheEarlierOfExpAndExpiresIn() {
        login("refresh-1");
        Instant exp = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
        decoding.set(token -> Mono.just(jwt(token, ORDERS, MockIdentityProvider.CLIENT_ID, exp)));

        OAuth2AuthorizedClient client = authorize(ORDERS_ID, null, USER).block(WAIT);

        assertThat(client.getAccessToken().getExpiresAt()).isEqualTo(exp);
        assertThat(stored(ORDERS_ID).getAccessToken().getExpiresAt()).isEqualTo(exp);
    }

    private AudienceRefreshTokenAuthorizedClientProvider provider(GrantRateLimiter limiter, Duration redemptionTimeout) {
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            grants.add(request);
            return Mono.just(answer.get().apply(request));
        }).build();
        ReactiveJwtDecoder jwtDecoder = token -> decoding.get().apply(token);
        AudienceTokenDecoder decoder = new AudienceTokenDecoder(jwtDecoder, "issuer", "tenant", MockIdentityProvider.CLIENT_ID);
        endpoint = new TokenEndpointClient(Duration.ofSeconds(5), webClient, clock);
        return new AudienceRefreshTokenAuthorizedClientProvider(audiences, registrations, tokenStore, redemptions,
                endpoint, decoder, Duration.ofSeconds(60), clock, meters, limiter, redemptionTimeout, ticker::get);
    }

    private InMemoryReactiveClientRegistrationRepository repository() {
        List<ClientRegistration> all = new ArrayList<>(audiences.registrations());
        all.add(entra);
        return new InMemoryReactiveClientRegistrationRepository(all);
    }

    /** The session with the {@code entra} client of a login. */
    private void login(String refreshToken) {
        MapSession stored = new MapSession(session.getId());
        Instant now = Instant.now();
        stored.setAttribute(SessionAuthorizedClientRepository.attribute("entra"), new OAuth2AuthorizedClient(entra, USER,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "graph", now, now.plusSeconds(3600)),
                refreshToken == null ? null : new OAuth2RefreshToken(refreshToken, now)));
        sessions.save(stored).block(WAIT);
    }

    private Mono<OAuth2AuthorizedClient> authorize(String registrationId, OAuth2AuthorizedClient current, String principal) {
        ClientRegistration registration = registrations.findByRegistrationId(registrationId).block(WAIT);
        OAuth2AuthorizationContext.Builder context = current == null
                ? OAuth2AuthorizationContext.withClientRegistration(registration)
                : OAuth2AuthorizationContext.withAuthorizedClient(current);
        return provider.authorize(context.principal(new TestingAuthenticationToken(principal, null))
                .attributes(attributes -> attributes.put(ServerWebExchange.class.getName(), exchange))
                .build());
    }

    private OAuth2AuthorizedClient client(String registrationId, String principal, long lifetimeSeconds) {
        Instant now = Instant.now();
        return new OAuth2AuthorizedClient(registrations.findByRegistrationId(registrationId).block(WAIT), principal,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "stored-" + registrationId, now,
                        now.plusSeconds(lifetimeSeconds)));
    }

    private OAuth2AuthorizedClient stored(String registrationId) {
        return (OAuth2AuthorizedClient) sessions.attribute(session.getId(), SessionAuthorizedClientRepository.attribute(registrationId));
    }

    private double count(String result) {
        var counter = meters.find(AudienceRefreshTokenAuthorizedClientProvider.GRANTS_METRIC).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    /** One sent grant, and one count with the result. {@code limited} is not a sent grant. */
    private void assertGrantCounted(String result) {
        assertThat(grants).hasSize(1);
        assertThat(count(result)).as("result " + result).isOne();
        assertThat(countedGrants()).as("all results of sent grants").isOne();
    }

    /** The sum of all results of {@code gateway.token.grants}, without {@code limited}. */
    private double countedGrants() {
        return meters.find(AudienceRefreshTokenAuthorizedClientProvider.GRANTS_METRIC).counters().stream()
                .filter(counter -> !"limited".equals(counter.getId().getTag("result")))
                .mapToDouble(counter -> counter.count()).sum();
    }

    /**
     * A grant of another request of the instance that gets {@code 429}: the pause of the instance starts.
     * Only the grants of the request of the test stay in {@link #grants}.
     */
    private Mono<Void> throttledGrantOfAnotherRequest() {
        Instant now = Instant.now();
        OAuth2RefreshTokenGrantRequest other = new OAuth2RefreshTokenGrantRequest(
                registrations.findByRegistrationId(PAYMENTS_ID).block(WAIT),
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "graph", now, now.plusSeconds(3600)),
                new OAuth2RefreshToken("refresh-other", now));
        Function<ClientRequest, ClientResponse> earlier = answer.getAndSet(request -> error(429, "{}"));
        return endpoint.send(other)
                .onErrorResume(AudienceTokenException.class, e -> Mono.empty())
                .doFinally(signal -> {
                    answer.set(earlier);
                    grants.clear();
                })
                .then();
    }

    private static void assertDenied(Mono<OAuth2AuthorizedClient> authorization, DenyReason reason) {
        StepVerifier.create(authorization)
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(AudienceTokenException.class,
                        denied -> assertThat(denied.reason()).isEqualTo(reason)))
                .verify(WAIT);
    }

    private static ClientResponse token(long expiresIn) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"token_type\":\"Bearer\",\"access_token\":\"access-" + System.nanoTime() + "\",\"expires_in\":"
                        + expiresIn + ",\"refresh_token\":\"refresh-new\"}")
                .build();
    }

    private static ClientResponse error(int status, String body) {
        return ClientResponse.create(HttpStatus.valueOf(status))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build();
    }

    private static Jwt jwt(String token, String audience, String azp, Instant expiresAt) {
        return Jwt.withTokenValue(token).header("alg", "RS256")
                .audience(List.of(audience))
                .claims(claims -> claims.putAll(Map.of("azp", azp)))
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(expiresAt)
                .build();
    }

    /** The store {@code memory}, with a hook around the grant counter, for example a delay. */
    private record GrantCounterHook(SessionTokenStore delegate, UnaryOperator<Mono<Boolean>> hook)
            implements SessionTokenStore {

        @Override
        public Mono<Stored> load(String sessionId, String registrationId) {
            return delegate.load(sessionId, registrationId);
        }

        @Override
        public Mono<Boolean> save(String sessionId, OAuth2AuthorizedClient client) {
            return delegate.save(sessionId, client);
        }

        @Override
        public Mono<Void> remove(String sessionId, String registrationId) {
            return delegate.remove(sessionId, registrationId);
        }

        @Override
        public Mono<Boolean> compareAndSet(String sessionId, Stored expected, OAuth2AuthorizedClient client) {
            return delegate.compareAndSet(sessionId, expected, client);
        }

        @Override
        public Mono<Boolean> compareAndRemove(String sessionId, Stored expected) {
            return delegate.compareAndRemove(sessionId, expected);
        }

        @Override
        public Mono<Boolean> countGrant(String sessionId) {
            return hook.apply(delegate.countGrant(sessionId));
        }

        @Override
        public Mono<Void> deleteSession(String sessionId) {
            return delegate.deleteSession(sessionId);
        }

        @Override
        public <T> Mono<T> withLock(String sessionId, Mono<T> work) {
            return delegate.withLock(sessionId, work);
        }
    }
}
