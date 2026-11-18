package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.RecordingMockBackend;
import com.johnowl.gateway.security.MockIdentityProvider.AudienceTokenDefect;
import com.johnowl.gateway.security.MockIdentityProvider.RefreshAnswer;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * No log line contains a token or a secret of the login (specs 022 and 023). Each case runs the whole
 * flow: login, redemption, relay (spec 025), a rejected audience token, and logout.
 *
 * <ul>
 *   <li>{@link GatewayDebug}: the level {@code DEBUG} for {@code com.johnowl.gateway}.
 *   <li>{@link RootTraceMemory} and {@link RootTraceRedis}: the level {@code TRACE} for the root logger,
 *       with each session store. The log guard pins the token-bearing loggers to {@code INFO}, and the
 *       log masking replaces the tokens that other loggers write. The test searches all output for the
 *       tokens, the session ids (raw and Base64), the client secret (raw and in the HTTP Basic value), the
 *       Redis password, the authorization code and the PKCE code verifier. It joins the ASCII columns of
 *       hex dumps first, so a value that a dump splits over several lines is found too.
 * </ul>
 */
class TokenLoggingIT {

    private static final String ORDER = "/orders-api/orders/42";

    private static final String PAYMENT = "/payments-api/payments/7";

    /** A JSON Web Token: a header {@code {"...} in base64url, a dot, and a payload. */
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

    /** One line of a Netty hex dump: offset, hex bytes, and the ASCII column. */
    private static final Pattern HEX_DUMP_LINE = Pattern.compile("^\\|[0-9a-f]{8}\\|[0-9a-f ]{49}\\|(.{1,16})\\|$");

    /** The other lines of a Netty hex dump: the borders and the column header. */
    private static final Pattern HEX_DUMP_FRAME = Pattern.compile("^(\\s*\\+-+\\+(-+\\+)*|\\s*\\|  0  1  2 .*\\|)$");

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        if (backend == null) {
            backend = new RecordingMockBackend();
        }
        SessionTestSpecs.registerWithPayments(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
        backend = null;
    }

    @AfterEach
    void reset() {
        idp.resetRefreshGrants();
    }

    // The search of the TRACE cases finds a value that a Netty hex dump splits over several lines.
    @Test
    void theJoinedHexDumpContainsTheSplitValue() {
        String value = "refresh-" + "0123456789abcdef".repeat(3);
        String dump = "DEBUG line before\n" + ByteBufUtil.prettyHexDump(Unpooled.copiedBuffer("token=" + value, StandardCharsets.UTF_8))
                + "\nINFO line after\n";
        assertThat(dump).doesNotContain(value);

        // Netty pads the ASCII column of the last row with spaces.
        assertThat(withoutHexDumpFraming(dump)).matches("DEBUG line before\ntoken=" + value + " *\nINFO line after\n");
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "logging.level.com.johnowl.gateway=DEBUG")
    @ExtendWith(OutputCaptureExtension.class)
    class GatewayDebug {

        @LocalServerPort
        private int port;

        @Test
        void noLogLineOfTheWholeFlowContainsAToken(CapturedOutput output) {
            assertThat(LoggerFactory.getLogger("com.johnowl.gateway.security").isDebugEnabled()).isTrue();

            runFlow(port);

            List<String> tokens = tokens();
            assertThat(tokens).hasSizeGreaterThanOrEqualTo(6);
            assertThat(output.getAll()).as("the flow logs at all").contains("reason=INVALID_AUDIENCE_TOKEN");
            assertThat(output.getAll().lines().toList()).allSatisfy(line -> {
                assertThat(tokens).noneMatch(line::contains);
                // Each token of the flow is a JWT or a refresh token: no part of a JWT either.
                assertThat(line).doesNotContain("eyJ");
            });
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "logging.level.root=TRACE")
    @DirtiesContext
    @ExtendWith(OutputCaptureExtension.class)
    class RootTraceMemory {

        @LocalServerPort
        private int port;

        @AfterAll
        static void restoreTheLevels() {
            TokenLoggingIT.restoreTheLevels();
        }

        @Test
        void noOutputOfTheWholeFlowContainsATokenOrASecret(CapturedOutput output) {
            assertNoSecretInTheOutput(port, output);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"gateway.session.store=redis", "logging.level.root=TRACE"})
    @DirtiesContext
    @ExtendWith(OutputCaptureExtension.class)
    class RootTraceRedis {

        @LocalServerPort
        private int port;

        /**
         * Before the gateway starts: the output of Testcontainers, with the ACL command and the Redis password of
         * the test container, is not output of the gateway.
         */
        @BeforeAll
        static void startRedis() {
            RedisTestContainer.host();
        }

        @AfterAll
        static void restoreTheLevels() {
            TokenLoggingIT.restoreTheLevels();
        }

        @Test
        void noOutputOfTheWholeFlowContainsATokenOrASecret(CapturedOutput output) {
            assertNoSecretInTheOutput(port, output);
        }
    }

    private void assertNoSecretInTheOutput(int port, CapturedOutput output) {
        assertThat(LoggerFactory.getLogger("com.johnowl.gateway").isTraceEnabled()).isTrue();

        Map<String, String> secrets = runFlow(port);
        List<String> tokens = tokens();
        assertThat(tokens).hasSizeGreaterThanOrEqualTo(9);
        for (int i = 0; i < tokens.size(); i++) {
            secrets.put("token " + i, tokens.get(i));
        }

        String all = withoutHexDumpFraming(output.getAll());
        assertThat(all).as("the flow logs at all").contains("reason=INVALID_AUDIENCE_TOKEN");
        assertThat(all).as("the log guard names the pinned loggers").contains(LogGuard.PINNED_WARNING);
        secrets.forEach((name, secret) -> assertThat(firstLineWith(all, secret)).as(name).isNull());
        Matcher jwt = JWT.matcher(all);
        assertThat(jwt.find() ? lineAt(all, jwt.start()) : null).as("a JSON Web Token").isNull();
    }

    /**
     * The flow of all cases. The login steps are explicit, so the test knows the authorization code.
     *
     * @return the secrets of the flow by name, without the tokens of {@link #tokens()}
     */
    private Map<String, String> runFlow(int port) {
        idp.resetRefreshGrants();
        backend.clear();
        TestBrowser browser = new TestBrowser(port);
        Map<String, String> secrets = new LinkedHashMap<>();

        URI authorizationRequest = browser.startLogin(idp);
        secrets.put("pre-login session id", browser.cookie(TestBrowser.SESSION));
        String code = idp.signIn(authorizationRequest, MockIdentityProvider.USER_OID);
        String state = TestBrowser.query(authorizationRequest).getFirst("state");
        assertThat(browser.navigate("/login/oauth2/code/entra?code=" + code + "&state=" + state).getStatus())
                .isEqualTo(HttpStatus.FOUND);
        secrets.put("authorization code", code);
        secrets.put("session id", browser.cookie(TestBrowser.SESSION));

        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(browser.fetch(HttpMethod.GET, ORDER).getStatus()).isEqualTo(HttpStatus.OK);
        // The gateway rejects this token, logs the failed check, and stores the rotated refresh token.
        idp.answerRefreshGrants(SessionTestSpecs.PAYMENTS_AUDIENCE, RefreshAnswer.token(AudienceTokenDefect.VERSION_1));
        assertThat(browser.fetch(HttpMethod.GET, PAYMENT).getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(browser.fetch(HttpMethod.POST, "/logout", browser.cookie(TestBrowser.XSRF)).getStatus())
                .isEqualTo(HttpStatus.FOUND);

        for (String name : List.of("pre-login session id", "session id")) {
            byte[] id = secrets.get(name).getBytes(StandardCharsets.US_ASCII);
            secrets.put(name + " (Base64)", Base64.getEncoder().withoutPadding().encodeToString(id));
            secrets.put(name + " (base64url)", Base64.getUrlEncoder().withoutPadding().encodeToString(id));
        }
        secrets.put("code verifier", idp.issued().getLast().form().get("code_verifier"));
        secrets.put("client secret", MockIdentityProvider.CLIENT_SECRET);
        secrets.put("client secret (HTTP Basic)", Base64.getEncoder().encodeToString(
                (URLEncoder.encode(MockIdentityProvider.CLIENT_ID, StandardCharsets.UTF_8) + ":"
                        + URLEncoder.encode(MockIdentityProvider.CLIENT_SECRET, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8)));
        secrets.put("Redis password", RedisTestContainer.PASSWORD);
        assertThat(secrets.values()).doesNotContainNull();
        return secrets;
    }

    /** The tokens of the login, the rotated refresh tokens, the audience tokens, and the relayed access tokens. */
    private List<String> tokens() {
        MockIdentityProvider.Tokens login = idp.issued().getLast();
        List<String> tokens = new ArrayList<>(List.of(login.idToken(), login.accessToken(), login.refreshToken()));
        idp.refreshGrants().stream().map(MockIdentityProvider.RefreshGrant::refreshToken).filter(Objects::nonNull)
                .forEach(tokens::add);
        tokens.addAll(idp.audienceTokens());
        backend.headers().stream().map(headers -> headers.getFirst(HttpHeaders.AUTHORIZATION)).filter(Objects::nonNull)
                .map(value -> value.substring("Bearer ".length())).forEach(tokens::add);
        return tokens;
    }

    /**
     * The output with the ASCII columns of each Netty hex dump joined to one line, without the borders,
     * so a value that the dump splits is one string again.
     */
    static String withoutHexDumpFraming(String output) {
        StringBuilder joined = new StringBuilder(output.length());
        boolean inDump = false;
        for (String line : output.lines().toList()) {
            Matcher dump = HEX_DUMP_LINE.matcher(line);
            if (dump.matches()) {
                joined.append(dump.group(1));
                inDump = true;
            } else if (HEX_DUMP_FRAME.matcher(line).matches()) {
                inDump = inDump || line.contains("|  0  1  2 ");
            } else {
                if (inDump) {
                    joined.append('\n');
                    inDump = false;
                }
                joined.append(line).append('\n');
            }
        }
        return joined.toString();
    }

    private static String firstLineWith(String output, String value) {
        int index = output.indexOf(value);
        return index < 0 ? null : lineAt(output, index);
    }

    /** The line around an index, at most 400 characters, for the failure message. */
    private static String lineAt(String output, int index) {
        int start = output.lastIndexOf('\n', index) + 1;
        int end = output.indexOf('\n', index);
        String line = output.substring(start, end < 0 ? output.length() : end);
        return line.length() <= 400 ? line : line.substring(Math.max(0, index - start - 200), Math.min(line.length(), index - start + 200));
    }

    /** The other tests of this JVM log at the default levels again. */
    private static void restoreTheLevels() {
        LoggingSystem logging = LoggingSystem.get(TokenLoggingIT.class.getClassLoader());
        logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO);
        LogGuard.TOKEN_LOGGERS.forEach(name -> logging.setLogLevel(name, null));
    }
}
