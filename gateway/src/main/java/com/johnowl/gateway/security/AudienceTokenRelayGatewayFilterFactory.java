package com.johnowl.gateway.security;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.johnowl.gateway.core.access.OperationAccess;
import com.johnowl.gateway.routing.RouteDefinitionMapper;
import java.security.Principal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The route filter {@code AudienceTokenRelay=<audience>} (spec 025, "Filters on each route"): it sends the
 * access token of the route audience as {@code Authorization: Bearer} to the backend. The route conversion
 * adds it to each route with an audience, after {@code RemoveRequestHeader=Authorization}.
 *
 * <p>The token is the {@link AudienceAccessToken} that the authorization manager of spec 024 checked for this
 * request: the filter reads the exchange attribute {@link GatewaySecurityAttributes#AUDIENCE_TOKEN}. So it
 * never calls the authorized client manager or Entra, and it never sends the ID token, the refresh token, or
 * the token of the registration {@code entra}. The {@code TokenRelay} filter of Spring Cloud Gateway is not
 * used: it calls the authorized client manager again, and it forwards the request without a token when it
 * finds none.
 *
 * <p>A public operation of the route gets no {@code Authorization} header. For a non-public operation, the
 * filter needs a token whose {@code aud} is the audience of the route and of the access rule. Without such a
 * token, the request does not reach the backend: the filter answers {@code 500} with the generic body and
 * writes one {@code ERROR} line without the token. A {@code 401} could start a login loop in the
 * single-page application, and a new login would not fix a defect of the gateway.
 */
public final class AudienceTokenRelayGatewayFilterFactory
        extends AbstractGatewayFilterFactory<AudienceTokenRelayGatewayFilterFactory.Config> {

    /** The filter name in a route definition. */
    public static final String NAME = RouteDefinitionMapper.TOKEN_RELAY;

    private static final Logger log = LoggerFactory.getLogger(AudienceTokenRelayGatewayFilterFactory.class);

    public AudienceTokenRelayGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("audience");
    }

    @Override
    public GatewayFilter apply(Config config) {
        String audience = config.getAudience();
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("The filter " + NAME + " needs an audience.");
        }
        return (exchange, chain) -> {
            OperationAccess operation = exchange.getAttribute(GatewaySecurityAttributes.OPERATION_ACCESS);
            if (operation != null && operation.rule().isPublic()) {
                return chain.filter(exchange);
            }
            AudienceAccessToken token = exchange.getAttribute(GatewaySecurityAttributes.AUDIENCE_TOKEN);
            if (operation == null || token == null
                    || !audience.equals(operation.rule().audience()) || !audience.equals(token.audience())) {
                return noToken(exchange, audience);
            }
            return chain.filter(exchange.mutate()
                    .request(request -> request.headers(headers -> headers.setBearerAuth(token.value())))
                    .build());
        };
    }

    private static Mono<Void> noToken(ServerWebExchange exchange, String audience) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("-")
                .flatMap(principal -> {
                    log.error("The gateway has no access token of the audience {} for the route {} and the user {}."
                            + " The gateway answers 500.", audience, route == null ? "-" : route.getId(), principal);
                    return ProblemResponses.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR);
                });
    }

    /** The configuration of one route. */
    public static final class Config {

        private String audience;

        /** The value of {@code x-gateway.audience}: the client id of the backend app. */
        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }
    }
}
