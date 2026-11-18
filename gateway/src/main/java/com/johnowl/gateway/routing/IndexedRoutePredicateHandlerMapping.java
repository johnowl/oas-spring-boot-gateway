package com.johnowl.gateway.routing;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_PATH_CONTAINER_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_ROUTE_ATTR;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.event.RefreshRoutesResultEvent;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.http.server.PathContainer;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Route lookup in O(number of path segments) instead of O(number of routes) (spec 017).
 *
 * <p>The trie selects one candidate OAS route. Routes without OAS metadata that come before the
 * candidate in route order are checked first; then the real predicate of the candidate decides. So
 * the routing behavior is the same as the linear lookup of Spring Cloud Gateway; only the cost
 * changes. If the candidate predicate is false, the lookup falls back to the linear lookup, counts
 * the fallback, and logs a warning at most once per minute.
 */
public class IndexedRoutePredicateHandlerMapping extends RoutePredicateHandlerMapping
        implements ApplicationListener<RefreshRoutesResultEvent> {

    private static final Logger log = LoggerFactory.getLogger(IndexedRoutePredicateHandlerMapping.class);

    private static final long WARN_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final RouteLocator routeLocator;

    private final String basePath;

    /** The number of the last rebuild that started. */
    private final AtomicLong rebuilds = new AtomicLong();

    /** Replaced as a whole after each route refresh. Requests in progress keep the old index. */
    private final AtomicReference<Installed> installed = new AtomicReference<>(new Installed(0, null));

    private final LongAdder fallbacks = new LongAdder();

    private final AtomicLong lastFallbackWarning = new AtomicLong(System.nanoTime() - WARN_INTERVAL_NANOS);

    /** An index and the number of the rebuild that built it. */
    private record Installed(long generation, RouteIndex index) {
    }

    public IndexedRoutePredicateHandlerMapping(FilteringWebHandler webHandler, RouteLocator routeLocator,
            GlobalCorsProperties globalCorsProperties, Environment environment) {
        this(webHandler, routeLocator, globalCorsProperties, environment, null);
    }

    /**
     * @param basePath {@code spring.webflux.base-path}, or {@code null}. The {@code Path} predicate
     *     adds it before each pattern, so the index does the same.
     */
    public IndexedRoutePredicateHandlerMapping(FilteringWebHandler webHandler, RouteLocator routeLocator,
            GlobalCorsProperties globalCorsProperties, Environment environment, String basePath) {
        super(webHandler, routeLocator, globalCorsProperties, environment);
        this.routeLocator = routeLocator;
        this.basePath = StringUtils.hasText(basePath) ? basePath : null;
    }

    @Override
    public void onApplicationEvent(RefreshRoutesResultEvent event) {
        if (event.isSuccess()) {
            rebuild().subscribe();
        }
    }

    /**
     * Builds a new index from the current routes, off the event loop, and swaps it in. If the build
     * fails, the previous index stays in use. If two rebuilds overlap, the index of the rebuild that
     * started last wins, also when it finishes first.
     */
    Mono<Void> rebuild() {
        return Mono.defer(() -> {
            long generation = rebuilds.incrementAndGet();
            return routeLocator.getRoutes().collectList()
                    .publishOn(Schedulers.boundedElastic())
                    .map(routes -> new Installed(generation, RouteIndex.build(routes, basePath)))
                    .doOnNext(this::install)
                    .doOnError(error ->
                            log.error("Cannot build the route index. The previous index stays in use.", error))
                    .onErrorComplete()
                    .then();
        });
    }

    private void install(Installed built) {
        Installed current = installed.accumulateAndGet(built,
                (previous, next) -> next.generation() > previous.generation() ? next : previous);
        if (current == built) {
            log.info("Route index built with {} route(s)", built.index().size());
        } else {
            log.debug("Route index of rebuild {} is older than the index in use. It is not used.",
                    built.generation());
        }
    }

    /** The linear lookup of Spring Cloud Gateway, for tests and benchmarks that compare both lookups. */
    public Mono<Route> linearLookup(ServerWebExchange exchange) {
        return super.lookupRoute(exchange);
    }

    /** The current index, or {@code null} before the first route load. */
    RouteIndex index() {
        return installed.get().index();
    }

    /** {@code true} after the first index build. */
    public boolean isIndexed() {
        return index() != null;
    }

    /** The number of lookups that used the linear lookup because the candidate predicate was false. */
    long fallbackCount() {
        return fallbacks.sum();
    }

    @Override
    protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
        RouteIndex current = index();
        if (current == null) {
            return super.lookupRoute(exchange);
        }
        RouteIndex.Entry candidate = candidate(current, exchange);
        // Keep the route order: only unindexed routes before the candidate can win over it.
        int candidatePosition = candidate == null ? Integer.MAX_VALUE : candidate.position();
        return Flux.fromIterable(current.unindexed())
                .takeWhile(entry -> entry.position() < candidatePosition)
                .map(RouteIndex.Entry::route)
                .filterWhen(route -> matches(route, exchange))
                .next()
                .switchIfEmpty(Mono.defer(() ->
                        candidate == null ? Mono.empty() : evaluateCandidate(candidate.route(), exchange)))
                .doOnNext(route -> validateRoute(route, exchange));
    }

    /** The trie candidate for the request, or {@code null}. */
    private static RouteIndex.Entry candidate(RouteIndex current, ServerWebExchange exchange) {
        // The same path and the same cache attribute as the Path predicate, so that a context path
        // (for example from X-Forwarded-Prefix) or a base path cannot split the two lookups.
        PathContainer path = (PathContainer) exchange.getAttributes().computeIfAbsent(
                GATEWAY_PREDICATE_PATH_CONTAINER_ATTR,
                key -> PathContainer.parsePath(exchange.getRequest().getURI().getRawPath()));
        List<String> segments = segments(path);
        if (segments == null) {
            return null;
        }
        return current.trie().match(segments, exchange.getRequest().getMethod().name()).orElse(null);
    }

    private Mono<Route> evaluateCandidate(Route candidate, ServerWebExchange exchange) {
        return matches(candidate, exchange).flatMap(matched -> {
            if (matched) {
                return Mono.just(candidate);
            }
            fallbacks.increment();
            warnAboutFallback(candidate, exchange);
            return super.lookupRoute(exchange);
        });
    }

    /** Logs at most one warning per minute, so that requests cannot flood the log. */
    private void warnAboutFallback(Route candidate, ServerWebExchange exchange) {
        long now = System.nanoTime();
        long last = lastFallbackWarning.get();
        if (now - last >= WARN_INTERVAL_NANOS && lastFallbackWarning.compareAndSet(last, now)) {
            log.warn("Route index selected route {} for {}, but its predicate is false. Using the linear lookup."
                            + " Fallbacks since start: {}. The next warning comes after one minute at the earliest.",
                    candidate.getId(), exchange.getRequest().getPath().value(), fallbackCount());
        }
    }

    /** The same predicate call as the linear lookup, with the same exchange attribute. */
    private static Mono<Boolean> matches(Route route, ServerWebExchange exchange) {
        exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, route.getId());
        try {
            return Mono.from(route.getPredicate().apply(exchange));
        } catch (RuntimeException e) {
            log.error("Error applying predicate for route: {}", route.getId(), e);
            return Mono.just(false);
        }
    }

    /**
     * The decoded segments of the path, as {@code PathPattern} compares them. Returns {@code null}
     * for a path that no OAS route matches: a path with a trailing slash ({@code path-pattern-supported}
     * rejects a trailing slash), or a path with two adjacent separators, such as {@code /a//b} or
     * {@code //a} ({@code PathPattern} does not match an empty segment). The security chain uses
     * the same segments (spec 022).
     */
    public static List<String> segments(PathContainer path) {
        List<String> segments = new ArrayList<>();
        List<PathContainer.Element> elements = path.elements();
        PathContainer.Element previous = null;
        for (PathContainer.Element element : elements) {
            if (element instanceof PathContainer.PathSegment segment) {
                segments.add(segment.valueToMatch());
            } else if (previous instanceof PathContainer.Separator) {
                return null;
            }
            previous = element;
        }
        boolean trailingSlash = elements.size() > 1 && elements.getLast() instanceof PathContainer.Separator;
        return trailingSlash ? null : segments;
    }

    @Override
    protected String getSimpleName() {
        return "IndexedRoutePredicateHandlerMapping";
    }
}
