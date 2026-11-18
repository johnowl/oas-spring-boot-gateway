package com.johnowl.gateway.security;

import com.johnowl.gateway.core.access.AccessRule;
import com.johnowl.gateway.core.access.OperationAccess;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The access decision for each request of the session chain (spec 024, "Decision table"). The rows, from
 * top to bottom; the first row that applies gives the result:
 *
 * <ol>
 *   <li>No access rule for the method and the path: deny with {@link DenyReason#NO_MATCH} ({@code 404}).
 *   <li>The rule is public: grant.
 *   <li>No authenticated user: deny with {@link DenyReason#NOT_AUTHENTICATED} (entry point).
 *   <li>The {@link TokenPermissionsResolver} of spec 023 gets no valid access token of the rule audience:
 *       deny with the reason of its {@link AudienceTokenException} (rows 4 to 11).
 *   <li>The token {@code aud} is not the rule audience: deny with {@link DenyReason#WRONG_AUDIENCE} ({@code 500}).
 *   <li>The token permissions do not satisfy the rule: deny with {@link DenyReason#MISSING_PERMISSION} ({@code 403}).
 *   <li>Grant, also for an {@code authenticated-only} rule: spec 025 relays the token.
 * </ol>
 *
 * <p>Any other error during rows 4 to 7, for example a defect in the resolver, denies with
 * {@link DenyReason#INTERNAL_ERROR} ({@code 500}). The manager never lets an error leave the decision.
 *
 * <p>The permissions come only from the access token of the rule audience, never from the ID token.
 *
 * <p>The access rule comes from the exchange attribute {@link GatewaySecurityAttributes#OPERATION_ACCESS} of
 * {@link UndocumentedOperationWebFilter}, so the security index is searched once for each request. Without
 * the attribute, for example in a unit test without the filter, the manager searches the index itself.
 * A client cannot set exchange attributes.
 *
 * <p>Each denial sets {@link GatewaySecurityAttributes#DENY_REASON} and writes one line of
 * {@link AuthorizationDenials}.
 */
public final class GatewayAuthorizationManager implements ReactiveAuthorizationManager<AuthorizationContext> {

    private static final AuthorizationDecision GRANTED = new AuthorizationDecision(true);

    private static final AuthorizationDecision DENIED = new AuthorizationDecision(false);

    private final SecurityIndex index;

    private final TokenPermissionsResolver permissions;

    private final AuthorizationDenials denials;

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    public GatewayAuthorizationManager(SecurityIndex index, TokenPermissionsResolver permissions,
            AuthorizationDenials denials) {
        this.index = index;
        this.permissions = permissions;
        this.denials = denials;
    }

    @Override
    public Mono<AuthorizationResult> authorize(Mono<Authentication> authentication, AuthorizationContext context) {
        ServerWebExchange exchange = context.getExchange();
        Optional<OperationAccess> found = Optional
                .ofNullable(exchange.<OperationAccess>getAttribute(GatewaySecurityAttributes.OPERATION_ACCESS))
                .or(() -> index.find(exchange));
        if (found.isEmpty()) {
            // The log line names the user, also for a denial before the user is needed.
            return authentication
                    .filter(trustResolver::isAuthenticated)
                    .map(user -> Optional.of(user.getName()))
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(principal -> deny(exchange, DenyReason.NO_MATCH, null, principal.orElse(null)));
        }
        OperationAccess operation = found.get();
        AccessRule rule = operation.rule();
        exchange.getAttributes().put(GatewaySecurityAttributes.ACCESS_RULE, rule);
        if (rule.isPublic()) {
            return Mono.just(GRANTED);
        }
        return authentication
                .filter(trustResolver::isAuthenticated)
                .flatMap(user -> Mono.defer(() -> permissions.resolve(user, rule.audience(), exchange))
                        .flatMap(token -> decide(exchange, operation, user, token))
                        .onErrorResume(AudienceTokenException.class,
                                e -> deny(exchange, e.reason(), operation, user.getName()))
                        .onErrorResume(e -> !(e instanceof AudienceTokenException),
                                e -> internalError(exchange, operation, user.getName(), e)))
                .switchIfEmpty(Mono.defer(() -> deny(exchange, DenyReason.NOT_AUTHENTICATED, operation, null)));
    }

    private Mono<AuthorizationResult> decide(ServerWebExchange exchange, OperationAccess operation, Authentication user,
            TokenPermissions token) {
        AccessRule rule = operation.rule();
        if (!rule.audience().equals(token.audience())) {
            exchange.getAttributes().put(GatewaySecurityAttributes.DENY_REASON, DenyReason.WRONG_AUDIENCE);
            denials.wrongAudience(exchange, operation, user.getName(), token.audience());
            return Mono.just(DENIED);
        }
        if (!rule.allows(token.permissions())) {
            return deny(exchange, DenyReason.MISSING_PERMISSION, operation, user.getName());
        }
        return Mono.just(GRANTED);
    }

    private Mono<AuthorizationResult> internalError(ServerWebExchange exchange, OperationAccess operation,
            String principal, Throwable error) {
        exchange.getAttributes().put(GatewaySecurityAttributes.DENY_REASON, DenyReason.INTERNAL_ERROR);
        denials.internalError(exchange, operation, principal, error);
        return Mono.just(DENIED);
    }

    private Mono<AuthorizationResult> deny(ServerWebExchange exchange, DenyReason reason, OperationAccess operation,
            String principal) {
        exchange.getAttributes().put(GatewaySecurityAttributes.DENY_REASON, reason);
        denials.denied(exchange, reason, operation, principal);
        return Mono.just(DENIED);
    }
}
