package com.johnowl.gateway.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * The log guard of spec 022: no log line contains a token or a secret, also with the level {@code DEBUG}
 * or {@code TRACE}. It runs at startup, right after Spring Boot sets the log levels, before the gateway
 * opens a connection.
 *
 * <ol>
 *   <li>Outside the Spring profile {@code local}, the startup stops when a wiretap of the HTTP client or of
 *       the HTTP server is on. A wiretap logs each header and each body.
 *   <li>Outside the Spring profile {@code local}, each logger of {@link #TOKEN_LOGGERS} with the effective
 *       level {@code DEBUG} or {@code TRACE}, set on the logger or inherited from a parent or the root, gets
 *       the level {@code INFO}. A child logger with its own level below {@code INFO} gets {@code INFO} too.
 *       One warning names the pinned loggers.
 *   <li>Always, also with the profile {@code local}: the masking of {@link MaskingEncoder} covers each
 *       appender of Logback that writes to an output stream.
 * </ol>
 */
final class LogGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    /**
     * The loggers that can write a token or a secret at the level {@code DEBUG} or {@code TRACE}, with their
     * child loggers.
     */
    static final List<String> TOKEN_LOGGERS = List.of(
            // The request headers to the backend, with the relayed Authorization header (DEBUG).
            "org.springframework.cloud.gateway.filter.headers.observation",
            // The decoded and encoded JSON and form values, for example the token response (TRACE).
            "org.springframework.http.codec",
            // The same lines when the logger of the codec is off: Spring writes them to this logger then (TRACE).
            // Found by TokenLoggingIT.
            "org.springframework.web.HttpLogging",
            // The token requests of the gateway; with spring.codec.log-request-details also their headers.
            "org.springframework.web.reactive.function.client",
            // Each request URI, with the authorization code of the login callback (DEBUG).
            "org.springframework.web.server.adapter.HttpWebHandlerAdapter",
            // The parsed URIs, with the authorization code (TRACE). RfcUriParser writes to a hidden logger with
            // the prefix "_". Found by TokenLoggingIT.
            "org.springframework.web.util",
            "_org.springframework.web.util",
            // The login, the token responses and their errors.
            "org.springframework.security.oauth2",
            // Each Redis command and reply: the session, its tokens, and the AUTH password (TRACE).
            "io.lettuce.core.protocol",
            // The connections and the wiretap of the HTTP client and the HTTP server.
            "reactor.netty.http.client",
            "reactor.netty.http.server");

    /** The start of the warning that names the pinned loggers. */
    static final String PINNED_WARNING = "The log guard set the level INFO for loggers that can write tokens or secrets";

    static final List<String> WIRETAP_PROPERTIES = List.of(
            "spring.cloud.gateway.server.webflux.httpclient.wiretap",
            "spring.cloud.gateway.server.webflux.httpserver.wiretap");

    static final String LOCAL_PROFILE = "local";

    private static final String LOGBACK = "ch.qos.logback.classic.LoggerContext";

    private static final Logger log = LoggerFactory.getLogger(LogGuard.class);

    @Override
    public int getOrder() {
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        boolean local = environment.acceptsProfiles(Profiles.of(LOCAL_PROFILE));
        if (!local) {
            checkWiretap(Binder.get(environment));
        }
        if (!local) {
            pin(LoggingSystem.get(event.getSpringApplication().getClassLoader()));
        }
        mask();
    }

    /** Stops the startup when a wiretap is on. */
    static void checkWiretap(Binder binder) {
        for (String property : WIRETAP_PROPERTIES) {
            if (binder.bind(property, Boolean.class).orElse(false)) {
                throw new IllegalStateException(property + " must be false outside the Spring profile 'local'. A"
                        + " wiretap logs the headers and the bodies of the requests, with tokens and secrets (spec 022).");
            }
        }
    }

    /**
     * Sets the level {@code INFO} for each token logger below {@code INFO}, and for each of its child loggers
     * with its own level below {@code INFO}.
     *
     * @return the pinned loggers
     */
    static Set<String> pin(LoggingSystem logging) {
        return pin(logging, TOKEN_LOGGERS);
    }

    /** As {@link #pin(LoggingSystem)}, for other loggers. For the tests. */
    static Set<String> pin(LoggingSystem logging, List<String> tokenLoggers) {
        Set<String> pinned = new LinkedHashSet<>();
        for (String name : tokenLoggers) {
            if (belowInfo(effectiveLevel(logging, name))) {
                pinned.add(name);
            }
        }
        for (LoggerConfiguration configuration : logging.getLoggerConfigurations()) {
            if (isChild(configuration.getName(), tokenLoggers) && belowInfo(configuration.getConfiguredLevel())) {
                pinned.add(configuration.getName());
            }
        }
        pinned.forEach(name -> logging.setLogLevel(name, LogLevel.INFO));
        if (!pinned.isEmpty()) {
            log.warn("{}: {}. Use the Spring profile '{}' to debug them on a developer machine (spec 022).",
                    PINNED_WARNING, String.join(", ", pinned), LOCAL_PROFILE);
        }
        return pinned;
    }

    /** Installs the masking in Logback. Another logging system gets a warning: it logs without the masking. */
    private static void mask() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!factory.getClass().getName().equals(LOGBACK)) {
            log.warn("The log masking needs Logback, but the logging system is {}. Log lines are not masked (spec 022).",
                    factory.getClass().getName());
            return;
        }
        List<String> notMasked = MaskingEncoder.install((ch.qos.logback.classic.LoggerContext) factory);
        if (!notMasked.isEmpty()) {
            log.warn("The log masking does not cover these appenders, because they do not write to an output stream: {}"
                    + " (spec 022).", String.join(", ", notMasked));
        }
    }

    /**
     * The effective level of a logger. A logger that does not exist yet, for example of a library that has not
     * logged yet, gets the level of its nearest parent.
     */
    private static LogLevel effectiveLevel(LoggingSystem logging, String name) {
        for (String current = name; current != null; current = parent(current)) {
            LoggerConfiguration configuration = logging.getLoggerConfiguration(current);
            if (configuration != null) {
                return configuration.getEffectiveLevel();
            }
        }
        LoggerConfiguration root = logging.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME);
        return root == null ? null : root.getEffectiveLevel();
    }

    private static String parent(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : name.substring(0, dot);
    }

    private static boolean belowInfo(LogLevel level) {
        return level != null && level.ordinal() < LogLevel.INFO.ordinal();
    }

    private static boolean isChild(String name, List<String> tokenLoggers) {
        return tokenLoggers.stream().anyMatch(parent -> name.startsWith(parent + "."));
    }
}
