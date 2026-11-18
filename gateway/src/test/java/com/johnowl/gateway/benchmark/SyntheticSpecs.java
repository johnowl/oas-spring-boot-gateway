package com.johnowl.gateway.benchmark;

import com.johnowl.gateway.core.spec.SpecSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates OAS files with a given number of routes, for the routing benchmarks (spec 016).
 *
 * <p>Each file has its own prefix ({@code /svc-<n>-api}) and 10 to 20 paths. Each path gives one
 * route with 1 to 3 methods. Path forms: 50% literal, 40% one variable, 10% two variables.
 */
public final class SyntheticSpecs {

    private static final List<String> METHODS = List.of("get", "post", "put", "delete", "patch");
    private static final List<String> RESOURCES =
            List.of("orders", "users", "payments", "invoices", "products", "carts", "reports", "accounts");

    private SyntheticSpecs() {
    }

    /** Creates OAS files with exactly {@code routes} routes. The same seed gives the same files. */
    public static List<SpecSource> generate(int routes, long seed, int upstreamPort) {
        Random random = new Random(seed);
        List<SpecSource> specs = new ArrayList<>();
        int remaining = routes;
        for (int service = 0; remaining > 0; service++) {
            int paths = Math.min(remaining, 10 + random.nextInt(11));
            specs.add(new SpecSource("svc-" + service + ".yaml", spec(service, paths, random, upstreamPort)));
            remaining -= paths;
        }
        return specs;
    }

    /** Writes the files to a directory and returns it. */
    public static Path write(int routes, long seed, int upstreamPort, Path directory) {
        try {
            Files.createDirectories(directory);
            for (SpecSource spec : generate(routes, seed, upstreamPort)) {
                Files.writeString(directory.resolve(spec.path()), spec.content());
            }
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String spec(int service, int paths, Random random, int upstreamPort) {
        StringBuilder yaml = new StringBuilder("""
                openapi: 3.0.3
                info: { title: Service %d, version: 1.0.0 }
                x-gateway:
                  upstream: http://localhost:%d
                  prefix: /svc-%d-api
                paths:
                """.formatted(service, upstreamPort, service));
        for (int i = 0; i < paths; i++) {
            String resource = RESOURCES.get(random.nextInt(RESOURCES.size())) + "-" + i;
            int form = random.nextInt(10);
            String path;
            List<String> variables;
            if (form < 5) {
                path = "/" + resource + "/summary";
                variables = List.of();
            } else if (form < 9) {
                path = "/" + resource + "/{id}";
                variables = List.of("id");
            } else {
                path = "/" + resource + "/{id}/items/{itemId}";
                variables = List.of("id", "itemId");
            }
            yaml.append("  ").append(path).append(":\n");
            if (!variables.isEmpty()) {
                yaml.append("    parameters:\n");
                for (String variable : variables) {
                    yaml.append("      - { name: ").append(variable)
                            .append(", in: path, required: true, schema: { type: string } }\n");
                }
            }
            List<String> methods = new ArrayList<>(METHODS);
            int count = 1 + random.nextInt(3);
            for (int m = 0; m < count; m++) {
                String method = methods.remove(random.nextInt(methods.size()));
                // Public operations: the benchmark measures routing, not authorization (spec 018).
                yaml.append("    ").append(method).append(":\n")
                        .append("      operationId: operation").append(i).append(method.substring(0, 1).toUpperCase())
                        .append(method.substring(1)).append("\n")
                        .append("      security: []\n")
                        .append("      responses: { \"200\": { description: OK } }\n");
            }
        }
        return yaml.toString();
    }
}
