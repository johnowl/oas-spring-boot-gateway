package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.johnowl.gateway.core.access.OperationAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** The denial log and the denial metrics of spec 024. */
class AuthorizationDenialsTest {

    /** The table of spec 024, "Denial logs and metrics". */
    private static final Map<DenyReason, Level> LEVELS = Map.ofEntries(
            Map.entry(DenyReason.NO_MATCH, Level.DEBUG),
            Map.entry(DenyReason.NOT_AUTHENTICATED, Level.DEBUG),
            Map.entry(DenyReason.MISSING_PERMISSION, Level.INFO),
            Map.entry(DenyReason.SESSION_INVALID, Level.INFO),
            Map.entry(DenyReason.CONSENT_REQUIRED, Level.INFO),
            Map.entry(DenyReason.REAUTHENTICATION_REQUIRED, Level.INFO),
            Map.entry(DenyReason.TOKEN_GRANT_LIMIT, Level.WARN),
            Map.entry(DenyReason.TOKEN_ENDPOINT_TIMEOUT, Level.WARN),
            Map.entry(DenyReason.TOKEN_ENDPOINT_ERROR, Level.WARN),
            Map.entry(DenyReason.TOKEN_ENDPOINT_THROTTLED, Level.WARN),
            Map.entry(DenyReason.TOKEN_REQUEST_REJECTED, Level.ERROR),
            Map.entry(DenyReason.INVALID_AUDIENCE_TOKEN, Level.ERROR),
            Map.entry(DenyReason.WRONG_AUDIENCE, Level.ERROR),
            Map.entry(DenyReason.INTERNAL_ERROR, Level.ERROR));

    private static final OperationAccess GET_ORDER = GatewayAuthorizationManagerTest.GET_ORDER;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final AuthorizationDenials denials = new AuthorizationDenials(meters);

    private final Logger logger = (Logger) LoggerFactory.getLogger(AuthorizationDenials.class);

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    private Level previousLevel;

    @BeforeEach
    void captureLog() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(lines);
        logger.setLevel(previousLevel);
    }

    @Test
    void theLevelTableCoversEachDenyReason() {
        assertThat(LEVELS).containsOnlyKeys(DenyReason.values());
    }

    @ParameterizedTest
    @EnumSource(value = DenyReason.class, mode = EnumSource.Mode.EXCLUDE, names = "WRONG_AUDIENCE")
    void eachDenialWritesOneLineAtItsLevelAndCountsItsReason(DenyReason reason) {
        MockServerWebExchange exchange = exchange("/orders-api/orders/42");

        denials.denied(exchange, reason, GET_ORDER, MockIdentityProvider.USER_OID);

        assertThat(lines.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(LEVELS.get(reason));
            assertThat(line.getFormattedMessage()).isEqualTo("Access denied: reason=" + reason
                    + " method=GET path=/orders-api/orders/42 principal=" + MockIdentityProvider.USER_OID
                    + " route=" + GET_ORDER.routeId());
        });
        assertThat(meters.get(AuthorizationDenials.DENIED_METRIC).tag("reason", reason.name()).counter().count())
                .isEqualTo(1);
        assertThat(meters.find(AuthorizationDenials.AUDIENCE_MISMATCH_METRIC).counters()).isEmpty();
    }

    @Test
    void withoutUserThePrincipalIsADash() {
        denials.denied(exchange("/admin"), DenyReason.NO_MATCH, null, null);

        assertThat(lines.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("Access denied: reason=NO_MATCH method=GET path=/admin principal=-");
    }

    @Test
    void wrongAudienceWritesTheRouteAndBothAudiencesAndHasItsOwnCounter() {
        denials.wrongAudience(exchange("/orders-api/orders/42"), GET_ORDER, MockIdentityProvider.USER_OID, "other-api");

        assertThat(lines.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.ERROR);
            assertThat(line.getFormattedMessage()).isEqualTo("Access denied: reason=WRONG_AUDIENCE method=GET"
                    + " path=/orders-api/orders/42 principal=" + MockIdentityProvider.USER_OID + " route=" + GET_ORDER.routeId()
                    + " expectedAudience=" + SessionTestSpecs.AUDIENCE + " tokenAudience=other-api");
        });
        assertThat(meters.get(AuthorizationDenials.AUDIENCE_MISMATCH_METRIC).tag("route", GET_ORDER.routeId())
                .counter().count()).isEqualTo(1);
        assertThat(meters.find(AuthorizationDenials.DENIED_METRIC).counters()).isEmpty();
    }

    // SEC-60-2: the class of the error, never its message, because a message can contain a token.
    @Test
    void internalErrorWritesTheClassOfTheErrorAndCountsIt() {
        denials.internalError(exchange("/orders-api/orders/42"), GET_ORDER, MockIdentityProvider.USER_OID,
                new IllegalStateException("eyJ secret"));

        assertThat(lines.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.ERROR);
            assertThat(line.getFormattedMessage()).isEqualTo("Access denied: reason=INTERNAL_ERROR method=GET"
                    + " path=/orders-api/orders/42 principal=" + MockIdentityProvider.USER_OID + " route=" + GET_ORDER.routeId()
                    + " exception=java.lang.IllegalStateException");
            assertThat(line.getThrowableProxy()).isNull();
        });
        assertThat(meters.get(AuthorizationDenials.DENIED_METRIC).tag("reason", "INTERNAL_ERROR").counter().count())
                .isEqualTo(1);
    }

    @Test
    void tokenErrorFieldsAreInTheSameLine() {
        MockServerWebExchange exchange = exchange("/orders-api/orders/42");
        exchange.getAttributes().put(GatewaySecurityAttributes.TOKEN_ERROR,
                TokenError.of("entra-" + SessionTestSpecs.AUDIENCE, 400, "invalid_scope", List.of(70011L)));

        denials.denied(exchange, DenyReason.TOKEN_REQUEST_REJECTED, GET_ORDER, MockIdentityProvider.USER_OID);

        assertThat(lines.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .endsWith(" registration=entra-" + SessionTestSpecs.AUDIENCE + " status=400 error=invalid_scope errorCodes=[70011]");
    }

    @Test
    void anInvalidTokenLineHasTheFailedCheck() {
        MockServerWebExchange exchange = exchange("/orders-api/orders/42");
        exchange.getAttributes().put(GatewaySecurityAttributes.TOKEN_ERROR,
                TokenError.invalidToken("entra-" + SessionTestSpecs.AUDIENCE, "iss"));

        denials.denied(exchange, DenyReason.INVALID_AUDIENCE_TOKEN, GET_ORDER, MockIdentityProvider.USER_OID);

        assertThat(lines.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains(" registration=entra-" + SessionTestSpecs.AUDIENCE).endsWith(" check=iss");
    }

    @Test
    void thePathIsRawEscapedAndCut() {
        denials.denied(exchange("/orders-api/%0Aorders"), DenyReason.NO_MATCH, null, null);
        denials.denied(exchange("/" + "a".repeat(2000)), DenyReason.NO_MATCH, null, null);

        assertThat(lines.list.get(0).getFormattedMessage()).contains(" path=/orders-api/%0Aorders ");
        assertThat(lines.list.get(1).getFormattedMessage()).contains(" path=/" + "a".repeat(511) + "... ");
    }

    private static MockServerWebExchange exchange(String rawPath) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, java.net.URI.create(rawPath)));
    }
}
