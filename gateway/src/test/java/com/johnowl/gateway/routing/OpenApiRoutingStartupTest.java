package com.johnowl.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.rules.BuiltInRules;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Reporter;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.RuleMeta;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.SpecContext;
import com.johnowl.gateway.core.validation.SpecRule;
import com.johnowl.gateway.core.validation.Violation;
import com.johnowl.gatewayextra.OutsideAudienceAuthorization;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Startup with valid, invalid and missing OAS files. */
class OpenApiRoutingStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenApiRoutingConfiguration.class);

    @TempDir
    Path directory;

    @Test
    void defaultLocationLoadsTheClasspathFiles() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OpenApiRouteDefinitionLocator.class).getRouteDefinitions().collectList().block())
                    .singleElement()
                    .satisfies(route -> assertThat(route.getId()).isEqualTo("sample:/items"));
        });
    }

    @Test
    void defaultLocationAlsoLoadsYmlAndJsonFiles() throws IOException {
        Path openapi = Files.createDirectories(directory.resolve("openapi"));
        Files.writeString(openapi.resolve("orders.yml"), """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: "http://orders:8080" }
                paths:
                  /orders:
                    get:
                      security: []
                      responses: { "200": { description: OK } }
                """);
        Files.writeString(openapi.resolve("users.json"), """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Users", "version": "1.0.0" },
                  "x-gateway": { "upstream": "http://users:8080" },
                  "paths": { "/users": { "get": { "security": [], "responses": { "200": { "description": "OK" } } } } }
                }
                """);
        Files.writeString(openapi.resolve("notes.txt"), "not an OAS file");

        // The default patterns, with the test folder as the classes of the gateway application.
        GatewayOpenApiProperties defaults = new Binder().bindOrCreate("gateway.openapi", GatewayOpenApiProperties.class);
        OpenApiRouteDefinitionLocator locator = new OpenApiRouteDefinitionLocator(defaults,
                new PathMatchingResourcePatternResolver(new OpenApiDirectoryClassLoader(openapi.toUri().toURL())),
                BuiltInRules.contract(), new RouteDefinitionMapper(), true, List.of(directory.toUri().toString()));

        assertThat(locator.getRouteDefinitions().collectList().block())
                .extracting(RouteDefinition::getId)
                .containsExactlyInAnyOrder("orders:/orders", "users:/users");
    }

    /**
     * SEC-75-1: {@code classpath:openapi/} of another module comes first on the classpath. The checks of the pull
     * requests read only the OAS folder of the gateway, so the gateway does not load these files.
     */
    @Test
    void defaultLocationInAnotherClasspathRootStopsStartup() throws IOException {
        Path openapi = Files.createDirectories(directory.resolve("other-module/openapi"));
        Files.writeString(openapi.resolve("orders.yaml"), PUBLIC_ORDERS);

        runner.withClassLoader(new OpenApiDirectoryClassLoader(openapi.toUri().toURL())).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("matches OAS files outside the classes of the gateway application")
                    .hasMessageContaining("other-module/openapi/orders.yaml]")
                    .hasMessageContaining("target/classes/");
        });
    }

    /** SEC-75-1: an OAS file in a dependency jar on the classpath stops the startup. */
    @Test
    void classpathFileInADependencyJarStopsStartup() throws IOException {
        Path jar = jar(directory.resolve("dependency.jar"), "openapi/extra.yaml", PUBLIC_ORDERS);

        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                OpenApiRoutingStartupTest.class.getClassLoader())) {
            runner.withClassLoader(loader).withPropertyValues("gateway.openapi.specs=classpath*:openapi/*.yaml")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("outside the classes of the gateway application")
                                .hasMessageContaining("dependency.jar!/openapi/extra.yaml]")
                                .hasMessageNotContaining("sample.yaml]");
                    });
        }
    }

    /** In a jar, the class and the OAS files of the gateway application have the same classpath root. */
    @Test
    void classpathFileInTheJarOfTheApplicationIsLoaded() throws IOException {
        String classFile = "com/johnowl/gateway/GatewayApplication.class";
        Path jar = jar(directory.resolve("app.jar"), "openapi/orders.yaml", PUBLIC_ORDERS);
        jar = jar(directory.resolve("app-with-class.jar"), classFile, "", jar);

        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            List<String> roots = OpenApiRouteDefinitionLocator.ownClassRoots(
                    loader.getResource(classFile).toString(), classFile);
            OpenApiRouteDefinitionLocator locator = new OpenApiRouteDefinitionLocator(
                    new GatewayOpenApiProperties(List.of("classpath:openapi/*.yaml")),
                    new PathMatchingResourcePatternResolver(loader), BuiltInRules.contract(),
                    new RouteDefinitionMapper(), true, roots);

            assertThat(roots).containsExactly("jar:" + jar.toUri().toURL() + "!/");
            assertThat(locator.getRouteDefinitions().collectList().block()).extracting(RouteDefinition::getId)
                    .containsExactly("orders:/orders");
        }
    }

    @Test
    void ownClassRootsOfTheJarAndOfAMavenBuild() {
        String classFile = "com/johnowl/gateway/GatewayApplication.class";

        assertThat(OpenApiRouteDefinitionLocator.ownClassRoots(
                "jar:nested:/app/gateway.jar/!BOOT-INF/classes/!/" + classFile, classFile))
                .containsExactly("jar:nested:/app/gateway.jar/!BOOT-INF/classes/!/");
        assertThat(OpenApiRouteDefinitionLocator.ownClassRoots("file:/work/gateway/target/classes/" + classFile,
                classFile))
                .containsExactly("file:///work/gateway/target/classes/", "file:///work/gateway/target/test-classes/");
        assertThat(OpenApiRouteDefinitionLocator.ownClassRoots("file:/app/BOOT-INF/classes/" + classFile, classFile))
                .containsExactly("file:///app/BOOT-INF/classes/");
        assertThat(OpenApiRouteDefinitionLocator.ownClassRoots(OpenApiRouteDefinitionLocator.class))
                .first().asString().endsWith("/gateway/target/classes/");
    }

    /** A file: location is explicit, so it can be outside the classes. The log shows the locations (SEC-75-2). */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void fileLocationOutsideTheClassesIsLoadedAndLogged(CapturedOutput output) throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), PUBLIC_ORDERS);

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(output).contains("gateway.openapi.specs: [file:" + directory + "/*.yaml]");
        });
    }

    private static final String PUBLIC_ORDERS = """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            x-gateway: { upstream: "http://orders:8080" }
            paths:
              /orders:
                get:
                  security: []
                  responses: { "200": { description: OK } }
            """;

    /** Writes a jar with the entry {@code name}, and the entries of {@code others}. */
    private static Path jar(Path jar, String name, String content, Path... others) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path other : others) {
                try (JarFile in = new JarFile(other.toFile())) {
                    for (JarEntry entry : Collections.list(in.entries())) {
                        out.putNextEntry(new JarEntry(entry.getName()));
                        in.getInputStream(entry).transferTo(out);
                        out.closeEntry();
                    }
                }
            }
            String path = "";
            for (String part : name.substring(0, name.lastIndexOf('/')).split("/")) {
                path += part + "/";
                out.putNextEntry(new JarEntry(path));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry(name));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void locatorRunsOnlyContractRules() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: "http://orders:8080" }
                paths:
                  /orders:
                    get:
                      security: []
                      responses: { "200": { description: OK } }
                """);
        GatewayOpenApiProperties properties = new GatewayOpenApiProperties(List.of("file:" + directory + "/*.yaml"));
        List<Rule> rules = new ArrayList<>(BuiltInRules.all());
        rules.add(new AlwaysFailingRule(Category.NAMING));

        assertThat(new OpenApiRouteDefinitionLocator(
                        properties, new PathMatchingResourcePatternResolver(), rules, new RouteDefinitionMapper(), true)
                .getRouteDefinitions().collectList().block())
                .hasSize(1);

        // The same test rule as CONTRACT stops startup, so the rule runs when it may.
        rules.add(new AlwaysFailingRule(Category.CONTRACT));
        assertThatThrownBy(() -> new OpenApiRouteDefinitionLocator(
                        properties, new PathMatchingResourcePatternResolver(), rules, new RouteDefinitionMapper(), true))
                .isInstanceOf(OpenApiValidationException.class)
                .hasMessageContaining("always-failing-contract");
    }

    @Test
    void contractViolationStopsStartup() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: "http://orders:8080/v1" }
                paths:
                  /orders:
                    get:
                      security: []
                      responses: { "200": { description: OK } }
                """);

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(OpenApiValidationException.class)
                    .hasMessageContaining("x-gateway-schema")
                    .hasMessageContaining("orders.yaml#/x-gateway/upstream");
        });
    }

    @Test
    void parseErrorStopsStartup() throws IOException {
        Files.writeString(directory.resolve("broken.yaml"), "openapi: 3.0.3\ninfo:\n  title: x\n    version: 1\n");

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml").run(context ->
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("oas-parse"));
    }

    // SEC-66-1: without the loader check, GET /orders/admin would use the public route /orders/{id}.
    @Test
    void pathItemReferenceToComponentsStopsStartup() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), """
                openapi: 3.1.0
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: "http://orders:8080" }
                components:
                  pathItems:
                    Admin:
                      get:
                        security: []
                        responses: { "200": { description: OK } }
                paths:
                  /orders/{id}:
                    get:
                      security: []
                      parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
                      responses: { "200": { description: OK } }
                  /orders/admin:
                    $ref: "#/components/pathItems/Admin"
                """);

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml").run(context ->
                assertThat(violations(context.getStartupFailure()))
                        .extracting(Violation::ruleId, Violation::pointer, Violation::message)
                        .containsExactly(tuple("oas-parse", "/paths/~1orders~1admin",
                                "Path item reference '#/components/pathItems/Admin' is not supported."
                                        + " Inline the operations.")));
    }

    // SEC-67-1: swagger-parser drops the get of #/webhooks/Admin, so GET /orders/admin would use /orders/{id}.
    @Test
    void pathItemReferenceChainStopsStartup() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), """
                openapi: 3.1.0
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: "http://orders:8080" }
                webhooks:
                  Admin:
                    $ref: "#/webhooks/Inner"
                    get:
                      security: []
                      responses: { "200": { description: OK } }
                  Inner:
                    post:
                      security: []
                      responses: { "200": { description: OK } }
                paths:
                  /orders/{id}:
                    get:
                      security: []
                      parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
                      responses: { "200": { description: OK } }
                  /orders/admin:
                    $ref: "#/webhooks/Admin"
                """);

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml").run(context ->
                assertThat(violations(context.getStartupFailure()))
                        .extracting(Violation::ruleId, Violation::pointer, Violation::message)
                        .containsExactly(
                                tuple("oas-parse", "/paths/~1orders~1admin",
                                        "Path item reference '#/webhooks/Admin' is not supported."
                                                + " Inline the operations."),
                                tuple("oas-parse", "/webhooks/Admin",
                                        "Path item reference '#/webhooks/Inner' is not supported."
                                                + " Inline the operations.")));
    }

    // SEC-67-4: the parsers read only the first object, so the second one is not routed and not checked.
    @Test
    void contentAfterTheFirstJsonValueStopsStartup() throws IOException {
        Files.writeString(directory.resolve("orders.json"), """
                {"openapi": "3.1.0", "info": {"title": "Orders", "version": "1.0.0"},
                 "x-gateway": {"upstream": "http://orders:8080"},
                 "paths": {"/orders": {"get": {"security": [], "responses": {"200": {"description": "OK"}}}}}}
                {"paths": {"/orders/admin": {"get": {"responses": {"200": {"description": "OK"}}}}}}
                """);

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.json").run(context ->
                assertThat(violations(context.getStartupFailure()))
                        .extracting(Violation::ruleId, Violation::pointer, Violation::message)
                        .containsExactly(tuple("oas-parse", "",
                                "Invalid JSON at line 4, column 1: content after the first JSON value is not allowed")));
    }

    // SEC-67-3: the file name, the pointer, and the message come from the OAS file and must not start a log line.
    @Test
    void escapesUnsafeCharactersInTheStartupFailure() throws IOException {
        Files.writeString(directory.resolve("orders.json"), """
                {"openapi": "3.1.0", "info": {"title": "Orders", "version": "1.0.0"},
                 "x-gateway": {"upstream": "http://orders:8080"},
                 "paths": {"/a\\nERROR forged": {"$ref": "#/x\\u2028y"}}}
                """);

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.json").run(context ->
                assertThat(NestedExceptionUtils.getRootCause(context.getStartupFailure()).getMessage())
                        .doesNotContain("\nERROR", "\u2028")
                        .contains("oas-parse ", "#/paths/~1a\\u000AERROR forged ",
                                "Path item reference '#/x\\u2028y' is not supported."));
    }

    @Test
    void noMatchingFileStopsStartup() {
        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("No OAS file matches gateway.openapi.specs");
        });
    }

    @Test
    void missingClasspathDirectoryStopsStartupWithClearMessage() {
        runner.withPropertyValues("gateway.openapi.specs=classpath:does-not-exist/*.yaml").run(context ->
                assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("No OAS file matches gateway.openapi.specs [classpath:does-not-exist/*.yaml]"));
    }

    @Test
    void bestPracticeProblemsDoNotStopStartup() throws IOException {
        // No summary, no description, no operationId: only best-practice rules care about these.
        Files.writeString(directory.resolve("orders.yaml"), """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: "http://orders:8080" }
                paths:
                  /getOrders:
                    get:
                      security: []
                      responses: { "200": { description: OK } }
                """);

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml")
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static final String PROTECTED = """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            x-gateway: { upstream: "http://orders:8080", audience: 6f1c2e8a-0000-0000-0000-000000000000 }
            components:
              securitySchemes:
                entra: { type: openIdConnect, openIdConnectUrl: "https://login.microsoftonline.com/t/v2.0/.well-known/openid-configuration" }
            security:
              - entra: []
            paths:
              /orders:
                get:
                  x-gateway-authorization: { all-of: [Orders.Read] }
                  responses: { "200": { description: OK } }
            """;

    /** The same file with only a public operation. */
    private static final String PUBLIC = """
            openapi: 3.0.3
            info: { title: Orders, version: 1.0.0 }
            x-gateway: { upstream: "http://orders:8080" }
            paths:
              /orders:
                get:
                  security: []
                  responses: { "200": { description: OK } }
            """;

    @Test
    void protectedOperationsNeedTheAuthorizationManager() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), PROTECTED);

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml").run(context ->
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("GET /orders is a protected operation, but the authorization manager (spec 024) is not active."));
    }

    // No property registers the marker or turns off the stop (spec 024). Without the authorization
    // configuration, the old properties of the permission catalog do not help.
    @Test
    void noPropertyTurnsOffTheStartupStop() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), PROTECTED);
        Path catalog = Files.writeString(directory.resolve("permissions.yml"), "audiences: []\n");

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml",
                        "gateway.security.catalog=file:" + catalog,
                        "gateway.security.enabled=false",
                        "gateway.security.authorization.enabled=true",
                        "gateway.security.audience-authorization-active=true")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("GET /orders is a protected operation, but the authorization manager (spec 024) is not active."));
    }

    @Test
    void startsWithProtectedOperationsWhenTheAuthorizationManagerIsActive() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), PROTECTED);

        runner.withUserConfiguration(TestAudienceAuthorization.class)
                .withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OpenApiRouteDefinitionLocator.class).getRouteDefinitions().collectList().block())
                            .extracting(RouteDefinition::getId)
                            .containsExactly("orders:/orders");
                });
    }

    // SEC-62-2: the relayed token of an http upstream goes over the network without TLS.
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void warnsOncePerRouteThatRelaysATokenToAnHttpUpstream(CapturedOutput output) throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), PROTECTED.replace("  /orders:\n",
                "  /orders/{id}:\n    get:\n      parameters:\n        - { name: id, in: path, required: true, schema: { type: string } }\n"
                        + "      x-gateway-authorization: { all-of: [Orders.Read] }\n"
                        + "      responses: { \"200\": { description: OK } }\n  /orders:\n"));
        Files.writeString(directory.resolve("site.yaml"), PUBLIC.replace("/orders:", "/site:"));
        Path secure = Files.createDirectory(directory.resolve("secure"));
        Files.writeString(secure.resolve("orders.yaml"), PROTECTED.replace("http://orders:8080", "https://orders:8443"));

        runner.withUserConfiguration(TestAudienceAuthorization.class)
                .withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml")
                .run(context -> assertThat(context).hasNotFailed());
        String audience = "the audience 6f1c2e8a-0000-0000-0000-000000000000 to http://orders:8080 without TLS";
        assertThat(output.getOut().lines().filter(line -> line.contains(" WARN ") && line.contains("without TLS")))
                .hasSize(2)
                .anySatisfy(line -> assertThat(line).contains("The route orders:/orders relays", audience))
                .anySatisfy(line -> assertThat(line).contains("The route orders:/orders/{id} relays", audience));

        int start = output.getOut().length();
        runner.withUserConfiguration(TestAudienceAuthorization.class)
                .withPropertyValues("gateway.openapi.specs=file:" + secure + "/*.yaml")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output.getOut().substring(start)).contains("Created 1 route(s)").doesNotContain("without TLS");
    }

    @Test
    void markerBeanMustBeGatewayCodeAndNotAProxy() {
        AudienceAuthorizationActive jdkProxy = (AudienceAuthorizationActive) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AudienceAuthorizationActive.class}, (proxy, method, args) -> null);
        ProxyFactory cglib = new ProxyFactory(new GatewayMarker());
        cglib.setProxyTargetClass(true);

        assertThatThrownBy(() -> OpenApiRoutingConfiguration.authorizationActive(List.of(jdkProxy)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(jdkProxy.getClass().getName() + " is not a valid bean of type AudienceAuthorizationActive."
                        + " The bean must be a class in the package com.johnowl.gateway, and not a proxy.");
        AudienceAuthorizationActive cglibProxy = (AudienceAuthorizationActive) cglib.getProxy();
        assertThatThrownBy(() -> OpenApiRoutingConfiguration.authorizationActive(List.of(cglibProxy)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(cglibProxy.getClass().getName() + " is not a valid bean of type AudienceAuthorizationActive."
                        + " The bean must be a class in the package com.johnowl.gateway, and not a proxy.");
        assertThatThrownBy(() -> OpenApiRoutingConfiguration.authorizationActive(List.of(new OutsideAudienceAuthorization())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("com.johnowl.gatewayextra.OutsideAudienceAuthorization is not a valid bean of type"
                        + " AudienceAuthorizationActive. The bean must be a class in the package com.johnowl.gateway,"
                        + " and not a proxy.");

        assertThat(OpenApiRoutingConfiguration.authorizationActive(List.of(new GatewayMarker()))).isTrue();
        assertThat(OpenApiRoutingConfiguration.authorizationActive(List.of())).isFalse();
    }

    @Test
    void proxyMarkerBeanStopsStartupAlsoWithOnlyPublicOperations() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), PUBLIC);

        runner.withBean(AudienceAuthorizationActive.class, () -> (AudienceAuthorizationActive) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {AudienceAuthorizationActive.class}, (proxy, method, args) -> null))
                .withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageEndingWith(" is not a valid bean of type AudienceAuthorizationActive."
                                + " The bean must be a class in the package com.johnowl.gateway, and not a proxy."));
    }

    /** A marker class in the gateway package. It is not final, so that CGLIB can create a subclass. */
    static class GatewayMarker implements AudienceAuthorizationActive {
    }

    @Test
    void publicOperationsStartWithoutTheAuthorizationManager() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), PUBLIC);

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void repeatedAudienceStopsStartup() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"), PROTECTED);
        Files.writeString(directory.resolve("reports.yaml"), PROTECTED
                .replace("title: Orders", "title: Reports")
                .replace("\"http://orders:8080\"", "\"http://reporting-service:8080\", prefix: /reports"));

        runner.withUserConfiguration(TestAudienceAuthorization.class)
                .withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml")
                .run(context -> {
                    List<Violation> violations = violations(context.getStartupFailure());
                    assertThat(violations).extracting(Violation::ruleId, Violation::pointer, OpenApiRoutingStartupTest::fileName)
                            .containsExactly(
                                    tuple("audience-unique", "/x-gateway/audience", "orders.yaml"),
                                    tuple("audience-unique", "/x-gateway/audience", "reports.yaml"));
                    String orders = violations.get(0).file();
                    String reports = violations.get(1).file();
                    assertThat(violations).extracting(Violation::message).containsExactly(
                            "Audience '6f1c2e8a-0000-0000-0000-000000000000' is also declared in " + reports
                                    + ". An audience can appear in one OAS file only.",
                            "Audience '6f1c2e8a-0000-0000-0000-000000000000' is also declared in " + orders
                                    + ". An audience can appear in one OAS file only.");
                });
    }

    @Test
    void microsoftGraphAudienceStopsStartup() throws IOException {
        Files.writeString(directory.resolve("orders.yaml"),
                PROTECTED.replace("6f1c2e8a-0000-0000-0000-000000000000", "00000003-0000-0000-c000-000000000000"));

        runner.withUserConfiguration(TestAudienceAuthorization.class)
                .withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml")
                .run(context -> assertThat(violations(context.getStartupFailure()))
                        .extracting(Violation::ruleId, Violation::pointer, OpenApiRoutingStartupTest::fileName,
                                Violation::message)
                        .containsExactly(tuple("audience-not-first-party", "/x-gateway/audience", "orders.yaml",
                                "Audience '00000003-0000-0000-c000-000000000000' is the Microsoft first-party app"
                                        + " 'Microsoft Graph'. The gateway does not request tokens for Microsoft APIs.")));
    }

    private static List<Violation> violations(Throwable startupFailure) {
        assertThat(startupFailure).rootCause().isInstanceOf(OpenApiValidationException.class);
        return ((OpenApiValidationException) NestedExceptionUtils.getRootCause(startupFailure)).violations();
    }

    private static String fileName(Violation violation) {
        return violation.file().substring(violation.file().lastIndexOf('/') + 1);
    }

    /** A rule with severity ERROR that reports each file. */
    private record AlwaysFailingRule(Category category) implements SpecRule {

        @Override
        public RuleMeta meta() {
            return new RuleMeta("always-failing-" + category.name().toLowerCase(Locale.ROOT), category, Severity.ERROR,
                    false, "Reports each file.", "Test rule.");
        }

        @Override
        public void check(SpecContext context, Reporter reporter) {
            reporter.report("", "Always fails.");
        }
    }

    /** Serves {@code classpath:openapi/} from a test folder or jar, so the test controls the default location. */
    private static final class OpenApiDirectoryClassLoader extends ClassLoader {

        private final URL openapi;

        OpenApiDirectoryClassLoader(URL openapi) {
            super(OpenApiRoutingStartupTest.class.getClassLoader());
            this.openapi = openapi;
        }

        @Override
        public URL getResource(String name) {
            if (name.equals("openapi/") || name.equals("openapi")) {
                return openapi;
            }
            return super.getResource(name);
        }
    }

    @Test
    void malformedDeviationDoesNotStopStartup() throws IOException {
        // The gateway never reads x-deviation (spec 028).
        Files.writeString(directory.resolve("orders.yaml"), """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: "http://orders:8080" }
                paths:
                  /orders:
                    get:
                      security: []
                      x-deviation:
                        - rule: security-explicit
                      responses: { "200": { description: OK } }
                """);

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void routesDoNotChangeAfterStartup() throws IOException {
        Path file = Files.writeString(directory.resolve("orders.yaml"), """
                openapi: 3.0.3
                info: { title: Orders, version: 1.0.0 }
                x-gateway: { upstream: "http://orders:8080" }
                paths:
                  /orders:
                    get:
                      security: []
                      responses: { "200": { description: OK } }
                """);

        runner.withPropertyValues("gateway.openapi.specs=file:" + directory + "/*.yaml").run(context -> {
            OpenApiRouteDefinitionLocator locator = context.getBean(OpenApiRouteDefinitionLocator.class);
            Files.delete(file);
            assertThat(locator.getRouteDefinitions().collectList().block()).hasSize(1);
        });
    }
}
