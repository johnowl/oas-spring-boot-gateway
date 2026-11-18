package com.johnowl.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayForwardedHeadersFilterTest {

    private final MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a")
            .remoteAddress(new InetSocketAddress("198.51.100.7", 50123)));

    @Test
    void valuesComeFromAnHttpsUrlWithoutAPort() {
        HttpHeaders headers = filter("https://gateway.example.com", new HttpHeaders());

        assertThat(headers.getFirst("X-Forwarded-Proto")).isEqualTo("https");
        assertThat(headers.getFirst("X-Forwarded-Host")).isEqualTo("gateway.example.com");
        assertThat(headers.getFirst("X-Forwarded-Port")).isEqualTo("443");
        assertThat(headers.getFirst("X-Forwarded-For")).isEqualTo("198.51.100.7");
    }

    @Test
    void valuesComeFromAnHttpsUrlWithAnExplicitPort() {
        HttpHeaders headers = filter("https://gateway.example.com:8443", new HttpHeaders());

        assertThat(headers.getFirst("X-Forwarded-Proto")).isEqualTo("https");
        assertThat(headers.getFirst("X-Forwarded-Host")).isEqualTo("gateway.example.com:8443");
        assertThat(headers.getFirst("X-Forwarded-Port")).isEqualTo("8443");
    }

    @Test
    void valuesComeFromALocalHttpUrl() {
        HttpHeaders withPort = filter("http://localhost:8080", new HttpHeaders());
        HttpHeaders withoutPort = filter("http://localhost", new HttpHeaders());

        assertThat(withPort.getFirst("X-Forwarded-Proto")).isEqualTo("http");
        assertThat(withPort.getFirst("X-Forwarded-Host")).isEqualTo("localhost:8080");
        assertThat(withPort.getFirst("X-Forwarded-Port")).isEqualTo("8080");
        assertThat(withoutPort.getFirst("X-Forwarded-Host")).isEqualTo("localhost");
        assertThat(withoutPort.getFirst("X-Forwarded-Port")).isEqualTo("80");
    }

    @Test
    void replacesTheForwardingHeadersOfTheClientAndKeepsTheOthers() {
        HttpHeaders input = new HttpHeaders();
        input.add("Forwarded", "for=203.0.113.9;host=evil.example.com");
        input.add("x-forwarded-for", "203.0.113.9");
        input.add("X-Forwarded-Host", "evil.example.com");
        input.add("X-FORWARDED-PREFIX", "/evil");
        input.add("Accept", "application/json");

        HttpHeaders headers = filter("https://gateway.example.com", input);

        assertThat(headers.headerNames()).containsExactlyInAnyOrder("Accept", "X-Forwarded-For", "X-Forwarded-Proto",
                "X-Forwarded-Host", "X-Forwarded-Port");
        assertThat(headers.get("X-Forwarded-For")).containsExactly("198.51.100.7");
        assertThat(headers.get("X-Forwarded-Host")).containsExactly("gateway.example.com");
    }

    // QA-61-2: the IPv6 zone id names an interface of the gateway host. It does not go to the backend.
    @Test
    void theAddressOfAnIpv6PeerHasNoZoneId() throws UnknownHostException {
        byte[] linkLocal = InetAddress.getByName("fe80::1").getAddress();
        InetSocketAddress scoped = new InetSocketAddress(Inet6Address.getByAddress(null, linkLocal, 5), 50123);

        assertThat(scoped.getAddress().getHostAddress()).isEqualTo("fe80:0:0:0:0:0:0:1%5");
        assertThat(forwardedFor(scoped)).isEqualTo("fe80::1");
        assertThat(forwardedFor(new InetSocketAddress("::1", 50123))).isEqualTo("::1");
        assertThat(forwardedFor(new InetSocketAddress("2001:db8:0:0:1:0:0:1", 50123))).isEqualTo("2001:db8::1:0:0:1");
    }

    @Test
    void theAddressOfAnUnresolvedPeerHasNoZoneId() {
        assertThat(GatewayForwardedHeadersFilter.address(InetSocketAddress.createUnresolved("fe80::1%eth0", 50123)))
                .isEqualTo("fe80::1");
        assertThat(GatewayForwardedHeadersFilter.address(InetSocketAddress.createUnresolved("peer.internal", 50123)))
                .isEqualTo("peer.internal");
    }

    @Test
    void runsLastAmongTheRequestHeaderFilters() {
        GatewayForwardedHeadersFilter filter = new GatewayForwardedHeadersFilter(URI.create("https://gateway.example.com"));

        assertThat(filter.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        assertThat(filter.supports(HttpHeadersFilter.Type.REQUEST)).isTrue();
        assertThat(filter.supports(HttpHeadersFilter.Type.RESPONSE)).isFalse();
    }

    private static String forwardedFor(InetSocketAddress peer) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/a").remoteAddress(peer));
        return new GatewayForwardedHeadersFilter(URI.create("https://gateway.example.com"))
                .filter(new HttpHeaders(), exchange).getFirst("X-Forwarded-For");
    }

    private HttpHeaders filter(String publicBaseUrl, HttpHeaders input) {
        return new GatewayForwardedHeadersFilter(URI.create(publicBaseUrl)).filter(input, exchange);
    }
}
