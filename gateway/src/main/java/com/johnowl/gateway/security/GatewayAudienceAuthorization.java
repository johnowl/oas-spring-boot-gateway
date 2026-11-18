package com.johnowl.gateway.security;

import com.johnowl.gateway.routing.AudienceAuthorizationActive;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationWebFilter;

/**
 * The production marker of spec 024: the {@link GatewayAuthorizationManager} checks the token {@code aud}
 * and the permissions of each protected operation, so the startup stop does not apply. A real class of
 * the gateway, not a proxy. {@link AuthorizationConfiguration} registers it.
 *
 * <p>The route locator needs the marker before the security chains exist. So the marker checks the chains
 * after all singletons exist (finding SEC-60-1): the method that builds each chain registers it here. The
 * session chain registers itself with the manager that it uses, and the management chain registers itself
 * as exempt. The startup stops if a {@link SecurityWebFilterChain} bean is not registered, or if the
 * session chain has no {@link AuthorizationWebFilter}.
 */
final class GatewayAudienceAuthorization implements AudienceAuthorizationActive, SmartInitializingSingleton,
        BeanFactoryAware {

    private final Set<SecurityWebFilterChain> authorizedChains = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Set<SecurityWebFilterChain> exemptChains = Collections.newSetFromMap(new IdentityHashMap<>());

    private ListableBeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = (ListableBeanFactory) beanFactory;
    }

    /**
     * Registers a chain whose {@code authorizeExchange} uses the manager. Called by the method that builds the
     * chain, once, at startup.
     *
     * @throws IllegalStateException if the chain has no {@link AuthorizationWebFilter}
     */
    void authorizes(SecurityWebFilterChain chain, GatewayAuthorizationManager manager) {
        // The filters of a built chain are in memory, so the call does not wait.
        Boolean authorizes = chain.getWebFilters().any(AuthorizationWebFilter.class::isInstance).block();
        if (manager == null || !Boolean.TRUE.equals(authorizes)) {
            throw new IllegalStateException("The session security chain must check each request with the"
                    + " GatewayAuthorizationManager (spec 024).");
        }
        authorizedChains.add(chain);
    }

    /** Registers the chain of the management context, which has no access rules (spec 024, "Management port"). */
    void exempts(SecurityWebFilterChain chain) {
        exemptChains.add(chain);
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, SecurityWebFilterChain> chains = beanFactory.getBeansOfType(SecurityWebFilterChain.class);
        for (Map.Entry<String, SecurityWebFilterChain> chain : chains.entrySet()) {
            if (!authorizedChains.contains(chain.getValue()) && !exemptChains.contains(chain.getValue())) {
                throw new IllegalStateException("The security chain " + chain.getKey() + " does not use the"
                        + " GatewayAuthorizationManager (spec 024). The startup stop of spec 024 cannot end.");
            }
        }
        if (authorizedChains.isEmpty()) {
            throw new IllegalStateException("No security chain uses the GatewayAuthorizationManager (spec 024)."
                    + " The startup stop of spec 024 cannot end.");
        }
    }
}
