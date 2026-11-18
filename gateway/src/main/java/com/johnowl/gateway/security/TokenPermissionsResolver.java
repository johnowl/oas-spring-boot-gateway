package com.johnowl.gateway.security;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The permissions of a user for an audience (spec 023, "Permission decoding and cache"). Spec 024 calls
 * {@link #resolve} for each protected operation.
 *
 * <p>The permissions come only from the access token of the audience: the authorized client manager gives
 * the per-audience token of the session, and the permissions are its {@code roles} claim. The ID token is
 * never read. The token comes from the session, never from the request.
 *
 * <p>The decoder of the audience tokens validates the token, with its {@code azp}. The {@code oid} of the
 * token must be the principal name of the user: the login of spec 022 uses the {@code oid} claim as the
 * principal name ({@code user-name-attribute: oid}).
 *
 * <p>A Caffeine cache keeps the result, so a token is decoded once in its lifetime. The key is the SHA-256
 * hash of the token, not the token. An entry expires at the token {@code exp}, but at most
 * {@link #MAX_ENTRY_LIFETIME} after it was added. A failed decoding is not in this cache. A second cache
 * keeps a token that failed the validation for {@link #INVALID_TOKEN_LIFETIME}, so the same token gets the
 * same error without a second decoding.
 *
 * <p>The resolver removes a stored token that fails the validation from the session: under the lock of the
 * session, and only if the session still has the same token (compare-and-delete). It never removes a token
 * when the JSON Web Key Set does not load: the token itself was not checked.
 */
public class TokenPermissionsResolver {

    /** The maximum lifetime of a cache entry, also for a token with a longer lifetime. */
    static final Duration MAX_ENTRY_LIFETIME = Duration.ofHours(1);

    /** How long the resolver remembers a token that failed the validation. */
    static final Duration INVALID_TOKEN_LIFETIME = Duration.ofSeconds(30);

    /** The highest accepted value of {@code gateway.security.permission-cache.maximum-size}. */
    static final int MAX_MAXIMUM_SIZE = 1_000_000;

    /** A {@code roles} claim with more values fails the check {@code roles}. */
    static final int MAX_ROLES = 200;

    /** A value of the {@code roles} claim with more characters fails the check {@code roles}. */
    static final int MAX_ROLE_LENGTH = 120;

    /** A cache entry: the permissions and the {@code oid} of the token. */
    record Decoded(TokenPermissions permissions, String objectId) {
    }

    private final ReactiveOAuth2AuthorizedClientManager clients;

    private final AudienceTokenDecoder decoder;

    private final SessionTokenStore store;

    private final Duration timeout;

    private final Clock clock;

    private final Scheduler scheduler;

    private final AsyncCache<String, Decoded> cache;

    private final Cache<String, InvalidAudienceTokenException> invalidTokens;

    /**
     * @param timeout the maximum time of one decoding: the timeout of the token endpoint, which is also the
     *     timeout of the JSON Web Key Set
     */
    TokenPermissionsResolver(ReactiveOAuth2AuthorizedClientManager clients, AudienceTokenDecoder decoder,
            SessionTokenStore store, int maximumSize, Duration timeout) {
        this(clients, decoder, store, maximumSize, timeout, Clock.systemUTC(), Ticker.systemTicker(), Schedulers.parallel());
    }

    /**
     * Tests give a clock for the token {@code exp}, a ticker for the lifetime of the entries, and a scheduler
     * for the timeout of the decoding.
     */
    TokenPermissionsResolver(ReactiveOAuth2AuthorizedClientManager clients, AudienceTokenDecoder decoder,
            SessionTokenStore store, int maximumSize, Duration timeout, Clock clock, Ticker ticker, Scheduler scheduler) {
        if (maximumSize < 1 || maximumSize > MAX_MAXIMUM_SIZE) {
            throw new IllegalStateException("gateway.security.permission-cache.maximum-size must be between 1 and "
                    + MAX_MAXIMUM_SIZE + ", but is " + maximumSize + ".");
        }
        this.clients = clients;
        this.decoder = decoder;
        this.store = store;
        this.timeout = timeout;
        this.clock = clock;
        this.scheduler = scheduler;
        // The calling thread does the maintenance: it is short and never waits.
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfter(Expiry.<String, Decoded>creating((key, decoded) -> lifetime(decoded.permissions())))
                .executor(Runnable::run)
                .ticker(ticker)
                .buildAsync();
        this.invalidTokens = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(INVALID_TOKEN_LIFETIME)
                .executor(Runnable::run)
                .ticker(ticker)
                .build();
    }

    /**
     * The permissions of the user for the audience, from the access token of the audience in the session.
     * The authorized client manager redeems a token if the session has no valid token (spec 023, "Custom
     * authorized client provider").
     *
     * @param user the user of the request
     * @param audience the value of {@code x-gateway.audience}: the client id of the backend app
     * @param exchange the request
     * @return the permissions; {@link TokenPermissions#audience()} is the {@code aud} of the token, and spec
     *     024 compares it with the audience. The exchange attribute
     *     {@link GatewaySecurityAttributes#AUDIENCE_TOKEN} then has the token for the relay of spec 025. An {@link AudienceTokenException} if the gateway has no valid
     *     token: the exchange attributes then have the deny reason and the log fields. Never empty: an
     *     authorized client manager without a client gives an {@link IllegalStateException}, a defect of the
     *     gateway.
     */
    public Mono<TokenPermissions> resolve(Authentication user, String audience, ServerWebExchange exchange) {
        String registrationId = AudienceRegistrationIds.forAudience(audience);
        return clients.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                        .principal(user)
                        .attribute(ServerWebExchange.class.getName(), exchange)
                        .build())
                .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                        "The authorized client manager gave no client for the registration " + registrationId + ".")))
                .map(client -> client.getAccessToken().getTokenValue())
                .flatMap(token -> decoded(token)
                        .<TokenPermissions>handle((decoded, sink) -> {
                            if (user.getName().equals(decoded.objectId())) {
                                // The relay of spec 025 sends this token, the token that passed the checks.
                                exchange.getAttributes().put(GatewaySecurityAttributes.AUDIENCE_TOKEN,
                                        new AudienceAccessToken(decoded.permissions().audience(), token));
                                sink.next(decoded.permissions());
                            } else {
                                sink.error(new InvalidAudienceTokenException(AudienceTokenDecoder.OBJECT_ID));
                            }
                        })
                        .onErrorResume(InvalidAudienceTokenException.class,
                                e -> removeStored(exchange, registrationId, token).then(Mono.error(e)))
                        .onErrorMap(InvalidAudienceTokenException.class, e -> new AudienceTokenException(
                                DenyReason.INVALID_AUDIENCE_TOKEN,
                                new TokenError(registrationId, 0, null, List.of(), e.check())))
                        .onErrorMap(KeySetUnavailableException.class, e -> new AudienceTokenException(
                                e.timeout() ? DenyReason.TOKEN_ENDPOINT_TIMEOUT : DenyReason.TOKEN_ENDPOINT_ERROR,
                                TokenError.withoutResponse(registrationId)))
                        .doOnError(AudienceTokenException.class, e -> {
                            exchange.getAttributes().put(GatewaySecurityAttributes.DENY_REASON, e.reason());
                            exchange.getAttributes().put(GatewaySecurityAttributes.TOKEN_ERROR, e.tokenError());
                        }));
    }

    /**
     * The decoded access token, from the cache or from the decoder. Parallel calls for the same token share
     * one decoding. A token that failed the validation in the last {@link #INVALID_TOKEN_LIFETIME} gets the
     * same error without a decoding.
     *
     * @return the permissions and the {@code oid}; an {@link InvalidAudienceTokenException}, a
     *     {@link KeySetUnavailableException}, or an {@link IllegalStateException} of a decoder without a result
     */
    Mono<Decoded> decoded(String token) {
        return Mono.defer(() -> {
            String key = key(token);
            InvalidAudienceTokenException invalid = invalidTokens.getIfPresent(key);
            if (invalid != null) {
                return Mono.error(invalid);
            }
            // Caffeine runs the mapping function under a lock: the decoding starts after it returns.
            CompletableFuture<Decoded> own = new CompletableFuture<>();
            CompletableFuture<Decoded> cached = cache.get(key, (unused, executor) -> own);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            if (cached == own) {
                start(token, key, own, failure);
            }
            return Mono.fromFuture(cached, true).switchIfEmpty(Mono.defer(() -> failed(token, key, failure.get())));
        });
    }

    /**
     * Runs the decoding of a new entry to its end, also when the request that started it is cancelled. A
     * failed decoding removes the entry and then completes it empty, so Caffeine logs nothing. The removal
     * comes first: the requests run on the thread that completes the entry, before the callback of Caffeine
     * that would remove it.
     */
    private void start(String token, String key, CompletableFuture<Decoded> entry, AtomicReference<Throwable> failure) {
        decode(token, key).subscribe(entry::complete, error -> {
            failure.set(error);
            removeEmpty(key, entry);
        }, () -> removeEmpty(key, entry));
    }

    /** Removes and completes an entry without a value. An entry with a value stays: the completion follows the value. */
    private void removeEmpty(String key, CompletableFuture<Decoded> entry) {
        if (!entry.isDone()) {
            cache.asMap().remove(key, entry);
            entry.complete(null);
        }
    }

    /**
     * The error of a failed decoding. The request that started it gets its error. A request that shared it
     * gets the remembered invalid token, or decodes again without the cache to get its own error, for
     * example after a key set that did not load.
     */
    private Mono<Decoded> failed(String token, String key, Throwable failure) {
        if (failure != null) {
            return Mono.error(failure);
        }
        InvalidAudienceTokenException invalid = invalidTokens.getIfPresent(key);
        return invalid != null ? Mono.error(invalid) : decode(token, key);
    }

    /** Decodes and validates the token. A token that fails the validation is remembered. */
    private Mono<Decoded> decode(String token, String key) {
        return decoder.decodeStored(token)
                // The decoding waits at most for the key set: a decoding that never ends would keep the entry.
                .timeout(timeout, scheduler)
                .onErrorMap(TimeoutException.class, e -> new KeySetUnavailableException(true))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("The audience token decoder gave no token.")))
                .<Decoded>handle((jwt, sink) -> {
                    Set<String> roles = roles(jwt);
                    if (roles == null) {
                        sink.error(new InvalidAudienceTokenException(AudienceTokenDecoder.ROLES));
                    } else {
                        sink.next(new Decoded(new TokenPermissions(jwt.getAudience().getFirst(), roles, jwt.getExpiresAt()),
                                jwt.getClaimAsString(AudienceTokenDecoder.OBJECT_ID)));
                    }
                })
                .doOnError(InvalidAudienceTokenException.class, e -> invalidTokens.put(key, e));
    }

    /**
     * The {@code roles} claim. Entra writes a JSON array of strings. A missing claim gives no permissions.
     * The resolver never splits a string into roles.
     *
     * @return the roles, or {@code null} if the claim is not an array of strings, has more than
     *     {@link #MAX_ROLES} values, or has a value longer than {@link #MAX_ROLE_LENGTH} characters
     */
    private static Set<String> roles(Jwt jwt) {
        Object claim = jwt.getClaims().get(AudienceTokenDecoder.ROLES);
        if (claim == null) {
            return Set.of();
        }
        if (!(claim instanceof List<?> values) || values.size() > MAX_ROLES
                || !values.stream().allMatch(value -> value instanceof String role && role.length() <= MAX_ROLE_LENGTH)) {
            return null;
        }
        return Set.copyOf(values.stream().map(String.class::cast).toList());
    }

    /**
     * Removes the stored token of the registration from the session, under the lock of the session, only if
     * the session still has this token. The copy of the session of this request also loses the token, so
     * the save at the end of the request does not store it again. A failed removal does not change the
     * error of the request: the next request with the token removes it.
     */
    private Mono<Void> removeStored(ServerWebExchange exchange, String registrationId, String token) {
        String attribute = SessionAuthorizedClientRepository.attribute(registrationId);
        return exchange.getSession()
                .flatMap(session -> {
                    if (session.getAttribute(attribute) instanceof OAuth2AuthorizedClient client && hasToken(client, token)) {
                        session.getAttributes().remove(attribute);
                    }
                    return store.withLock(session.getId(), store.load(session.getId(), registrationId)
                            .filter(stored -> hasToken(stored.client(), token))
                            .flatMap(stored -> store.compareAndRemove(session.getId(), stored)));
                })
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    private static boolean hasToken(OAuth2AuthorizedClient client, String token) {
        return token.equals(client.getAccessToken().getTokenValue());
    }

    /** Until the token {@code exp}, at most {@link #MAX_ENTRY_LIFETIME}. */
    private Duration lifetime(TokenPermissions permissions) {
        Duration untilExpiry = Duration.between(clock.instant(), permissions.expiresAt());
        if (untilExpiry.isNegative()) {
            return Duration.ZERO;
        }
        return untilExpiry.compareTo(MAX_ENTRY_LIFETIME) > 0 ? MAX_ENTRY_LIFETIME : untilExpiry;
    }

    /** The cache key: the SHA-256 hash of the token, so the cache holds no token. */
    private static String key(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The configured maximum number of entries. */
    long maximumSize() {
        return cache.synchronous().policy().eviction().orElseThrow().getMaximum();
    }

    /** The number of entries, after the pending maintenance. For tests. */
    long size() {
        cache.synchronous().cleanUp();
        return cache.synchronous().estimatedSize();
    }
}
