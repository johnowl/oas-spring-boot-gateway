package com.johnowl.gateway;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Mock backend for upstream tests. It answers {@code 200 ok} after the delay in
 * milliseconds that the query parameter {@code delay} gives. The delay does not block.
 */
final class DelayingMockBackend implements AutoCloseable {

    private final AtomicInteger received;
    private final DisposableServer server;

    private DelayingMockBackend(DisposableServer server, AtomicInteger received) {
        this.server = server;
        this.received = received;
    }

    static DelayingMockBackend start() {
        AtomicInteger received = new AtomicInteger();
        DisposableServer server = HttpServer.create()
                .host(Loopback.ADDRESS)
                .port(0)
                .handle((request, response) -> {
                    received.incrementAndGet();
                    String delay = UriComponentsBuilder.fromUriString(request.uri())
                            .build()
                            .getQueryParams()
                            .getFirst("delay");
                    Duration wait = Duration.ofMillis(delay == null ? 0 : Long.parseLong(delay));
                    return Mono.delay(wait).then(response.sendString(Mono.just("ok")).then());
                })
                .bindNow();
        return new DelayingMockBackend(server, received);
    }

    /** The number of requests that the backend has received. */
    int received() {
        return received.get();
    }

    int port() {
        return server.port();
    }

    @Override
    public void close() {
        server.disposeNow();
    }
}
