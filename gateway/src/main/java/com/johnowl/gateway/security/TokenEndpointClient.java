package com.johnowl.gateway.security;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ReactiveHttpInputMessage;
import org.springframework.http.client.reactive.ClientHttpResponse;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.core.endpoint.DefaultMapOAuth2AccessTokenResponseConverter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.web.reactive.function.BodyExtractor;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Sends the refresh token grants of spec 023 to the token endpoint, with a timeout, and maps each
 * failure to one row of "Token endpoint errors". The {@code OAuth2Error} of Spring Security keeps only
 * {@code error}, {@code error_description} and {@code error_uri}, so this client reads the error body
 * itself: the mapping also needs {@code suberror} and {@code error_codes}.
 *
 * <p>Each failure is an {@link AudienceTokenException}. It never contains {@code error_description}.
 *
 * <p>Throttling: after a {@code 429} status or the error {@code temporarily_unavailable}, this client sends
 * no grant until the {@code Retry-After} delay of the response has passed, for all sessions of this
 * gateway instance. The delay is at most {@value #MAX_PAUSE_SECONDS} seconds, and
 * {@value #DEFAULT_PAUSE_SECONDS} seconds without a valid header. A grant in the pause fails with
 * {@code TOKEN_ENDPOINT_THROTTLED} and does not reach Entra.
 */
final class TokenEndpointClient {

    /** The AADSTS code of missing consent. */
    static final long CONSENT_REQUIRED_CODE = 65001;

    /** The longest pause after a throttled response. */
    static final long MAX_PAUSE_SECONDS = 60;

    /** The pause after a throttled response without a valid {@code Retry-After} header. */
    static final long DEFAULT_PAUSE_SECONDS = 5;

    static final String TEMPORARILY_UNAVAILABLE = "temporarily_unavailable";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final WebClientReactiveRefreshTokenTokenResponseClient client = new WebClientReactiveRefreshTokenTokenResponseClient();

    private final DefaultMapOAuth2AccessTokenResponseConverter converter = new DefaultMapOAuth2AccessTokenResponseConverter();

    private final Duration timeout;

    private final Clock clock;

    /** The end of the pause after a throttled response, in epoch milliseconds. */
    private final AtomicLong pausedUntil = new AtomicLong();

    /**
     * @param timeout the maximum time for one call ({@code gateway.security.token-endpoint.timeout})
     * @param webClient the client for the token endpoint; production uses {@link #create}
     */
    TokenEndpointClient(Duration timeout, WebClient webClient, Clock clock) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException("gateway.security.token-endpoint.timeout must be a positive duration, but is "
                    + timeout + ".");
        }
        this.timeout = timeout;
        this.clock = clock;
        client.setWebClient(webClient);
        client.setBodyExtractor(this::extract);
    }

    /** The client of production, with its own {@link WebClient} ({@link IdentityProviderWebClient}). */
    static TokenEndpointClient create(Duration timeout, ObservationRegistry observations, Clock clock) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException("gateway.security.token-endpoint.timeout must be a positive duration, but is "
                    + timeout + ".");
        }
        return new TokenEndpointClient(timeout, IdentityProviderWebClient.create(timeout, observations), clock);
    }

    Duration timeout() {
        return timeout;
    }

    /**
     * Sends one refresh token grant to the token endpoint of the registration of the request.
     *
     * @return the token response, or an {@link AudienceTokenException}
     */
    Mono<OAuth2AccessTokenResponse> send(OAuth2RefreshTokenGrantRequest request) {
        String registrationId = request.getClientRegistration().getRegistrationId();
        return Mono.defer(() -> paused()
                ? Mono.error(new AudienceTokenException(DenyReason.TOKEN_ENDPOINT_THROTTLED, TokenError.withoutResponse(registrationId)))
                : client.getTokenResponse(request)
                        .timeout(timeout)
                        .onErrorMap(e -> failure(registrationId, e)));
    }

    /** {@code true} while the pause after a throttled response lasts. Then no grant reaches Entra. */
    boolean paused() {
        return clock.millis() < pausedUntil.get();
    }

    private Mono<OAuth2AccessTokenResponse> extract(ReactiveHttpInputMessage message, BodyExtractor.Context context) {
        HttpStatusCode status = message instanceof ClientHttpResponse response ? response.getStatusCode() : null;
        if (status == null) {
            return Mono.error(new ErrorResponse(0, Map.of(), null));
        }
        String retryAfter = message.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        Mono<Map<String, Object>> body = BodyExtractors.toMono(JSON).extract(message, context)
                // An error body that is not JSON still gives its status.
                .onErrorResume(e -> !status.is2xxSuccessful(), e -> Mono.empty())
                .defaultIfEmpty(Map.of());
        return body.flatMap(values -> status.is2xxSuccessful()
                ? Mono.fromCallable(() -> converter.convert(values))
                : Mono.error(new ErrorResponse(status.value(), values, retryAfter)));
    }

    private Throwable failure(String registrationId, Throwable e) {
        if (e instanceof ErrorResponse response) {
            List<Long> codes = errorCodes(response.body().get("error_codes"));
            DenyReason reason = reason(response.status(), response.body(), codes);
            if (reason == DenyReason.TOKEN_ENDPOINT_THROTTLED) {
                pause(response.retryAfter());
            }
            return new AudienceTokenException(reason,
                    TokenError.of(registrationId, response.status(), response.body().get("error"), codes));
        }
        DenyReason reason = IdentityProviderWebClient.isTimeout(e) ? DenyReason.TOKEN_ENDPOINT_TIMEOUT : DenyReason.TOKEN_ENDPOINT_ERROR;
        return new AudienceTokenException(reason, TokenError.withoutResponse(registrationId));
    }

    /** Starts or extends the pause of all grants of this instance. */
    private void pause(String retryAfter) {
        long until = clock.millis() + pauseSeconds(retryAfter, clock) * 1000;
        pausedUntil.accumulateAndGet(until, Math::max);
    }

    /**
     * The pause for a {@code Retry-After} header: seconds or an HTTP date, at most
     * {@value #MAX_PAUSE_SECONDS} seconds, and {@value #DEFAULT_PAUSE_SECONDS} seconds without a valid value.
     */
    static long pauseSeconds(String retryAfter, Clock clock) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return DEFAULT_PAUSE_SECONDS;
        }
        long seconds;
        try {
            seconds = Long.parseLong(retryAfter.trim());
        } catch (NumberFormatException notSeconds) {
            try {
                Instant date = ZonedDateTime.parse(retryAfter.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                seconds = Duration.between(clock.instant(), date).toSeconds();
            } catch (DateTimeParseException notDate) {
                return DEFAULT_PAUSE_SECONDS;
            }
        }
        return Math.clamp(seconds, 0, MAX_PAUSE_SECONDS);
    }

    /**
     * The rows of "Token endpoint errors", in their order. Throttling comes first. Rows 1 to 4 apply only
     * to a {@code 4xx} status. The consent row comes first of them, because Entra reports missing consent
     * with {@code invalid_grant} and sometimes with {@code interaction_required}.
     */
    static DenyReason reason(int status, Map<String, Object> body, List<Long> errorCodes) {
        Object error = body.get("error");
        if (status == 429 || (status >= 400 && status < 600 && TEMPORARILY_UNAVAILABLE.equals(error))) {
            return DenyReason.TOKEN_ENDPOINT_THROTTLED;
        }
        if (status < 400 || status >= 500) {
            return DenyReason.TOKEN_ENDPOINT_ERROR;
        }
        if (("invalid_grant".equals(error) && "consent_required".equals(body.get("suberror")))
                || errorCodes.contains(CONSENT_REQUIRED_CODE)) {
            return DenyReason.CONSENT_REQUIRED;
        }
        if ("interaction_required".equals(error)) {
            return DenyReason.REAUTHENTICATION_REQUIRED;
        }
        if ("invalid_grant".equals(error)) {
            return DenyReason.SESSION_INVALID;
        }
        return DenyReason.TOKEN_REQUEST_REJECTED;
    }

    /** The numbers of {@code error_codes}. Other values are ignored. */
    private static List<Long> errorCodes(Object value) {
        List<Long> codes = new ArrayList<>();
        if (value instanceof Collection<?> values) {
            for (Object code : values) {
                if (code instanceof Number number) {
                    codes.add(number.longValue());
                }
            }
        }
        return codes;
    }

    /** An error response of the token endpoint. Only inside this class. */
    private static final class ErrorResponse extends RuntimeException {

        private final int status;

        private final Map<String, Object> body;

        private final String retryAfter;

        ErrorResponse(int status, Map<String, Object> body, String retryAfter) {
            super(null, null, false, false);
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }

        String retryAfter() {
            return retryAfter;
        }

        int status() {
            return status;
        }

        Map<String, Object> body() {
            return body;
        }
    }
}
