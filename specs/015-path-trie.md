# 015 — Path trie

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 5 — Path trie and route lookup performance |
| Depends on | [001](001-project-skeleton.md) |
| Modules | `openapi-gateway-core` |

## Summary

This spec adds `PathTrie<T>`, a generic segment trie in `openapi-gateway-core`. The trie maps a path pattern and a set of HTTP methods to a value. It finds the value for a request path in a time that depends on the number of path segments, not on the number of routes. The security index always uses the trie ([024](024-authorization-manager.md)). The indexed route lookup can also use it, if the benchmark shows the need ([016](016-routing-benchmark.md), [017](017-indexed-route-lookup.md)).

## Motivation

Spring Cloud Gateway evaluates routes one by one, in order. The cost of a lookup grows with the number of routes, and a 404 response is the worst case because the gateway evaluates every route. Security filters run before gateway routing, so `UndocumentedOperationWebFilter` and the authorization manager need their own lookup from a request to an `AccessRule`. A linear scan in the security filter doubles the linear cost. One shared data structure with a cost of O(number of path segments) solves both problems.

## Scope

### In scope

- Class `PathTrie<T>` with `insert` and `match`.
- Path normalization: segment splitting and trailing slash handling.
- Literal priority over variables, with backtracking.
- Method-aware matching.
- Unit tests and a differential test against a reference linear matcher.

### Out of scope

- The security index and the authorization manager. See [024](024-authorization-manager.md).
- The indexed route lookup in the gateway. See [017](017-indexed-route-lookup.md).
- Benchmarks of the trie and of the route lookup. See [016](016-routing-benchmark.md).
- Extraction of URI template variable values. The gateway gets these values from the real route predicate ([017](017-indexed-route-lookup.md)).
- Pattern syntax that the contract rule `path-pattern-supported` rejects: `{*var}`, regex variables such as `{id:\d+}`, and `**` ([007](007-route-model.md)).

## Design

### Package and public API

Package: `com.johnowl.gateway.core.trie`.

```java
public final class PathTrie<T> {

    /** Adds a value for a path pattern and a set of HTTP methods. */
    public void insert(String pattern, Set<String> methods, T value);

    /** Returns the value for the path segments and the HTTP method, or empty. */
    public Optional<T> match(List<String> segments, String method);

    /** Splits a path into normalized segments. */
    public static List<String> segments(String path);
}
```

- `pattern` is a public path pattern, for example `/orders-api/orders/{id}` (prefix + OAS path).
- `methods` contains uppercase HTTP method names, for example `GET` and `DELETE`.
- `method` in `match` is an uppercase HTTP method name. The caller converts it.
- `segments` is the output of `PathTrie.segments(path)`. The caller gives the path without the query string.

The trie checks segment syntax itself. It does not use `PathSyntax` of [007](007-route-model.md), so that it has no dependency on the route rules. A segment is a variable only if it is a whole `{name}` without `{`, `}`, or `:` inside the name.

### Node structure

```java
final class Node<T> {
    final Map<String, Node<T>> literals = new HashMap<>(); // literal children
    Node<T> variable;                                      // at most one variable child
    final Map<String, T> valuesByMethod = new HashMap<>(); // values on this node
}
```

The trie supports two segment kinds:

| Segment kind | Example | Stored in |
|---|---|---|
| Literal | `orders`, `me` | `literals`, key = segment text |
| Variable | `{id}`, `{userId}` | `variable` (the variable name is not stored) |

Two patterns that differ only in variable names use the same nodes. For example, `/users/{id}` and `/users/{userId}` share one path in the trie. This behavior matches the normalized path of the rule `route-collision` ([007](007-route-model.md)).

### Normalization

`PathTrie.segments(path)` applies these steps:

1. Remove one trailing `/` if the path is longer than `/`.
2. Split the path on `/`.
3. Remove the first empty element (the element before the leading `/`).

| Path | Segments |
|---|---|
| `/` | `[]` |
| `/users` | `[users]` |
| `/users/` | `[users]` |
| `/users/me/orders` | `[users, me, orders]` |
| `/users//42` | `[users, "", 42]` (empty segments inside the path stay) |

`insert` uses the same function for patterns. Literal comparison is case-sensitive, the same as the default of the Spring `PathPattern` matcher. A case variant of a literal does not fall to the variable child. See "Case variants of a literal".

### Insert

1. Normalize the pattern into segments, and check the syntax of every segment.
2. Check that the node of the pattern (if it exists) has no value for any method in `methods`.
3. For each segment, go to the literal child or to the variable child. Create the child if it does not exist.
4. On the last node, add `value` for each method in `methods`.

`insert` throws `IllegalArgumentException` in these cases:

- A segment mixes literal text and a variable, for example `{name}.json` (see "Open questions").
- A segment uses unsupported syntax: `{*var}`, `{id:\d+}`, `**`, or `*`.
- The node already has a value for one of the methods. The rule `route-collision` prevents this case for validated specs. `insert` checks all segments and all methods before it creates a node or stores a value, so a failed `insert` changes nothing.

### Match with literal priority and backtracking

`match` first checks the segments. If a segment is empty, `.`, or `..`, `match` returns empty (fail closed). A variable in Spring `PathPattern` does not match an empty segment, so `/users//` must not match `/users/{id}`. A backend can resolve dot segments to another path, so `/files/../admin` must not match `/files/{name}`.

Then `match` runs a depth-first search. At each segment, it tries the literal child first. If that branch gives no value for the method, it tries the variable child.

```java
private T find(Node<T> node, List<String> segments, int index, String method) {
    if (index == segments.size()) {
        return node.valuesByMethod.get(method);              // null if no value for method
    }
    Node<T> literal = node.literals.get(segments.get(index));
    if (literal != null) {
        T found = find(literal, segments, index + 1, method);
        if (found != null) {
            return found;
        }
    }
    if (node.variable != null) {
        return find(node.variable, segments, index + 1, method); // backtracking
    }
    return null;
}
```

Example with three patterns:

| Pattern | Methods |
|---|---|
| `/users/me` | `GET` |
| `/users/{id}` | `GET`, `DELETE` |
| `/users/me/settings` | `GET` |

| Request | Result | Reason |
|---|---|---|
| `GET /users/me` | `/users/me` | Literal has priority. |
| `GET /users/42` | `/users/{id}` | No literal child `42`. |
| `DELETE /users/me` | `/users/{id}` | `/users/me` has no `DELETE`; the search backtracks to the variable. |
| `GET /users/42/settings` | empty | The variable node has no child `settings`. |
| `GET /users/me/settings/` | `/users/me/settings` | Trailing slash is normalized. |
| `GET /orders` | empty | No match. For the security index, `UndocumentedOperationWebFilter` ([024](024-authorization-manager.md)) returns 404. |

The method-aware backtracking gives the same result as the route order of [013](013-oas-route-locator.md). In that order, literal routes (order 0) come before routes with variables (order 100). Each route also has a `Method` predicate. The only exception is a case variant of a literal (next section).

### Case variants of a literal

Decision (2026-11-14, team default after the review of PR #59, finding SEC-59-1). A backend that compares paths without case serves `/users/me` for `GET /users/ME`. If the trie gave `/users/{id}` for this request, the security index of [024](024-authorization-manager.md) would check the rule of `/users/{id}` for the operation `/users/me`.

Rule: when a segment is not a literal child of the node, but equals a literal child when the case is ignored, `find` does not go to the variable child of that node. The search returns no value for this branch. Each node keeps the folded keys of its literal children. The fold maps each character `c` to `Character.toLowerCase(Character.toUpperCase(c))`, the same comparison as `String.equalsIgnoreCase`. It also folds characters outside ASCII, for example the Kelvin sign (U+212A) to `k`.

| Request | Result | Reason |
|---|---|---|
| `GET /users/ME` | empty | `ME` is a case variant of the literal `me`. |
| `DELETE /users/Me` | empty | The same rule, also when the literal has no value for the method. |
| `DELETE /users/me` | `/users/{id}` | An exact literal backtracks as before. |

This is the only case where the trie differs from Spring `PathPattern` and the route order of [013](013-oas-route-locator.md). `PathPattern` gives `/users/{id}`.

### Complexity

- Typical cost: O(number of path segments). Each step is one `HashMap` lookup.
- Worst case: the search visits each trie node at most once, because each node has one parent. The cost does not depend on the number of routes that do not share a prefix with the request path.
- `match` does not allocate objects other than the `Optional` result, and one folded copy of a segment when the search goes to the variable child of a node with literal children.

### Thread safety

`insert` is not thread-safe. A component builds a trie once, then shares it read-only. A rebuild creates a new trie and replaces the old reference with a `volatile` write ([017](017-indexed-route-lookup.md), [024](024-authorization-manager.md)). `match` is safe for concurrent use after a safe publication.

## Acceptance criteria

- [x] `PathTrie` is in `openapi-gateway-core` and has no dependency on Spring Cloud Gateway or Spring WebFlux.
- [x] `match` returns the value of a literal pattern before the value of a variable pattern at the same position.
- [x] `match` backtracks to the variable child when the literal branch has no value for the path and method.
- [x] `DELETE /users/me` matches `/users/{id}` when `/users/me` declares only `GET`.
- [x] `GET /users/ME`, `GET /users/Me`, and `DELETE /users/ME` match nothing when `/users/me` and `/users/{id}` exist. A case variant with the Kelvin sign or the dotless `i` also matches nothing.
- [x] `/users` and `/users/` give the same result.
- [x] `match` returns empty for a path with no matching pattern and for a known path with an unknown method.
- [x] `match` returns empty for a path with an empty, `.`, or `..` segment, for example `/users//` or `/users/../me`.
- [x] A failed `insert` creates no node and stores no value, also for a new method that comes before a duplicate method.
- [x] `insert` throws `IllegalArgumentException` for `{*var}`, `{id:\d+}`, `**`, and `{name}.json`.
- [x] `insert` throws `IllegalArgumentException` when the same normalized pattern and method are inserted twice, for example `/users/{id}` and `/users/{userId}` with `GET`.
- [x] The differential test finds no difference between `PathTrie` and the reference matcher for at least 10,000 random requests.

## Test plan

- Unit tests (`PathTrieSegmentsTest`, `PathTrieMatchTest`): normalization table, literal priority, backtracking, method mismatch, root path `/`, deep paths, insert errors.
- Differential test (`PathTrieDifferentialTest`): generate random route sets with literal and variable segments and random requests. Compare the trie result with a reference matcher in test code. The reference matcher sorts patterns by the order rule of [013](013-oas-route-locator.md) and scans them linearly. Use a fixed seed and print the seed on failure. The generator excludes the overlapping patterns listed in "Open questions".
  - Implementation: 100 route sets with up to 30 routes (segments `a`, `b`, `c`, or a variable; 1 to 3 segments; methods `GET`, `POST`, `DELETE`), and 200 requests for each set (20,000 requests, segments `a` to `d` and the empty segment, 0 to 4 segments). The reference matcher does not match a variable to an empty segment, the same as Spring `PathPattern`. Seed `20261112`.
  - A mutation check proved the test: without backtracking in `find`, the test fails in the first route set.
- Concurrency test: several threads call `match` on a shared trie and get the expected results.

## Delivery

One PR. Main files: `PathTrie.java` (about 150 lines), tests (about 250 lines).

Decision (2026-11-12): `route-collision` ([007](007-route-model.md)) rejects overlapping patterns, also across files. The only overlap that validated specs can contain is a path without variables that specializes a template in the same file. The path without variables has order 0, and the trie also prefers it, so both select the same operation. Two templates that overlap (for example `/a/{x}/c` and `/a/{id}/{y}`) both have order 100. Spring Cloud Gateway selects between them by registration order, and the trie can select the other one. `route-collision` therefore rejects them, also in the same file. With this rule, the trie and Spring Cloud Gateway always agree.

## Open questions

- Mixed segments such as `/files/{name}.json` are valid in OAS and in Spring `PathPattern`. Does `path-pattern-supported` ([007](007-route-model.md)) reject them? This spec rejects them in `insert` until the decision is made.
- Should the caller give decoded or encoded segments (for example `%20`)? The decision belongs to the callers in [017](017-indexed-route-lookup.md) and [024](024-authorization-manager.md). It must be the same in both callers.
- Empty segments, for example `/users//42`, are not normalized, and `match` does not match them. Confirm that the gateway rejects or normalizes them before the lookup ([013](013-oas-route-locator.md)).

## References

- [007 — Route model](007-route-model.md)
- [013 — OAS route locator](013-oas-route-locator.md)
- [016 — Routing benchmark](016-routing-benchmark.md)
- [017 — Indexed route lookup](017-indexed-route-lookup.md)
- [024 — Authorization manager](024-authorization-manager.md)
- Spring Framework `PathPattern` documentation.
