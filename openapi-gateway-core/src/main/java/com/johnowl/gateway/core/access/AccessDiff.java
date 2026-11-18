package com.johnowl.gateway.core.access;

import com.johnowl.gateway.core.route.GatewayRouteBuilder;
import com.johnowl.gateway.core.route.PathSyntax;
import com.johnowl.gateway.core.route.XGatewaySettings;
import com.johnowl.gateway.core.spec.SpecFile;
import com.johnowl.gateway.core.validation.JsonPointers;
import io.swagger.v3.oas.models.PathItem;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares the access rules of two versions of the OAS files: the base and the head. It reports each change that
 * loosens the access to an operation, and each new or changed audience binding. See
 * specs/034-access-change-review.md.
 *
 * <p>A head operation is compared with the base operation of the same public path, with each base operation of the
 * same backend target, and, if its public path is new, with each base template that it overlaps. See "Operation
 * matching" of the spec.
 *
 * <p>The files of each side must have no CONTRACT violation, as for {@link AccessRuleCompiler}. Then each operation
 * key and each audience appears once on each side ({@code route-collision}, {@code audience-unique}).
 */
public final class AccessDiff {

    /**
     * @param changes sorted by public path, method, change id, and permission
     * @param bindings sorted by audience, then file
     */
    public record Result(List<AccessChange> changes, List<AudienceBinding> bindings) {

        public Result {
            changes = List.copyOf(changes);
            bindings = List.copyOf(bindings);
        }

        /** The number of operations with at least one change. */
        public long operationCount() {
            return changes.stream().map(change -> change.method() + " " + change.publicPath()).distinct().count();
        }
    }

    private static final Comparator<AccessChange> CHANGE_ORDER = Comparator.comparing(AccessChange::publicPath)
            .thenComparing(AccessChange::method)
            .thenComparing(change -> change.kind().id())
            .thenComparing(AccessChange::permission, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final Comparator<AudienceBinding> BINDING_ORDER = Comparator.comparing(AudienceBinding::audience,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(AudienceBinding::file);

    private final GatewayRouteBuilder routeBuilder = new GatewayRouteBuilder();
    private final AccessRuleCompiler compiler = new AccessRuleCompiler();

    public Result compare(List<SpecFile> base, List<SpecFile> head) {
        Map<String, Operation> baseOperations = operations(base);
        Map<String, List<Operation>> baseTargets = new LinkedHashMap<>();
        baseOperations.values().forEach(operation ->
                baseTargets.computeIfAbsent(operation.targetKey(), k -> new ArrayList<>()).add(operation));
        List<Operation> baseTemplates = baseOperations.values().stream().filter(Operation::isTemplate).toList();

        Map<String, Operation> headOperations = operations(head);
        List<Operation> headTemplates = headOperations.values().stream().filter(Operation::isTemplate).toList();

        // A head operation can match one base operation in more than one way; the set keeps each change once.
        Set<AccessChange> changes = new LinkedHashSet<>();
        for (Operation headOperation : headOperations.values()) {
            Operation samePath = baseOperations.get(headOperation.key());
            if (samePath != null) {
                changes.addAll(changes(samePath.rule(), headOperation, ""));
            }
            changes.addAll(sameTargetChanges(baseTargets.getOrDefault(headOperation.targetKey(), List.of()), samePath,
                    headOperation));
            if (samePath == null) {
                changes.addAll(newOperationChanges(baseTemplates, headOperation));
                changes.addAll(weakerThanGetChanges(baseOperations, baseTargets, headOperation));
            }
        }
        for (Operation removed : baseOperations.values()) {
            if (!removed.isTemplate() && !headOperations.containsKey(removed.key())) {
                changes.addAll(removedLiteralChanges(removed, headTemplates));
            }
        }
        List<AccessChange> sorted = new ArrayList<>(changes);
        sorted.sort(CHANGE_ORDER);
        return new Result(sorted, bindings(base, head));
    }

    /**
     * "Operation matching", backend target: the changes against each base operation with the same backend target. The
     * base can reach one backend target through more than one public path. If one of them is not stricter than the
     * head operation, the head adds no weaker access to the backend, and this gives no change. The base operation of
     * the same public path is already compared.
     */
    private static List<AccessChange> sameTargetChanges(List<Operation> sameTarget, Operation samePath, Operation head) {
        if (sameTarget.stream().anyMatch(base -> changes(base.rule(), head, "").isEmpty())) {
            return List.of();
        }
        List<AccessChange> result = new ArrayList<>();
        for (Operation base : sameTarget) {
            if (base != samePath) {
                result.addAll(changes(base.rule(), head, " (the same backend as " + base.method() + " "
                        + base.publicPath() + " in the base)"));
            }
        }
        return result;
    }

    /**
     * Checks 7 and 8 of "Loosening changes" for a head operation with a public path that the base does not have: a
     * new public operation, and a new operation that takes requests from a base template with a stricter rule.
     */
    private static List<AccessChange> newOperationChanges(List<Operation> baseTemplates, Operation head) {
        List<AccessChange> result = new ArrayList<>();
        if (head.rule().isPublic()) {
            result.add(head.change(AccessChange.Kind.NEW_PUBLIC_OPERATION, null, "base: none; head: public"));
        }
        for (Operation template : baseTemplates) {
            if (template.overlaps(head) && !changes(template.rule(), head, "").isEmpty()) {
                result.add(head.change(AccessChange.Kind.SHADOWS_PROTECTED_TEMPLATE, null, "overlaps "
                        + template.method() + " " + template.publicPath() + " in the base; base: "
                        + describe(template.rule()) + "; head: " + describe(head.rule())));
            }
        }
        return result;
    }

    /**
     * Check 9 of "Loosening changes": the head no longer has a base literal path, so a head template with the same
     * method takes its requests. The removed literal rule is the base rule.
     */
    private static List<AccessChange> removedLiteralChanges(Operation removed, List<Operation> headTemplates) {
        List<AccessChange> result = new ArrayList<>();
        for (Operation template : headTemplates) {
            if (template.overlaps(removed) && !changes(removed.rule(), template, "").isEmpty()) {
                result.add(template.change(AccessChange.Kind.LITERAL_REMOVED_TO_TEMPLATE, null,
                        "takes the requests of the removed " + removed.method() + " " + removed.publicPath()
                                + "; base: " + describe(removed.rule()) + "; head: " + describe(template.rule())));
            }
        }
        return result;
    }

    /**
     * Check 10 of "Loosening changes": a new HEAD or OPTIONS operation with a weaker rule than the base GET of the same
     * public path or backend target. A backend often answers HEAD with the GET handler. For the backend target, the
     * same rule as in {@link #sameTargetChanges} applies.
     */
    private static List<AccessChange> weakerThanGetChanges(Map<String, Operation> baseOperations,
            Map<String, List<Operation>> baseTargets, Operation head) {
        if (!head.method().equals("HEAD") && !head.method().equals("OPTIONS")) {
            return List.of();
        }
        Set<Operation> gets = new LinkedHashSet<>();
        Operation samePath = baseOperations.get("GET " + PathSyntax.normalize(head.publicPath()));
        if (samePath != null) {
            gets.add(samePath);
        }
        List<Operation> sameTarget = baseTargets.getOrDefault("GET " + head.target(), List.of());
        if (sameTarget.stream().noneMatch(get -> changes(get.rule(), head, "").isEmpty())) {
            gets.addAll(sameTarget);
        }
        List<AccessChange> result = new ArrayList<>();
        for (Operation get : gets) {
            if (!changes(get.rule(), head, "").isEmpty()) {
                result.add(head.change(AccessChange.Kind.HEAD_WEAKER_THAN_GET, null, "weaker than GET "
                        + get.publicPath() + " in the base; base: " + describe(get.rule()) + "; head: "
                        + describe(head.rule())));
            }
        }
        return result;
    }

    /**
     * Checks 1 to 6 of "Loosening changes", in their order.
     *
     * @param source added to each message, empty for the base operation of the same public path
     */
    private static List<AccessChange> changes(AccessRule base, Operation head, String source) {
        AccessRule rule = head.rule();
        String both = "base: " + describe(base) + "; head: " + describe(rule) + source;
        if (!base.isPublic() && rule.isPublic()) {
            return List.of(head.change(AccessChange.Kind.BECAME_PUBLIC, null, both));
        }
        if (base.isPublic() || rule.isPublic()) {
            return List.of();
        }
        if (!Objects.equals(base.audience(), rule.audience())) {
            return List.of(head.change(AccessChange.Kind.AUDIENCE_CHANGED, null, both));
        }
        boolean basePermissions = !base.allOf().isEmpty() || !base.anyOf().isEmpty();
        if (basePermissions && rule.allOf().isEmpty() && rule.anyOf().isEmpty()) {
            return List.of(head.change(AccessChange.Kind.AUTHENTICATED_ONLY_REPLACED_PERMISSIONS, null, both));
        }
        List<AccessChange> result = new ArrayList<>();
        for (String permission : new TreeSet<>(base.allOf())) {
            if (!rule.allOf().contains(permission)) {
                result.add(head.change(AccessChange.Kind.PERMISSION_REMOVED_FROM_ALL_OF, permission,
                        permission + " removed from all-of" + source));
            }
        }
        if (!base.anyOf().isEmpty()) {
            for (String permission : new TreeSet<>(rule.anyOf())) {
                if (!base.anyOf().contains(permission)) {
                    result.add(head.change(AccessChange.Kind.PERMISSION_ADDED_TO_ANY_OF, permission,
                            permission + " added to any-of" + source));
                }
            }
        }
        // Without any-of, the head needs one permission of the base any-of only if its all-of has one.
        if (!base.anyOf().isEmpty() && rule.anyOf().isEmpty()
                && rule.allOf().stream().noneMatch(base.anyOf()::contains)) {
            result.add(head.change(AccessChange.Kind.ANY_OF_REMOVED, null, both));
        }
        return result;
    }

    /** {@code public}, or the audience and the sorted permissions, for example {@code audience=..., all-of=[A.B]}. */
    private static String describe(AccessRule rule) {
        if (rule.isPublic()) {
            return "public";
        }
        StringBuilder text = new StringBuilder("audience=").append(rule.audience());
        if (!rule.allOf().isEmpty()) {
            text.append(", all-of=").append(new TreeSet<>(rule.allOf()));
        }
        if (!rule.anyOf().isEmpty()) {
            text.append(", any-of=").append(new TreeSet<>(rule.anyOf()));
        }
        if (rule.allOf().isEmpty() && rule.anyOf().isEmpty()) {
            text.append(", authenticated-only");
        }
        return text.toString();
    }

    /**
     * The operations of all files by key: the method and the normalized public path, as in {@code route-collision}.
     * Each operation also has its backend target ({@link #target}).
     *
     * @throws IllegalArgumentException if two operations have the same key; {@code route-collision} reports this
     */
    private Map<String, Operation> operations(List<SpecFile> specs) {
        Map<String, Operation> result = new LinkedHashMap<>();
        for (SpecFile spec : specs) {
            XGatewaySettings xGateway = XGatewaySettings.of(spec).orElse(null);
            if (xGateway == null || spec.openApi().getPaths() == null) {
                continue;
            }
            Map<String, AccessRule> rules = new LinkedHashMap<>();
            compiler.compile(spec, routeBuilder.build(spec))
                    .forEach(access -> rules.put(access.method() + " " + access.publicPathPattern(), access.rule()));
            spec.openApi().getPaths().forEach((oasPath, item) -> {
                String publicPath = xGateway.publicPath(oasPath);
                for (PathItem.HttpMethod httpMethod : item.readOperationsMap().keySet()) {
                    String method = httpMethod.name();
                    String pointer = "/paths/" + JsonPointers.escape(oasPath) + "/" + method.toLowerCase(Locale.ROOT);
                    Operation operation = new Operation(spec.path(), pointer, method, publicPath,
                            target(xGateway, oasPath), Objects.requireNonNull(rules.get(method + " " + publicPath),
                                    pointer));
                    Operation other = result.putIfAbsent(operation.key(), operation);
                    if (other != null) {
                        throw new IllegalArgumentException(method + " " + publicPath + " in " + spec.path()
                                + " is also declared in " + other.file());
                    }
                }
            });
        }
        return result;
    }

    /**
     * The backend target of an operation: the {@link #canonical canonical} upstream, the upstream base path, and the
     * OAS path, without a trailing slash. The variables are normalized and the text is in lowercase, because many
     * backends ignore the case of a path.
     */
    private static String target(XGatewaySettings xGateway, String oasPath) {
        String basePath = xGateway.upstreamBasePath() == null ? "" : xGateway.upstreamBasePath();
        String target = canonical(xGateway.upstream()) + basePath + PathSyntax.normalize(oasPath);
        return target.replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }

    /**
     * The upstream with the scheme and the host in lowercase, without user information, and without the default port
     * of the scheme: {@code HTTP://Orders:80} gives {@code http://orders}. Two host names of one backend (DNS aliases)
     * stay different.
     */
    static String canonical(URI upstream) {
        String scheme = upstream.getScheme().toLowerCase(Locale.ROOT);
        String authority = upstream.getRawAuthority() == null ? ""
                : upstream.getRawAuthority().toLowerCase(Locale.ROOT);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        if (scheme.equals("http") && authority.endsWith(":80") || scheme.equals("https") && authority.endsWith(":443")) {
            authority = authority.substring(0, authority.lastIndexOf(':'));
        }
        return scheme + "://" + authority + (upstream.getRawPath() == null ? "" : upstream.getRawPath());
    }

    /**
     * "Audience bindings": each head binding that the base does not have. A binding with an audience matches the base
     * binding of the same audience. A binding without an audience (a file with only public operations) needs no entry
     * if a base binding has the same upstream, prefix, and upstream base path. Otherwise it matches the first base
     * binding without an audience with the same prefix (SEC-73-6).
     */
    private static List<AudienceBinding> bindings(List<SpecFile> base, List<SpecFile> head) {
        List<Binding> baseBindings = base.stream().flatMap(spec -> binding(spec).stream()).toList();
        Map<String, Binding> byAudience = new LinkedHashMap<>();
        baseBindings.stream().filter(binding -> binding.audience() != null)
                .forEach(binding -> byAudience.putIfAbsent(binding.audience(), binding));
        List<AudienceBinding> result = new ArrayList<>();
        for (SpecFile spec : head) {
            Binding binding = binding(spec).orElse(null);
            if (binding == null) {
                continue;
            }
            Binding before;
            if (binding.audience() != null) {
                before = byAudience.get(binding.audience());
            } else if (baseBindings.stream().anyMatch(other -> changedField(other, binding) == null)) {
                continue;
            } else {
                before = baseBindings.stream()
                        .filter(other -> other.audience() == null && Objects.equals(other.prefix(), binding.prefix()))
                        .findFirst().orElse(null);
            }
            if (before == null) {
                result.add(entry(AudienceBinding.Kind.NEW, null, binding, spec.path(), "/x-gateway/upstream"));
            } else {
                String pointer = changedField(before, binding);
                if (pointer != null) {
                    result.add(entry(AudienceBinding.Kind.CHANGED, before, binding, spec.path(), pointer));
                }
            }
        }
        result.sort(BINDING_ORDER);
        return result;
    }

    /** The pointer of the first changed field of the binding, or {@code null} if the target is the same. */
    private static String changedField(Binding before, Binding head) {
        if (!before.upstream().equals(head.upstream())) {
            return "/x-gateway/upstream";
        }
        if (!Objects.equals(before.prefix(), head.prefix())) {
            return field("prefix", head.prefix());
        }
        if (!Objects.equals(before.upstreamBasePath(), head.upstreamBasePath())) {
            return field("upstream-base-path", head.upstreamBasePath());
        }
        return null;
    }

    /** The pointer of a changed field; {@code /x-gateway} if the head file no longer has the field. */
    private static String field(String name, String headValue) {
        return headValue == null ? "/x-gateway" : "/x-gateway/" + name;
    }

    private static AudienceBinding entry(AudienceBinding.Kind kind, Binding before, Binding head, String file,
            String pointer) {
        return new AudienceBinding(kind, head.audience(), before == null ? null : before.upstream(), head.upstream(),
                before == null ? null : before.prefix(), head.prefix(),
                before == null ? null : before.upstreamBasePath(), head.upstreamBasePath(), file, pointer);
    }

    /** The binding of a file with at least one operation and a valid {@code upstream}, with or without an audience. */
    private static Optional<Binding> binding(SpecFile spec) {
        boolean hasOperations = spec.openApi().getPaths() != null && spec.openApi().getPaths().values().stream()
                .anyMatch(item -> !item.readOperationsMap().isEmpty());
        return XGatewaySettings.of(spec)
                .filter(xGateway -> hasOperations)
                .map(xGateway -> new Binding(xGateway.audience(), xGateway.upstream().toString(), xGateway.prefix(),
                        xGateway.upstreamBasePath()));
    }

    private record Binding(String audience, String upstream, String prefix, String upstreamBasePath) {
    }

    /**
     * One operation of one side.
     *
     * @param target the backend target, see {@link #target}
     */
    private record Operation(String file, String pointer, String method, String publicPath, String target,
            AccessRule rule) {

        /** The method and the normalized public path, as in {@code route-collision}. */
        String key() {
            return method + " " + PathSyntax.normalize(publicPath);
        }

        String targetKey() {
            return method + " " + target;
        }

        boolean isTemplate() {
            return publicPath.contains("{");
        }

        /**
         * At each segment, the literals are equal or one of the two segments is a variable, as in
         * {@code route-collision}. So at least one request matches both operations.
         */
        boolean overlaps(Operation other) {
            if (!method.equals(other.method)) {
                return false;
            }
            String[] segments = PathSyntax.normalize(publicPath).split("/", -1);
            String[] others = PathSyntax.normalize(other.publicPath).split("/", -1);
            if (segments.length != others.length) {
                return false;
            }
            for (int i = 0; i < segments.length; i++) {
                if (!segments[i].equals("{}") && !others[i].equals("{}") && !segments[i].equals(others[i])) {
                    return false;
                }
            }
            return true;
        }

        AccessChange change(AccessChange.Kind kind, String permission, String message) {
            return new AccessChange(kind, method, publicPath, file, pointer, permission, message);
        }
    }
}
