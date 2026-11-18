package com.johnowl.gateway.routing;

import com.johnowl.gateway.core.access.OperationAccess;
import com.johnowl.gateway.core.route.GatewayRoute;
import com.johnowl.gateway.core.spec.SpecSource;
import com.johnowl.gateway.core.validation.Category;
import com.johnowl.gateway.core.validation.Rule;
import com.johnowl.gateway.core.validation.Severity;
import com.johnowl.gateway.core.validation.ValidationEngine;
import com.johnowl.gateway.core.validation.ValidationReport;
import com.johnowl.gateway.core.validation.Violation;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import reactor.core.publisher.Flux;

/**
 * Creates the gateway routes from the OAS files. The constructor loads and validates the files
 * once, with the CONTRACT rules of the rule list that the caller gives. It never runs other rules,
 * so a best-practice rule cannot stop the gateway, whatever its severity. An ERROR violation stops
 * the application. {@link #getRouteDefinitions()} only returns the cached list.
 *
 * <p>A pattern that does not start with {@code file:} reads the classpath. Each file that such a pattern matches
 * MUST be in the classes of the gateway application ({@link #ownClassRoots(Class)}), otherwise the application
 * stops (spec 013, "OAS file location"). Reason: the classpath has the resources of the other modules and of the
 * dependency jars, and the checks of the pull requests read only the OAS folder of the gateway (SEC-75-1).
 */
public final class OpenApiRouteDefinitionLocator implements RouteDefinitionLocator {

    private static final Logger log = LoggerFactory.getLogger(OpenApiRouteDefinitionLocator.class);

    private final List<RouteDefinition> routes;

    private final List<OperationAccess> accessRules;

    /**
     * @param authorizationActive {@code true} if the application context has a bean of type
     *     {@link AudienceAuthorizationActive}; if {@code false}, all operations must be public
     */
    public OpenApiRouteDefinitionLocator(
            GatewayOpenApiProperties properties,
            ResourcePatternResolver resolver,
            List<Rule> rules,
            RouteDefinitionMapper mapper,
            boolean authorizationActive) {
        this(properties, resolver, rules, mapper, authorizationActive,
                ownClassRoots(OpenApiRouteDefinitionLocator.class));
    }

    /**
     * @param ownClassRoots the URIs of the classpath roots of the gateway application, each with a trailing
     *     {@code /}; a classpath pattern matches only files below one of them
     */
    OpenApiRouteDefinitionLocator(
            GatewayOpenApiProperties properties,
            ResourcePatternResolver resolver,
            List<Rule> rules,
            RouteDefinitionMapper mapper,
            boolean authorizationActive,
            List<String> ownClassRoots) {
        // SEC-75-2: the log shows the locations that the configuration of the running instance gives.
        log.info("gateway.openapi.specs: {}", properties.specs());
        List<SpecSource> sources = load(properties.specs(), resolver,
                ownClassRoots.stream().map(OpenApiRouteDefinitionLocator::root).toList());
        List<Rule> contractRules = rules.stream().filter(rule -> rule.meta().category() == Category.CONTRACT).toList();
        // Default policies: the gateway never reads .oas-gateway.yaml, x-deviation or a baseline.
        ValidationReport report = new ValidationEngine(contractRules).validate(sources);
        for (Violation violation : report.violations()) {
            String line = OpenApiValidationException.line(violation);
            switch (violation.severity()) {
                case ERROR -> log.error(line);
                case WARN -> log.warn(line);
                case INFO -> log.debug(line);
            }
        }
        if (report.hasAtLeast(Severity.ERROR)) {
            throw new OpenApiValidationException(
                    report.violations().stream().filter(v -> v.severity() == Severity.ERROR).toList());
        }
        if (!authorizationActive) {
            // Startup stop of spec 024: without the authorization manager, the gateway checks no aud and no permission.
            report.accessRules().stream().filter(access -> !access.rule().isPublic()).findFirst().ifPresent(access -> {
                throw new IllegalStateException(access.method() + " " + access.publicPathPattern()
                        + " is a protected operation, but the authorization manager (spec 024) is not active.");
            });
        }
        // SEC-62-2: no rule yet (spec 025, "Open questions"). The token of the audience goes over plain http.
        for (GatewayRoute route : report.routes()) {
            if (route.audience() != null && "http".equalsIgnoreCase(route.upstream().getScheme())) {
                log.warn("The route {} relays the access token of the audience {} to {} without TLS. Use an https"
                        + " upstream, or keep the network between the gateway and the backend private (spec 025).",
                        route.id(), route.audience(), route.upstream());
            }
        }
        this.routes = report.routes().stream().map(mapper::map).toList();
        this.accessRules = report.accessRules();
        log.info("Created {} route(s) from {} OAS file(s)", routes.size(), sources.size());
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(routes);
    }

    /** The access rule of each operation of the OAS files. Built once, at construction. */
    public List<OperationAccess> accessRules() {
        return accessRules;
    }

    /**
     * The classpath roots of the classes of the gateway application: the root of the class {@code type}, for example
     * {@code jar:nested:/app.jar/!BOOT-INF/classes/!/} in the jar, or {@code file:/.../gateway/target/classes/} in a
     * Maven build. In a Maven build, {@code target/test-classes/} of the same module also counts, so that the tests
     * can use the default location.
     */
    static List<String> ownClassRoots(Class<?> type) {
        String classFile = type.getName().replace('.', '/') + ".class";
        URL url = type.getClassLoader().getResource(classFile);
        if (url == null) {
            throw new IllegalStateException("Cannot find the classpath root of " + type.getName());
        }
        return ownClassRoots(url.toString(), classFile);
    }

    static List<String> ownClassRoots(String classFileUrl, String classFile) {
        if (!classFileUrl.endsWith("/" + classFile)) {
            throw new IllegalStateException("Cannot find the classpath root of " + classFile + " in " + classFileUrl);
        }
        String root = root(classFileUrl.substring(0, classFileUrl.length() - classFile.length()));
        String maven = "/target/classes/";
        if (root.startsWith("file:") && root.endsWith(maven)) {
            return List.of(root, root.substring(0, root.length() - maven.length()) + "/target/test-classes/");
        }
        return List.of(root);
    }

    private static List<SpecSource> load(List<String> patterns, ResourcePatternResolver resolver,
            List<String> ownClassRoots) {
        Map<String, Resource> resources = new LinkedHashMap<>();
        List<String> outside = new ArrayList<>();
        for (String pattern : patterns) {
            boolean classpath = !pattern.toLowerCase(Locale.ROOT).startsWith("file:");
            try {
                for (Resource resource : resolver.getResources(pattern)) {
                    if (resource.exists() && resource.isReadable()) {
                        String uri = resource.getURI().toString();
                        if (classpath && ownClassRoots.stream().noneMatch(normalize(uri)::startsWith)) {
                            outside.add(uri);
                        }
                        resources.putIfAbsent(uri, resource);
                    }
                }
            } catch (FileNotFoundException e) {
                // The directory of the pattern does not exist: the pattern matches nothing.
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot resolve OAS location '" + pattern + "'", e);
            }
        }
        if (!outside.isEmpty()) {
            throw new IllegalStateException("gateway.openapi.specs " + patterns + " matches OAS files outside the"
                    + " classes of the gateway application " + ownClassRoots + ": " + outside + ". A classpath"
                    + " location reads only the OAS files of the gateway module, not of another module or of a"
                    + " dependency jar (spec 013).");
        }
        if (resources.isEmpty()) {
            throw new IllegalStateException(
                    "No OAS file matches gateway.openapi.specs " + patterns + ". The gateway needs at least one.");
        }
        List<SpecSource> sources = new ArrayList<>();
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try {
                sources.add(new SpecSource(entry.getKey(), entry.getValue().getContentAsString(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read OAS file " + entry.getKey(), e);
            }
        });
        return sources;
    }

    /**
     * The normalized URI of a classpath root, with a trailing {@code /}. {@code Path.toUri()} has it only for an
     * existing folder.
     */
    private static String root(String uri) {
        String root = normalize(uri);
        return root.endsWith("/") ? root : root + "/";
    }

    /**
     * One form for each URI, so that a prefix comparison works: {@code file:///path} without {@code .} and
     * {@code ..} for a file, the URI itself for a file in a jar.
     */
    private static String normalize(String uri) {
        try {
            URI parsed = new URI(uri);
            if ("file".equalsIgnoreCase(parsed.getScheme())) {
                return Path.of(parsed).normalize().toUri().toString();
            }
            return parsed.isOpaque() ? uri : parsed.normalize().toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid URI of an OAS file: " + uri, e);
        }
    }
}
