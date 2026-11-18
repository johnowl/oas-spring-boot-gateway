package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.routing.OpenApiRoutingConfiguration;
import com.johnowl.gateway.routing.TestAudienceAuthorization;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The gateway does not start with invalid session login settings (spec 022). */
@ExtendWith(OutputCaptureExtension.class)
class SessionLoginStartupTest {

    private static final String WARNING = "Profile 'local' is active. Do not use it in a deployed gateway.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenApiRoutingConfiguration.class, SessionLoginConfiguration.class,
                    TestAudienceAuthorization.class)
            .withPropertyValues(SessionTestSpecs.write(9))
            .withPropertyValues("gateway.public-base-url=https://gateway.example.com");

    @ParameterizedTest
    @ValueSource(strings = {"/?login=failed", "/signed-out"})
    void startsWithValidRedirectPaths(String path) {
        runner.withPropertyValues(
                        "gateway.security.login.failure-path=" + path,
                        "gateway.security.logout.post-logout-redirect-path=" + path)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SessionLoginSettings settings = context.getBean(SessionLoginSettings.class);
                    assertThat(settings.loginFailureUri()).isEqualTo(URI.create("https://gateway.example.com" + path));
                    assertThat(settings.postLogoutRedirectUri())
                            .isEqualTo(URI.create("https://gateway.example.com" + path));
                });
    }

    @Test
    void sessionStoreIsMemoryWithALimitOf100000ByDefault() {
        runner.run(context -> {
            GatewayProperties.Session session = context.getBean(GatewayProperties.class).session();
            assertThat(session.store()).isEqualTo(GatewayProperties.SessionStore.MEMORY);
            assertThat(session.memory().maxSessions()).isEqualTo(100_000);
        });
    }

    @Test
    void doesNotStartWithAnUnknownSessionStore() {
        runner.withPropertyValues("gateway.session.store=disk").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void defaultsMatchTheSpec() {
        runner.run(context -> {
            SessionLoginSettings settings = context.getBean(SessionLoginSettings.class);
            assertThat(settings.loginFailureUri()).hasToString("https://gateway.example.com/?login=failed");
            assertThat(settings.postLogoutRedirectUri()).hasToString("https://gateway.example.com/");
            assertThat(settings.session().preLoginTimeout()).hasMinutes(5);
            assertThat(settings.session().idleTimeout()).hasMinutes(30);
            assertThat(settings.session().maxLifetime()).hasHours(8);
        });
    }

    // The values of the acceptance criterion. "\u0007" is a control character.
    @ParameterizedTest
    @ValueSource(strings = {
        "//evil.example.com/",
        "/\\evil.example.com",
        "/%2F%2Fevil.example.com",
        "https://evil.example.com/",
        "/a#b",
        "/oauth2/authorization/entra",
        "/login",
        "/logout",
        "/a\u0007b",
        "/orders-api/orders/1",
    })
    void doesNotStartWithAnInvalidRedirectPath(String path) {
        runner.withPropertyValues("gateway.security.login.failure-path=" + path).run(context ->
                assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageStartingWith("gateway.security.login.failure-path"));
        runner.withPropertyValues("gateway.security.logout.post-logout-redirect-path=" + path).run(context ->
                assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageStartingWith("gateway.security.logout.post-logout-redirect-path"));
    }

    @Test
    void doesNotStartWithoutPublicBaseUrl() {
        new ApplicationContextRunner()
                .withUserConfiguration(OpenApiRoutingConfiguration.class, SessionLoginConfiguration.class,
                        TestAudienceAuthorization.class)
                .withPropertyValues(SessionTestSpecs.write(9))
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("gateway.public-base-url 'null' is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://gateway.example.com",
        "gateway.example.com",
        "https://gateway.example.com/",
        "https://gateway.example.com/app",
        "https://gateway.example.com?a=1",
        "http://localhost:8080",
    })
    void doesNotStartWithAnInvalidPublicBaseUrl(String value) {
        runner.withPropertyValues("gateway.public-base-url=" + value).run(context ->
                assertThat(context.getStartupFailure()).rootCause().hasMessageStartingWith("gateway.public-base-url"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost.evil.example",
        "http://localhost@evil.example",
        "http://user@localhost:8080",
        "http://evil.example#@localhost",
        "http://127.0.0.1.nip.io",
        "http://LOCALHOST:8080",
    })
    void doesNotStartWithALookalikeLocalHost(String value) {
        runner.withPropertyValues("spring.profiles.active=local", "gateway.public-base-url=" + value).run(context ->
                assertThat(context.getStartupFailure()).rootCause().hasMessageStartingWith("gateway.public-base-url"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080"})
    void startsWithALocalHostAndTheLocalProfile(String value, CapturedOutput output) {
        runner.withPropertyValues("spring.profiles.active=local", "gateway.public-base-url=" + value)
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(output.getOut().lines().filter(line -> line.contains(WARNING)))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("WARN"));
    }

    @Test
    void writesNoWarningWithoutTheLocalProfile(CapturedOutput output) {
        runner.run(context -> assertThat(context).hasNotFailed());

        assertThat(output).doesNotContain(WARNING);
    }

    @Test
    void doesNotStartWithANonPositiveTimeout() {
        runner.withPropertyValues("gateway.security.session.idle-timeout=0s").run(context ->
                assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("gateway.security.session.idle-timeout must be a positive duration"));
    }
}
