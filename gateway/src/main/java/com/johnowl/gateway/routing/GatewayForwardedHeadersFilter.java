package com.johnowl.gateway.routing;

import io.netty.util.NetUtil;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;

/**
 * Sets the forwarding headers of the gateway on each request to a backend (spec 025, "Forwarding
 * headers"). The gateway does not support trusted proxies, so it never forwards or appends to a value of
 * the client:
 *
 * <ul>
 *   <li>{@code X-Forwarded-Proto}, {@code X-Forwarded-Host} and {@code X-Forwarded-Port} come from
 *       {@code gateway.public-base-url}, never from the {@code Host} header of the request.
 *   <li>{@code X-Forwarded-For} is the address of the connection peer. It is not the client address when
 *       a load balancer is in front of the gateway.
 *   <li>The filter removes {@code Forwarded} and every other {@code X-Forwarded-*} header, for example
 *       {@code X-Forwarded-Prefix}.
 * </ul>
 *
 * <p>{@link IdentityHeaderRemovalFilter} already removes the client values. This filter runs last among
 * the request header filters, so a header filter of Spring Cloud Gateway cannot add another value.
 */
public final class GatewayForwardedHeadersFilter implements HttpHeadersFilter, Ordered {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";

    static final String X_FORWARDED_HOST = "X-Forwarded-Host";

    static final String X_FORWARDED_PORT = "X-Forwarded-Port";

    private final String proto;

    private final String host;

    private final String port;

    /** @param publicBaseUrl the checked value of {@code gateway.public-base-url}, for example {@code https://gateway.example.com} */
    public GatewayForwardedHeadersFilter(URI publicBaseUrl) {
        proto = publicBaseUrl.getScheme();
        int explicitPort = publicBaseUrl.getPort();
        host = explicitPort == -1 ? publicBaseUrl.getHost() : publicBaseUrl.getHost() + ":" + explicitPort;
        port = Integer.toString(explicitPort != -1 ? explicitPort : "https".equals(proto) ? 443 : 80);
    }

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        HttpHeaders filtered = new HttpHeaders();
        for (Map.Entry<String, List<String>> header : input.headerSet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!name.equals("forwarded") && !name.startsWith("x-forwarded-")) {
                filtered.addAll(header.getKey(), header.getValue());
            }
        }
        InetSocketAddress peer = exchange.getRequest().getRemoteAddress();
        if (peer != null) {
            filtered.set(X_FORWARDED_FOR, address(peer));
        }
        filtered.set(X_FORWARDED_PROTO, proto);
        filtered.set(X_FORWARDED_HOST, host);
        filtered.set(X_FORWARDED_PORT, port);
        return filtered;
    }

    /**
     * The address of the connection peer, without an IPv6 zone id: {@code ::1}, not
     * {@code 0:0:0:0:0:0:0:1}, and {@code fe80::1}, not {@code fe80:0:0:0:0:0:0:1%eth0}. The zone id names a
     * network interface of the gateway host and means nothing to a backend.
     */
    static String address(InetSocketAddress peer) {
        if (peer.getAddress() != null) {
            // The compressed form of RFC 5952, from the address bytes only, so without the zone id.
            return NetUtil.toAddressString(peer.getAddress());
        }
        String host = peer.getHostString();
        int zone = host.indexOf('%');
        return zone == -1 ? host : host.substring(0, zone);
    }

    /** Last: after the filters of Spring Cloud Gateway that remove or add forwarding headers. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
