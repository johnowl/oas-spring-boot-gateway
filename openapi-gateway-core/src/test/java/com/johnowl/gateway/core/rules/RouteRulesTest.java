package com.johnowl.gateway.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.Violation;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteRulesTest {

    @Test
    void routeCollisionNamesTheOtherDeclaration() {
        List<Violation> violations = run(new RouteCollisionRule(),
                spec("orders.yaml", "/orders-api", "/orders/{id}", "get"),
                spec("payments.yaml", "/orders-api", "/orders/{orderId}", "get"));

        assertThat(violations).extracting(Violation::file, Violation::message).containsExactly(
                tuple("orders.yaml",
                        "GET /orders-api/orders/{} is also declared in payments.yaml at /paths/~1orders~1{orderId}/get."),
                tuple("payments.yaml",
                        "GET /orders-api/orders/{} is also declared in orders.yaml at /paths/~1orders~1{id}/get."));
    }

    @Test
    void routeCollisionCountsMoreThanTwoDeclarations() {
        List<Violation> violations = run(new RouteCollisionRule(),
                spec("a.yaml", null, "/x", "get"),
                spec("b.yaml", null, "/x", "get"),
                spec("c.yaml", null, "/x", "get"));

        assertThat(violations).hasSize(3).first().extracting(Violation::message)
                .isEqualTo("GET /x is also declared in b.yaml at /paths/~1x/get and in 1 more place(s).");
    }

    @Test
    void routeCollisionIgnoresDifferentMethods() {
        assertThat(run(new RouteCollisionRule(),
                spec("a.yaml", null, "/users/{id}", "get"),
                spec("b.yaml", null, "/users/{id}", "delete"),
                spec("c.yaml", null, "/users/me", "post"))).isEmpty();
    }

    @Test
    void literalSpecializationInTheSameFileIsAllowed() {
        assertThat(run(new RouteCollisionRule(), multi("users.yaml", "/users/me", "/users/{id}"))).isEmpty();
    }

    @Test
    void literalOverTemplateInAnotherFileCollides() {
        List<Violation> violations = run(new RouteCollisionRule(),
                spec("a.yaml", null, "/users/{id}", "get"),
                spec("c.yaml", null, "/users/me", "get"));

        assertThat(violations).extracting(Violation::file, Violation::message).containsExactly(
                tuple("a.yaml", "GET /users/{} overlaps GET /users/me in c.yaml at /paths/~1users~1me/get."
                        + " Both match /users/me."),
                tuple("c.yaml", "GET /users/me overlaps GET /users/{} in a.yaml at /paths/~1users~1{id}/get."
                        + " Both match /users/me."));
    }

    @Test
    void overlappingTemplatesCollideAlsoInTheSameFile() {
        List<Violation> violations = run(new RouteCollisionRule(), multi("a.yaml", "/a/{x}/c", "/a/b/{y}"));

        assertThat(violations).extracting(Violation::pointer, Violation::message).containsExactlyInAnyOrder(
                tuple("/paths/~1a~1{x}~1c/get",
                        "GET /a/{}/c overlaps GET /a/b/{} in a.yaml at /paths/~1a~1b~1{y}/get. Both match /a/b/c."),
                tuple("/paths/~1a~1b~1{y}/get",
                        "GET /a/b/{} overlaps GET /a/{}/c in a.yaml at /paths/~1a~1{x}~1c/get. Both match /a/b/c."));
    }

    @Test
    void templateThatSpecializesATemplateCollidesAlsoInTheSameFile() {
        // Both routes have order 100, so Spring Cloud Gateway and the path trie can select different operations.
        List<Violation> violations = run(new RouteCollisionRule(), multi("a.yaml", "/a/{x}/c", "/a/{id}/{y}"));

        assertThat(violations).extracting(Violation::pointer, Violation::message).containsExactlyInAnyOrder(
                tuple("/paths/~1a~1{x}~1c/get",
                        "GET /a/{}/c overlaps GET /a/{}/{} in a.yaml at /paths/~1a~1{id}~1{y}/get. Both match /a/x/c."),
                tuple("/paths/~1a~1{id}~1{y}/get",
                        "GET /a/{}/{} overlaps GET /a/{}/c in a.yaml at /paths/~1a~1{x}~1c/get. Both match /a/x/c."));
    }

    @Test
    void tildeLiteralInATemplateCollidesAlsoInTheSameFile() {
        assertThat(run(new RouteCollisionRule(), multi("a.yaml", "/a/{x}/~c", "/a/{x}/{y}"))).hasSize(2);
    }

    @Test
    void messageCountsDuplicatesAndOverlapsSeparately() {
        List<Violation> violations = run(new RouteCollisionRule(),
                spec("a.yaml", null, "/users/{id}", "get"),
                spec("b.yaml", null, "/users/{key}", "get"),
                spec("c.yaml", null, "/users/me", "get"));

        assertThat(violations).extracting(Violation::file, Violation::message).containsExactly(
                tuple("a.yaml", "GET /users/{} is also declared in b.yaml at /paths/~1users~1{key}/get."
                        + " It also overlaps 1 other operation(s)."),
                tuple("b.yaml", "GET /users/{} is also declared in a.yaml at /paths/~1users~1{id}/get."
                        + " It also overlaps 1 other operation(s)."),
                tuple("c.yaml", "GET /users/me overlaps GET /users/{} in a.yaml at /paths/~1users~1{id}/get"
                        + " and in 1 more place(s). Both match /users/me."));
    }

    @Test
    void differentLengthsOrLiteralsDoNotOverlap() {
        assertThat(run(new RouteCollisionRule(),
                spec("a.yaml", null, "/a/{x}", "get"),
                spec("b.yaml", null, "/a/{x}/c", "get"),
                spec("c.yaml", null, "/b/{x}", "get"))).isEmpty();
    }

    @Test
    void routeCollisionComparesLiteralSegmentsWithCase() {
        assertThat(run(new RouteCollisionRule(),
                spec("a.yaml", null, "/Orders", "get"),
                spec("b.yaml", null, "/orders", "get"))).isEmpty();
    }

    @Test
    void rootPathWithPrefixHasThePrefixAsPublicPath() {
        List<Violation> violations = run(new RouteCollisionRule(),
                spec("a.yaml", "/orders-api", "/", "get"),
                spec("b.yaml", null, "/orders-api", "get"));

        assertThat(violations).extracting(Violation::file, Violation::message).containsExactly(
                tuple("a.yaml", "GET /orders-api is also declared in b.yaml at /paths/~1orders-api/get."),
                tuple("b.yaml", "GET /orders-api is also declared in a.yaml at /paths/~1/get."));
    }

    @Test
    void routeIdUniqueReportsRootPathWithPrefix() {
        assertThat(run(new RouteIdUniqueRule(),
                spec("team-a/orders.yaml", "/team-a", "/", "get"),
                spec("team-b/orders.yaml", "/team-b", "/", "get")))
                .extracting(Violation::file, Violation::pointer)
                .containsExactly(tuple("team-a/orders.yaml", "/paths/~1"), tuple("team-b/orders.yaml", "/paths/~1"));
    }

    @Test
    void invalidUpstreamSkipsOnlyThatFile() {
        SpecSource invalidUpstream = new SpecSource("a/orders.yaml", """
                openapi: 3.0.3
                info: { title: T, version: 1.0.0 }
                x-gateway: { upstream: "{{ORDERS_URL}}" }
                paths:
                  /orders:
                    get:
                      responses: { "200": { description: OK } }
                """);
        List<SpecSource> sources = List.of(invalidUpstream,
                spec("a/pay.yaml", null, "/y", "get"),
                spec("a/pay2.yaml", null, "/y", "get"),
                spec("b/pay.yaml", "/b", "/y", "get"));

        List<Violation> collisions = run(new RouteCollisionRule(), sources.toArray(SpecSource[]::new));
        List<Violation> ids = run(new RouteIdUniqueRule(), sources.toArray(SpecSource[]::new));

        assertThat(collisions).extracting(Violation::file).containsExactly("a/pay.yaml", "a/pay2.yaml");
        assertThat(ids).extracting(Violation::file).containsExactly("a/pay.yaml", "b/pay.yaml");
        assertThat(collisions).extracting(Violation::message).noneMatch(m -> m.startsWith("Rule failed:"));
        assertThat(ids).extracting(Violation::message).noneMatch(m -> m.startsWith("Rule failed:"));
    }

    @Test
    void routeCollisionSkipsUnsupportedPaths() {
        assertThat(run(new RouteCollisionRule(),
                spec("a.yaml", null, "/files/**", "get"),
                spec("b.yaml", null, "/files/**", "get"))).isEmpty();
    }

    @Test
    void routeIdUniqueNamesTheOtherFile() {
        List<Violation> violations = run(new RouteIdUniqueRule(),
                spec("team-a/orders.yaml", "/team-a", "/orders", "get"),
                spec("team-b/orders.yaml", "/team-b", "/orders", "get"));

        assertThat(violations).extracting(Violation::file, Violation::pointer, Violation::message).containsExactly(
                tuple("team-a/orders.yaml", "/paths/~1orders",
                        "Route id 'orders:/orders' is also used by team-b/orders.yaml. Rename one of the files."),
                tuple("team-b/orders.yaml", "/paths/~1orders",
                        "Route id 'orders:/orders' is also used by team-a/orders.yaml. Rename one of the files."));
    }

    private static List<Violation> run(Rule rule, SpecSource... sources) {
        return new ValidationEngine(List.of(rule)).validate(List.of(sources)).violations();
    }

    /** One file with a GET operation on each path. */
    private static SpecSource multi(String file, String... paths) {
        StringBuilder yaml = new StringBuilder("""
                openapi: 3.0.3
                info: { title: T, version: 1.0.0 }
                x-gateway: { upstream: http://service:8080 }
                paths:
                """);
        for (String path : paths) {
            StringBuilder parameters = new StringBuilder();
            java.util.regex.Matcher variable = java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(path);
            while (variable.find()) {
                parameters.append("        - { name: ").append(variable.group(1))
                        .append(", in: path, required: true, schema: { type: string } }\n");
            }
            yaml.append("  \"").append(path).append("\":\n    get:\n");
            if (!parameters.isEmpty()) {
                yaml.append("      parameters:\n").append(parameters.toString().replace("        - ", "        - "));
            }
            yaml.append("      responses: { \"200\": { description: OK } }\n");
        }
        return new SpecSource(file, yaml.toString());
    }

    private static SpecSource spec(String file, String prefix, String path, String method) {
        String prefixLine = prefix == null ? "" : ", prefix: " + prefix;
        String parameters = path.contains("{")
                ? "      parameters: [{ name: " + path.substring(path.indexOf('{') + 1, path.indexOf('}'))
                        + ", in: path, required: true, schema: { type: string } }]\n"
                : "";
        return new SpecSource(file, """
                openapi: 3.0.3
                info: { title: T, version: 1.0.0 }
                x-gateway: { upstream: http://service:8080%s }
                paths:
                  "%s":
                    %s:
                %s      responses: { "200": { description: OK } }
                """.formatted(prefixLine, path, method, parameters));
    }
}
