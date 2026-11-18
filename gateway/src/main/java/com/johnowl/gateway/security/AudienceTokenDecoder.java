package com.johnowl.gateway.security;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.publisher.Mono;

/**
 * Validates the access tokens of the audiences (spec 023, "Permission decoding and cache"). One decoder
 * serves all audiences: all apps of the tenant share the signing keys of the JSON Web Key Set.
 *
 * <p>The decoder checks the signature, the issuer (exact string), {@code ver} ({@code 2.0}),
 * {@code tid} (the tenant of the issuer), {@code exp} (required) and {@code nbf}. {@link #decode(String)}
 * does not check {@code aud}: spec 024 checks {@code aud} for each route. {@link #decode(String, String)}
 * also checks {@code aud} and {@code azp}, for a new token before the gateway stores it.
 * {@link #decodeStored(String)} checks exactly one {@code aud} of any value and {@code azp}, for a stored
 * token before the gateway reads its permissions. A version 1 token has another issuer and the
 * {@code ver} {@code 1.0}, so the decoder rejects it.
 *
 * <p>Issuer pin: the issuer of the registration {@code entra} must be
 * {@code https://<host>/<tenant-id>/v2.0}, with an Entra ID host and a tenant id in lowercase GUID form. The
 * JSON Web Key Set must be on the same host, under {@code /<tenant-id>/discovery/}. Only a test bean of
 * {@link LocalIdentityProvider} allows another host.
 */
public final class AudienceTokenDecoder {

    // The names of the checks, as they appear in the log line of INVALID_AUDIENCE_TOKEN.
    static final String SIGNATURE = "signature";
    static final String ISSUER = "iss";
    static final String VERSION = "ver";
    static final String TENANT = "tid";
    static final String EXPIRES = "exp";
    static final String NOT_BEFORE = "nbf";
    static final String AUDIENCE = "aud";
    static final String AUTHORIZED_PARTY = "azp";
    static final String ROLES = "roles";
    static final String OBJECT_ID = "oid";

    /** The hosts of the Entra ID clouds: global, US Government, and China (21Vianet). */
    static final Set<String> ENTRA_HOSTS = Set.of(
            "login.microsoftonline.com", "login.microsoftonline.us", "login.partner.microsoftonline.cn");

    /** A version 2 issuer: {@code <scheme>://<host>/<tenant-id>/v2.0}. */
    private static final Pattern ISSUER_PATTERN = Pattern.compile("^(https?)://([^/?#@]+)/([^/?#]+)/v2\\.0$");

    private static final Pattern TENANT_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final Pattern KEY_SET_PATH = Pattern.compile("^[A-Za-z0-9._~/-]*$");

    /**
     * A token with a valid form and an invalid signature. Its validation makes the decoder fetch and
     * cache the JSON Web Key Set. The header has no key id, so the decoder fetches the key set once.
     */
    private static final String KEY_SET_PROBE = "eyJhbGciOiJSUzI1NiJ9.e30.AA";

    private final ReactiveJwtDecoder decoder;

    private final String issuer;

    private final String tenantId;

    private final String clientId;

    AudienceTokenDecoder(ReactiveJwtDecoder decoder, String issuer, String tenantId, String clientId) {
        this.decoder = decoder;
        this.issuer = issuer;
        this.tenantId = tenantId;
        this.clientId = clientId;
    }

    /**
     * Creates the decoder from the registration {@code entra}: its issuer is the only accepted issuer,
     * and its JSON Web Key Set gives the keys.
     *
     * @param webClient the client for the JSON Web Key Set, with timeouts ({@link IdentityProviderWebClient})
     * @throws IllegalStateException if the issuer of {@code entra} is not a version 2 issuer of Entra ID,
     *     or the JSON Web Key Set is not on its host
     */
    public static AudienceTokenDecoder of(ClientRegistration entra, WebClient webClient) {
        return create(entra, webClient, false);
    }

    /** As {@link #of}, but the issuer may have any host and any tenant segment. Only for a {@link LocalIdentityProvider}. */
    static AudienceTokenDecoder ofLocalIdentityProvider(ClientRegistration entra, WebClient webClient) {
        return create(entra, webClient, true);
    }

    private static AudienceTokenDecoder create(ClientRegistration entra, WebClient webClient, boolean local) {
        String issuer = entra.getProviderDetails().getIssuerUri();
        Matcher matcher = issuer == null ? null : ISSUER_PATTERN.matcher(issuer);
        if (matcher == null || !matcher.matches() || !(local || isEntra(matcher))) {
            throw new IllegalStateException("The issuer of the registration entra must have the form "
                    + "https://login.microsoftonline.com/<tenant-id>/v2.0, with the host " + String.join(", ", hosts())
                    + " and the tenant id as a GUID in lowercase, but is '" + issuer + "'.");
        }
        String tenantId = matcher.group(3);
        String jwkSetUri = entra.getProviderDetails().getJwkSetUri();
        String keySetPrefix = matcher.group(1) + "://" + matcher.group(2) + "/" + tenantId + "/discovery/";
        if (jwkSetUri == null || !jwkSetUri.startsWith(keySetPrefix) || jwkSetUri.contains("..")
                || !KEY_SET_PATH.matcher(jwkSetUri.substring(keySetPrefix.length())).matches()) {
            throw new IllegalStateException("The jwks_uri of the registration entra must start with '" + keySetPrefix
                    + "', but is '" + jwkSetUri + "'.");
        }
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .webClient(webClient)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                timestamps,
                claim("iss", issuer, ISSUER),
                claim("ver", "2.0", VERSION),
                claim("tid", tenantId, TENANT))));
        return new AudienceTokenDecoder(decoder, issuer, tenantId, entra.getClientId());
    }

    private static boolean isEntra(Matcher issuer) {
        return "https".equals(issuer.group(1)) && ENTRA_HOSTS.contains(issuer.group(2))
                && TENANT_ID.matcher(issuer.group(3)).matches();
    }

    private static List<String> hosts() {
        return ENTRA_HOSTS.stream().sorted().toList();
    }

    /** The only accepted issuer. */
    public String issuer() {
        return issuer;
    }

    /** The tenant id of the issuer. */
    public String tenantId() {
        return tenantId;
    }

    /**
     * Decodes and validates a token.
     *
     * @return the token; an {@link InvalidAudienceTokenException} with the name of the failed check; or a
     *     {@link KeySetUnavailableException} if the JSON Web Key Set does not load
     */
    public Mono<Jwt> decode(String token) {
        return decoder.decode(token).onErrorMap(AudienceTokenDecoder::failure);
    }

    /**
     * Decodes and validates a new token of an audience (spec 023, step 7 of the provider). In addition to
     * {@link #decode(String)}, the token must have exactly this {@code aud}, and its {@code azp} must be
     * the client id of the registration {@code entra}.
     */
    public Mono<Jwt> decode(String token, String audience) {
        return decodeWithParties(token, audience);
    }

    /**
     * Decodes and validates a stored token of an audience (spec 023, "Permission decoding and cache"). In
     * addition to {@link #decode(String)}, the token must have exactly one {@code aud}, of any value, and
     * its {@code azp} must be the client id of the registration {@code entra}. Spec 024 compares the
     * {@code aud} with the audience of the route.
     */
    public Mono<Jwt> decodeStored(String token) {
        return decodeWithParties(token, null);
    }

    /** {@link #decode(String)} and the checks {@code aud} and {@code azp}; without an audience, any single {@code aud} passes. */
    private Mono<Jwt> decodeWithParties(String token, String audience) {
        return decode(token).handle((jwt, sink) -> {
            String failed = audienceAndAuthorizedParty(jwt, audience);
            if (failed != null) {
                sink.error(new InvalidAudienceTokenException(failed));
            } else {
                sink.next(jwt);
            }
        });
    }

    /** The failed check of {@code aud} or {@code azp}, or {@code null}. */
    private String audienceAndAuthorizedParty(Jwt jwt, String audience) {
        List<String> audiences = jwt.getAudience();
        if (audiences == null || audiences.size() != 1
                || (audience != null && !audience.equalsIgnoreCase(audiences.getFirst()))) {
            return AUDIENCE;
        }
        if (!clientId.equalsIgnoreCase(String.valueOf(jwt.getClaims().get(AUTHORIZED_PARTY)))) {
            return AUTHORIZED_PARTY;
        }
        return null;
    }

    /**
     * Fetches the JSON Web Key Set into the cache of the decoder. The gateway calls it once at startup, so
     * the first token redemption does not wait for it. If it fails, the decoder fetches the key set at the
     * first validation.
     *
     * @return empty, or a {@link KeySetUnavailableException}
     */
    public Mono<Void> loadKeys() {
        return decode(KEY_SET_PROBE).then().onErrorResume(InvalidAudienceTokenException.class, e -> Mono.empty());
    }

    /**
     * The decoder of Spring Security reports a key set that does not load as an
     * {@link IllegalStateException} ("Could not obtain the keys") or as a {@link JwtException} with the
     * error of the {@code WebClient} as a cause.
     */
    private static Throwable failure(Throwable e) {
        boolean keySet = e instanceof IllegalStateException;
        for (Throwable cause = e.getCause(); cause != null && !keySet; cause = cause.getCause()) {
            keySet = cause instanceof WebClientException;
        }
        if (keySet) {
            return new KeySetUnavailableException(IdentityProviderWebClient.isTimeout(e));
        }
        return e instanceof JwtException jwt ? new InvalidAudienceTokenException(check(jwt)) : e;
    }

    /** The name of the failed check. The exception messages of Spring Security never reach the log. */
    private static String check(JwtException e) {
        if (!(e instanceof JwtValidationException validation)) {
            return SIGNATURE;
        }
        for (OAuth2Error error : validation.getErrors()) {
            String description = String.valueOf(error.getDescription());
            if (List.of(ISSUER, VERSION, TENANT).contains(description)) {
                return description;
            }
            if (description.startsWith("Jwt expired") || description.startsWith("exp is required")) {
                return EXPIRES;
            }
            if (description.startsWith("Jwt used before")) {
                return NOT_BEFORE;
            }
        }
        return SIGNATURE;
    }

    /** Exact string comparison of a claim. The error description is the name of the check. */
    private static OAuth2TokenValidator<Jwt> claim(String name, String expected, String check) {
        OAuth2TokenValidatorResult failure = OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", check, null));
        return jwt -> {
            Object value = jwt.getClaims().get(name);
            return value != null && expected.equals(value.toString()) ? OAuth2TokenValidatorResult.success() : failure;
        };
    }
}
