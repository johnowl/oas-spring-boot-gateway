package com.johnowl.gateway.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The single-flight redemption of a session on one gateway instance (spec 023, "Single-flight
 * redemption"). All audiences of a session share one refresh token, so the key is the session.
 *
 * <ul>
 *   <li>A request for the audience of the running redemption waits for it and gets its result, also an error.
 *   <li>A request for another audience waits until the running redemption completes, and then starts
 *       its own redemption.
 *   <li>A redemption runs to its end also when the request that started it is cancelled: it has its own
 *       subscription. A cancelled request only stops waiting.
 *   <li>The entry of the session is removed when the redemption ends, before the waiting requests get the
 *       result: after success, after an error, and after a timeout.
 * </ul>
 *
 * <p>No request waits on a thread: the waiting requests subscribe to the result.
 */
final class SessionRedemptions {

    private record Flight(String registrationId, Mono<OAuth2AuthorizedClient> result) {
    }

    private final Map<String, Flight> flights = new ConcurrentHashMap<>();

    /**
     * Runs the redemption of a registration for the session, or joins or waits for the running one.
     *
     * @param redemption the redemption; it runs at most once for this call
     */
    Mono<OAuth2AuthorizedClient> redeem(String sessionId, String registrationId,
            Supplier<Mono<OAuth2AuthorizedClient>> redemption) {
        return Mono.defer(() -> {
            Sinks.One<OAuth2AuthorizedClient> sink = Sinks.one();
            Flight own = new Flight(registrationId, sink.asMono());
            Flight running = flights.putIfAbsent(sessionId, own);
            if (running == null) {
                start(sessionId, own, sink, redemption);
                return own.result();
            }
            if (running.registrationId().equals(registrationId)) {
                return running.result();
            }
            return running.result()
                    .onErrorResume(e -> Mono.empty())
                    .then(Mono.defer(() -> redeem(sessionId, registrationId, redemption)));
        });
    }

    /** The number of sessions with a running redemption. For the tests. */
    int running() {
        return flights.size();
    }

    private void start(String sessionId, Flight own, Sinks.One<OAuth2AuthorizedClient> sink,
            Supplier<Mono<OAuth2AuthorizedClient>> redemption) {
        Mono.defer(redemption).subscribe(
                client -> {
                    flights.remove(sessionId, own);
                    sink.tryEmitValue(client);
                },
                error -> {
                    flights.remove(sessionId, own);
                    sink.tryEmitError(error);
                },
                () -> {
                    flights.remove(sessionId, own);
                    sink.tryEmitEmpty();
                });
    }
}
