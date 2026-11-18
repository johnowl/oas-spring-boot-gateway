package com.johnowl.gateway.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * A {@link TokenPermissionsResolver} with chosen answers, for the tests of spec 024 without a token
 * endpoint: for each audience, a token with a chosen {@code aud} and chosen permissions, or a token error of
 * spec 023. Like the real resolver, a token error sets the deny reason and the token error attributes, and a
 * token sets the attribute {@link GatewaySecurityAttributes#AUDIENCE_TOKEN} with the value {@link #tokenValue(String)}
 * for the relay of spec 025.
 */
class StubTokenPermissionsResolver extends TokenPermissionsResolver {

    private final Map<String, Object> answers = new ConcurrentHashMap<>();

    private final Set<String> withoutRelayToken = ConcurrentHashMap.newKeySet();

    StubTokenPermissionsResolver() {
        super(null, null, null, 1, Duration.ofSeconds(1));
    }

    /** The token of the audience has the {@code aud} of the audience and the permissions. */
    void permissions(String audience, String... permissions) {
        token(audience, audience, permissions);
    }

    /** The token of the audience has the {@code aud} and the permissions. */
    void token(String audience, String tokenAudience, String... permissions) {
        answers.put(audience, new TokenPermissions(tokenAudience, Set.of(permissions), Instant.now().plusSeconds(3600)));
    }

    /**
     * The token of the audience has the {@code aud} of the audience and the permissions, but the resolver sets no
     * {@link GatewaySecurityAttributes#AUDIENCE_TOKEN}, as a defect would (spec 025, "no token at relay").
     */
    void permissionsWithoutRelayToken(String audience, String... permissions) {
        permissions(audience, permissions);
        withoutRelayToken.add(audience);
    }

    /** The value of the stub token for the {@code aud}. */
    static String tokenValue(String tokenAudience) {
        return "stub-access-token-" + tokenAudience;
    }

    /** The redemption for the audience fails with the deny reason. */
    void fails(String audience, DenyReason reason) {
        answers.put(audience, new AudienceTokenException(reason,
                new TokenError(AudienceRegistrationIds.forAudience(audience), 400, "invalid_scope", List.of(70011L), null)));
    }

    /** The resolver for the audience fails with an error that is not a token error, as a defect would. */
    void breaks(String audience, RuntimeException error) {
        answers.put(audience, error);
    }

    void reset() {
        answers.clear();
        withoutRelayToken.clear();
    }

    @Override
    public Mono<TokenPermissions> resolve(Authentication user, String audience, ServerWebExchange exchange) {
        Object answer = answers.get(audience);
        if (answer instanceof AudienceTokenException e) {
            exchange.getAttributes().put(GatewaySecurityAttributes.DENY_REASON, e.reason());
            exchange.getAttributes().put(GatewaySecurityAttributes.TOKEN_ERROR, e.tokenError());
            return Mono.error(e);
        }
        if (answer instanceof RuntimeException e) {
            return Mono.error(e);
        }
        if (answer == null) {
            return Mono.error(new IllegalStateException("No answer for the audience " + audience));
        }
        TokenPermissions token = (TokenPermissions) answer;
        if (!withoutRelayToken.contains(audience)) {
            exchange.getAttributes().put(GatewaySecurityAttributes.AUDIENCE_TOKEN,
                    new AudienceAccessToken(token.audience(), tokenValue(token.audience())));
        }
        return Mono.just(token);
    }
}
