package com.johnowl.gateway.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Redeems the refresh token of the registration {@code entra} for an access token of an audience (spec
 * 023, "Custom authorized client provider"). It handles only the per-audience registrations.
 *
 * <ol>
 *   <li>A registration that is not a per-audience registration: empty. Other providers handle it.
 *   <li>A token of the request for the user of the request that expires more than the clock skew from
 *       now: empty. It stays in use. A failure of the last 30 seconds for the session and the
 *       registration: the same failure, without a grant.
 *   <li>The single-flight redemption of the session, and inside it the lock of the session store. The
 *       work under the lock has a timeout of {@link #REDEMPTION_TIMEOUT}:
 *   <li>Read the client of the registration again from the store. A valid token of the user of the
 *       request from another request: use it.
 *   <li>Read the client of {@code entra} from the store. Without a refresh token, or of another user:
 *       {@code SESSION_INVALID}.
 *   <li>Check the pause after a throttled response and the grant limit of the instance, count the grant
 *       (grant limit of the session), then send the refresh token grant with the scope
 *       {@code <client-id>/.default offline_access}. The registrations come from the repository, never
 *       from the session: the session has no client secret, and a person with write access to Redis
 *       could change its token URI.
 *   <li>Validate the new token, with its {@code aud} and {@code azp}. A valid token goes to the store,
 *       without a refresh token, with the earlier expiry of {@code exp} and {@code expires_in}.
 *   <li>A new refresh token goes to the client of {@code entra} with a compare-and-set, also when the
 *       validation failed.
 * </ol>
 *
 * <p>The provider does not log. A failure is an {@link AudienceTokenException}; the provider also puts
 * the deny reason and the {@link TokenError} into the exchange attributes for spec 024. For
 * {@code SESSION_INVALID} and {@code REAUTHENTICATION_REQUIRED} it deletes the session.
 */
final class AudienceRefreshTokenAuthorizedClientProvider implements ReactiveOAuth2AuthorizedClientProvider {

    /** The counter of the refresh token grants, with the tag {@code result}. */
    static final String GRANTS_METRIC = "gateway.token.grants";

    static final String OFFLINE_ACCESS = "offline_access";

    /**
     * The maximum time of the work under the lock of the session (steps 4 to 8). Shorter than the
     * lifetime of the Redis lock (10 seconds), so the lock does not expire while the work runs.
     */
    static final Duration REDEMPTION_TIMEOUT = Duration.ofSeconds(8);

    /** How long the provider remembers a failed grant of a session and a registration. */
    static final Duration FAILURE_LIFETIME = Duration.ofSeconds(30);

    /** The maximum number of remembered failures. An evicted failure only allows one more grant. */
    static final long MAX_FAILURES = 10_000;

    private static final String ENTRA = EntraAuthorizationRequestResolver.REGISTRATION_ID;

    private final AudienceClientRegistrations audiences;

    private final ReactiveClientRegistrationRepository registrations;

    private final SessionTokenStore store;

    private final SessionRedemptions redemptions;

    private final TokenEndpointClient tokenEndpoint;

    private final AudienceTokenDecoder decoder;

    private final Duration clockSkew;

    private final Clock clock;

    private final MeterRegistry meters;

    private final GrantRateLimiter grantLimiter;

    private final Duration redemptionTimeout;

    /** The failed grants of the last 30 seconds, by session and registration. */
    private final Cache<String, AudienceTokenException> failures;

    AudienceRefreshTokenAuthorizedClientProvider(AudienceClientRegistrations audiences,
            ReactiveClientRegistrationRepository registrations, SessionTokenStore store, SessionRedemptions redemptions,
            TokenEndpointClient tokenEndpoint, AudienceTokenDecoder decoder, Duration clockSkew, Clock clock,
            MeterRegistry meters, GrantRateLimiter grantLimiter) {
        this(audiences, registrations, store, redemptions, tokenEndpoint, decoder, clockSkew, clock, meters, grantLimiter,
                REDEMPTION_TIMEOUT, Ticker.systemTicker());
    }

    /** Tests give a short redemption timeout and a ticker for the failure lifetime. */
    AudienceRefreshTokenAuthorizedClientProvider(AudienceClientRegistrations audiences,
            ReactiveClientRegistrationRepository registrations, SessionTokenStore store, SessionRedemptions redemptions,
            TokenEndpointClient tokenEndpoint, AudienceTokenDecoder decoder, Duration clockSkew, Clock clock,
            MeterRegistry meters, GrantRateLimiter grantLimiter, Duration redemptionTimeout, Ticker ticker) {
        if (clockSkew == null || clockSkew.isNegative()) {
            throw new IllegalStateException("gateway.security.token-clock-skew must not be negative, but is " + clockSkew + ".");
        }
        this.audiences = audiences;
        this.registrations = registrations;
        this.store = store;
        this.redemptions = redemptions;
        this.tokenEndpoint = tokenEndpoint;
        this.decoder = decoder;
        this.clockSkew = clockSkew;
        this.clock = clock;
        this.meters = meters;
        this.grantLimiter = grantLimiter;
        this.redemptionTimeout = redemptionTimeout;
        this.failures = Caffeine.newBuilder()
                .expireAfterWrite(FAILURE_LIFETIME)
                .maximumSize(MAX_FAILURES)
                .executor(Runnable::run)
                .ticker(ticker)
                .build();
    }

    @Override
    public Mono<OAuth2AuthorizedClient> authorize(OAuth2AuthorizationContext context) {
        String registrationId = context.getClientRegistration().getRegistrationId();
        String principalName = context.getPrincipal().getName();
        if (!audiences.contains(registrationId) || isValid(context.getAuthorizedClient(), principalName)) {
            return Mono.empty();
        }
        ServerWebExchange exchange = context.getAttribute(ServerWebExchange.class.getName());
        if (exchange == null) {
            return Mono.error(new IllegalStateException("The authorize request has no ServerWebExchange attribute."));
        }
        return exchange.getSession()
                .flatMap(session -> {
                    AudienceTokenException failure = failures.getIfPresent(failureKey(session.getId(), registrationId));
                    return failure != null ? Mono.<OAuth2AuthorizedClient>error(failure)
                            : redemptions.redeem(session.getId(), registrationId,
                                    () -> redeem(session.getId(), registrationId, principalName));
                })
                .onErrorResume(AudienceTokenException.class, e -> deny(exchange, e));
    }

    /** Steps 4 to 8, under the lock of the session. */
    private Mono<OAuth2AuthorizedClient> redeem(String sessionId, String registrationId, String principalName) {
        Mono<OAuth2AuthorizedClient> redemption = store.load(sessionId, registrationId)
                .map(SessionTokenStore.Stored::client)
                .filter(client -> isValid(client, principalName))
                .switchIfEmpty(Mono.defer(() -> grant(sessionId, registrationId, principalName)))
                // For example a JSON Web Key Set that does not answer: the lock must not expire during the work.
                .timeout(redemptionTimeout)
                .onErrorMap(TimeoutException.class, e -> new AudienceTokenException(
                        DenyReason.TOKEN_ENDPOINT_TIMEOUT, TokenError.withoutResponse(registrationId)));
        return store.withLock(sessionId, redemption)
                .onErrorMap(SessionTokenStore.LockTimeoutException.class, e -> new AudienceTokenException(
                        DenyReason.TOKEN_ENDPOINT_TIMEOUT, TokenError.withoutResponse(registrationId)));
    }

    private Mono<OAuth2AuthorizedClient> grant(String sessionId, String registrationId, String principalName) {
        Mono<SessionTokenStore.Stored> entra = store.load(sessionId, ENTRA)
                .filter(stored -> stored.client().getRefreshToken() != null
                        && stored.client().getPrincipalName().equals(principalName))
                // Without a refresh token the session cannot get tokens: as invalid_grant (row 3).
                .switchIfEmpty(store.deleteSession(sessionId).then(Mono.error(() -> new AudienceTokenException(
                        DenyReason.SESSION_INVALID, TokenError.withoutResponse(registrationId)))));
        return Mono.zip(entra, registration(ENTRA), registration(registrationId)).flatMap(found -> {
            // The pause and the limit of the instance come first: a stopped grant does not use the limit of the session.
            if (tokenEndpoint.paused() || !grantLimiter.tryAcquire()) {
                return Mono.error(new AudienceTokenException(DenyReason.TOKEN_ENDPOINT_THROTTLED,
                        TokenError.withoutResponse(registrationId)));
            }
            return store.countGrant(sessionId).flatMap(allowed -> allowed
                    ? send(sessionId, found.getT1(), found.getT2(), found.getT3())
                            .doOnError(AudienceTokenException.class, e -> remember(sessionId, registrationId, e))
                    : Mono.error(new AudienceTokenException(DenyReason.TOKEN_GRANT_LIMIT,
                            TokenError.withoutResponse(registrationId))));
        });
    }

    /**
     * A sent grant failed, and the session stays valid: the next requests of the session for the
     * registration get the same failure for 30 seconds. Not for throttling: the pause of the instance
     * ends it.
     */
    private void remember(String sessionId, String registrationId, AudienceTokenException e) {
        if (!endsSession(e.reason()) && e.reason() != DenyReason.TOKEN_ENDPOINT_THROTTLED) {
            failures.put(failureKey(sessionId, registrationId), e);
        }
    }

    private static String failureKey(String sessionId, String registrationId) {
        return sessionId + " " + registrationId;
    }

    private Mono<OAuth2AuthorizedClient> send(String sessionId, SessionTokenStore.Stored entra,
            ClientRegistration entraRegistration, ClientRegistration audience) {
        OAuth2AuthorizedClient stored = entra.client();
        Set<String> scopes = new LinkedHashSet<>(audience.getScopes());
        scopes.add(OFFLINE_ACCESS);
        OAuth2RefreshTokenGrantRequest request = new OAuth2RefreshTokenGrantRequest(audience, stored.getAccessToken(),
                stored.getRefreshToken(), scopes);
        // The first signal counts: a success and the cancel of the redemption timeout can come at the same time.
        AtomicBoolean counted = new AtomicBoolean();
        return tokenEndpoint.send(request)
                .onErrorResume(AudienceTokenException.class, e -> endsSession(e.reason())
                        ? store.deleteSession(sessionId).then(Mono.error(e)) : Mono.error(e))
                .flatMap(response -> {
                    // Step 8: a rotated refresh token, with the registration from the repository.
                    Mono<Boolean> rotate = response.getRefreshToken() == null ? Mono.just(false)
                            : store.compareAndSet(sessionId, entra, new OAuth2AuthorizedClient(entraRegistration,
                                    stored.getPrincipalName(), stored.getAccessToken(), response.getRefreshToken()));
                    return validateAndStore(sessionId, audience, stored.getPrincipalName(), response)
                            .onErrorResume(e -> rotate.then(Mono.error(e)))
                            .flatMap(client -> rotate.thenReturn(client));
                })
                // Each sent grant counts once. Only the redemption timeout cancels the grant: the request has no
                // subscription to it.
                .doOnSuccess(client -> countOnce(counted, "success"))
                .doOnError(e -> countFailedGrant(counted, e))
                .doOnCancel(() -> countOnce(counted, result(DenyReason.TOKEN_ENDPOINT_TIMEOUT)));
    }

    /**
     * Step 7: only a valid token with the {@code aud} of the registration and the {@code azp} of
     * {@code gateway-bff} goes to the store, without a refresh token. Its expiry is the earlier time of
     * {@code exp} and {@code expires_in}.
     */
    private Mono<OAuth2AuthorizedClient> validateAndStore(String sessionId, ClientRegistration audience,
            String principalName, OAuth2AccessTokenResponse response) {
        String registrationId = audience.getRegistrationId();
        OAuth2AccessToken received = response.getAccessToken();
        return decoder.decode(received.getTokenValue(), registrationId.substring(AudienceRegistrationIds.PREFIX.length()))
                .onErrorMap(InvalidAudienceTokenException.class, e -> new AudienceTokenException(
                        DenyReason.INVALID_AUDIENCE_TOKEN, TokenError.invalidToken(registrationId, e.check())))
                .onErrorMap(KeySetUnavailableException.class, e -> new AudienceTokenException(
                        e.timeout() ? DenyReason.TOKEN_ENDPOINT_TIMEOUT : DenyReason.TOKEN_ENDPOINT_ERROR,
                        TokenError.withoutResponse(registrationId)))
                .flatMap(jwt -> {
                    OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(audience, principalName,
                            new OAuth2AccessToken(received.getTokenType(), received.getTokenValue(), received.getIssuedAt(),
                                    earlier(received.getExpiresAt(), jwt.getExpiresAt()), received.getScopes()));
                    return store.save(sessionId, client).flatMap(saved -> saved ? Mono.just(client)
                            : Mono.error(new AudienceTokenException(DenyReason.SESSION_INVALID,
                                    TokenError.withoutResponse(registrationId))));
                });
    }

    private static Instant earlier(Instant a, Instant b) {
        return a == null || (b != null && b.isBefore(a)) ? b : a;
    }

    /** Stores the reason for spec 024. Ends the session of the request for the reasons that end it. */
    private Mono<OAuth2AuthorizedClient> deny(ServerWebExchange exchange, AudienceTokenException e) {
        exchange.getAttributes().put(GatewaySecurityAttributes.DENY_REASON, e.reason());
        exchange.getAttributes().put(GatewaySecurityAttributes.TOKEN_ERROR, e.tokenError());
        if (stoppedBeforeTheGrant(e)) {
            count("limited");   // once for each stopped request, also for the requests that waited for the redemption
        }
        if (!endsSession(e.reason())) {
            return Mono.error(e);
        }
        // Clear first: the security context repository reads the same session object later.
        return exchange.getSession()
                .flatMap(session -> {
                    session.getAttributes().clear();
                    return session.invalidate();
                })
                .then(Mono.error(e));
    }

    /** The registration from the repository. A missing registration is a defect of the gateway. */
    private Mono<ClientRegistration> registration(String registrationId) {
        return registrations.findByRegistrationId(registrationId)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("No registration " + registrationId)));
    }

    /** A limit of the session, the limit of the instance, or the pause stopped the grant. No grant was sent. */
    private static boolean stoppedBeforeTheGrant(AudienceTokenException e) {
        return e.reason() == DenyReason.TOKEN_GRANT_LIMIT
                || (e.reason() == DenyReason.TOKEN_ENDPOINT_THROTTLED && e.tokenError().status() == 0);
    }

    /**
     * A client of the user of the request whose token expires more than the clock skew from now. A client
     * of another user, for example of an earlier login in the same session, is treated as missing.
     */
    private boolean isValid(OAuth2AuthorizedClient client, String principalName) {
        if (client == null || !principalName.equals(client.getPrincipalName())) {
            return false;
        }
        Instant expiresAt = client.getAccessToken().getExpiresAt();
        return expiresAt != null && expiresAt.isAfter(clock.instant().plus(clockSkew));
    }

    private static boolean endsSession(DenyReason reason) {
        return reason == DenyReason.SESSION_INVALID || reason == DenyReason.REAUTHENTICATION_REQUIRED;
    }

    /** A pause that started after step 6 also stops the grant in the token endpoint client: {@code limited} counts it. */
    private void countFailedGrant(AtomicBoolean counted, Throwable e) {
        if (!(e instanceof AudienceTokenException denied)) {
            countOnce(counted, "error");
        } else if (!stoppedBeforeTheGrant(denied)) {
            countOnce(counted, result(denied.reason()));
        }
    }

    /** Counts the result of a sent grant, if no other signal of the same grant counted it first. */
    private void countOnce(AtomicBoolean counted, String result) {
        if (counted.compareAndSet(false, true)) {
            count(result);
        }
    }

    private void count(String result) {
        meters.counter(GRANTS_METRIC, "result", result).increment();
    }

    private static String result(DenyReason reason) {
        return switch (reason) {
            case CONSENT_REQUIRED -> "consent";
            case REAUTHENTICATION_REQUIRED -> "reauthentication";
            case SESSION_INVALID -> "invalid_grant";
            case TOKEN_REQUEST_REJECTED -> "rejected";
            case TOKEN_ENDPOINT_TIMEOUT -> "timeout";
            case INVALID_AUDIENCE_TOKEN -> "invalid_token";
            case TOKEN_ENDPOINT_THROTTLED -> "throttled";
            default -> "error";
        };
    }
}
