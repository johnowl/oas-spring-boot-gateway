package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.johnowl.gateway.core.access.AccessRule;
import com.johnowl.gateway.core.access.OperationAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Each row of the decision table of spec 024, with a stub permission resolver. */
class GatewayAuthorizationManagerTest {

    private static final String ORDERS = SessionTestSpecs.AUDIENCE;

    private static final String PAYMENTS = SessionTestSpecs.PAYMENTS_AUDIENCE;

    static final AccessRule PROTECTED = new AccessRule(false, ORDERS, Set.of("Orders.Read"), Set.of());

    static final OperationAccess HEALTH =
            new OperationAccess("orders:/health", "/orders-api/health", "GET", AccessRule.publicAccess());

    static final OperationAccess GET_ORDER =
            new OperationAccess("orders:/orders/{id}", "/orders-api/orders/{id}", "GET", PROTECTED);

    private static final OperationAccess REPORTS = new OperationAccess("orders:/reports", "/orders-api/reports", "GET",
            new AccessRule(false, ORDERS, Set.of(), Set.of("Reports.Read", "Reports.Admin")));

    private static final OperationAccess REFUNDS = new OperationAccess("orders:/refunds", "/orders-api/refunds", "GET",
            new AccessRule(false, ORDERS, Set.of("Orders.Read"), Set.of("Refunds.Approve", "Refunds.Admin")));

    private static final OperationAccess PROFILE = new OperationAccess("orders:/profile", "/orders-api/profile", "GET",
            new AccessRule(false, ORDERS, Set.of(), Set.of()));

    private static final OperationAccess PAYMENTS_ADMIN = new OperationAccess("payments:/admin", "/payments-api/admin",
            "GET", new AccessRule(false, PAYMENTS, Set.of("Admin"), Set.of()));

    private final CountingSecurityIndex index =
            new CountingSecurityIndex(List.of(HEALTH, GET_ORDER, REPORTS, REFUNDS, PROFILE, PAYMENTS_ADMIN));

    private final StubTokenPermissionsResolver permissions = new StubTokenPermissionsResolver();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final GatewayAuthorizationManager manager =
            new GatewayAuthorizationManager(index, permissions, new AuthorizationDenials(meters));

    @Test
    void row1RequestWithoutAccessRuleIsDeniedWithNoMatch() {
        for (Mono<Authentication> user : List.of(Mono.<Authentication>empty(), user())) {
            ServerWebExchange exchange = exchange(HttpMethod.GET, "/logout");

            assertThat(authorize(user, exchange).isGranted()).isFalse();
            assertThat(reason(exchange)).isEqualTo(DenyReason.NO_MATCH);
            assertThat(exchange.<Object>getAttribute(GatewaySecurityAttributes.ACCESS_RULE)).isNull();
        }
    }

    // QA-60-5: the NO_MATCH line of the manager names the user.
    @Test
    void row1LogsThePrincipalOfTheUser() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuthorizationDenials.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        lines.start();
        logger.addAppender(lines);
        try {
            authorize(user(), exchange(HttpMethod.GET, "/logout"));
            authorize(Mono.empty(), exchange(HttpMethod.GET, "/logout"));
        } finally {
            logger.detachAppender(lines);
            logger.setLevel(previous);
        }

        assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Access denied: reason=NO_MATCH method=GET path=/logout principal=" + MockIdentityProvider.USER_OID,
                "Access denied: reason=NO_MATCH method=GET path=/logout principal=-");
    }

    @Test
    void row2PublicOperationIsGrantedWithoutUser() {
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/orders-api/health");

        assertThat(authorize(Mono.empty(), exchange).isGranted()).isTrue();
        assertThat(exchange.<AccessRule>getAttribute(GatewaySecurityAttributes.ACCESS_RULE)).isEqualTo(HEALTH.rule());
        assertThat(reason(exchange)).isNull();
    }

    @Test
    void row3ProtectedOperationWithoutUserIsDeniedWithNotAuthenticated() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        for (Mono<Authentication> user : List.of(Mono.<Authentication>empty(), Mono.just(anonymous))) {
            ServerWebExchange exchange = exchange(HttpMethod.GET, "/orders-api/orders/42");

            assertThat(authorize(user, exchange).isGranted()).isFalse();
            assertThat(reason(exchange)).isEqualTo(DenyReason.NOT_AUTHENTICATED);
        }
    }

    // Rows 4 to 11: the token errors of spec 023 keep their deny reason.
    @ParameterizedTest
    @EnumSource(value = DenyReason.class, names = {"CONSENT_REQUIRED", "REAUTHENTICATION_REQUIRED", "SESSION_INVALID",
        "TOKEN_REQUEST_REJECTED", "TOKEN_GRANT_LIMIT", "TOKEN_ENDPOINT_TIMEOUT", "TOKEN_ENDPOINT_ERROR",
        "TOKEN_ENDPOINT_THROTTLED", "INVALID_AUDIENCE_TOKEN"})
    void rows4To11TokenErrorsAreDeniedWithTheirReason(DenyReason tokenError) {
        permissions.fails(ORDERS, tokenError);
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/orders-api/orders/42");

        assertThat(authorize(user(), exchange).isGranted()).isFalse();
        assertThat(reason(exchange)).isEqualTo(tokenError);
        assertThat(denied(tokenError)).isEqualTo(1);
    }

    // SEC-60-2, QA-60-1: another error of the resolver is a denial, never an error of the chain.
    @Test
    void anotherErrorOfTheResolverIsDeniedWithInternalError() {
        permissions.breaks(ORDERS, new IllegalStateException("defect"));
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/orders-api/orders/42");

        assertThat(authorize(user(), exchange).isGranted()).isFalse();
        assertThat(reason(exchange)).isEqualTo(DenyReason.INTERNAL_ERROR);
        assertThat(denied(DenyReason.INTERNAL_ERROR)).isEqualTo(1);
    }

    @Test
    void anErrorThatTheResolverThrowsIsDeniedWithInternalError() {
        StubTokenPermissionsResolver throwing = new StubTokenPermissionsResolver() {
            @Override
            public Mono<TokenPermissions> resolve(Authentication user, String audience, ServerWebExchange exchange) {
                throw new IllegalStateException("defect");
            }
        };
        GatewayAuthorizationManager withThrowingResolver =
                new GatewayAuthorizationManager(index, throwing, new AuthorizationDenials(meters));
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/orders-api/orders/42");

        assertThat(withThrowingResolver.authorize(user(), new AuthorizationContext(exchange)).block().isGranted()).isFalse();
        assertThat(reason(exchange)).isEqualTo(DenyReason.INTERNAL_ERROR);
        assertThat(denied(DenyReason.INTERNAL_ERROR)).isEqualTo(1);
    }

    @Test
    void row12TokenOfAnotherAudienceIsDeniedWithWrongAudience() {
        permissions.token(ORDERS, PAYMENTS, "Orders.Read");
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/orders-api/orders/42");

        assertThat(authorize(user(), exchange).isGranted()).isFalse();
        assertThat(reason(exchange)).isEqualTo(DenyReason.WRONG_AUDIENCE);
        assertThat(meters.get(AuthorizationDenials.AUDIENCE_MISMATCH_METRIC).tag("route", GET_ORDER.routeId())
                .counter().count()).isEqualTo(1);
        assertThat(meters.find(AuthorizationDenials.DENIED_METRIC).counters()).isEmpty();
    }

    @Test
    void row13AllOfNeedsEveryPermission() {
        permissions.permissions(ORDERS, "Orders.Write");

        assertThat(granted("/orders-api/orders/42")).isFalse();
        assertThat(denied(DenyReason.MISSING_PERMISSION)).isEqualTo(1);
        permissions.permissions(ORDERS, "Orders.Read", "Orders.Write");
        assertThat(granted("/orders-api/orders/42")).isTrue();
    }

    @Test
    void row13AnyOfNeedsOnePermission() {
        permissions.permissions(ORDERS, "Orders.Read");
        assertThat(granted("/orders-api/reports")).isFalse();

        permissions.permissions(ORDERS, "Reports.Admin");
        assertThat(granted("/orders-api/reports")).isTrue();
    }

    @Test
    void row13AllOfAndAnyOfGrantOnlyWhenBothHold() {
        permissions.permissions(ORDERS, "Orders.Read");
        assertThat(granted("/orders-api/refunds")).isFalse();
        permissions.permissions(ORDERS, "Refunds.Approve");
        assertThat(granted("/orders-api/refunds")).isFalse();

        permissions.permissions(ORDERS, "Orders.Read", "Refunds.Approve");
        assertThat(granted("/orders-api/refunds")).isTrue();
    }

    @Test
    void row14AuthenticatedOnlyGrantsAnyUserWithAToken() {
        permissions.permissions(ORDERS);

        assertThat(granted("/orders-api/profile")).isTrue();
    }

    // Both audiences use a permission named Admin. The token of orders-api does not pass payments-api.
    @Test
    void permissionOfAnotherAudienceDoesNotPass() {
        permissions.token(PAYMENTS, ORDERS, "Admin");

        ServerWebExchange exchange = exchange(HttpMethod.GET, "/payments-api/admin");
        assertThat(authorize(user(), exchange).isGranted()).isFalse();
        assertThat(reason(exchange)).isEqualTo(DenyReason.WRONG_AUDIENCE);

        permissions.permissions(PAYMENTS, "Admin");
        assertThat(granted("/payments-api/admin")).isTrue();
    }

    @Test
    void usesTheAccessRuleOfTheFilterWithoutASecondLookup() {
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/orders-api/orders/42");
        // The attribute wins over the path: the manager does not search the index.
        exchange.getAttributes().put(GatewaySecurityAttributes.OPERATION_ACCESS, HEALTH);

        assertThat(authorize(Mono.empty(), exchange).isGranted()).isTrue();
        assertThat(index.lookups.get()).isZero();
    }

    @Test
    void filterAndManagerSearchTheIndexOnceForEachRequest() {
        permissions.permissions(ORDERS, "Orders.Read");
        ServerWebExchange exchange = exchange(HttpMethod.GET, "/orders-api/orders/42");
        UndocumentedOperationWebFilter filter =
                new UndocumentedOperationWebFilter(index, ManagementEndpoints.DEFAULT, new AuthorizationDenials(meters));

        filter.filter(exchange, next -> manager.authorize(user(), new AuthorizationContext(next))
                .doOnNext(result -> assertThat(result.isGranted()).isTrue()).then()).block();

        assertThat(index.lookups.get()).isEqualTo(1);
    }

    private boolean granted(String path) {
        return authorize(user(), exchange(HttpMethod.GET, path)).isGranted();
    }

    private double denied(DenyReason reason) {
        return meters.get(AuthorizationDenials.DENIED_METRIC).tag("reason", reason.name()).counter().count();
    }

    private AuthorizationResult authorize(Mono<Authentication> user, ServerWebExchange exchange) {
        return manager.authorize(user, new AuthorizationContext(exchange)).block();
    }

    private static DenyReason reason(ServerWebExchange exchange) {
        return exchange.getAttribute(GatewaySecurityAttributes.DENY_REASON);
    }

    static Mono<Authentication> user() {
        return Mono.just(new TestingAuthenticationToken(MockIdentityProvider.USER_OID, null, "OIDC_USER"));
    }

    static ServerWebExchange exchange(HttpMethod method, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, URI.create(path)));
    }

    /** A spy on {@link SecurityIndex#find}: counts the lookups. */
    static final class CountingSecurityIndex extends SecurityIndex {

        final AtomicInteger lookups = new AtomicInteger();

        CountingSecurityIndex(Collection<OperationAccess> operations) {
            super(operations, null);
        }

        @Override
        public Optional<OperationAccess> find(ServerWebExchange exchange) {
            lookups.incrementAndGet();
            return super.find(exchange);
        }
    }
}
