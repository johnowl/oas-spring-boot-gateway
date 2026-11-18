# 029 — Naming rules

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 8 — Best-practice rules |
| Depends on | [005](005-validation-engine.md), [027](027-rule-configuration.md) |
| Modules | `openapi-gateway-core` |

## Summary

This spec adds four best-practice rules in the NAMING category. They check that each operation has a camelCase `operationId`, and that OpenAPI Specification (OAS) paths use lowercase kebab-case segments without a trailing slash. Consistent names make client code generation predictable and make the public API easier to read. All four rules are configurable with [027](027-rule-configuration.md) and can be suppressed with [028](028-suppressions-and-baseline.md).

## Motivation

Code generators use `operationId` as the method name. A missing or duplicate `operationId` gives generated names such as `ordersIdGet1`, or a generation failure. Mixed path styles (`/orderItems`, `/order_items`, `/order-items`) make the public API inconsistent. A trailing slash creates a second form of the same path that clients can use by mistake.

## Scope

### In scope

| Id | Category | Default severity | Rule type |
|---|---|---|---|
| `operation-id-required` | NAMING | error | `OperationRule` |
| `operation-id-unique` | NAMING | error | `SpecRule` |
| `operation-id-camel-case` | NAMING | warn | `OperationRule` |
| `path-kebab-case` | NAMING | warn | `OperationRule` |
| `path-no-trailing-slash` | NAMING | error | `OperationRule` |

### Out of scope

- Names of parameters, schemas, and properties.
- The public prefix `x-gateway.prefix`. The schema in [006](006-x-gateway-extension.md) already restricts its pattern.
- Uniqueness of `operationId` across spec files (see "Open questions").

## Design

### Common definitions

- **Operation pointer**: the JSON pointer of the operation object, for example `/paths/~1orders~1{id}/get`.
- **Literal segment**: a segment of the OAS path, split on `/`, that does not contain `{`.
- The path rules are `OperationRule`s. They report one violation for each operation of the path, at the operation pointer. This lets a team suppress a path rule on one operation with `x-deviation` ([028](028-suppressions-and-baseline.md)).
- The rules check only the OAS path (the key under `paths`). They do not check the prefix.

### `operation-id-required`

Checks that the operation has an `operationId` that is not empty after trimming white space.

```yaml
# Bad
/orders:
  get:
    summary: List orders
# Good
/orders:
  get:
    operationId: listOrders
```

- Pointer: the operation pointer.
- Edge cases: `operationId: ""` and `operationId: "   "` are violations.

### `operation-id-unique`

Decision (2026-11-12): this rule is not implemented. swagger-parser already reports a duplicate `operationId` (`attribute paths.'/items'(get).operationId is repeated`). The loader turns this message into an `oas-parse` violation ([004](004-oas-loading.md)), which is a CONTRACT ERROR. A file with a duplicate therefore never reaches the rules, also in the gateway. The behavior is stricter than this section describes: a duplicate `operationId` stops the gateway at startup. The text below stays as a record of the original design.

Checks that no two operations in the same spec file have the same `operationId`. The comparison is exact and case-sensitive, as in the OAS specification.

```yaml
# Bad
/orders:
  get: { operationId: getOrders }
/orders/{id}:
  get: { operationId: getOrders }
# Good
/orders:
  get: { operationId: listOrders }
/orders/{id}:
  get: { operationId: getOrder }
```

- Pointer: `<operation pointer>/operationId` of each duplicate after the first one, in document order. The message names the pointer of the first operation.
- Edge cases: operations without `operationId` are not compared. `getOrders` and `GetOrders` are different values for this rule.

### `operation-id-camel-case`

Checks that `operationId` matches `^[a-z][a-zA-Z0-9]*$`.

```yaml
# Bad
operationId: get_orders
operationId: GetOrders
operationId: get-orders
# Good
operationId: getOrders
```

- Pointer: `<operation pointer>/operationId`.
- Edge cases: an operation without `operationId` is not reported by this rule (`operation-id-required` reports it). `getHTTPStatus` passes, because the regular expression does not check how capital letters are grouped.

### `path-kebab-case`

Checks that each literal segment matches `^[a-z0-9]+(-[a-z0-9]+)*$`.

```yaml
# Bad
/orderItems/{id}:
/order_items/{id}:
# Good
/order-items/{id}:
```

- Pointer: the operation pointer.
- Edge cases:
  - Segments with a variable (`{id}`, `{fileName}`) are not checked. Parameter names are out of scope.
  - The empty segment after a trailing slash is not checked. `path-no-trailing-slash` reports it.
  - The root path `/` passes.
  - `/v1` and `/v2` pass. `/v1.2` and `/.well-known` fail. A team suppresses these cases with `x-deviation`.
  - The message names each segment that fails.

### `path-no-trailing-slash`

Checks that the OAS path is `/`, or does not end with `/`.

```yaml
# Bad
/orders/:
# Good
/orders:
```

- Pointer: the operation pointer.
- Edge case: the path `/` passes.
- The CONTRACT rule `path-pattern-supported` ([007](007-route-model.md)) also rejects a trailing slash. Both rules report the same path.

### Implementation

The rules are in `com.johnowl.gateway.core.rules`, with the metadata helper `BestPracticeRule`. Severities: `operation-id-required` ERROR, `operation-id-camel-case` WARN, `path-kebab-case` WARN, `path-no-trailing-slash` ERROR. All are configurable ([027](027-rule-configuration.md)). Messages:

- `Operation has no operationId.`
- `operationId 'get_orders' is not lower camel case. Example: getOrders.`
- `Path '/orderItems/{id}' has segments that are not kebab case: orderItems.`
- `Path '/orders/' ends with '/'. Remove the trailing slash.`

## Acceptance criteria

- [x] Each of the four implemented rules has fixtures `valid.yaml`, `invalid.yaml`, and `expected.txt`, and the fixture test passes.
- [x] Each rule has the id, category, and default severity of the table in "Scope".
- [x] An operation without `operationId` produces exactly one violation: `operation-id-required`.
- [x] `operationId: "  "` produces `operation-id-required`.
- [ ] (Not implemented, see the decision above.) Two operations with `operationId: getOrders` in one file produce one `operation-id-unique` violation, at the second operation.
- [ ] (Not implemented, see the decision above.) The same `operationId` in two different files produces no `operation-id-unique` violation.
- [x] `get_orders`, `GetOrders`, and `get-orders` produce `operation-id-camel-case`. `getOrders` and `getHTTPStatus` do not.
- [x] `/orderItems/{id}` with GET and DELETE produces two `path-kebab-case` violations, one at each operation pointer.
- [x] `/order-items/{orderItemId}` produces no `path-kebab-case` violation.
- [x] `/orders/` produces `path-no-trailing-slash`, and `/` does not.
- [x] An `x-deviation` entry for `path-kebab-case` on the GET operation removes only the GET violation.

## Test plan

- Fixture tests for each rule in `openapi-gateway-core/src/test/resources/rules/<rule-id>/`:
  - `valid.yaml`: all good examples and all passing edge cases.
  - `invalid.yaml`: all bad examples and all failing edge cases.
  - `expected.txt`: the expected pointers, one per line.
- `PathKebabCaseRuleTest`: `/orderItems/{id}` with GET and DELETE gives exactly two violations, and an `x-deviation` entry on the GET operation leaves only the DELETE violation.
- The meta-test from [005](005-validation-engine.md) checks that each rule has fixtures and a unique id.
- Unit tests for the segment split helper (literal segments, variable segments, trailing slash, root path).

## Delivery

One PR. Main files: four rule classes, one shared path segment helper, registration in the rule registry, profile entries ([027](027-rule-configuration.md)), and fixtures. Estimate: 250–350 changed lines without fixtures.

## Decisions (2026-11-12)

- `operation-id-required` stays at severity ERROR. The rollout policy of [012](012-cli-distribution.md) starts with the first release, so it does not apply to the rules of that release. This closes the review finding QA-31-1.

## Open questions

- Should `operation-id-unique` also check across spec files? Generated clients for different specs do not conflict, but [007](007-route-model.md) may derive route ids from `operationId`.
- Should the path rules report once per path at the path item pointer instead of once per operation? This spec chooses once per operation, so that suppressions at operation level work.
- Should `path-kebab-case` allow dots in segments (for example `/v1.2`)?

## References

- [005 — Validation engine](005-validation-engine.md)
- [007 — Route model](007-route-model.md)
- [027 — Rule configuration](027-rule-configuration.md)
- [028 — Suppressions and baseline](028-suppressions-and-baseline.md)
- [032 — CLI `rules` and `explain`](032-cli-rules-and-explain.md)
- OpenAPI Specification, Operation Object (`operationId`): <https://spec.openapis.org/oas/latest.html#operation-object>
