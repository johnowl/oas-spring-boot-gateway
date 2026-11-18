package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.authorization.AuthenticatedReactiveAuthorizationManager;
import org.springframework.security.web.server.MatcherSecurityWebFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationWebFilter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.WebFilter;

/** SEC-60-1: the production marker ends the startup stop only when the security chains use the manager. */
class GatewayAudienceAuthorizationTest {

    private final GatewayAuthorizationManager manager = new GatewayAuthorizationManager(
            SecurityIndex.build(List.of()), new StubTokenPermissionsResolver(), new AuthorizationDenials(new SimpleMeterRegistry()));

    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();

    private final GatewayAudienceAuthorization marker = new GatewayAudienceAuthorization();

    private final SecurityWebFilterChain session =
            chain(new AuthorizationWebFilter(AuthenticatedReactiveAuthorizationManager.authenticated()));

    private final SecurityWebFilterChain management = chain(new CsrfCookieWebFilter());

    GatewayAudienceAuthorizationTest() {
        marker.setBeanFactory(beans);
    }

    @Test
    void registeredChainsEndTheStartupStop() {
        beans.addBean("sessionSecurityChain", session);
        beans.addBean("managementSecurityChain", management);
        marker.authorizes(session, manager);
        marker.exempts(management);

        assertThatNoException().isThrownBy(marker::afterSingletonsInstantiated);
    }

    @Test
    void sessionChainWithoutAuthorizationFilterStopsTheStartup() {
        assertThatIllegalStateException().isThrownBy(() -> marker.authorizes(chain(new CsrfCookieWebFilter()), manager))
                .withMessage("The session security chain must check each request with the GatewayAuthorizationManager"
                        + " (spec 024).");
    }

    @Test
    void anotherChainStopsTheStartup() {
        beans.addBean("sessionSecurityChain", session);
        beans.addBean("otherChain", chain(new CsrfCookieWebFilter()));
        marker.authorizes(session, manager);

        assertThatIllegalStateException().isThrownBy(marker::afterSingletonsInstantiated)
                .withMessage("The security chain otherChain does not use the GatewayAuthorizationManager (spec 024)."
                        + " The startup stop of spec 024 cannot end.");
    }

    @Test
    void withoutASessionChainTheStartupStops() {
        beans.addBean("managementSecurityChain", management);
        marker.exempts(management);

        assertThatIllegalStateException().isThrownBy(marker::afterSingletonsInstantiated)
                .withMessage("No security chain uses the GatewayAuthorizationManager (spec 024)."
                        + " The startup stop of spec 024 cannot end.");
    }

    /** A chain with the filters. A chain needs at least one filter; {@link CsrfCookieWebFilter} is not an authorization filter. */
    private static SecurityWebFilterChain chain(WebFilter... filters) {
        return new MatcherSecurityWebFilterChain(ServerWebExchangeMatchers.anyExchange(), List.of(filters));
    }
}
