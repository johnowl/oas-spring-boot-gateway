package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.johnowl.gateway.Loopback;
import com.johnowl.gateway.routing.RecordingMockBackend;
import com.johnowl.gateway.security.MockIdentityProvider.AudienceTokenDefect;
import com.johnowl.gateway.security.MockIdentityProvider.RefreshAnswer;
import com.johnowl.gateway.security.MockIdentityProvider.RefreshGrant;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The per-audience tokens of spec 023 with the session store {@code memory} (the default), the mock
 * identity provider, and BlockHound. The authorization manager of spec 024 asks for the token of each
 * protected operation and maps a failure to its response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class AudienceTokenIT {

    private static final String ORDERS = SessionTestSpecs.AUDIENCE;

    private static final String PAYMENTS = SessionTestSpecs.PAYMENTS_AUDIENCE;

    private static final String ORDER = "/orders-api/orders/42";

    private static final String PAYMENT = "/payments-api/payments/7";

    private static final String SECOND_USER = "00000000-0000-0000-0000-0000000000b2";

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @LocalServerPort
    private int port;

    @Autowired
    private MemorySessionRepository sessions;

    @Autowired
    private SessionRedemptions redemptions;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private TokenPermissionsResolver permissions;

    private TestBrowser browser;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.registerWithPayments(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @BeforeEach
    void login() {
        idp.resetRefreshGrants();
        browser = new TestBrowser(port);
        browser.login(idp);
        backend.clear();
    }

    @AfterEach
    void reset() {
        idp.resetRefreshGrants();
    }

    @Test
    void firstCallRedeemsOnceAndTheSecondCallUsesTheStoredToken() {
        double successes = grants("success");

        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);

        assertThat(idp.refreshGrants()).singleElement().satisfies(grant -> {
            assertThat(grant.form()).containsEntry("grant_type", "refresh_token")
                    .containsEntry("scope", ORDERS + "/.default offline_access")
                    .containsEntry("refresh_token", idp.issued().getLast().refreshToken());
        });
        OAuth2AuthorizedClient orders = stored("entra-" + ORDERS);
        assertThat(orders.getRefreshToken()).isNull();
        assertThat(orders.getClientRegistration().getRegistrationId()).isEqualTo("entra-" + ORDERS);
        assertThat(stored("entra").getRefreshToken().getTokenValue()).isEqualTo(idp.refreshGrants().getFirst().refreshToken());
        assertThat(grants("success")).isEqualTo(successes + 1);
        assertThat(backend.requests()).hasSize(2);
        assertThat(redemptions.running()).isZero();
    }

    @Test
    void thePermissionCacheHasTheDefaultMaximumSize() {
        assertThat(permissions.maximumSize()).isEqualTo(10_000);
    }

    @Test
    void parallelFirstCallsOfOneSessionCauseOneGrant() {
        idp.delayRefreshGrants(Duration.ofMillis(300));

        assertThat(parallel(browser, ORDER, ORDER, ORDER)).containsOnly(200);

        assertThat(idp.refreshGrants()).hasSize(1);
    }

    @Test
    void parallelFirstCallsForTwoAudiencesCauseTwoGrantsOneAfterTheOther() {
        idp.delayRefreshGrants(Duration.ofMillis(300));

        assertThat(parallel(browser, ORDER, PAYMENT)).containsOnly(200);

        List<RefreshGrant> grants = idp.refreshGrants();
        assertThat(grants).hasSize(2);
        assertThat(grants.get(1).received()).isAfterOrEqualTo(grants.get(0).answered());
        assertThat(grants.get(1).form().get("refresh_token")).isEqualTo(grants.get(0).refreshToken());
        assertThat(stored("entra-" + ORDERS)).isNotNull();
        assertThat(stored("entra-" + PAYMENTS)).isNotNull();
        assertThat(stored("entra").getRefreshToken().getTokenValue()).isEqualTo(grants.get(1).refreshToken());
    }

    @Test
    void parallelFirstCallsOfTwoSessionsCauseOneGrantForEachSession() {
        TestBrowser other = new TestBrowser(port);
        other.login(idp);
        idp.delayRefreshGrants(Duration.ofMillis(300));

        List<Integer> statuses = Flux.merge(call(browser, ORDER), call(other, ORDER)).collectList().block(Duration.ofSeconds(10));

        assertThat(statuses).containsOnly(200);
        assertThat(idp.refreshGrants()).hasSize(2)
                .extracting(grant -> grant.form().get("refresh_token")).doesNotHaveDuplicates();
    }

    @Test
    void twoParallelCallsInTheSkewWindowCauseOneGrant() {
        idp.audienceTokenLifetime(Duration.ofSeconds(30));   // less than the clock skew of 60 seconds
        browser.fetch(HttpMethod.GET, ORDER);
        idp.delayRefreshGrants(Duration.ofMillis(300));

        assertThat(parallel(browser, ORDER, ORDER)).containsOnly(200);

        assertThat(idp.refreshGrants()).hasSize(2);
        assertThat(idp.refreshGrants().get(1).form().get("refresh_token")).isEqualTo(idp.refreshGrants().get(0).refreshToken());
    }

    @Test
    void aDisconnectedClientDoesNotStopTheRedemption() {
        idp.delayRefreshGrants(Duration.ofMillis(500));

        // The client gives up after 100 milliseconds.
        call(browser, ORDER).timeout(Duration.ofMillis(100)).onErrorResume(e -> Mono.empty()).block(Duration.ofSeconds(5));

        // The redemption stores the tokens before it ends: wait for all three conditions together.
        await().atMost(Duration.ofSeconds(5)).until(() -> redemptions.running() == 0
                && stored("entra-" + ORDERS) != null
                && idp.refreshGrants().size() == 1
                && idp.refreshGrants().getFirst().refreshToken().equals(stored("entra").getRefreshToken().getTokenValue()));
    }

    @Test
    void theEleventhGrantOfASessionWithinAMinuteIsNotSent() {
        // Tokens in the clock skew window: each call redeems again. A failed grant would be remembered.
        idp.audienceTokenLifetime(Duration.ofSeconds(30));
        double limited = grants("limited");
        for (int i = 0; i < SessionTokenStore.GRANT_LIMIT; i++) {
            assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);
        }

        EntityExchangeResult<byte[]> eleventh = browser.fetch(HttpMethod.GET, ORDER);

        assertThat(eleventh.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(body(eleventh)).contains("\"detail\":\"Too many requests.\"");
        assertThat(idp.refreshGrants()).hasSize(SessionTokenStore.GRANT_LIMIT);
        assertThat(grants("limited")).isEqualTo(limited + 1);
        assertThat(sessions.contains(sessionId())).isTrue();
    }

    @Test
    void missingConsentGivesTheConsentBodyAndKeepsTheSession() {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, MockIdentityProvider.CONSENT_REQUIRED_BODY));

        EntityExchangeResult<byte[]> result = browser.fetch(HttpMethod.GET, ORDER);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(result)).contains("urn:oas-spring-boot-gateway:problem:consent-required");
        assertThat(backend.requests()).isEmpty();
        assertThat(sessions.contains(sessionId())).isTrue();

        // The failure is remembered for 30 seconds: the next call gets it without a grant.
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(idp.refreshGrants()).hasSize(1);
    }

    // Spec 023, "Token endpoint errors", row 1: each consent marker gives 403 with the consent body.
    @ParameterizedTest
    @ValueSource(strings = {"invalid_grant:consent_required:", "invalid_grant::65001", "interaction_required::65001"})
    void eachConsentErrorGives403AndKeepsTheSession(String variant) {
        String[] parts = variant.split(":", -1);
        Map<String, Object> error = new HashMap<>(Map.of("error", parts[0], "error_description", "AADSTS65001: detail"));
        if (!parts[1].isEmpty()) {
            error.put("suberror", parts[1]);
        }
        if (!parts[2].isEmpty()) {
            error.put("error_codes", List.of(Long.parseLong(parts[2])));
        }
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, error));

        EntityExchangeResult<byte[]> result = browser.fetch(HttpMethod.GET, ORDER);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(result)).isEqualTo(GatewayAccessDeniedHandlerTest.CONSENT_BODY);
        assertThat(backend.requests()).isEmpty();
        assertThat(sessions.contains(sessionId())).isTrue();
    }

    // Spec 024, row 13, with a real audience token: its roles do not have Orders.Read.
    @Test
    void aTokenWithoutTheRequiredRoleGets403() {
        idp.audienceRoles(List.of("Orders.Write"));

        EntityExchangeResult<byte[]> result = browser.fetch(HttpMethod.GET, ORDER);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(result)).isEqualTo(GatewayAccessDeniedHandlerTest.FORBIDDEN_BODY);
        assertThat(backend.requests()).isEmpty();
    }

    @Test
    void throttlingGives503AndPausesTheGrantsOfAllSessions() {
        TestBrowser other = new TestBrowser(port);
        other.login(idp);
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.raw(429, Map.of("Retry-After", "1"), ""));

        EntityExchangeResult<byte[]> result = browser.fetch(HttpMethod.GET, ORDER);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(result)).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,"
                + "\"detail\":\"Try again later.\"}");
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.token(null));
        assertThat(other.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(idp.refreshGrants()).hasSize(1);
        assertThat(sessions.contains(sessionId())).isTrue();
        // After the pause, grants go to Entra again.
        await().atMost(Duration.ofSeconds(3)).until(() -> HttpStatus.OK.equals(other.fetch(HttpMethod.GET, ORDER).getStatus()));
    }

    @Test
    void aLoginOfAnotherUserInTheSameSessionDoesNotUseTheTokensOfTheFirstUser() {
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(stored("entra-" + ORDERS).getPrincipalName()).isEqualTo(MockIdentityProvider.USER_OID);

        browser.login(idp, SECOND_USER);

        assertThat(stored("entra-" + ORDERS)).isNull();
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);
        List<RefreshGrant> grants = idp.refreshGrants();
        assertThat(grants).hasSize(2);
        assertThat(grants.get(1).form().get("refresh_token")).isEqualTo(idp.issued().getLast().refreshToken());
        assertThat(stored("entra-" + ORDERS).getPrincipalName()).isEqualTo(SECOND_USER);
        assertThat(stored("entra").getPrincipalName()).isEqualTo(SECOND_USER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_resource", "invalid_scope", "invalid_client"})
    void anotherClientErrorGives500AndKeepsTheSession(String error, CapturedOutput output) {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, Map.of("error", error, "error_description", "secret-detail")));
        int before = errorLines(output);

        EntityExchangeResult<byte[]> result = browser.fetch(HttpMethod.GET, ORDER);

        // Exactly one ERROR line, from the denial log of spec 024.
        assertThat(errorLines(output)).isEqualTo(before + 1);
        assertThat(lastErrorLine(output)).contains("reason=TOKEN_REQUEST_REJECTED", "registration=entra-" + ORDERS,
                        "error=" + error)
                .doesNotContain("secret-detail", MockIdentityProvider.CLIENT_SECRET, idp.issued().getLast().refreshToken());
        assertThat(result.getResponseHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).isNull();

        assertThat(result.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(result)).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Internal Server Error\",\"status\":500,"
                + "\"detail\":\"Internal error.\"}");
        assertThat(backend.requests()).isEmpty();
        assertThat(sessions.contains(sessionId())).isTrue();
    }

    @Test
    void invalidGrantEndsTheSession() {
        String session = sessionId();
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, Map.of("error", "invalid_grant", "error_codes", List.of(700082))));

        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(sessions.contains(session)).isFalse();
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void invalidGrantOfAnHtmlNavigationRedirectsToTheLoginAndEndsTheSession() {
        String session = sessionId();
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, Map.of("error", "invalid_grant")));

        EntityExchangeResult<byte[]> result = browser.navigate(ORDER);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.FOUND);
        assertThat(result.getResponseHeaders().getLocation()).hasToString(SessionAuthenticationEntryPoint.LOGIN_PATH);
        assertThat(sessions.contains(session)).isFalse();
    }

    @Test
    void otherInteractionEndsTheSessionAndRemovesAllClients(CapturedOutput output) {
        String session = sessionId();
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(400, Map.of("error", "interaction_required", "error_codes", List.of(50076))));

        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(output.getOut().lines().filter(line -> line.contains("reason=REAUTHENTICATION_REQUIRED")).toList())
                .singleElement().asString().contains(" INFO ", "errorCodes=[50076]");

        assertThat(sessions.contains(session)).isFalse();
        assertThat(sessions.attribute(session, SessionAuthorizedClientRepository.attribute("entra"))).isNull();
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void aServerErrorGives502AndKeepsTheSession(int status) {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.error(status, Map.of()));

        EntityExchangeResult<byte[]> result = browser.fetch(HttpMethod.GET, ORDER);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(sessions.contains(sessionId())).isTrue();
    }

    @Test
    void aVersion1TokenIsNotStoredAndTheRotatedRefreshTokenIs(CapturedOutput output) {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.token(AudienceTokenDefect.VERSION_1));
        int before = errorLines(output);
        double denied = denied(DenyReason.INVALID_AUDIENCE_TOKEN);

        EntityExchangeResult<byte[]> result = browser.fetch(HttpMethod.GET, ORDER);

        assertThat(result.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(result)).isEqualTo(GatewayAccessDeniedHandlerTest.INTERNAL_ERROR_BODY);
        assertThat(result.getResponseHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(denied(DenyReason.INVALID_AUDIENCE_TOKEN)).isEqualTo(denied + 1);
        assertThat(errorLines(output)).isEqualTo(before + 1);
        // A version 1 token has the issuer of the version 1 endpoint, and the issuer is checked first.
        assertThat(lastErrorLine(output)).contains("reason=INVALID_AUDIENCE_TOKEN", "registration=entra-" + ORDERS)
                .endsWith(" check=iss")
                .doesNotContain("eyJ");

        assertThat(stored("entra-" + ORDERS)).isNull();
        assertThat(stored("entra").getRefreshToken().getTokenValue()).isEqualTo(idp.refreshGrants().getFirst().refreshToken());
        assertThat(sessions.contains(sessionId())).isTrue();
    }

    // Step 7 accepts the token; the permission resolver rejects it and removes it from the session.
    @Test
    void aStoredTokenWithAMalformedRolesClaimIsRemoved() {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.token(AudienceTokenDefect.ROLES_STRING));

        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(idp.refreshGrants()).hasSize(1);
        assertThat(stored("entra-" + ORDERS)).isNull();
        assertThat(sessions.contains(sessionId())).isTrue();
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.token(null));
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(idp.refreshGrants()).hasSize(2);
    }

    // QA-60-2: the same response, log line, and counter as for every INVALID_AUDIENCE_TOKEN, never 401.
    @ParameterizedTest
    @EnumSource(value = AudienceTokenDefect.class, names = {"OTHER_TENANT", "OTHER_TID"})
    void aTokenOfAnotherTenantIsNotStored(AudienceTokenDefect defect, CapturedOutput output) {
        idp.answerRefreshGrants(ORDERS, RefreshAnswer.token(defect));
        int before = errorLines(output);
        double denied = denied(DenyReason.INVALID_AUDIENCE_TOKEN);

        EntityExchangeResult<byte[]> fetch = browser.fetch(HttpMethod.GET, ORDER);

        assertThat(fetch.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(fetch)).isEqualTo(GatewayAccessDeniedHandlerTest.INTERNAL_ERROR_BODY);
        assertThat(fetch.getResponseHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(denied(DenyReason.INVALID_AUDIENCE_TOKEN)).isEqualTo(denied + 1);
        assertThat(errorLines(output)).isEqualTo(before + 1);
        // OTHER_TENANT has the issuer of another tenant; OTHER_TID has the right issuer and another tid.
        String check = defect == AudienceTokenDefect.OTHER_TENANT ? "iss" : "tid";
        assertThat(lastErrorLine(output)).contains("reason=INVALID_AUDIENCE_TOKEN", "registration=entra-" + ORDERS)
                .endsWith(" check=" + check)
                .doesNotContain("eyJ");

        EntityExchangeResult<byte[]> navigation = browser.navigate(ORDER);

        assertThat(navigation.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(navigation)).isEqualTo(GatewayAccessDeniedHandlerTest.INTERNAL_ERROR_BODY);
        assertThat(navigation.getResponseHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(stored("entra-" + ORDERS)).isNull();
        assertThat(sessions.contains(sessionId())).isTrue();
    }

    @Test
    void anExpiringTokenIsRedeemedAgainWithTheNewRefreshToken() {
        idp.audienceTokenLifetime(Duration.ofSeconds(30));
        browser.fetch(HttpMethod.GET, ORDER);

        browser.fetch(HttpMethod.GET, ORDER);

        List<RefreshGrant> grants = idp.refreshGrants();
        assertThat(grants).hasSize(2);
        assertThat(grants.get(1).form().get("refresh_token")).isEqualTo(grants.get(0).refreshToken());
    }

    @Test
    void aPerAudienceRegistrationStartsNoLogin() {
        int signIns = idp.issued().size();
        TestBrowser anonymous = new TestBrowser(port);

        EntityExchangeResult<byte[]> navigation = anonymous.navigate("/oauth2/authorization/entra-" + ORDERS);
        EntityExchangeResult<byte[]> authenticated = browser.navigate("/oauth2/authorization/entra-" + ORDERS);

        // Spec 024, row 1: no access rule, 404 also without a session. No login starts.
        for (EntityExchangeResult<byte[]> result : List.of(navigation, authenticated)) {
            assertThat(result.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(result.getResponseHeaders().getLocation()).isNull();
            assertThat(body(result)).isEqualTo(SessionAuthenticationEntryPointTest.NOT_FOUND_BODY);
        }
        assertThat(idp.issued()).hasSize(signIns);
        assertThat(idp.refreshGrants()).isEmpty();
    }

    @Test
    void noResponseContainsAToken() {
        browser.fetch(HttpMethod.GET, ORDER);

        String token = stored("entra-" + ORDERS).getAccessToken().getTokenValue();
        assertThat(browser.responses()).noneMatch(response -> response.contains(token)
                || response.contains(idp.refreshGrants().getFirst().refreshToken()));
    }

    private double denied(DenyReason reason) {
        var counter = meters.find(AuthorizationDenials.DENIED_METRIC).tag("reason", reason.name()).counter();
        return counter == null ? 0 : counter.count();
    }

    private static int errorLines(CapturedOutput output) {
        return (int) output.getOut().lines().filter(line -> line.contains(" ERROR ")).count();
    }

    private static String lastErrorLine(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains(" ERROR ")).toList().getLast();
    }

    private double grants(String result) {
        var counter = meters.find(AudienceRefreshTokenAuthorizedClientProvider.GRANTS_METRIC).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private String sessionId() {
        return browser.cookie(TestBrowser.SESSION);
    }

    private OAuth2AuthorizedClient stored(String registrationId) {
        return (OAuth2AuthorizedClient) sessions.attribute(sessionId(), SessionAuthorizedClientRepository.attribute(registrationId));
    }

    private static String body(EntityExchangeResult<byte[]> result) {
        return new String(result.getResponseBody(), StandardCharsets.UTF_8);
    }

    private List<Integer> parallel(TestBrowser client, String... paths) {
        return Flux.fromArray(paths).flatMap(path -> call(client, path)).collectList().block(Duration.ofSeconds(10));
    }

    /** A fetch request with the session cookie of the browser, without the cookie handling of the browser. */
    private Mono<Integer> call(TestBrowser client, String path) {
        return WebClient.create(Loopback.url(port)).get().uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .cookie(TestBrowser.SESSION, client.cookie(TestBrowser.SESSION))
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()));
    }
}
