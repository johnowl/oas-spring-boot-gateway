# 007 — Route model

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 2 — OAS core |
| Depends on | [005](005-validation-engine.md), [006](006-x-gateway-extension.md) |
| Modules | `openapi-gateway-core` |

## Summary

This spec adds the `GatewayRoute` model and `GatewayRouteBuilder` to `openapi-gateway-core`. The builder converts each valid OAS file into routes: public path pattern, HTTP methods, backend address, path rewrite values, order, response timeout, and audience. The model uses no Spring Cloud Gateway classes, so the CLI can build and check the same routes as the gateway. This spec also adds three contract rules: `path-pattern-supported`, `route-collision`, and `route-id-unique`. They make sure that the gateway can register all routes and that each request matches only one operation.

## Motivation

The gateway exposes only operations that OAS files document. The route locator in [013](013-oas-route-locator.md) converts routes into Spring Cloud Gateway `RouteDefinition` objects. If the conversion logic lived only in the gateway, the CLI could not detect two problems before a merge:

- A path that Spring `PathPattern` does not support, or that has a different meaning there, for example `/files/**`.
- Two OAS files that expose the same method and path. For example, `GET /orders-api/orders/{id}` and `GET /orders-api/orders/{orderId}` in two files. Only one route can win, and the result depends on the load order.

## Scope

### In scope

- `GatewayRoute` record and `GatewayRouteBuilder`.
- Rules for grouping methods, order, prefix stripping, and base path.
- Rules `path-pattern-supported`, `route-collision`, and `route-id-unique`, with fixtures.
- The `routes` field of `ValidationReport` ([005](005-validation-engine.md)).

### Out of scope

- Conversion to `RouteDefinition` and registration in the gateway. See [013](013-oas-route-locator.md).
- Access rules and the security index. See [018](018-authorization-extension.md) and [024](024-authorization-manager.md).
- The path trie and indexed route lookup. See [015](015-path-trie.md) and [017](017-indexed-route-lookup.md).

## Design

Package: `com.johnowl.gateway.core.route`.

### `GatewayRoute`

```java
public record GatewayRoute(
        String id,
        URI upstream,                  // x-gateway.upstream
        String path,                   // public path pattern = prefix + OAS path
        Set<String> methods,           // uppercase, sorted, for example DELETE, GET
        int stripPrefix,               // number of segments in x-gateway.prefix; 0 if no prefix
        String prefixPath,             // x-gateway.upstream-base-path; null if not set
        int order,                     // 0 for literal paths, 100 for paths with variables
        Integer responseTimeoutMillis, // x-gateway-response-timeout; null if not set
        String audience) { }           // x-gateway.audience; null if not set
```

### Example

Input:

```yaml
x-gateway:
  upstream: http://orders-service:8080
  prefix: /orders-api
  upstream-base-path: /v1
paths:
  /orders/{id}:
    get: { ... }
    delete: { ... }
  /orders/export:
    get:
      x-gateway-response-timeout: 30000
```

Output:

| `path` | `methods` | `stripPrefix` | `prefixPath` | `order` | `responseTimeoutMillis` |
|---|---|---|---|---|---|
| `/orders-api/orders/{id}` | `DELETE, GET` | 1 | `/v1` | 100 | null |
| `/orders-api/orders/export` | `GET` | 1 | `/v1` | 0 | 30000 |

A request `GET /orders-api/orders/42` goes to `http://orders-service:8080/v1/orders/42`. The gateway removes one segment (`/orders-api`) and adds `/v1`.

### Builder rules

`GatewayRouteBuilder.build(SpecFile spec)` returns `List<GatewayRoute>`.

| Rule | Behavior |
|---|---|
| Public path | `prefix` + OAS path. Without a prefix, the public path is the OAS path. The OAS path `/` with a prefix gives the prefix alone: `/orders-api`, not `/orders-api/`. |
| Grouping | One route for each OAS path. The route contains all methods of that path. |
| Split | If the operations of one path have different `x-gateway-response-timeout` values, the builder creates one route for each operation. An operation without the extension counts as a different value. |
| Order | `0` if the public path has no `{variable}` segment. `100` if it has at least one. `/users/me` therefore wins over `/users/{id}`. |
| `stripPrefix` | Number of segments of `prefix`. `/orders-api` gives 1. `/a/b` gives 2. |
| Methods | Only the eight OAS operation methods: `GET`, `PUT`, `POST`, `DELETE`, `OPTIONS`, `HEAD`, `PATCH`, `TRACE`. |
| Sort | Routes are sorted by `path`, then by the first method. The output is the same on each run. |
| No valid `x-gateway.upstream` | The builder returns no routes for the file. This is the case when `upstream` is missing, is not text, or is not an absolute URI. The builder never throws for this value. The CONTRACT rule `x-gateway-schema` ([006](006-x-gateway-extension.md)) reports the file, and the gateway always runs it. |

The builder expects a file without `CONTRACT` violations. The engine fills `ValidationReport.routes` only when the report has no `ERROR` violation. Otherwise, the list is empty. The engine sorts the routes of all files by `path`, then by `id`.

`ValidationReport` keeps a constructor with only the violations. It creates a report without routes.

### Route id

Proposed format, to confirm (see "Open questions"):

| Case | Format | Example |
|---|---|---|
| Grouped route | `<file-name>:<OAS path>` | `orders:/orders/{id}` |
| Split route | `<file-name>:<METHOD>:<OAS path>` | `orders:GET:/orders/export` |

`<file-name>` is the file name without the directory and the extension.

### Rules

| Id | Kind | Category | Severity | Checks |
|---|---|---|---|---|
| `path-pattern-supported` | `OperationRule` | CONTRACT | ERROR | The OAS path uses only literal segments and whole-segment variables. This syntax is a subset of the Spring `PathPattern` syntax, with the same meaning. |
| `route-collision` | `CrossSpecRule` | CONTRACT | ERROR | No request can match two operations with the same method, in the same file or in different files. Exception: in one file, a path without variables may specialize a template. |
| `route-id-unique` | `CrossSpecRule` | CONTRACT | ERROR | No two routes of all files have the same id. |

`prefix-unique` is not a rule. Two files MAY use the same prefix if their paths do not collide.

#### `path-pattern-supported`

OAS path templates such as `/users/{id}` are valid `PathPattern` syntax. The class `PathSyntax` (package `com.johnowl.gateway.core.route`) accepts only this grammar:

- The path starts with `/`. The path `/` alone is valid.
- Each segment is not empty. A trailing slash or a double slash is therefore an error.
- Each segment is a literal or one whole variable `{name}`.
- A literal segment has only the RFC 3986 unreserved characters `[A-Za-z0-9._~-]`. A literal segment is not `.` or `..`. Other characters have a different meaning in the gateway. Spring Cloud Gateway splits the `Path` predicate at `,`. `PathPattern` reads `?` as "any one character". `PathPattern` compares literals with the decoded request path, so `%` never matches, and it removes `;` parameters before matching. Control characters and spaces are also rejected.
- A variable name matches `[A-Za-z_][A-Za-z0-9_-]*`. This is the ASCII part of the names that `PathPattern` accepts. `PathPattern` rejects `.` in a name and a digit as the first character.
- Each variable name is used only once in a path. `PathPattern` rejects `/a/{id}/b/{id}`.

The rule rejects this syntax:

| Syntax | Example | Message |
|---|---|---|
| Capture-all variable | `/files/{*path}` | `Path '/files/{*path}' uses unsupported syntax '{*...}'.` |
| Regex variable | `/users/{id:\d+}` | `Path '/users/{id:\d+}' uses unsupported syntax '{...:regex}'.` |
| Multi-segment wildcard | `/files/**` | `Path '/files/**' uses unsupported syntax '**'.` |
| Single wildcard | `/files/*` | `Path '/files/*' uses unsupported syntax '*'.` |
| Unbalanced braces | `/users/{id` | `Path '/users/{id' cannot be parsed: unbalanced '{' or '}'.` |
| Variable inside a segment | `/files/{name}.json` | `Path '/files/{name}.json' uses unsupported syntax: a variable must be a whole segment.` |
| Invalid variable name | `/items/{item.id}` | `Path '/items/{item.id}' has an invalid variable name 'item.id'. Use letters, digits, '_' and '-', and do not start with a digit.` |
| Empty segment | `/orders/`, `/a//b` | `Path '/orders/' has an empty segment. Remove the trailing or double slash.` |
| Variable name used twice | `/a/{id}/b/{id}` | `Path '/a/{id}/b/{id}' uses the variable name 'id' twice.` |
| Character that is not unreserved | `/x,/orders/{id}`, `/orders/?xport`, `/files/report%202024` | `Path '/orders/?xport' has the unsupported character '?'. Literal segments use only letters, digits, '-', '.', '_' and '~'.` A character that is not printable ASCII appears as `\uXXXX`. |
| Dot segment | `/a/../b` | `Path '/a/../b' has the dot segment '..'.` |

Pointer: the operation, for example `/paths/~1files~1{*path}/get`. swagger-parser accepts all these paths, so the rule is necessary.

Decision (2026-11-12): `openapi-gateway-core` does not depend on `spring-web`. The rule uses the grammar above instead of `PathPatternParser`. The test `PathSyntaxTest` uses `spring-web` in test scope only. It proves that each accepted path parses in `PathPatternParser` and matches the same requests. The Maven Enforcer rule of [004](004-oas-loading.md) bans Spring in every scope of the main classpath, so only tests can use it.

#### `route-collision`

Normalization replaces each `{variable}` with `{}`. `/users/{id}` and `/users/{userId}` both become `/users/{}`, so they collide.

Decision (2026-11-12): the rule also reports **overlapping** operations. Two operations with the same method and the same number of segments overlap when, at each segment, the literals are equal or at least one segment is a variable. Then at least one request matches both. One exception: in the same file, a path without variables may specialize a template (`/users/me` and `/users/{id}`). This is a common and intentional design. The path without variables has order 0, so it wins in Spring Cloud Gateway and in the path trie ([015](015-path-trie.md)). The exception does not apply across files. It also does not apply when both paths have variables, even if one path is more specific (`/a/{x}/c` and `/a/{id}/{y}`). Both routes have order 100, so Spring Cloud Gateway selects by registration order, and the trie can select the other operation.

| Operation A | Operation B | Collision |
|---|---|---|
| `GET /orders-api/orders/{id}` | `GET /orders-api/orders/{orderId}` | Yes (same path) |
| `GET /orders-api/orders/{id}` | `DELETE /orders-api/orders/{id}` | No (different method) |
| `GET /users/me` | `GET /users/{id}`, same file | No (literal specialization) |
| `GET /users/me` | `GET /users/{id}`, different files | Yes (overlap) |
| `GET /a/{x}/c` | `GET /a/b/{y}`, same file or not | Yes (overlap: both match `/a/b/c`) |
| `GET /a/{x}/c` | `GET /a/{id}/{y}`, same file or not | Yes (overlap of two templates: both match `/a/x/c`) |
| File A: prefix `/a`, path `/b` | File B: no prefix, path `/a/b` | Yes (same public path) |

The rule reports one violation for each operation in a collision. The message names the other file and pointer. Examples:

- `GET /orders-api/orders/{} is also declared in payments.yaml at /paths/~1orders~1{orderId}/get.`
- `GET /a/{}/c overlaps GET /a/b/{} in a.yaml at /paths/~1a~1b~1{y}/get. Both match /a/b/c.`

If more than two operations collide, the message adds `and in <n> more place(s)`. If an operation has the same path as another operation and also overlaps other operations, the message names the same path and adds `It also overlaps <n> other operation(s).` The rule skips operations that fail `path-pattern-supported`.

Performance: the rule compares an operation only with operations of the same method, the same number of segments, and the same first segment (or a variable first segment). The first segment is usually the prefix of the file, so the cost stays low for many files. The target is 10,000 routes.

The comparison is case-sensitive, like `PathPattern` matching. `/Orders` and `/orders` do not collide.

#### `route-id-unique`

Example: `orders.yaml` exists in two directories with different prefixes, and both files declare `/orders/{id}`. Both routes get the id `orders:/orders/{id}`. The rule reports one violation for each duplicate, at the pointer of the path item. Message: `Route id 'orders:/orders/{id}' is also used by team-b/orders.yaml. Rename one of the files.`

The class `XGatewaySettings` reads the `x-gateway` object for the builder and for these rules. It never throws. For a file without a valid `upstream`, it returns no settings, and both rules skip only that file. `x-gateway-schema` reports the file.

## Acceptance criteria

- [x] The builder produces the two routes of the example with the listed values.
- [x] A path with `GET` and `DELETE` and no timeout produces one route with both methods.
- [x] A path with `GET` (timeout `30000`) and `DELETE` (no timeout) produces two routes.
- [x] `/users/me` has order `0`, and `/users/{id}` has order `100`.
- [x] A file without `prefix` produces routes with `stripPrefix` `0`. A file without `upstream-base-path` produces routes with `prefixPath` `null`.
- [x] Two builder runs on the same input produce equal lists in the same order.
- [x] `GatewayRoute` and `GatewayRouteBuilder` import no class from `org.springframework.cloud` or `org.springframework.web.reactive`.
- [x] `path-pattern-supported` reports each example of its syntax table.
- [x] `route-collision` reports both operations for each "Yes" row of its table, and nothing for each "No" row.
- [x] `route-id-unique` reports duplicate ids from two files with the same file name.
- [x] `ValidationReport.routes` is empty when the report has an `ERROR` violation.
- [x] A file with an invalid `upstream` gives no routes. The engine returns a report and does not throw, also with a rule set without `x-gateway-schema`.
- [x] Each rule has fixtures and passes `RuleFixtureTest`.
- [x] `path-pattern-supported` accepts only the grammar above. A test proves that each accepted path parses in `PathPatternParser` and matches the same requests.
- [x] An invalid `upstream` in one file does not stop `route-collision` and `route-id-unique` for the other files.
- [x] The OAS path `/` with a prefix has the prefix as its public path.

## Test plan

- Unit tests `GatewayRouteBuilderTest`: grouping, split, order, `stripPrefix`, `prefixPath`, missing optional fields, sort order.
- Fixture tests for `path-pattern-supported` (single file).
- Fixture tests for `route-collision` and `route-id-unique`, with the multi-file fixture format of [005](005-validation-engine.md).
- Unit tests `PathSyntaxTest` (each row of the syntax table, and the `PathPatternParser` comparison) and `RouteRulesTest` (messages).
- Unit test that the engine fills `routes` only without `ERROR` violations.

## Delivery

Two PRs:

1. `GatewayRoute`, `GatewayRouteBuilder`, the `routes` field of `ValidationReport`, and builder tests. Estimate: 300 changed lines.
2. Rules `path-pattern-supported`, `route-collision`, and `route-id-unique`, with fixtures. Estimate: 300 changed lines without fixtures.

## Open questions

- What is the route id format? The format in this spec is a proposal.
- The rule rejects `{name}` inside a segment, for example `/files/{name}.json`. `PathPattern` supports it, but the path trie ([015](015-path-trie.md)) matches whole segments. Must a later spec allow it?
- The rule rejects a trailing slash. Must a later spec allow it and normalize it?

## References

- [005 — Validation engine](005-validation-engine.md)
- [006 — x-gateway extension](006-x-gateway-extension.md)
- [013 — OAS route locator](013-oas-route-locator.md)
- [015 — Path trie](015-path-trie.md)
- [017 — Indexed route lookup](017-indexed-route-lookup.md)
- Spring Framework `PathPattern`: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/util/pattern/PathPattern.html
