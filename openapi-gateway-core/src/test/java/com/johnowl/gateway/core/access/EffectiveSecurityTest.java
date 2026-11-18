package com.johnowl.gateway.core.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.johnowl.gateway.core.access.EffectiveSecurity.Kind;
import com.johnowl.gateway.core.spec.SpecFile;
import com.johnowl.gateway.core.spec.SpecLoader;
import com.johnowl.gateway.core.spec.SpecSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EffectiveSecurityTest {

    @ParameterizedTest
    @ValueSource(strings = {"3.0.3", "3.1.0"})
    void readsAbsentEmptyAndOverriddenSecurity(String version) {
        SpecFile spec = load(version, """
                security:
                  - entra: []
                paths:
                  /inherited:
                    get:
                      responses: { "200": { description: OK } }
                  /public:
                    get:
                      security: []
                      responses: { "200": { description: OK } }
                  /own:
                    get:
                      security:
                        - other: []
                      responses: { "200": { description: OK } }
                """);

        EffectiveSecurity inherited = EffectiveSecurity.of(spec, "/inherited", "GET");
        assertThat(inherited.kind()).isEqualTo(Kind.REQUIRED);
        assertThat(inherited.pointer()).isEqualTo("/security");

        EffectiveSecurity publicOperation = EffectiveSecurity.of(spec, "/public", "GET");
        assertThat(publicOperation.kind()).isEqualTo(Kind.PUBLIC);
        assertThat(publicOperation.pointer()).isEqualTo("/paths/~1public/get/security");

        EffectiveSecurity own = EffectiveSecurity.of(spec, "/own", "GET");
        assertThat(own.kind()).isEqualTo(Kind.REQUIRED);
        assertThat(own.requirements().get(0).has("other")).isTrue();
        assertThat(own.requirements().get(0).has("entra")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.0.3", "3.1.0"})
    void missingWithoutRootOrWithEmptyRoot(String version) {
        SpecFile noRoot = load(version, """
                paths:
                  /x:
                    get:
                      responses: { "200": { description: OK } }
                """);
        SpecFile emptyRoot = load(version, """
                security: []
                paths:
                  /x:
                    get:
                      responses: { "200": { description: OK } }
                """);

        assertThat(EffectiveSecurity.of(noRoot, "/x", "GET").kind()).isEqualTo(Kind.MISSING);
        assertThat(EffectiveSecurity.of(emptyRoot, "/x", "GET").kind()).isEqualTo(Kind.MISSING);
    }

    private static SpecFile load(String version, String body) {
        return new SpecLoader().load(new SpecSource("test.yaml", """
                openapi: %s
                info: { title: T, version: 1.0.0 }
                components:
                  securitySchemes:
                    entra: { type: openIdConnect, openIdConnectUrl: "https://login.microsoftonline.com/t/v2.0/.well-known/openid-configuration" }
                    other: { type: openIdConnect, openIdConnectUrl: "https://login.microsoftonline.com/t/v2.0/.well-known/openid-configuration" }
                """.formatted(version) + body)).spec().orElseThrow();
    }
}
