package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.johnowl.gateway.core.validation.Violation;
import org.junit.jupiter.api.Test;

/** servers-https-only (spec 030). */
class ServersHttpsOnlyRuleTest {

    private static final String SPEC = """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            servers:
            %s
            paths: {}
            """;

    @Test
    void reportsHttpAtTheUrlField() {
        assertThat(RuleRunner.run(new ServersHttpsOnlyRule(), SPEC.formatted("""
                  - url: http://api.example.com
                  - url: https://api.example.com
                  - url: /api
                """))).extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple("/servers/0/url", "Server URL 'http://api.example.com' does not use https."
                        + " Clients can send credentials without TLS."));
    }

    @Test
    void replacesVariablesWithTheirDefault() {
        assertThat(RuleRunner.run(new ServersHttpsOnlyRule(), SPEC.formatted("""
                  - url: "{scheme}://api.example.com"
                    variables: { scheme: { default: http, enum: [http, https] } }
                """))).extracting(Violation::pointer, Violation::message)
                .containsExactly(tuple("/servers/0/url", "Server URL '{scheme}://api.example.com' (with default"
                        + " values: 'http://api.example.com') does not use https. Clients can send credentials"
                        + " without TLS."));
    }

    @Test
    void reportsUrlsThatAParserReadsAsHttp() {
        assertThat(RuleRunner.run(new ServersHttpsOnlyRule(), SPEC.formatted("""
                  - url: " http://api.example.com"
                  - url: "ht\\ttp://api.example.com"
                  - url: "\\u0001http://api.example.com"
                """))).extracting(Violation::pointer, Violation::message)
                .containsExactly(
                        tuple("/servers/0/url", "Server URL ' http://api.example.com' does not use https."
                                + " Clients can send credentials without TLS."),
                        tuple("/servers/1/url", "Server URL 'ht\\u0009tp://api.example.com' does not use https."
                                + " Clients can send credentials without TLS."),
                        tuple("/servers/2/url", "Server URL '\\u0001http://api.example.com' does not use https."
                                + " Clients can send credentials without TLS."));
    }

    @Test
    void reportsControlCharactersAndOuterWhitespaceInHttpsAndRelativeUrls() {
        assertThat(RuleRunner.run(new ServersHttpsOnlyRule(), SPEC.formatted("""
                  - url: "https://api.example.com "
                  - url: "https://api.example.com/\\nv1"
                  - url: " /api"
                  - url: "{host}/api"
                    variables: { host: { default: "\\r" } }
                  - url: //api.example.com
                """))).extracting(Violation::pointer, Violation::message)
                .containsExactly(
                        tuple("/servers/0/url", "Server URL 'https://api.example.com ' has a control character,"
                                + " or whitespace at the start or the end. Clients can read it as another URL."),
                        tuple("/servers/1/url", "Server URL 'https://api.example.com/\\u000Av1' has a control"
                                + " character, or whitespace at the start or the end. Clients can read it as another"
                                + " URL."),
                        tuple("/servers/2/url", "Server URL ' /api' has a control character, or whitespace at the"
                                + " start or the end. Clients can read it as another URL."),
                        tuple("/servers/3/url", "Server URL '{host}/api' (with default values: '\\u000D/api') has"
                                + " a control character, or whitespace at the start or the end. Clients can read it"
                                + " as another URL."));
    }

    /**
     * No path references {@code components/pathItems/Orders}: the loader rejects every path item {@code $ref}
     * (spec 004, findings SEC-66-1 and SEC-67-1). The rule still checks the component.
     */
    @Test
    void checksEveryServerObjectOfTheDocument() {
        String spec = """
                openapi: 3.1.0
                info: { title: Orders, version: 1.0.0 }
                paths:
                  /payments:
                    post:
                      operationId: createPayment
                      callbacks:
                        paid:
                          "{$request.body#/callbackUrl}":
                            servers: [{ url: http://callback.example.com }]
                            post:
                              servers: [{ url: http://deep.example.com }]
                              responses: { "200": { description: OK } }
                      responses:
                        "201":
                          description: Created.
                          links:
                            payment/self:
                              operationId: createPayment
                              server: { url: http://link.example.com }
                webhooks:
                  orderCreated:
                    post:
                      servers: [{ url: http://webhook.example.com }]
                      responses: { "200": { description: OK } }
                components:
                  pathItems:
                    Orders:
                      servers: [{ url: http://orders.example.com }]
                      get:
                        operationId: listOrders
                        responses: { "200": { description: OK } }
                  links:
                    Self:
                      operationId: listOrders
                      server: { url: http://self.example.com }
                  callbacks:
                    Done:
                      "{$request.body#/doneUrl}":
                        post:
                          servers: [{ url: http://done.example.com }]
                          responses: { "200": { description: OK } }
                """;

        assertThat(RuleRunner.run(new ServersHttpsOnlyRule(), spec)).extracting(Violation::pointer)
                .containsExactlyInAnyOrder(
                        "/paths/~1payments/post/callbacks/paid/{$request.body#~1callbackUrl}/servers/0/url",
                        "/paths/~1payments/post/callbacks/paid/{$request.body#~1callbackUrl}/post/servers/0/url",
                        "/paths/~1payments/post/responses/201/links/payment~1self/server/url",
                        "/webhooks/orderCreated/post/servers/0/url",
                        "/components/pathItems/Orders/servers/0/url",
                        "/components/links/Self/server/url",
                        "/components/callbacks/Done/{$request.body#~1doneUrl}/post/servers/0/url");
    }

    @Test
    void schemeTextRemovesTabsAndNewlinesAndOuterControlCharacters() {
        assertThat(ServersHttpsOnlyRule.schemeText(" \u0001h\tt\rt\np://a \u0000")).isEqualTo("http://a");
    }

    @Test
    void expandKeepsVariablesWithoutDefault() throws Exception {
        var variables = new ObjectMapper().readTree("""
                { "host": { "default": "api.example.com" }, "port": { "enum": ["8443"] } }
                """);

        assertThat(ServersHttpsOnlyRule.expand("https://{host}:{port}/{base}", variables))
                .isEqualTo("https://api.example.com:{port}/{base}");
    }
}
