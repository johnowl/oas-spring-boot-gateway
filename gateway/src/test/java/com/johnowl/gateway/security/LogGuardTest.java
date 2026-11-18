package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

/**
 * The log guard of spec 022: the startup check of the wiretap, and the pinned token loggers. The tests start
 * a small Spring application, so the guard runs as at the startup of the gateway.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogGuardTest {

    private static final String LETTUCE = "io.lettuce.core.protocol";

    private static final String CODEC = "org.springframework.http.codec";

    private final LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());

    @AfterEach
    void restoreTheLevels() {
        logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO);
        LogGuard.TOKEN_LOGGERS.forEach(name -> logging.setLogLevel(name, null));
        logging.setLogLevel(CODEC + ".json", null);
        logging.setLogLevel(CODEC + ".xml", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "--spring.cloud.gateway.server.webflux.httpclient.wiretap=true",
        "--spring.cloud.gateway.server.webflux.httpserver.wiretap=true",
    })
    void aWiretapStopsTheStartup(String argument) {
        String property = argument.substring(2, argument.indexOf('='));

        assertThatThrownBy(() -> start(argument).close())
                .satisfies(failure -> assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(property + " must be false outside the Spring profile 'local'. A wiretap logs the"
                                + " headers and the bodies of the requests, with tokens and secrets (spec 022)."));
    }

    @Test
    void theRelaxedNameOfAnEnvironmentVariableAlsoCounts() {
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of("spring.cloud.gateway.server.webflux.httpclient.wiretap", "TRUE")));

        assertThatThrownBy(() -> LogGuard.checkWiretap(binder)).isInstanceOf(IllegalStateException.class);
        LogGuard.checkWiretap(new Binder(new MapConfigurationPropertySource(
                Map.of("spring.cloud.gateway.server.webflux.httpclient.wiretap", "false"))));
    }

    @Test
    void theProfileLocalAllowsAWiretap() {
        start("--spring.profiles.active=local", "--spring.cloud.gateway.server.webflux.httpclient.wiretap=true",
                "--spring.cloud.gateway.server.webflux.httpserver.wiretap=true").close();
    }

    @Test
    void aRootLevelTraceDoesNotReachTheTokenLoggers(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start("--logging.level.root=TRACE")) {
            assertThat(LoggerFactory.getLogger("com.johnowl.gateway").isTraceEnabled()).isTrue();
            for (String name : LogGuard.TOKEN_LOGGERS) {
                assertThat(LoggerFactory.getLogger(name).isDebugEnabled()).as(name).isFalse();
                assertThat(LoggerFactory.getLogger(name + ".Child").isDebugEnabled()).as(name + ".Child").isFalse();
                assertThat(LoggerFactory.getLogger(name).isInfoEnabled()).as(name).isTrue();
            }
        }
        assertThat(output).containsOnlyOnce(LogGuard.PINNED_WARNING)
                .contains(LogGuard.PINNED_WARNING + ": " + String.join(", ", LogGuard.TOKEN_LOGGERS));
    }

    @Test
    void aTokenLoggerOrAChildWithItsOwnLevelBelowInfoGetsInfo(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start("--logging.level.io.lettuce.core.protocol=DEBUG",
                "--logging.level.org.springframework.http.codec.json=TRACE",
                "--logging.level.org.springframework.http.codec.xml=WARN")) {
            assertThat(logging.getLoggerConfiguration(LETTUCE).getConfiguredLevel()).isEqualTo(LogLevel.INFO);
            assertThat(logging.getLoggerConfiguration(CODEC + ".json").getConfiguredLevel()).isEqualTo(LogLevel.INFO);
            assertThat(logging.getLoggerConfiguration(CODEC + ".xml").getConfiguredLevel()).isEqualTo(LogLevel.WARN);
            assertThat(logging.getLoggerConfiguration(CODEC).getConfiguredLevel()).isNull();
        }
        assertThat(output).contains(LogGuard.PINNED_WARNING + ": " + LETTUCE + ", " + CODEC + ".json. ");
    }

    // A library logger that has not logged yet does not exist in Logback: its parent gives the level.
    @Test
    void aTokenLoggerThatDoesNotExistYetGetsTheLevelOfItsParent() {
        String parent = "test.guard." + System.nanoTime();
        logging.setLogLevel(parent, LogLevel.DEBUG);
        try {
            assertThat(LogGuard.pin(logging, List.of(parent + ".protocol"))).containsExactly(parent + ".protocol");

            assertThat(LoggerFactory.getLogger(parent + ".protocol.CommandEncoder").isDebugEnabled()).isFalse();
            assertThat(LoggerFactory.getLogger(parent + ".other").isDebugEnabled()).isTrue();
        } finally {
            logging.setLogLevel(parent, null);
            logging.setLogLevel(parent + ".protocol", null);
        }
    }

    @Test
    void withoutALevelBelowInfoTheGuardPinsNothing(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start("--logging.level.io.lettuce.core.protocol=WARN")) {
            assertThat(logging.getLoggerConfiguration(LETTUCE).getConfiguredLevel()).isEqualTo(LogLevel.WARN);
        }
        assertThat(output).doesNotContain(LogGuard.PINNED_WARNING);
    }

    @Test
    void theProfileLocalKeepsTheLevels(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=local", "--logging.level.root=TRACE",
                "--logging.level.io.lettuce.core.protocol=DEBUG")) {
            assertThat(LoggerFactory.getLogger(LETTUCE).isDebugEnabled()).isTrue();
            assertThat(LoggerFactory.getLogger(CODEC).isTraceEnabled()).isTrue();
        }
        assertThat(output).doesNotContain(LogGuard.PINNED_WARNING);
    }

    @Test
    void theMaskingCoversTheConsole(CapturedOutput output) {
        try (ConfigurableApplicationContext context = start()) {
            LoggerFactory.getLogger(LogGuardTest.class).info("Sent refresh_token=refresh-secret-1");
        }

        assertThat(output).contains("Sent refresh_token=***").doesNotContain("refresh-secret-1");
    }

    private static ConfigurableApplicationContext start(String... arguments) {
        return new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE).run(arguments);
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
