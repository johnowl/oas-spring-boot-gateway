package com.johnowl.gateway.security;

import com.johnowl.gateway.Loopback;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.HttpServer;

/**
 * A small OpenID Connect provider on localhost for the tests. It never calls Microsoft. It serves
 * the discovery document, the keys, and the token endpoint. The test plays the browser and the
 * sign-in page: {@link #signIn(URI)} returns an authorization code for an authorization request.
 *
 * <p>The token endpoint checks the client secret (HTTP Basic), the code, and the PKCE code
 * verifier ({@code S256}). The credentials are dummy test values.
 */
public final class MockIdentityProvider implements AutoCloseable {

    public static final String CLIENT_ID = "test-gateway-bff";

    /** The default {@code roles} of the audience tokens: the permissions of the protected operations of {@link SessionTestSpecs}. */
    public static final List<String> DEFAULT_ROLES = List.of("Orders.Read", "Orders.Write", "Payments.Read");

    public static final String CLIENT_SECRET = "test-only-dummy-secret";

    static final String USER_OID = "00000000-0000-0000-0000-0000000000a1";

    /** The tenant id in the issuer of this provider, {@code <base>/tenant/v2.0}. */
    public static final String TENANT_ID = "tenant";

    /** A defect in an ID token, for the tests of a failed login. */
    public enum IdTokenDefect {
        WRONG_NONCE, BAD_SIGNATURE, WRONG_ISSUER, WRONG_AUDIENCE, EXPIRED
    }

    /**
     * The error body that Entra returned for a backend without admin consent, in the scope check of
     * 2026-11-13 (spec 023, "Scope form", result 3): status {@code 400}.
     */
    public static final Map<String, Object> CONSENT_REQUIRED_BODY = Map.of(
            "error", "invalid_grant",
            "error_description", "AADSTS65001: The user or administrator has not consented to use the application ...",
            "error_codes", List.of(65001),
            "suberror", "consent_required");

    /** The error body of Entra for a client id that is not in the tenant (spec 023, "Scope form", result 2): status {@code 400}. */
    public static final Map<String, Object> INVALID_RESOURCE_BODY = Map.of(
            "error", "invalid_resource",
            "error_description", "AADSTS500011: The resource principal was not found in the tenant ...",
            "error_codes", List.of(500011));

    /**
     * One refresh token grant that the token endpoint received.
     *
     * @param form the form parameters
     * @param received when the request arrived
     * @param answered when the response was sent
     * @param refreshToken the new refresh token of the response, or {@code null}
     */
    public record RefreshGrant(Map<String, String> form, Instant received, Instant answered, String refreshToken) {

        /** The audience of the scope {@code <client-id>/.default}. */
        public String audience() {
            String scope = form.getOrDefault("scope", "");
            int end = scope.indexOf("/.default");
            return end < 0 ? null : scope.substring(0, end);
        }
    }

    /**
     * The answer of the token endpoint to the refresh token grants of one audience.
     *
     * @param status the HTTP status; {@code 200} gives a token
     * @param errorBody the body of an error status
     * @param defect the defect of the token, or {@code null}
     */
    public record RefreshAnswer(int status, Map<String, ?> errorBody, AudienceTokenDefect defect, Raw raw) {

        public static RefreshAnswer error(int status, Map<String, ?> body) {
            return new RefreshAnswer(status, body, null, null);
        }

        public static RefreshAnswer token(AudienceTokenDefect defect) {
            return new RefreshAnswer(200, Map.of(), defect, null);
        }

        /** A response with this status, headers and body, for example {@code 302} or an HTML error page. */
        public static RefreshAnswer raw(int status, Map<String, String> headers, String body) {
            return new RefreshAnswer(status, Map.of(), null, new Raw(headers, body));
        }
    }

    /** The headers and the body of a {@link RefreshAnswer#raw} response. */
    public record Raw(Map<String, String> headers, String body) {
    }

    /** A defect in an audience token (spec 023, token validation). */
    public enum AudienceTokenDefect {
        /** A version 1 token: issuer {@code https://sts.windows.net/<tenant-id>/} and {@code ver} {@code 1.0}. */
        VERSION_1,
        /** A version 2 token with the issuer and the {@code tid} of another tenant, signed with the key of this provider. */
        OTHER_TENANT,
        /** The issuer of this provider, but the {@code tid} of another tenant. */
        OTHER_TID,
        /** The issuer of this provider, but {@code ver} {@code 1.0}. */
        VERSION_CLAIM,
        /** Signed with a key that this provider does not publish. */
        BAD_SIGNATURE,
        /** Expired 10 minutes ago, beyond the clock skew. */
        EXPIRED,
        /** Not valid before 10 minutes from now ({@code nbf}), beyond the clock skew. */
        NOT_YET_VALID,
        /** Without the {@code exp} claim. */
        NO_EXPIRY,
        /** The {@code azp} of another client, not of gateway-bff. */
        OTHER_CLIENT,
        /** A {@code roles} claim that is one string, not an array. Step 7 accepts it; the permission resolver does not. */
        ROLES_STRING
    }

    /** A sign-in that the test started. */
    private record Grant(String nonce, String codeChallenge, String redirectUri, String oid) {
    }

    /**
     * The tokens of one token response.
     *
     * @param form the form parameters of the token request
     */
    public record Tokens(String idToken, String accessToken, String refreshToken, Map<String, String> form) {
    }

    private final RSAKey key;

    /** A key that the discovery document does not publish, with the same key id. */
    private final RSAKey otherKey;

    private final AtomicReference<IdTokenDefect> nextDefect = new AtomicReference<>();

    private final DisposableServer server;

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    private final List<Tokens> issued = new CopyOnWriteArrayList<>();

    private final AtomicInteger keySetRequests = new AtomicInteger();

    /** The keys of the JSON Web Key Set. */
    private final List<JWK> publishedKeys = new CopyOnWriteArrayList<>();

    /** The key that signs the audience tokens. */
    private volatile RSAKey audienceKey;

    private volatile Duration keySetDelay = Duration.ZERO;

    private final List<RefreshGrant> refreshGrants = new CopyOnWriteArrayList<>();

    /** The access tokens of the refresh token grants, also the tokens with a defect. */
    private final List<String> audienceTokens = new CopyOnWriteArrayList<>();

    /**
     * The refresh tokens that the token endpoint issued, with the {@code oid} of their user. Like Entra, an old
     * refresh token stays valid after a rotation.
     */
    private final Map<String, String> refreshTokens = new ConcurrentHashMap<>();

    private final Map<String, RefreshAnswer> refreshAnswers = new ConcurrentHashMap<>();

    private volatile Duration refreshDelay = Duration.ZERO;

    private volatile boolean holdRefreshGrants;

    /** The gates of the held refresh token grants, in the order of arrival. */
    private final List<Sinks.Empty<Void>> heldRefreshGrants = new CopyOnWriteArrayList<>();

    private volatile boolean rotateRefreshTokens = true;

    private volatile Duration audienceTokenLifetime = Duration.ofHours(1);

    /** The {@code roles} of the audience tokens of the refresh token grants. */
    private volatile List<String> audienceRoles = DEFAULT_ROLES;

    private final AtomicInteger receivedRefreshGrants = new AtomicInteger();

    public MockIdentityProvider() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        publishedKeys.add(key.toPublicJWK());
        audienceKey = key;
        server = HttpServer.create()
                .host(Loopback.ADDRESS)
                .port(0)
                .route(routes -> routes
                        .get("/tenant/v2.0/.well-known/openid-configuration", (request, response) -> json(response, 200, discovery()))
                        .get("/tenant/discovery/v2.0/keys", this::keySet)
                        .post("/tenant/oauth2/v2.0/token", this::token))
                .bindNow();
    }

    /** The issuer, for {@code spring.security.oauth2.client.provider.entra.issuer-uri}. */
    public String issuer() {
        return baseUrl() + "/tenant/v2.0";
    }

    /** The JSON Web Key Set ({@code jwks_uri}). */
    public String jwkSetUri() {
        return baseUrl() + "/tenant/discovery/v2.0/keys";
    }

    /** The token endpoint. */
    public String tokenEndpoint() {
        return baseUrl() + "/tenant/oauth2/v2.0/token";
    }

    /** The logout endpoint ({@code end_session_endpoint}). */
    public String logoutEndpoint() {
        return baseUrl() + "/tenant/oauth2/v2.0/logout";
    }

    /** The authorization endpoint. */
    public String authorizationEndpoint() {
        return baseUrl() + "/tenant/oauth2/v2.0/authorize";
    }

    /**
     * Signs the user in, as the sign-in page of Entra ID does.
     *
     * @param authorizationRequest the {@code Location} of the redirect from the gateway to this provider
     * @return the authorization code for the callback
     */
    public String signIn(URI authorizationRequest) {
        return signIn(authorizationRequest, USER_OID);
    }

    /**
     * Signs in another user.
     *
     * @param oid the object id of the user: the principal name in the gateway ({@code user-name-attribute: oid})
     */
    public String signIn(URI authorizationRequest, String oid) {
        var query = UriComponentsBuilder.fromUri(authorizationRequest).build(true).getQueryParams();
        String code = UUID.randomUUID().toString();
        grants.put(code, new Grant(decode(query.getFirst("nonce")), query.getFirst("code_challenge"),
                decode(query.getFirst("redirect_uri")), oid));
        return code;
    }

    /** The next token response has an ID token with this defect. The responses after it are valid again. */
    public void defectInNextIdToken(IdTokenDefect defect) {
        nextDefect.set(defect);
    }

    /** The JSON Web Key Set answers after this delay. {@link Duration#ZERO} answers at once. */
    public void delayKeySet(Duration delay) {
        keySetDelay = delay;
    }

    /**
     * Signs the next audience tokens with a new key, which the JSON Web Key Set also publishes. A decoder
     * that has the key set in its cache must load it again. {@link #resetRefreshGrants()} goes back to the
     * first key; the new key stays published.
     */
    public void signAudienceTokensWithANewKey() {
        try {
            RSAKey newKey = new RSAKeyGenerator(2048).keyID("test-key-" + UUID.randomUUID()).generate();
            publishedKeys.add(newKey.toPublicJWK());
            audienceKey = newKey;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The number of requests for the JSON Web Key Set. */
    public int keySetRequests() {
        return keySetRequests.get();
    }

    /** The refresh token grants that the token endpoint received, oldest first. */
    public List<RefreshGrant> refreshGrants() {
        return List.copyOf(refreshGrants);
    }

    /** The number of refresh token grants that arrived, also the grants without an answer yet. */
    public int receivedRefreshGrants() {
        return receivedRefreshGrants.get();
    }

    /** The refresh token grants for an audience. */
    public List<RefreshGrant> refreshGrants(String audience) {
        return refreshGrants.stream().filter(grant -> audience.equals(grant.audience())).toList();
    }

    /** A new refresh token that the token endpoint accepts, as after a login. For unit tests without a login. */
    public String newRefreshToken() {
        String refreshToken = "refresh-" + UUID.randomUUID();
        refreshTokens.put(refreshToken, USER_OID);
        return refreshToken;
    }

    /** The token endpoint answers the refresh token grants of the audience with this answer. */
    public void answerRefreshGrants(String audience, RefreshAnswer answer) {
        refreshAnswers.put(audience, answer);
    }

    /** The token endpoint waits this time before it answers a refresh token grant. */
    public void delayRefreshGrants(Duration delay) {
        refreshDelay = delay;
    }

    /**
     * {@code true}: the token endpoint holds each refresh token grant until the test releases it with
     * {@link #releaseRefreshGrant(int)}. The tests use it instead of a delay to order parallel grants.
     */
    public void holdRefreshGrants(boolean hold) {
        holdRefreshGrants = hold;
    }

    /**
     * Answers a held refresh token grant.
     *
     * @param index the index of the grant in the order of arrival, from 0
     */
    public void releaseRefreshGrant(int index) {
        heldRefreshGrants.get(index).tryEmitEmpty();
    }

    /** {@code false}: the response of a refresh token grant has no new refresh token. */
    public void rotateRefreshTokens(boolean rotate) {
        rotateRefreshTokens = rotate;
    }

    /** The lifetime of the audience tokens of the refresh token grants ({@code expires_in} and {@code exp}). */
    public void audienceTokenLifetime(Duration lifetime) {
        audienceTokenLifetime = lifetime;
    }

    /** The {@code roles} of the audience tokens of the next refresh token grants. */
    public void audienceRoles(List<String> roles) {
        audienceRoles = List.copyOf(roles);
    }

    /** Forgets the refresh token grants and the answers. Each test of spec 023 calls it first. */
    public void resetRefreshGrants() {
        audienceTokenLifetime = Duration.ofHours(1);
        audienceRoles = DEFAULT_ROLES;
        refreshGrants.clear();
        audienceTokens.clear();
        receivedRefreshGrants.set(0);
        refreshAnswers.clear();
        refreshDelay = Duration.ZERO;
        holdRefreshGrants = false;
        heldRefreshGrants.forEach(Sinks.Empty::tryEmitEmpty);
        heldRefreshGrants.clear();
        rotateRefreshTokens = true;
        audienceKey = key;
    }

    /** The access tokens that the refresh token grants returned, also the tokens with a defect, oldest first. */
    public List<String> audienceTokens() {
        return List.copyOf(audienceTokens);
    }

    /** The tokens that the token endpoint returned, oldest first. */
    public List<Tokens> issued() {
        return List.copyOf(issued);
    }

    @Override
    public void close() {
        server.disposeNow();
    }

    private String baseUrl() {
        // An IP address: "localhost" can resolve to ::1 first, and the server listens on IPv4.
        return Loopback.url(server.port());
    }

    private Map<String, Object> discovery() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer());
        metadata.put("authorization_endpoint", authorizationEndpoint());
        metadata.put("token_endpoint", tokenEndpoint());
        metadata.put("jwks_uri", jwkSetUri());
        metadata.put("end_session_endpoint", logoutEndpoint());
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("subject_types_supported", List.of("pairwise"));
        metadata.put("id_token_signing_alg_values_supported", List.of("RS256"));
        metadata.put("scopes_supported", List.of("openid", "profile", "email", "offline_access"));
        metadata.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post"));
        return metadata;
    }

    private Mono<Void> keySet(HttpServerRequest request, HttpServerResponse response) {
        keySetRequests.incrementAndGet();
        return Mono.delay(keySetDelay).then(Mono.defer(() -> json(response, 200, new JWKSet(publishedKeys).toJSONObject())));
    }

    private Mono<Void> token(HttpServerRequest request, HttpServerResponse response) {
        String expectedAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        boolean clientAuthenticated = expectedAuthorization.equals(request.requestHeaders().get("Authorization"));
        return request.receive().aggregate().asString().defaultIfEmpty("").flatMap(body -> {
            Map<String, String> form = form(body);
            if (!clientAuthenticated) {
                return json(response, 401, Map.of("error", "invalid_client"));
            }
            if ("refresh_token".equals(form.get("grant_type"))) {
                return refreshGrant(form, response);
            }
            Grant grant = grants.remove(form.getOrDefault("code", ""));
            if (grant == null
                    || !grant.redirectUri().equals(form.get("redirect_uri"))
                    || !s256(form.getOrDefault("code_verifier", "")).equals(grant.codeChallenge())) {
                return json(response, 400, Map.of("error", "invalid_grant"));
            }
            IdTokenDefect defect = nextDefect.getAndSet(null);
            // Signing and UUIDs use SecureRandom: not on the event loop (BlockHound is active).
            return Mono.fromCallable(() -> new Tokens(idToken(grant.nonce(), grant.oid(), defect), "graph-access-" + UUID.randomUUID(),
                            "refresh-" + UUID.randomUUID(), form))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(tokens -> {
                        issued.add(tokens);
                        refreshTokens.put(tokens.refreshToken(), grant.oid());
                        Map<String, Object> tokenResponse = new LinkedHashMap<>();
                        tokenResponse.put("token_type", "Bearer");
                        tokenResponse.put("scope", "openid profile offline_access");
                        tokenResponse.put("expires_in", 3600);
                        tokenResponse.put("access_token", tokens.accessToken());
                        tokenResponse.put("refresh_token", tokens.refreshToken());
                        tokenResponse.put("id_token", tokens.idToken());
                        return json(response, 200, tokenResponse);
                    });
        });
    }

    private String idToken(String nonce, String oid, IdTokenDefect defect) {
        Instant now = Instant.now();
        // Older than the clock skew of 60 seconds that Spring Security allows.
        Instant issued = defect == IdTokenDefect.EXPIRED ? now.minusSeconds(3600) : now;
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(defect == IdTokenDefect.WRONG_ISSUER ? "https://evil.example.com/tenant/v2.0" : issuer())
                .audience(defect == IdTokenDefect.WRONG_AUDIENCE ? "other-client" : CLIENT_ID)
                .subject("pairwise-subject-a1")
                .issueTime(Date.from(issued))
                .expirationTime(Date.from(issued.plusSeconds(300)))
                .claim("nonce", defect == IdTokenDefect.WRONG_NONCE ? "wrong-nonce" : nonce)
                .claim("oid", oid)
                .claim("tid", "00000000-0000-0000-0000-00000000000t")
                .claim("name", "Test User")
                .claim("preferred_username", "test.user@example.com")
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(defect == IdTokenDefect.BAD_SIGNATURE ? otherKey : key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    /**
     * A refresh token grant for the scope {@code <client-id>/.default offline_access}. The answer of the
     * audience gives the response; by default, a valid version 2 token with the {@link #DEFAULT_ROLES}
     * and a new refresh token.
     */
    private Mono<Void> refreshGrant(Map<String, String> form, HttpServerResponse response) {
        Instant received = Instant.now();
        // The gate exists before the count, so a test that sees the count can release the grant.
        Mono<Void> held = Mono.empty();
        if (holdRefreshGrants) {
            Sinks.Empty<Void> gate = Sinks.empty();
            heldRefreshGrants.add(gate);
            held = gate.asMono();
        }
        receivedRefreshGrants.incrementAndGet();
        String audience = new RefreshGrant(form, received, received, null).audience();
        RefreshAnswer answer = audience == null ? null : refreshAnswers.get(audience);
        String oid = refreshTokens.get(form.getOrDefault("refresh_token", ""));
        boolean known = oid != null;
        boolean offlineAccess = List.of(form.getOrDefault("scope", "").split(" ")).contains("offline_access");
        // Signing and UUIDs use SecureRandom: not on the event loop (BlockHound is active).
        return Mono.delay(refreshDelay).then(held).thenReturn(0L).publishOn(Schedulers.boundedElastic()).flatMap(tick -> {
            if (!known || audience == null || !offlineAccess) {
                refreshGrants.add(new RefreshGrant(form, received, Instant.now(), null));
                return json(response, 400, Map.of("error", known ? "invalid_scope" : "invalid_grant"));
            }
            if (answer != null && answer.raw() != null) {
                refreshGrants.add(new RefreshGrant(form, received, Instant.now(), null));
                answer.raw().headers().forEach(response::header);
                return response.status(answer.status()).sendString(Mono.just(answer.raw().body())).then();
            }
            if (answer != null && answer.status() != 200) {
                refreshGrants.add(new RefreshGrant(form, received, Instant.now(), null));
                return json(response, answer.status(), answer.errorBody());
            }
            String refreshToken = rotateRefreshTokens ? "refresh-" + UUID.randomUUID() : null;
            Map<String, Object> tokenResponse = new LinkedHashMap<>();
            tokenResponse.put("token_type", "Bearer");
            tokenResponse.put("scope", audience + "/access_as_user");
            Duration lifetime = audienceTokenLifetime;
            tokenResponse.put("expires_in", lifetime.toSeconds());
            String accessToken = audienceToken(audience, oid, audienceRoles, Instant.now().plus(lifetime),
                    answer == null ? null : answer.defect());
            audienceTokens.add(accessToken);
            tokenResponse.put("access_token", accessToken);
            if (refreshToken != null) {
                refreshTokens.put(refreshToken, oid);
                tokenResponse.put("refresh_token", refreshToken);
            }
            refreshGrants.add(new RefreshGrant(form, received, Instant.now(), refreshToken));
            return json(response, 200, tokenResponse);
        });
    }

    /**
     * An access token for an audience, as Entra issues it for the scope {@code <client-id>/.default}.
     *
     * @param defect the defect, or {@code null} for a valid version 2 token of this tenant
     */
    public String audienceToken(String audience, List<String> roles, Instant expiresAt, AudienceTokenDefect defect) {
        return audienceToken(audience, USER_OID, roles, expiresAt, defect);
    }

    /**
     * An access token of a user for an audience.
     *
     * @param oid the object id of the user
     */
    public String audienceToken(String audience, String oid, List<String> roles, Instant expiresAt, AudienceTokenDefect defect) {
        Instant now = Instant.now();
        String issuer = switch (defect) {
            case VERSION_1 -> "https://sts.windows.net/" + TENANT_ID + "/";
            case OTHER_TENANT -> baseUrl() + "/other-tenant/v2.0";
            case null, default -> issuer();
        };
        if (defect == AudienceTokenDefect.EXPIRED) {
            expiresAt = now.minusSeconds(600);
        }
        Instant notBefore = defect == AudienceTokenDefect.NOT_YET_VALID ? now.plusSeconds(600) : now.minusSeconds(3600);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject("pairwise-subject-a1")
                .issueTime(Date.from(now.minusSeconds(3600)))
                .notBeforeTime(Date.from(notBefore))
                .expirationTime(defect == AudienceTokenDefect.NO_EXPIRY ? null : Date.from(expiresAt))
                .claim("ver", defect == AudienceTokenDefect.VERSION_1 || defect == AudienceTokenDefect.VERSION_CLAIM ? "1.0" : "2.0")
                .claim("tid", defect == AudienceTokenDefect.OTHER_TENANT || defect == AudienceTokenDefect.OTHER_TID
                        ? "other-tenant" : TENANT_ID)
                .claim("oid", oid)
                .claim("azp", defect == AudienceTokenDefect.OTHER_CLIENT ? "other-client" : CLIENT_ID)
                .claim("scp", "access_as_user")
                .claim("jti", UUID.randomUUID().toString());
        if (defect == AudienceTokenDefect.ROLES_STRING) {
            claims.claim("roles", String.join(",", roles));
        } else if (!roles.isEmpty()) {
            claims.claim("roles", roles);
        }
        RSAKey signingKey = defect == AudienceTokenDefect.BAD_SIGNATURE ? otherKey : audienceKey;
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims.build());
        try {
            jwt.sign(new RSASSASigner(signingKey));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static Mono<Void> json(HttpServerResponse response, int status, Map<String, ?> body) {
        return response.status(status)
                .header("Content-Type", "application/json")
                .header("Cache-Control", "no-store")
                .sendString(Mono.just(JSONObjectUtils.toJSONString(body)))
                .then();
    }

    private static Map<String, String> form(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        UriComponentsBuilder.newInstance().query(body).build(true).getQueryParams()
                .forEach((name, values) -> form.put(decode(name), decode(values.getFirst())));
        return form;
    }

    private static String decode(String value) {
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
