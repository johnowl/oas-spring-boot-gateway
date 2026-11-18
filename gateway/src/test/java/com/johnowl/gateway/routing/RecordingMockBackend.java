package com.johnowl.gateway.routing;

import com.johnowl.gateway.Loopback;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Mock backend that records the method, the path, the {@code Cookie} header, and all headers of each
 * request. A path that contains {@code slow} answers after 1 second, without blocking. A request with
 * {@code set-cookie} in the query gets a {@code Set-Cookie} and a {@code Set-Cookie2} header. A request
 * with {@code cors} in the query gets the CORS headers, {@code Clear-Site-Data}, {@code Cache-Control},
 * and {@code Content-Type} of {@link #CORS_RESPONSE_HEADERS}. A request with {@code site-headers} in the query
 * gets the headers of {@link #SITE_RESPONSE_HEADERS}. A request with {@code location=upstream},
 * {@code location=other}, or {@code location=relative} in the query gets a {@code Location} to the backend
 * itself ({@code http://127.0.0.1:<port>/orders/43?page=2}), to another host, or a relative one.
 */
public final class RecordingMockBackend implements AutoCloseable {

    /** The name of the cookie that the backend sets. */
    public static final String BACKEND_COOKIE = "tracking";

    /** The value of {@link #cookieHeaders()} for a request without a {@code Cookie} header. */
    public static final String NO_COOKIE = "(none)";

    /** The response headers for a request with {@code cors} in the query. */
    public static final Map<String, String> CORS_RESPONSE_HEADERS = Map.of(
            "Access-Control-Allow-Origin", "*",
            "Access-Control-Allow-Credentials", "true",
            "Clear-Site-Data", "\"cookies\"",
            "Cache-Control", "no-store",
            "Content-Type", "text/plain;charset=UTF-8");

    /**
     * The response headers for a request with {@code site-headers} in the query: headers that act on the
     * whole site, browser policies, a sign-in challenge, and a cache policy for shared caches.
     */
    public static final Map<String, String> SITE_RESPONSE_HEADERS = Map.ofEntries(
            Map.entry("Strict-Transport-Security", "max-age=0"),
            Map.entry("NEL", "{\"report_to\":\"backend\",\"max_age\":86400}"),
            Map.entry("Report-To", "{\"group\":\"backend\"}"),
            Map.entry("Reporting-Endpoints", "backend=\"https://evil.example.com/r\""),
            Map.entry("Service-Worker-Allowed", "/"),
            Map.entry("Alt-Svc", "h3=\"evil.example.com:443\""),
            Map.entry("Origin-Agent-Cluster", "?0"),
            Map.entry("Accept-CH", "Sec-CH-UA-Model"),
            Map.entry("Critical-CH", "Sec-CH-UA-Model"),
            Map.entry("WWW-Authenticate", "Basic realm=\"orders\""),
            Map.entry("X-Frame-Options", "ALLOWALL"),
            Map.entry("X-Content-Type-Options", "none"),
            Map.entry("Referrer-Policy", "unsafe-url"),
            Map.entry("Cache-Control", "public, max-age=3600"));

    /** The relative {@code Location} for a request with {@code location=relative} in the query. */
    public static final String RELATIVE_LOCATION = "/orders-api/orders/43";

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> cookieHeaders = new CopyOnWriteArrayList<>();
    private final List<HttpHeaders> headers = new CopyOnWriteArrayList<>();
    private final DisposableServer server;

    public RecordingMockBackend() {
        server = HttpServer.create()
                .host(Loopback.ADDRESS)
                .port(0)
                .handle((request, response) -> {
                    requests.add(request.method().name() + " " + request.uri());
                    cookieHeaders.add(String.join("; ", request.requestHeaders().getAll("Cookie")).transform(
                            cookies -> cookies.isEmpty() ? NO_COOKIE : cookies));
                    HttpHeaders received = new HttpHeaders();
                    request.requestHeaders().forEach(header -> received.add(header.getKey(), header.getValue()));
                    headers.add(received);
                    if (request.uri().contains("set-cookie")) {
                        response.addHeader("Set-Cookie", BACKEND_COOKIE + "=1; Path=/")
                                .addHeader("Set-Cookie2", "legacy=1");
                    }
                    if (request.uri().contains("cors")) {
                        CORS_RESPONSE_HEADERS.forEach(response::header);
                    }
                    if (request.uri().contains("site-headers")) {
                        SITE_RESPONSE_HEADERS.forEach(response::header);
                    }
                    if (request.uri().contains("location=upstream")) {
                        response.header("Location", "http://127.0.0.1:" + port() + "/orders/43?page=2");
                    } else if (request.uri().contains("location=other")) {
                        response.header("Location", "https://evil.example.com/orders/43");
                    } else if (request.uri().contains("location=relative")) {
                        response.header("Location", RELATIVE_LOCATION);
                    }
                    Duration delay = request.uri().contains("slow") ? Duration.ofSeconds(1) : Duration.ZERO;
                    return Mono.delay(delay).then(response.sendString(Mono.just("ok")).then());
                })
                .bindNow();
    }

    public int port() {
        return server.port();
    }

    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** The {@code Cookie} header of each request, or {@link #NO_COOKIE}. */
    public List<String> cookieHeaders() {
        return List.copyOf(cookieHeaders);
    }

    /** All headers of each request, in the order of the requests. */
    public List<HttpHeaders> headers() {
        return List.copyOf(headers);
    }

    public void clear() {
        requests.clear();
        cookieHeaders.clear();
        headers.clear();
    }

    @Override
    public void close() {
        server.disposeNow();
    }
}
