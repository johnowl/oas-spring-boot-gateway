package com.johnowl.gateway.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A Logback encoder that masks the encoded line of another encoder with {@link LogMasking} (spec 022, "Log
 * guard"). It masks the whole line: the message, the stack trace, and each structured logging format of
 * Spring Boot, because it works on the bytes that the other encoder writes. The format stays the same.
 */
final class MaskingEncoder extends EncoderBase<ILoggingEvent> {

    private final Encoder<ILoggingEvent> delegate;

    MaskingEncoder(Encoder<ILoggingEvent> delegate) {
        this.delegate = delegate;
        setContext(delegate.getContext());
    }

    /**
     * Puts a masking encoder around the encoder of each appender that writes to an output stream, for example
     * the console and a file, also inside an asynchronous appender. An appender that already masks stays.
     *
     * @return the names of the appenders that do not write to an output stream, so the masking does not cover them
     */
    static List<String> install(LoggerContext context) {
        List<String> notMasked = new ArrayList<>();
        for (Logger logger : context.getLoggerList()) {
            install(logger, notMasked);
        }
        return notMasked;
    }

    private static void install(AppenderAttachable<ILoggingEvent> attachable, List<String> notMasked) {
        for (Iterator<Appender<ILoggingEvent>> appenders = attachable.iteratorForAppenders(); appenders.hasNext();) {
            Appender<ILoggingEvent> appender = appenders.next();
            if (appender instanceof OutputStreamAppender<ILoggingEvent> stream) {
                if (stream.getEncoder() != null && !(stream.getEncoder() instanceof MaskingEncoder)) {
                    stream.setEncoder(new MaskingEncoder(stream.getEncoder()));
                }
            } else if (appender instanceof AppenderAttachable<?>) {
                @SuppressWarnings("unchecked")
                AppenderAttachable<ILoggingEvent> children = (AppenderAttachable<ILoggingEvent>) appender;
                install(children, notMasked);
            } else {
                notMasked.add(appender.getName());
            }
        }
    }

    Encoder<ILoggingEvent> delegate() {
        return delegate;
    }

    @Override
    public byte[] headerBytes() {
        return delegate.headerBytes();
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        return LogMasking.mask(delegate.encode(event));
    }

    @Override
    public byte[] footerBytes() {
        return delegate.footerBytes();
    }

    @Override
    public void start() {
        if (!delegate.isStarted()) {
            delegate.start();
        }
        super.start();
    }

    @Override
    public void stop() {
        delegate.stop();
        super.stop();
    }

    @Override
    public boolean isStarted() {
        return delegate.isStarted();
    }
}
