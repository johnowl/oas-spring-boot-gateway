package com.johnowl.gateway.security;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reads the CSRF token only from the header {@code X-XSRF-TOKEN}. The default handler also reads the
 * form field {@code _csrf}, and for that it reads and buffers the request body. A form field or a
 * query parameter has no effect here, and the body is never read.
 */
final class HeaderOnlyCsrfTokenRequestHandler extends ServerCsrfTokenRequestAttributeHandler {

    @Override
    public Mono<String> resolveCsrfTokenValue(ServerWebExchange exchange, CsrfToken csrfToken) {
        return Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst(csrfToken.getHeaderName()));
    }
}
