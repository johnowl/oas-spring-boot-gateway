package com.johnowl.gateway.routing;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;

/**
 * Cleans the headers of each backend response (spec 022, spec 025, "Response headers"), before the gateway
 * copies them into the client response:
 *
 * <ul>
 *   <li>It removes the headers that act on the whole gateway site: cookies ({@code Set-Cookie},
 *       {@code Set-Cookie2}, {@code Clear-Site-Data}), CORS ({@code Access-Control-*}), and browser policies
 *       such as {@code Strict-Transport-Security}, {@code NEL}, and {@code Service-Worker-Allowed}. It also
 *       removes {@code WWW-Authenticate}: the gateway, not the backend, decides how the browser signs in.
 *   <li>It sets {@code X-Frame-Options: DENY}, {@code X-Content-Type-Options: nosniff}, and
 *       {@code Referrer-Policy: no-referrer}, also when the backend sends another value.
 *   <li>It rewrites an absolute {@code Location} that points at the upstream of the route to the public URL
 *       of the gateway. It removes an absolute {@code Location} to another host. It keeps a relative
 *       {@code Location}.
 * </ul>
 *
 * <p>The filter changes only the headers of the backend response. The gateway writes its own cookies
 * ({@code __Host-SESSION} and {@code __Host-XSRF-TOKEN}) and its own {@code Strict-Transport-Security} when
 * it commits the response, so the filter does not remove them.
 */
public final class BackendResponseHeadersFilter implements HttpHeadersFilter {

    static final String X_FRAME_OPTIONS = "X-Frame-Options";

    static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    static final String REFERRER_POLICY = "Referrer-Policy";

    /** The removed names, in lowercase. */
    static final Set<String> REMOVED_NAMES = Set.of(
            "set-cookie", "set-cookie2", "clear-site-data",
            "strict-transport-security", "nel", "report-to", "reporting-endpoints", "service-worker-allowed",
            "alt-svc", "origin-agent-cluster", "accept-ch", "critical-ch",
            "www-authenticate");

    /** The headers that the gateway sets with its own value, in lowercase. */
    private static final Set<String> OVERWRITTEN_NAMES = Set.of("x-frame-options", "x-content-type-options",
            "referrer-policy");

    private static final String ACCESS_CONTROL_PREFIX = "access-control-";

    private final String publicBaseUrl;

    private final String basePath;

    /**
     * @param publicBaseUrl the checked value of {@code gateway.public-base-url}, for example
     *     {@code https://gateway.example.com}
     * @param basePath {@code spring.webflux.base-path}, or {@code null}
     */
    public BackendResponseHeadersFilter(URI publicBaseUrl, String basePath) {
        this.publicBaseUrl = publicBaseUrl.toString();
        this.basePath = basePath == null ? "" : basePath;
    }

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        // A copy: the input can be read-only.
        HttpHeaders filtered = new HttpHeaders();
        for (Map.Entry<String, List<String>> header : input.headerSet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (name.equals("location")) {
                List<String> locations = header.getValue().stream()
                        .map(location -> location(location, exchange.getAttribute(GATEWAY_ROUTE_ATTR)))
                        .filter(Objects::nonNull)
                        .toList();
                if (!locations.isEmpty()) {
                    filtered.addAll(HttpHeaders.LOCATION, locations);
                }
            } else if (!removes(name) && !OVERWRITTEN_NAMES.contains(name)) {
                filtered.addAll(header.getKey(), header.getValue());
            }
        }
        filtered.set(X_FRAME_OPTIONS, "DENY");
        filtered.set(X_CONTENT_TYPE_OPTIONS, "nosniff");
        filtered.set(REFERRER_POLICY, "no-referrer");
        return filtered;
    }

    @Override
    public boolean supports(Type type) {
        return type == Type.RESPONSE;
    }

    /** {@code true} if the gateway removes the backend response header with this name. The names are case-insensitive. */
    static boolean removes(String name) {
        String lowerCase = name.toLowerCase(Locale.ROOT);
        return REMOVED_NAMES.contains(lowerCase) || lowerCase.startsWith(ACCESS_CONTROL_PREFIX);
    }

    /**
     * The {@code Location} value for the client, or {@code null} to remove it.
     *
     * <ul>
     *   <li>A relative reference without a host, for example {@code /orders/43}: unchanged.
     *   <li>An absolute reference to the scheme, host and port of the route upstream: the public base URL,
     *       {@code spring.webflux.base-path}, and the public prefix of the route, followed by the path after
     *       the upstream base path, the query and the fragment. A path outside the upstream base path has no
     *       public URL: removed.
     *   <li>Any other reference with a host, also {@code //other.example.com/a}, or a value that is not a URI:
     *       removed.
     * </ul>
     */
    String location(String value, Route route) {
        URI location;
        try {
            location = new URI(value.strip());
        } catch (URISyntaxException e) {
            return null;
        }
        if (location.getRawAuthority() == null && location.getScheme() == null) {
            return value;
        }
        if (route == null || !sameOrigin(location, route.getUri())) {
            return null;
        }
        String upstreamBasePath = (String) route.getMetadata().getOrDefault(RouteDefinitionMapper.UPSTREAM_BASE_PATH, "");
        String path = location.getRawPath() == null || location.getRawPath().isEmpty() ? "/" : location.getRawPath();
        if (!upstreamBasePath.isEmpty() && !upstreamBasePath.equals("/")) {
            if (!path.equals(upstreamBasePath) && !path.startsWith(upstreamBasePath + "/")) {
                return null;
            }
            path = path.substring(upstreamBasePath.length());
        }
        String prefix = basePath + route.getMetadata().getOrDefault(RouteDefinitionMapper.PUBLIC_PREFIX, "");
        if (path.isEmpty() && prefix.isEmpty()) {
            path = "/";
        }
        StringBuilder rewritten = new StringBuilder(publicBaseUrl).append(prefix).append(path);
        if (location.getRawQuery() != null) {
            rewritten.append('?').append(location.getRawQuery());
        }
        if (location.getRawFragment() != null) {
            rewritten.append('#').append(location.getRawFragment());
        }
        return rewritten.toString();
    }

    /** {@code true} if the location has the scheme, the host and the port of the upstream. */
    private static boolean sameOrigin(URI location, URI upstream) {
        if (location.getHost() == null || upstream.getHost() == null || location.getRawUserInfo() != null) {
            return false;
        }
        // A reference such as //host/path has no scheme: it uses the scheme of the upstream.
        String scheme = location.getScheme() == null ? upstream.getScheme() : location.getScheme();
        return scheme.equalsIgnoreCase(upstream.getScheme())
                && location.getHost().equalsIgnoreCase(upstream.getHost())
                && port(location, scheme) == port(upstream, upstream.getScheme());
    }

    private static int port(URI uri, String scheme) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : "http".equalsIgnoreCase(scheme) ? 80 : -1;
    }
}
