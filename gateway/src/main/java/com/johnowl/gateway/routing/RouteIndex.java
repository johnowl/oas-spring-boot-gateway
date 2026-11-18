package com.johnowl.gateway.routing;

import com.johnowl.gateway.core.trie.PathTrie;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.cloud.gateway.route.Route;

/**
 * The routes of the gateway for the indexed lookup (spec 017): a trie of the routes from OAS files,
 * and the other routes in route order. Each entry keeps the position of its route in the route
 * order, so the lookup can keep the order between the two groups.
 *
 * @param unindexed routes without OAS metadata, for example routes from {@code application.yaml}
 * @param trie the routes from OAS files, by public path pattern and method
 * @param size the number of routes
 */
record RouteIndex(List<Entry> unindexed, PathTrie<Entry> trie, int size) {

    /**
     * A route and its position in the route order of the route locator.
     *
     * @param route the route
     * @param position 0 for the first route of the route locator
     */
    record Entry(Route route, int position) {
    }

    /** Builds the index for a gateway without a base path. */
    static RouteIndex build(List<Route> routesInOrder) {
        return build(routesInOrder, null);
    }

    /**
     * Builds the index. Keeps the route order of the route locator for the unindexed routes.
     *
     * @param basePath {@code spring.webflux.base-path}, or {@code null}. The {@code Path} predicate
     *     adds it before each pattern, so the trie does the same.
     * @throws IllegalArgumentException if two OAS routes have the same pattern and method, or a
     *     pattern that the trie does not support
     */
    static RouteIndex build(List<Route> routesInOrder, String basePath) {
        String prefix = basePath == null ? "" : basePath;
        List<Entry> unindexed = new ArrayList<>();
        PathTrie<Entry> trie = new PathTrie<>();
        int position = 0;
        for (Route route : routesInOrder) {
            Entry entry = new Entry(route, position++);
            Object path = route.getMetadata().get(RouteDefinitionMapper.OAS_PATH);
            Object methods = route.getMetadata().get(RouteDefinitionMapper.OAS_METHODS);
            if (path instanceof String pattern && methods instanceof Collection<?> values) {
                Set<String> methodNames = new LinkedHashSet<>();
                values.forEach(value -> methodNames.add(String.valueOf(value)));
                trie.insert(prefix + pattern, methodNames, entry);
            } else {
                unindexed.add(entry);
            }
        }
        return new RouteIndex(List.copyOf(unindexed), trie, routesInOrder.size());
    }
}
