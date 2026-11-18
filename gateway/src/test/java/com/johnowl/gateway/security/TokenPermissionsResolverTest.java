package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.github.benmanes.caffeine.cache.Ticker;
import com.johnowl.gateway.routing.GatewaySecurityProperties;
import com.johnowl.gateway.security.MockIdentityProvider.AudienceTokenDefect;
import io.micrometer.observation.ObservationRegistry;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.mock.web.server.MockWebSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.session.MapSession;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/** The permissions of the audience tokens and their cache (spec 023, "Permission decoding and cache"). */
class TokenPermissionsResolverTest {

    private static final String ORDERS = "6f1c2e8a-0000-0000-0000-000000000000";

    private static final String ORDERS_ID = "entra-" + ORDERS;

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final AtomicLong nanos = new AtomicLong();

    private final Ticker ticker = nanos::get;

    private final MemorySessionRepository sessions = new MemorySessionRepository(new DrbgSessionIdGenerator(),
            Duration.ofMinutes(5), 10);

    private final MemorySessionTokenStore store = new MemorySessionTokenStore(sessions, new TestClock());

    /** The session of the requests of {@link #exchange()}. */
    private final MockWebSession defaultSession = new MockWebSession();

    /** The tokens that the stub decoder knows, and the number of its calls. */
    private final Map<String, Jwt> tokens = new ConcurrentHashMap<>();

    private final AtomicInteger decodings = new AtomicInteger();

    private final AudienceTokenDecoder stubDecoder = decoder(token -> {
        Jwt jwt = tokens.get(token);
        return jwt == null ? Mono.error(new BadJwtException("unknown token")) : Mono.just(jwt);
    });

    @Test
    void thePermissionsAreTheRolesOfTheAudienceTokenNotOfTheIdToken() {
        Instant expiresAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        String token = idp.audienceToken(ORDERS, List.of("Orders.Read", "Orders.Write"), expiresAt, null);
        AtomicReference<OAuth2AuthorizeRequest> asked = new AtomicReference<>();
        TokenPermissionsResolver resolver = new TokenPermissionsResolver(request -> {
            asked.set(request);
            return Mono.just(client(token));
        }, realDecoder(), store, 10, TIMEOUT);
        MockServerWebExchange exchange = exchange();

        TokenPermissions permissions = resolver.resolve(userWithIdTokenRoles("Admin"), ORDERS, exchange).block(WAIT);

        assertThat(permissions.audience()).isEqualTo(ORDERS);
        assertThat(permissions.permissions()).containsExactlyInAnyOrder("Orders.Read", "Orders.Write");
        assertThat(permissions.expiresAt()).isEqualTo(expiresAt);
        assertThat(asked.get().getClientRegistrationId()).isEqualTo(ORDERS_ID);
        assertThat(asked.get().<ServerWebExchange>getAttribute(ServerWebExchange.class.getName())).isSameAs(exchange);
        // The relay of spec 025 sends this token.
        assertThat(exchange.<AudienceAccessToken>getAttribute(GatewaySecurityAttributes.AUDIENCE_TOKEN))
                .isEqualTo(new AudienceAccessToken(ORDERS, token));
    }

    // Spec 024 compares the aud with the audience of the route (WRONG_AUDIENCE); the resolver only reports it.
    @Test
    void theAudienceOfThePermissionsIsTheAudOfTheTokenNotTheArgument() {
        String token = idp.audienceToken("other-client-id", List.of("Orders.Read"), Instant.now().plusSeconds(3600), null);
        MockServerWebExchange exchange = exchange();

        TokenPermissions permissions = manager(token, realDecoder()).resolve(userWithIdTokenRoles(), ORDERS, exchange)
                .block(WAIT);

        assertThat(permissions.audience()).isEqualTo("other-client-id");
        assertThat(exchange.getAttributes()).doesNotContainKeys(GatewaySecurityAttributes.DENY_REASON,
                GatewaySecurityAttributes.TOKEN_ERROR);
        assertThat(exchange.<AudienceAccessToken>getAttribute(GatewaySecurityAttributes.AUDIENCE_TOKEN).audience())
                .isEqualTo("other-client-id");
    }

    @Test
    void aTokenWithoutRolesHasNoPermissions() {
        String token = idp.audienceToken(ORDERS, List.of(), Instant.now().plusSeconds(3600), null);

        TokenPermissions permissions = resolver(realDecoder(), 10).decoded(token).block(WAIT).permissions();

        assertThat(permissions.permissions()).isEmpty();
    }

    // Spring would split a string at the commas. Entra writes an array, so another form is an invalid token.
    @Test
    void aRolesClaimThatIsNotABoundedArrayOfStringsIsInvalid() {
        tokens.put("string", jwt(Duration.ofHours(1)).claim("roles", "Orders.Read,Orders.Write").build());
        tokens.put("mixed", jwt(Duration.ofHours(1)).claim("roles", List.of("Orders.Read", 7)).build());
        tokens.put("many", jwt(Duration.ofHours(1)).claim("roles", roles(201, 10)).build());
        tokens.put("long", jwt(Duration.ofHours(1)).claim("roles", roles(1, 121)).build());
        TokenPermissionsResolver resolver = resolver(stubDecoder, 10);

        for (String token : List.of("string", "mixed", "many", "long")) {
            StepVerifier.create(resolver.decoded(token))
                    .expectErrorSatisfies(invalid("roles"))
                    .verify(WAIT);
        }
        assertThat(resolver.size()).isZero();
    }

    @Test
    void aRolesClaimAtTheLimitsIsValid() {
        tokens.put("limits", jwt(Duration.ofHours(1)).claim("roles", roles(200, 120)).build());

        assertThat(resolver(stubDecoder, 10).decoded("limits").block(WAIT).permissions().permissions()).hasSize(200);
    }

    @Test
    void aTokenWithAWrongSignatureOrAWrongIssuerHasNoPermissions() {
        for (AudienceTokenDefect defect : List.of(AudienceTokenDefect.BAD_SIGNATURE, AudienceTokenDefect.OTHER_TENANT)) {
            String token = idp.audienceToken(ORDERS, List.of("Orders.Read"), Instant.now().plusSeconds(3600), defect);
            TokenPermissionsResolver resolver = manager(token, realDecoder());
            MockServerWebExchange exchange = exchange();
            String check = defect == AudienceTokenDefect.BAD_SIGNATURE ? "signature" : "iss";

            StepVerifier.create(resolver.resolve(userWithIdTokenRoles("Admin"), ORDERS, exchange))
                    .expectErrorSatisfies(denied(DenyReason.INVALID_AUDIENCE_TOKEN, new TokenError(ORDERS_ID, 0, null, List.of(), check)))
                    .verify(WAIT);
            assertThat(exchange.<DenyReason>getAttribute(GatewaySecurityAttributes.DENY_REASON))
                    .isEqualTo(DenyReason.INVALID_AUDIENCE_TOKEN);
            TokenError logged = exchange.getAttribute(GatewaySecurityAttributes.TOKEN_ERROR);
            assertThat(logged.failedCheck()).isEqualTo(check);
            assertThat(logged.status()).isZero();
            assertThat(resolver.size()).isZero();
            assertThat(exchange.getAttributes()).doesNotContainKey(GatewaySecurityAttributes.AUDIENCE_TOKEN);
        }
    }

    @Test
    void aTokenWithoutAudOrWithTheAzpOfAnotherClientIsInvalid() {
        tokens.put("no-aud", jwt(Duration.ofHours(1)).claims(claims -> claims.remove("aud")).build());
        tokens.put("other-azp", jwt(Duration.ofHours(1)).claim("azp", "other-client").build());

        for (Map.Entry<String, String> token : Map.of("no-aud", "aud", "other-azp", "azp").entrySet()) {
            MockServerWebExchange exchange = exchange();

            StepVerifier.create(manager(token.getKey(), stubDecoder).resolve(userWithIdTokenRoles(), ORDERS, exchange))
                    .expectErrorSatisfies(denied(DenyReason.INVALID_AUDIENCE_TOKEN,
                            new TokenError(ORDERS_ID, 0, null, List.of(), token.getValue())))
                    .verify(WAIT);
            assertThat(exchange.<DenyReason>getAttribute(GatewaySecurityAttributes.DENY_REASON))
                    .isEqualTo(DenyReason.INVALID_AUDIENCE_TOKEN);
        }
    }

    @Test
    void aTokenWithMoreThanOneAudienceIsInvalid() {
        tokens.put("two", jwt(Duration.ofHours(1)).audience(List.of(ORDERS, "other")).build());

        StepVerifier.create(resolver(stubDecoder, 10).decoded("two")).expectErrorSatisfies(invalid("aud")).verify(WAIT);
    }

    // The login of spec 022 uses the oid claim as the principal name (user-name-attribute: oid).
    @Test
    void aTokenOfAnotherUserIsInvalid() {
        tokens.put("a", jwt(Duration.ofHours(1)).claim("oid", "00000000-0000-0000-0000-0000000000b2").build());
        TokenPermissionsResolver resolver = manager("a", stubDecoder);
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(resolver.resolve(userWithIdTokenRoles(), ORDERS, exchange))
                .expectErrorSatisfies(denied(DenyReason.INVALID_AUDIENCE_TOKEN, new TokenError(ORDERS_ID, 0, null, List.of(), "oid")))
                .verify(WAIT);
        assertThat(exchange.getAttributes()).doesNotContainKey(GatewaySecurityAttributes.AUDIENCE_TOKEN);
    }

    @Test
    void aKeySetThatDoesNotLoadIsATokenEndpointError() {
        AudienceTokenDecoder decoder = decoder(token -> Mono.error(new IllegalStateException("Could not obtain the keys")));
        MockServerWebExchange exchange = exchange();
        storeToken("token");

        StepVerifier.create(manager("token", decoder).resolve(userWithIdTokenRoles(), ORDERS, exchange))
                .expectErrorSatisfies(denied(DenyReason.TOKEN_ENDPOINT_ERROR, TokenError.withoutResponse(ORDERS_ID)))
                .verify(WAIT);
        assertThat(exchange.<DenyReason>getAttribute(GatewaySecurityAttributes.DENY_REASON)).isEqualTo(DenyReason.TOKEN_ENDPOINT_ERROR);
        assertThat(exchange.<TokenError>getAttribute(GatewaySecurityAttributes.TOKEN_ERROR))
                .isEqualTo(TokenError.withoutResponse(ORDERS_ID));
        // The token itself was not checked: it stays in the session.
        assertThat(storedToken()).isEqualTo("token");
    }

    @Test
    void aKeySetThatDoesNotAnswerInTimeIsATokenEndpointTimeout() {
        WebClientRequestException readTimeout = new WebClientRequestException(ReadTimeoutException.INSTANCE, HttpMethod.GET,
                URI.create("https://login.example.com/tenant/discovery/v2.0/keys"), new HttpHeaders());
        AudienceTokenDecoder decoder = decoder(token -> Mono.error(new JwtException("An error occurred", readTimeout)));
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(manager("token", decoder).resolve(userWithIdTokenRoles(), ORDERS, exchange))
                .expectErrorSatisfies(denied(DenyReason.TOKEN_ENDPOINT_TIMEOUT, TokenError.withoutResponse(ORDERS_ID)))
                .verify(WAIT);
        assertThat(exchange.<DenyReason>getAttribute(GatewaySecurityAttributes.DENY_REASON))
                .isEqualTo(DenyReason.TOKEN_ENDPOINT_TIMEOUT);
        assertThat(exchange.<TokenError>getAttribute(GatewaySecurityAttributes.TOKEN_ERROR))
                .isEqualTo(TokenError.withoutResponse(ORDERS_ID));
    }

    // The decoding has its own timeout: a decoder that never answers does not keep the cache entry.
    @Test
    void aDecodingThatNeverEndsTimesOutAndFreesTheEntry() {
        VirtualTimeScheduler time = VirtualTimeScheduler.create();
        AudienceTokenDecoder never = decoder(token -> Mono.never());
        TokenPermissionsResolver resolver = new TokenPermissionsResolver(request -> Mono.just(client("token")), never, store,
                10, TIMEOUT, clock, ticker, time);
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(resolver.resolve(userWithIdTokenRoles(), ORDERS, exchange))
                .then(() -> time.advanceTimeBy(TIMEOUT))
                .expectErrorSatisfies(denied(DenyReason.TOKEN_ENDPOINT_TIMEOUT, TokenError.withoutResponse(ORDERS_ID)))
                .verify(WAIT);
        assertThat(resolver.size()).isZero();

        StepVerifier.create(resolver.decoded("token"))
                .then(() -> time.advanceTimeBy(TIMEOUT))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(KeySetUnavailableException.class,
                        unavailable -> assertThat(unavailable.timeout()).isTrue()))
                .verify(WAIT);
        assertThat(decodings).hasValue(2);
    }

    @Test
    void anErrorOfTheAuthorizedClientManagerPassesUnchanged() {
        AudienceTokenException consent = new AudienceTokenException(DenyReason.CONSENT_REQUIRED,
                TokenError.withoutResponse(ORDERS_ID));
        TokenPermissionsResolver resolver = new TokenPermissionsResolver(request -> Mono.error(consent), stubDecoder, store,
                10, TIMEOUT);

        StepVerifier.create(resolver.resolve(userWithIdTokenRoles(), ORDERS, exchange())).expectErrorMatches(e -> e == consent)
                .verify(WAIT);
        assertThat(decodings).hasValue(0);
    }

    @Test
    void resolveNeverCompletesEmpty() {
        TokenPermissionsResolver withoutClient = new TokenPermissionsResolver(request -> Mono.empty(), stubDecoder, store,
                10, TIMEOUT);
        TokenPermissionsResolver withoutToken = manager("token", decoder(token -> Mono.empty()));

        StepVerifier.create(withoutClient.resolve(userWithIdTokenRoles(), ORDERS, exchange()))
                .expectErrorMessage("The authorized client manager gave no client for the registration " + ORDERS_ID + ".")
                .verify(WAIT);
        StepVerifier.create(withoutToken.resolve(userWithIdTokenRoles(), ORDERS, exchange()))
                .expectErrorMessage("The audience token decoder gave no token.")
                .verify(WAIT);
    }

    @Test
    void twoDecodingsOfTheSameTokenCauseOneDecoderCall() {
        tokens.put("a", jwt(Duration.ofHours(1)).build());
        TokenPermissionsResolver resolver = resolver(stubDecoder, 10);

        resolver.decoded("a").block(WAIT);
        resolver.decoded("a").block(WAIT);

        assertThat(decodings).hasValue(1);
    }

    @Test
    void parallelDecodingsOfTheSameTokenShareOneDecoderCall() {
        Sinks.One<Jwt> pending = Sinks.one();
        TokenPermissionsResolver resolver = resolver(decoder(token -> pending.asMono()), 10);

        Mono<TokenPermissionsResolver.Decoded> first = resolver.decoded("a").cache();
        Mono<TokenPermissionsResolver.Decoded> second = resolver.decoded("a").cache();
        first.subscribe();
        second.subscribe();
        pending.tryEmitValue(jwt(Duration.ofHours(1)).build());

        assertThat(first.block(WAIT)).isEqualTo(second.block(WAIT));
        assertThat(decodings).hasValue(1);
    }

    // The failure of the shared decoding is remembered: the second request gets it without a decoding.
    @Test
    void parallelRequestsOfAnInvalidTokenGetTheErrorOfOneDecoding() {
        Sinks.One<Jwt> pending = Sinks.one();
        TokenPermissionsResolver resolver = resolver(decoder(token -> pending.asMono()), 10);

        Mono<TokenPermissionsResolver.Decoded> first = resolver.decoded("a").cache();
        Mono<TokenPermissionsResolver.Decoded> second = resolver.decoded("a").cache();
        first.subscribe(value -> { }, error -> { });
        second.subscribe(value -> { }, error -> { });
        pending.tryEmitError(new BadJwtException("bad signature"));

        StepVerifier.create(first).expectErrorSatisfies(invalid("signature")).verify(WAIT);
        StepVerifier.create(second).expectErrorSatisfies(invalid("signature")).verify(WAIT);
        assertThat(decodings).hasValue(1);
        assertThat(resolver.size()).isZero();
    }

    // A key set that did not load is not remembered: the second request decodes again to get its own error.
    @Test
    void parallelRequestsOfAFailedKeySetDecodeAgain() {
        Sinks.One<Jwt> pending = Sinks.one();
        AtomicInteger calls = new AtomicInteger();
        TokenPermissionsResolver resolver = resolver(decoder(token -> calls.incrementAndGet() == 1 ? pending.asMono()
                : Mono.error(new IllegalStateException("Could not obtain the keys"))), 10);

        Mono<TokenPermissionsResolver.Decoded> first = resolver.decoded("a").cache();
        Mono<TokenPermissionsResolver.Decoded> second = resolver.decoded("a").cache();
        first.subscribe(value -> { }, error -> { });
        second.subscribe(value -> { }, error -> { });
        pending.tryEmitError(new IllegalStateException("Could not obtain the keys"));

        StepVerifier.create(first).expectError(KeySetUnavailableException.class).verify(WAIT);
        StepVerifier.create(second).expectError(KeySetUnavailableException.class).verify(WAIT);
        assertThat(decodings).hasValue(2);
        assertThat(resolver.size()).isZero();
    }

    // The decoding of the real decoder ends on a Netty thread. The request must not see its error before the
    // entry is removed: a test thread that checks the size then would see the entry.
    @Test
    void theEntryOfAFailedDecodingIsRemovedBeforeTheRequestGetsTheError() {
        for (Throwable error : List.of(new BadJwtException("bad signature"),
                new IllegalStateException("Could not obtain the keys"))) {
            Sinks.One<Jwt> pending = Sinks.one();
            TokenPermissionsResolver resolver = resolver(decoder(token -> pending.asMono()), 10);
            AtomicLong sizeAtError = new AtomicLong(-1);

            resolver.decoded("a").subscribe(value -> { }, failure -> sizeAtError.set(resolver.size()));
            pending.tryEmitError(error);

            assertThat(sizeAtError).hasValue(0);
        }
    }

    // Caffeine creates an entry under the lock of its map: a decoding inside it could not ask for the same key.
    @Test
    void theDecodingStartsAfterTheCacheCreatedTheEntry() {
        tokens.put("a", jwt(Duration.ofHours(1)).build());
        AtomicReference<TokenPermissionsResolver> self = new AtomicReference<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        TokenPermissionsResolver resolver = resolver(decoder(token -> {
            self.get().decoded(token).subscribe(value -> { }, errors::add);
            return Mono.just(tokens.get(token));
        }), 10);
        self.set(resolver);

        assertThat(resolver.decoded("a").block(WAIT).permissions().permissions()).containsExactly("Orders.Read");
        assertThat(errors).isEmpty();
        assertThat(decodings).hasValue(1);
    }

    @Test
    void anInvalidTokenIsRememberedFor30Seconds() {
        TokenPermissionsResolver resolver = resolver(stubDecoder, 10);

        StepVerifier.create(resolver.decoded("unknown")).expectErrorSatisfies(invalid("signature")).verify(WAIT);
        nanos.addAndGet(TokenPermissionsResolver.INVALID_TOKEN_LIFETIME.minusSeconds(1).toNanos());
        StepVerifier.create(resolver.decoded("unknown")).expectErrorSatisfies(invalid("signature")).verify(WAIT);
        assertThat(decodings).hasValue(1);
        assertThat(resolver.size()).isZero();

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        StepVerifier.create(resolver.decoded("unknown")).expectErrorSatisfies(invalid("signature")).verify(WAIT);
        assertThat(decodings).hasValue(2);
    }

    @Test
    void aStoredTokenThatFailsTheValidationIsRemovedFromTheSession() {
        MockWebSession session = new MockWebSession();
        MockServerWebExchange exchange = exchange(session);
        storeToken(session.getId(), "invalid");
        session.getAttributes().put(SessionAuthorizedClientRepository.attribute(ORDERS_ID), client("invalid"));

        StepVerifier.create(manager("invalid", stubDecoder).resolve(userWithIdTokenRoles(), ORDERS, exchange))
                .expectError(AudienceTokenException.class)
                .verify(WAIT);

        assertThat(store.load(session.getId(), ORDERS_ID).blockOptional(WAIT)).isEmpty();
        assertThat(session.getAttributes()).doesNotContainKey(SessionAuthorizedClientRepository.attribute(ORDERS_ID));
    }

    // Compare-and-delete: another request stored a new token after this request read the invalid one.
    @Test
    void aNewerStoredTokenIsNotRemoved() {
        MockWebSession session = new MockWebSession();
        storeToken(session.getId(), "newer");

        StepVerifier.create(manager("invalid", stubDecoder).resolve(userWithIdTokenRoles(), ORDERS, exchange(session)))
                .expectError(AudienceTokenException.class)
                .verify(WAIT);

        assertThat(store.load(session.getId(), ORDERS_ID).block(WAIT).client().getAccessToken().getTokenValue())
                .isEqualTo("newer");
    }

    @Test
    void theRemovalRunsUnderTheLockOfTheSession() {
        MockWebSession session = new MockWebSession();
        storeToken(session.getId(), "invalid");
        AtomicInteger locks = new AtomicInteger();
        SessionTokenStore locking = new CountingLocks(store, locks);
        TokenPermissionsResolver resolver = new TokenPermissionsResolver(request -> Mono.just(client("invalid")), stubDecoder,
                locking, 10, TIMEOUT, clock, ticker, Schedulers.parallel());

        StepVerifier.create(resolver.resolve(userWithIdTokenRoles(), ORDERS, exchange(session)))
                .expectError(AudienceTokenException.class)
                .verify(WAIT);

        assertThat(locks).hasValue(1);
        assertThat(store.load(session.getId(), ORDERS_ID).blockOptional(WAIT)).isEmpty();
    }

    @Test
    void anEntryExpiresAtTheTokenExpiry() {
        tokens.put("short", jwt(Duration.ofMinutes(10)).build());
        TokenPermissionsResolver resolver = resolver(stubDecoder, 10);
        resolver.decoded("short").block(WAIT);

        nanos.addAndGet(Duration.ofMinutes(10).minusSeconds(1).toNanos());
        resolver.decoded("short").block(WAIT);
        assertThat(decodings).hasValue(1);

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        resolver.decoded("short").block(WAIT);
        assertThat(decodings).hasValue(2);
    }

    @Test
    void anEntryOfATokenWithALifetimeOfTwoHoursExpiresOneHourAfterItWasAdded() {
        tokens.put("long", jwt(Duration.ofHours(2)).build());
        TokenPermissionsResolver resolver = resolver(stubDecoder, 10);
        resolver.decoded("long").block(WAIT);

        nanos.addAndGet(Duration.ofHours(1).minusSeconds(1).toNanos());
        resolver.decoded("long").block(WAIT);
        assertThat(decodings).hasValue(1);

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        resolver.decoded("long").block(WAIT);
        assertThat(decodings).hasValue(2);
    }

    // The resolver uses a direct executor: cleanUp() runs the eviction on this thread, without timing.
    @Test
    void theCacheHoldsAtMostTheConfiguredMaximumSize() {
        List<String> names = List.of("a", "b", "c");
        for (String token : names) {
            tokens.put(token, jwt(Duration.ofHours(1)).build());
        }
        TokenPermissionsResolver resolver = resolver(stubDecoder, 2);

        for (String token : names) {
            resolver.decoded(token).block(WAIT);
        }

        assertThat(resolver.maximumSize()).isEqualTo(2);
        assertThat(resolver.size()).isEqualTo(2);
        // The cache evicted one of the three entries: decoding all three again calls the decoder again.
        for (String token : names) {
            resolver.decoded(token).block(WAIT);
        }
        assertThat(decodings.get()).isGreaterThan(3);
    }

    @Test
    void theDefaultMaximumSizeIs10000() {
        GatewaySecurityProperties properties = new Binder(new MapConfigurationPropertySource(Map.of("gateway.security.login.failure-path", "/")))
                .bindOrCreate("gateway.security", GatewaySecurityProperties.class);

        assertThat(properties.permissionCache().maximumSize()).isEqualTo(10_000);
        assertThat(new TokenPermissionsResolver(request -> Mono.empty(), stubDecoder, store,
                properties.permissionCache().maximumSize(), TIMEOUT).maximumSize()).isEqualTo(10_000);
    }

    @Test
    void theMaximumSizeMustBeBetweenOneAndOneMillion() {
        assertThatIllegalStateException().isThrownBy(() -> resolver(stubDecoder, 0))
                .withMessage("gateway.security.permission-cache.maximum-size must be between 1 and 1000000, but is 0.");
        assertThatIllegalStateException().isThrownBy(() -> resolver(stubDecoder, 1_000_001))
                .withMessage("gateway.security.permission-cache.maximum-size must be between 1 and 1000000, but is 1000001.");
        assertThat(resolver(stubDecoder, 1_000_000).maximumSize()).isEqualTo(1_000_000);
    }

    private TokenPermissionsResolver resolver(AudienceTokenDecoder decoder, int maximumSize) {
        return new TokenPermissionsResolver(request -> Mono.empty(), decoder, store, maximumSize, TIMEOUT, clock, ticker,
                Schedulers.parallel());
    }

    /** A resolver whose authorized client manager gives a client with the token. */
    private TokenPermissionsResolver manager(String token, AudienceTokenDecoder decoder) {
        return new TokenPermissionsResolver(request -> Mono.just(client(token)), decoder, store, 10, TIMEOUT, clock, ticker,
                Schedulers.parallel());
    }

    /** A decoder with the checks of the audience token decoder after the stub, which counts its calls. */
    private AudienceTokenDecoder decoder(Function<String, Mono<Jwt>> decoding) {
        return new AudienceTokenDecoder(token -> {
            decodings.incrementAndGet();
            return decoding.apply(token);
        }, idp.issuer(), MockIdentityProvider.TENANT_ID, MockIdentityProvider.CLIENT_ID);
    }

    private Jwt.Builder jwt(Duration lifetime) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .audience(List.of(ORDERS))
                .issuedAt(NOW)
                .expiresAt(NOW.plus(lifetime))
                .claim("azp", MockIdentityProvider.CLIENT_ID)
                .claim("oid", MockIdentityProvider.USER_OID)
                .claim("roles", List.of("Orders.Read"));
    }

    private static List<String> roles(int count, int length) {
        List<String> roles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "Role." + i + ".";
            roles.add(name + "x".repeat(length - name.length()));
        }
        return roles;
    }

    private static Consumer<Throwable> invalid(String check) {
        return e -> assertThat(e).isInstanceOfSatisfying(InvalidAudienceTokenException.class,
                invalid -> assertThat(invalid.check()).isEqualTo(check));
    }

    private static Consumer<Throwable> denied(DenyReason reason, TokenError tokenError) {
        return e -> assertThat(e).isInstanceOfSatisfying(AudienceTokenException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.tokenError()).isEqualTo(tokenError);
        });
    }

    private AudienceTokenDecoder realDecoder() {
        ClientRegistration entra = ClientRegistration.withClientRegistration(
                        AudienceClientRegistrationsTest.entra(MockIdentityProvider.CLIENT_ID))
                .issuerUri(idp.issuer())
                .jwkSetUri(idp.jwkSetUri())
                .build();
        WebClient webClient = IdentityProviderWebClient.create(Duration.ofSeconds(5), ObservationRegistry.NOOP);
        return AudienceTokenDecoder.ofLocalIdentityProvider(entra, webClient);
    }

    private static OAuth2AuthorizedClient client(String token) {
        ClientRegistration registration = ClientRegistration.withClientRegistration(
                        AudienceClientRegistrationsTest.entra(MockIdentityProvider.CLIENT_ID))
                .registrationId(ORDERS_ID)
                .build();
        return new OAuth2AuthorizedClient(registration, MockIdentityProvider.USER_OID, new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, token, Instant.now(), Instant.now().plusSeconds(3600)));
    }

    /** A session in the store memory with the token, under the id of the session of the request. */
    private void storeToken(String sessionId, String token) {
        if (!sessions.contains(sessionId)) {
            sessions.save(new MapSession(sessionId)).block(WAIT);
        }
        assertThat(store.save(sessionId, client(token)).block(WAIT)).isTrue();
    }

    private void storeToken(String token) {
        storeToken(defaultSession.getId(), token);
    }

    private String storedToken() {
        return store.load(defaultSession.getId(), ORDERS_ID).block(WAIT).client().getAccessToken().getTokenValue();
    }

    /** A user whose ID token has a {@code roles} claim. The resolver must ignore it. */
    private static Authentication userWithIdTokenRoles(String... roles) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .subject("pairwise-subject-a1")
                .claim("oid", MockIdentityProvider.USER_OID)
                .claim("roles", List.of(roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        DefaultOidcUser user = new DefaultOidcUser(List.of(), idToken, "oid");
        return new OAuth2AuthenticationToken(user, List.of(), "entra");
    }

    private MockServerWebExchange exchange() {
        return exchange(defaultSession);
    }

    private static MockServerWebExchange exchange(MockWebSession session) {
        return MockServerWebExchange.builder(MockServerHttpRequest.get("/orders-api/orders/42")).session(session).build();
    }

    /** The store memory, with a count of the locks. */
    private record CountingLocks(SessionTokenStore delegate, AtomicInteger locks) implements SessionTokenStore {

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
            return delegate.countGrant(sessionId);
        }

        @Override
        public Mono<Void> deleteSession(String sessionId) {
            return delegate.deleteSession(sessionId);
        }

        @Override
        public <T> Mono<T> withLock(String sessionId, Mono<T> work) {
            locks.incrementAndGet();
            return delegate.withLock(sessionId, work);
        }
    }
}
