package com.johnowl.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.johnowl.gateway.core.access.AccessRule;
import com.johnowl.gateway.core.access.OperationAccess;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@ExtendWith(OutputCaptureExtension.class)
class AudienceTokenRelayGatewayFilterFactoryTest {

    private static final String ORDERS = SessionTestSpecs.AUDIENCE;

    private static final String PAYMENTS = SessionTestSpecs.PAYMENTS_AUDIENCE;

    private static final AccessRule PROTECTED = new AccessRule(false, ORDERS, Set.of("Orders.Read"), Set.of());

    private final AudienceTokenRelayGatewayFilterFactory factory = new AudienceTokenRelayGatewayFilterFactory();

    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    @Test
    void relaysTheCheckedTokenOfTheAudience() {
        MockServerWebExchange exchange = exchange(PROTECTED, new AudienceAccessToken(ORDERS, "orders-token"));

        filter(relay(ORDERS), exchange);

        assertThat(forwarded.get().getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION))
                .containsExactly("Bearer orders-token");
    }

    @Test
    void sendsNoTokenForAPublicOperation() {
        MockServerWebExchange exchange = exchange(AccessRule.publicAccess(), new AudienceAccessToken(ORDERS, "orders-token"));

        filter(relay(ORDERS), exchange);

        // The route filter RemoveRequestHeader=Authorization runs before: the relay adds nothing.
        assertThat(forwarded.get()).isSameAs(exchange);
    }

    @Test
    void withoutATokenTheRequestDoesNotReachTheBackend(CapturedOutput output) {
        MockServerWebExchange exchange = exchange(PROTECTED, null);

        filter(relay(ORDERS), exchange);

        assertInternalError(exchange);
        // QA-62-3: all ERROR lines. The exchange has no route and no principal, so both are "-".
        assertThat(output.getOut().lines().filter(line -> line.contains(" ERROR ")))
                .singleElement().asString()
                .contains("no access token of the audience " + ORDERS + " for the route - and the user -.");
    }

    // A token of another audience, or a rule of another audience, is a defect: never relay it.
    @Test
    void aTokenOrARuleOfAnotherAudienceGives500() {
        MockServerWebExchange otherToken = exchange(PROTECTED, new AudienceAccessToken(PAYMENTS, "payments-token"));
        MockServerWebExchange otherRule = exchange(new AccessRule(false, PAYMENTS, Set.of(), Set.of()),
                new AudienceAccessToken(PAYMENTS, "payments-token"));

        filter(relay(ORDERS), otherToken);
        filter(relay(ORDERS), otherRule);

        assertInternalError(otherToken);
        assertInternalError(otherRule);
    }

    @Test
    void withoutAnAccessRuleTheRequestDoesNotReachTheBackend() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders-api/orders/42"));
        exchange.getAttributes().put(GatewaySecurityAttributes.AUDIENCE_TOKEN, new AudienceAccessToken(ORDERS, "orders-token"));

        filter(relay(ORDERS), exchange);

        assertInternalError(exchange);
    }

    @Test
    void theShortcutArgumentIsTheAudience() {
        AudienceTokenRelayGatewayFilterFactory.Config config = new ConfigurationService(new DefaultListableBeanFactory(),
                DefaultConversionService::new, () -> null)
                .with(factory).name("test").properties(Map.of("audience", ORDERS)).bind();

        assertThat(factory.name()).isEqualTo("AudienceTokenRelay");
        assertThat(factory.shortcutFieldOrder()).containsExactly("audience");
        assertThat(config.getAudience()).isEqualTo(ORDERS);
    }

    @Test
    void needsAnAudience() {
        assertThatThrownBy(() -> relay(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTokenIsNotInItsTextForm() {
        assertThat(new AudienceAccessToken(ORDERS, "secret-token")).hasToString("AudienceAccessToken[audience=" + ORDERS + "]");
    }

    private GatewayFilter relay(String audience) {
        AudienceTokenRelayGatewayFilterFactory.Config config = new AudienceTokenRelayGatewayFilterFactory.Config();
        config.setAudience(audience);
        return factory.apply(config);
    }

    private void filter(GatewayFilter filter, ServerWebExchange exchange) {
        forwarded.set(null);
        filter.filter(exchange, next -> {
            forwarded.set(next);
            return Mono.empty();
        }).block();
    }

    private void assertInternalError(MockServerWebExchange exchange) {
        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(GatewayAccessDeniedHandlerTest.INTERNAL_ERROR_BODY);
    }

    private static MockServerWebExchange exchange(AccessRule rule, AudienceAccessToken token) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders-api/orders/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer forged"));
        exchange.getAttributes().put(GatewaySecurityAttributes.OPERATION_ACCESS,
                new OperationAccess("orders:/orders/{id}", "/orders-api/orders/{id}", "GET", rule));
        if (token != null) {
            exchange.getAttributes().put(GatewaySecurityAttributes.AUDIENCE_TOKEN, token);
        }
        return exchange;
    }
}
