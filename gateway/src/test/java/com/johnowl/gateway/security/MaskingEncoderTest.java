package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The masking encoder of spec 022, "Log guard", in a Logback context of its own: a pattern with a stack
 * trace, and the structured logging format {@code ecs} of Spring Boot.
 */
class MaskingEncoderTest {

    private static final String JWT = "eyJraWQiOiJ0ZXN0In0.eyJzdWIiOiJhIn0.c2lnbmF0dXJl";

    private final LoggerContext context = new LoggerContext();

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    MaskingEncoderTest() {
        context.setMDCAdapter(new LogbackMDCAdapter());
    }

    @AfterEach
    void stop() {
        context.stop();
    }

    @Test
    void masksTheMessageAndTheStackTraceOfAPattern() {
        PatternLayoutEncoder pattern = new PatternLayoutEncoder();
        pattern.setPattern("%level %logger - %msg%n%ex");
        Logger logger = logger(pattern);

        assertThat(MaskingEncoder.install(context)).isEmpty();
        logger.info("Sent Authorization: Bearer {}", JWT);
        logger.warn("Failed", new IllegalStateException("refresh_token=refresh-1 rejected",
                new IllegalArgumentException("access_token=" + JWT)));

        String written = output.toString(StandardCharsets.UTF_8);
        assertThat(written).startsWith("INFO test - Sent Authorization: Bearer ***\nWARN test - Failed\n")
                .contains("java.lang.IllegalStateException: refresh_token=*** rejected")
                .contains("Caused by: java.lang.IllegalArgumentException: access_token=***")
                .doesNotContain("refresh-1", "eyJ");
    }

    @Test
    void keepsTheStructuredLoggingFormatValid() {
        context.putObject(Environment.class.getName(), new MockEnvironment().withProperty("spring.application.name", "gateway"));
        StructuredLogEncoder structured = new StructuredLogEncoder();
        structured.setFormat("ecs");
        Logger logger = logger(structured);

        MaskingEncoder.install(context);
        logger.info("Token response {\"access_token\":\"{}\",\"code\":7}", JWT);
        logger.error("Failed", new IllegalStateException("client_secret=test-only-dummy-secret"));

        List<JsonNode> lines = output.toString(StandardCharsets.UTF_8).lines().map(JsonMapper.shared()::readTree).toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("message").asString()).isEqualTo("Token response {\"access_token\":\"***\",\"code\":7}");
        assertThat(lines.get(0).get("service").get("name").asString()).isEqualTo("gateway");
        assertThat(lines.get(1).get("error").get("message").asString()).isEqualTo("client_secret=***");
        assertThat(lines.get(1).get("error").get("stack_trace").asString()).doesNotContain("test-only-dummy-secret");
    }

    @Test
    void coversTheAppendersOfAnAsynchronousAppenderAndInstallsOnce() {
        PatternLayoutEncoder pattern = new PatternLayoutEncoder();
        pattern.setPattern("%msg%n");
        OutputStreamAppender<ILoggingEvent> stream = streamAppender(pattern);
        AsyncAppender async = new AsyncAppender();
        async.setContext(context);
        async.addAppender(stream);
        async.start();
        context.getLogger("test").addAppender(async);

        MaskingEncoder.install(context);
        MaskingEncoder.install(context);

        Encoder<ILoggingEvent> encoder = stream.getEncoder();
        assertThat(encoder).isInstanceOf(MaskingEncoder.class);
        assertThat(((MaskingEncoder) encoder).delegate()).isSameAs(pattern);
    }

    private Logger logger(Encoder<ILoggingEvent> encoder) {
        Logger logger = context.getLogger("test");
        logger.addAppender(streamAppender(encoder));
        return logger;
    }

    private OutputStreamAppender<ILoggingEvent> streamAppender(Encoder<ILoggingEvent> encoder) {
        encoder.setContext(context);
        encoder.start();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setName("memory");
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(output);
        appender.start();
        return appender;
    }
}
