package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.FreePort;
import com.johnowl.gateway.GatewayApplication;
import com.johnowl.gateway.Loopback;
import com.johnowl.gateway.OwnNettyResources;
import com.johnowl.gateway.routing.RecordingMockBackend;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * Sessions in Redis with the default timeouts (spec 022, session store {@code redis}). The gateway
 * uses the Redis ACL user of the spec; the test reads Redis with the default user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "gateway.session.store=redis")
class RedisSessionIT {

    private static final String PROTECTED = "/orders-api/orders/42";

    /** One session attribute for each authorized client (spec 023, "Storage of authorized clients"). */
    private static final String ENTRA_CLIENT = "sessionAttr:" + SessionAuthorizedClientRepository.attribute("entra");

    private static RecordingMockBackend backend;

    private final MockIdentityProvider idp = MockIdentityProviderSession.provider();

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    private TestBrowser browser;

    @DynamicPropertySource
    static void specs(DynamicPropertyRegistry registry) {
        backend = new RecordingMockBackend();
        SessionTestSpecs.register(registry, backend.port());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @BeforeEach
    void emptyRedis() {
        RedisTestContainer.admin().flushall();
        browser = new TestBrowser(port);
    }

    @Test
    void preLoginSessionLivesAtMostFiveMinutes() {
        browser.navigate(PROTECTED);

        String key = sessionKey(browser.cookie(TestBrowser.SESSION));
        assertThat(RedisTestContainer.sessionKeys()).containsExactly(key);
        assertThat(RedisTestContainer.admin().ttl(key)).isBetween(1L, 300L);
    }

    @Test
    void publicOperationsAnd401ResponsesCreateNoSession() {
        assertThat(browser.navigate("/orders-api/health").getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(browser.cookie(TestBrowser.SESSION)).isNull();
        assertThat(RedisTestContainer.sessionKeys()).isEmpty();
    }

    @Test
    void longNavigationIsNotSaved() {
        assertThat(browser.navigate(PROTECTED + "?q=" + "x".repeat(1100)).getStatus()).isEqualTo(HttpStatus.FOUND);

        assertThat(RedisTestContainer.sessionKeys()).isEmpty();
    }

    @Test
    void loginReplacesThePreLoginSessionAndRaisesTheTimeout() {
        browser.navigate(PROTECTED);
        String preLoginKey = sessionKey(browser.cookie(TestBrowser.SESSION));

        browser.login(idp);

        String key = sessionKey(browser.cookie(TestBrowser.SESSION));
        assertThat(RedisTestContainer.sessionKeys()).containsExactly(key).doesNotContain(preLoginKey);
        assertThat(RedisTestContainer.admin().ttl(key)).isBetween(1790L, 1800L);
        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void sessionHoldsTheAuthorizedClientAsJsonWithoutTheClientSecret() {
        browser.login(idp);

        String key = sessionKey(browser.cookie(TestBrowser.SESSION));
        String client = new String(RedisTestContainer.admin().hget(key, ENTRA_CLIENT), StandardCharsets.UTF_8);
        assertThat(client).contains("\"entra\"").contains(idp.issued().getLast().refreshToken());
        for (Map.Entry<String, byte[]> value : RedisTestContainer.admin().hgetall(key).entrySet()) {
            if (value.getValue().length == 0) {
                continue;   // a removed attribute
            }
            String text = new String(value.getValue(), StandardCharsets.UTF_8);
            assertThat(value.getValue()[0]).as("Java serialization in " + value.getKey()).isNotEqualTo((byte) 0xAC);
            assertThat(text).as(value.getKey()).doesNotContain(MockIdentityProvider.CLIENT_SECRET);
        }
    }

    @Test
    void logoutDeletesTheSession() {
        browser.login(idp);
        String key = sessionKey(browser.cookie(TestBrowser.SESSION));

        assertThat(browser.fetch(HttpMethod.POST, "/logout", browser.cookie(TestBrowser.XSRF)).getStatus())
                .isEqualTo(HttpStatus.FOUND);

        assertThat(RedisTestContainer.admin().exists(key)).isZero();
        assertThat(RedisTestContainer.sessionKeys()).isEmpty();
    }

    @Test
    void sessionWithoutLoginTimeIsInvalidated() {
        browser.login(idp);
        String key = sessionKey(browser.cookie(TestBrowser.SESSION));

        RedisTestContainer.admin().hdel(key, "sessionAttr:" + SessionLifetimeWebFilter.LOGIN_TIME_ATTRIBUTE);

        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(RedisTestContainer.admin().exists(key)).isZero();
    }

    @Test
    void valueOfATypeOutsideTheAllowlistInvalidatesTheSession() {
        browser.login(idp);
        String key = sessionKey(browser.cookie(TestBrowser.SESSION));

        RedisTestContainer.admin().hset(key, "sessionAttr:injected",
                "{\"@class\":\"java.net.URL\",\"protocol\":\"http\",\"host\":\"evil.example\",\"port\":-1,\"file\":\"/\"}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(RedisTestContainer.admin().exists(key)).isZero();
    }

    // The ACL user cannot run CONFIG, so a CONFIG command at startup would be in the ACL LOG.
    @Test
    void secondInstanceAcceptsTheSessionAndSendsNoDeniedCommandAtStartup() {
        browser.login(idp);
        RedisTestContainer.admin().aclLogReset();

        try (ConfigurableApplicationContext second =
                new SpringApplicationBuilder(GatewayApplication.class, OwnNettyResources.class,
                        TestLocalIdentityProvider.class).run(
                "--server.port=0",
                "--management.server.port=" + FreePort.next(),
                Loopback.serverAddressArguments()[0],
                Loopback.serverAddressArguments()[1],
                "--gateway.session.store=redis",
                "--gateway.openapi.specs=" + environment.getProperty("gateway.openapi.specs"),
                "--spring.data.redis.host=" + environment.getProperty("spring.data.redis.host"),
                "--spring.data.redis.port=" + environment.getProperty("spring.data.redis.port"),
                "--spring.data.redis.username=" + RedisTestContainer.USER,
                "--spring.data.redis.password=" + RedisTestContainer.PASSWORD)) {
            TestBrowser secondBrowser = new TestBrowser(((WebServerApplicationContext) second).getWebServer().getPort());
            secondBrowser.setCookie(TestBrowser.SESSION, browser.cookie(TestBrowser.SESSION));

            assertThat(secondBrowser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.OK);
        }
        assertThat(RedisTestContainer.admin().aclLog()).as("ACL LOG").isEmpty();
    }

    @Test
    void gatewayUsesOnlyTheAllowedCommands() {
        RedisTestContainer.admin().aclLogReset();

        idp.resetRefreshGrants();
        browser.navigate(PROTECTED);
        browser.login(idp);
        // The authorization manager of spec 024 redeems the token of the audience (spec 023).
        assertThat(browser.fetch(HttpMethod.GET, PROTECTED).getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(idp.refreshGrants()).hasSize(1);
        assertThat(browser.fetch(HttpMethod.POST, "/logout", browser.cookie(TestBrowser.XSRF)).getStatus())
                .isEqualTo(HttpStatus.FOUND);

        assertThat(RedisTestContainer.admin().aclLog()).as("ACL LOG").isEmpty();
        idp.resetRefreshGrants();
    }

    // Spec 024: UndocumentedOperationWebFilter answers before the security chain reads the session.
    @Test
    void undocumentedRequestsSendNoRedisCommand() {
        browser.login(idp);
        TestBrowser withoutSession = new TestBrowser(port);
        RedisTestContainer.admin().configResetstat();

        for (TestBrowser client : List.of(browser, withoutSession)) {
            for (String path : List.of("/admin/secrets", "/login", "/orders-api/orders/42/items", "/actuator/health")) {
                assertThat(client.navigate(path).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(client.fetch(HttpMethod.GET, path).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            }
            assertThat(client.fetch(HttpMethod.PUT, PROTECTED, browser.cookie(TestBrowser.XSRF)).getStatus())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        assertThat(gatewayCommands()).isEmpty();
        // Control: a documented operation reads the session, and the count shows it.
        browser.fetch(HttpMethod.GET, "/orders-api/health");
        browser.fetch(HttpMethod.GET, PROTECTED);
        assertThat(gatewayCommands()).isNotEmpty();
    }

    @Test
    void htmlLogoutWithoutSessionGets404AndStartsNoSession() {
        EntityExchangeResult<byte[]> response = browser.navigate("/logout");

        assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(new String(response.getResponseBodyContent(), StandardCharsets.UTF_8))
                .isEqualTo(SessionAuthenticationEntryPointTest.NOT_FOUND_BODY);
        assertThat(browser.cookie(TestBrowser.SESSION)).isNull();
        assertThat(RedisTestContainer.sessionKeys()).isEmpty();
    }

    /** The commands since the last {@code CONFIG RESETSTAT}, without the commands of the test itself. */
    private static List<String> gatewayCommands() {
        return RedisTestContainer.admin().info("commandstats").lines()
                .filter(line -> line.startsWith("cmdstat_"))
                .map(line -> line.substring("cmdstat_".length(), line.indexOf(':')))
                .filter(command -> !command.startsWith("config") && !command.equals("info"))
                .toList();
    }

    // ACL DRYRUN checks the permission without running the command.
    @ParameterizedTest
    @ValueSource(strings = {"KEYS *", "SCAN 0", "MONITOR", "RANDOMKEY", "SLOWLOG GET", "PSUBSCRIBE *", "SUBSCRIBE c",
        "CONFIG SET maxmemory 1", "FLUSHALL", "FLUSHDB", "REPLICAOF NO ONE", "MODULE LIST", "DEBUG SLEEP 0", "SHUTDOWN"})
    void aclUserCannotRunDangerousCommands(String command) {
        assertThat(RedisTestContainer.dryRun(command.split(" "))).contains("no permissions");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HGETALL spring:session:sessions:a", "EXPIRE spring:session:sessions:a 60",
        "RENAME spring:session:sessions:a spring:session:sessions:b", "DEL spring:session:sessions:a"})
    void aclUserCanRunTheSessionCommandsOnSessionKeysOnly(String command) {
        assertThat(RedisTestContainer.dryRun(command.split(" "))).isEqualTo("OK");
        assertThat(RedisTestContainer.dryRun(command.replace("spring:session:", "other:").split(" ")))
                .contains("no permissions");
    }

    private static String sessionKey(String sessionId) {
        assertThat(sessionId).isNotNull();
        return "spring:session:sessions:" + sessionId;
    }
}
