package com.johnowl.gateway.security;

import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * The {@link WebClient} for the calls of the gateway to Entra ID (spec 023): the JSON Web Key Set of the
 * audience token decoder and the token endpoint. It does not use the {@code WebClient.Builder} of Spring
 * Boot, so no customizer of the application changes it:
 *
 * <ul>
 *   <li>its own Reactor Netty {@link HttpClient}, with a connect timeout and a response timeout;
 *   <li>no redirects: a {@code 3xx} status is a response, and the client never follows it to another host;
 *   <li>no logging of request details: the form parameters contain the refresh token and the headers
 *       contain the client secret;
 *   <li>only the observation registry of the application.
 * </ul>
 */
final class IdentityProviderWebClient {

    private IdentityProviderWebClient() {
    }

    /**
     * @param timeout the connect timeout and the response timeout
     */
    static WebClient create(Duration timeout, ObservationRegistry observations) {
        HttpClient http = HttpClient.create()
                .followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(timeout.toMillis(), Integer.MAX_VALUE))
                .responseTimeout(timeout);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(codecs -> codecs.defaultCodecs().enableLoggingRequestDetails(false))
                .observationRegistry(observations)
                .build();
    }

    /** {@code true} if the error or one of its causes is a connect timeout or a response timeout. */
    static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof io.netty.handler.timeout.TimeoutException
                    || cause instanceof ConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
