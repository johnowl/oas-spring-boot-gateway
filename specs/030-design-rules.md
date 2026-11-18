# 030 — Design rules

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 8 — Best-practice rules |
| Depends on | [005](005-validation-engine.md), [027](027-rule-configuration.md) |
| Modules | `openapi-gateway-core`, `openapi-gateway-cli` (rule options) |

## Summary

This spec adds seven best-practice rules in the DESIGN, ERRORS, and SECURITY categories. They check paths, success responses, GET request bodies, and the `Location` header on `201`. They also check unused components, error media types, and HTTPS server URLs. These rules give all APIs behind the gateway the same basic design. All seven rules are configurable with [027](027-rule-configuration.md) and can be suppressed with [028](028-suppressions-and-baseline.md).

## Motivation

Each team designs its API alone. Without shared checks, clients see verbs in paths, error bodies in many formats, and operations without a documented success response. A spec with unused components is harder to review. An `http://` server URL in a spec invites clients to send credentials without TLS.

## Scope

### In scope

| Id | Category | Default severity | Rule type |
|---|---|---|---|
| `path-no-verbs` | DESIGN | warn | `OperationRule` |
| `operation-success-response` | DESIGN | error | `OperationRule` |
| `get-no-request-body` | DESIGN | error | `OperationRule` |
| `post-201-location-header` | DESIGN | info | `OperationRule` |
| `components-unused` | DESIGN | warn | `SpecRule` |
| `error-response-problem-json` | ERRORS | warn | `OperationRule` |
| `servers-https-only` | SECURITY | error | `SpecRule` |

### Out of scope

- A configurable verb list for `path-no-verbs` (see "Open questions").
- Components in external files referenced with `$ref` to another file.
- Request bodies on `HEAD` and `DELETE`.

## Design

The operation pointer and the literal segment have the definitions of [029](029-naming-rules.md). `$ref` targets are resolved before a check, unless the rule says otherwise.

### `path-no-verbs`

Checks that no word of the OAS path outside its variables is a word from this fixed list:

```text
add, create, delete, do, edit, execute, fetch, find, get, insert, list,
make, modify, patch, post, put, read, remove, retrieve, save, set, update
```

The rule removes each variable (`{...}`) from the path, also a variable inside a segment. It splits the rest into words on each character that is not a letter or a digit (for example `/`, `-`, `_`, `.`, and `:`), and on each change from a lowercase letter to an uppercase letter. It compares each word with the list, case-insensitive. A word matches only if it is equal to a list entry.

This rule does not use the literal segment of [029](029-naming-rules.md). A segment with a variable is not a literal segment, but the rule checks its text outside the variable. So `/orders/{id}:delete` and `/orders/getAll.{format}` fail (finding QA-64-2). The rule `path-pattern-supported` of [007](007-route-model.md) also rejects these paths.

```yaml
# Bad
/get-orders:
/orders/{id}/delete:
/createOrder:
/orders/{id}:delete:
# Good
/orders:
/orders/{id}:
/orders/{id}/cancellation:
```

- Pointer: the operation pointer. One violation per operation. The message names the matched words.
- Edge cases:
  - `/settings` passes: `settings` is not equal to `set`.
  - `/orders/{id}/status-update` fails, because `update` is a word. A team suppresses it if the word is a noun.
  - `search`, `cancel`, `approve`, and `run` are not in the list. Action resources such as `/orders/search` pass.
  - The rule never checks the name of a variable. `/targets/{getTarget}` passes.

### `operation-success-response`

Checks that `responses` has at least one key from `200` to `299`, or the key `2XX`.

```yaml
# Bad
responses:
  default: { $ref: "#/components/responses/Problem" }
# Good
responses:
  "200": { description: The order }
  default: { $ref: "#/components/responses/Problem" }
```

- Pointer: `<operation pointer>/responses`.
- Edge cases: `default` alone fails. An operation that only returns a redirect (`302`) fails. A team suppresses it with a reason. OAS 3.1 permits an operation without `responses`, but the loader of [004](004-oas-loading.md) rejects it with `oas-parse`, in OAS 3.0 and 3.1. The rule never sees such an operation (finding QA-64-1). The range key is exactly `2XX`, as in the OAS specification.

### `get-no-request-body`

Checks that a GET operation has no `requestBody`.

```yaml
# Bad
get:
  requestBody: { content: { application/json: { schema: { type: object } } } }
# Good
post:
  requestBody: { content: { application/json: { schema: { type: object } } } }
```

- Pointer: `<operation pointer>/requestBody`.
- Edge case: OAS 3.1 permits a GET request body. The rule still reports it, because many proxies and clients drop the body.

### `post-201-location-header`

Checks that a POST operation with a `201` response declares the header `Location` in that response. The header name comparison is case-insensitive.

```yaml
# Bad
"201": { description: Created }
# Good
"201":
  description: Created
  headers:
    Location: { schema: { type: string, format: uri } }
```

- Pointer: `<operation pointer>/responses/201`.
- Edge cases: a POST without a `201` response passes. A `201` on PUT is not checked. If the `201` response is a `$ref`, the rule checks the headers of the referenced response.

### `components-unused`

Checks that each component is reachable from the operations of the same file.

A component is **used** when a local `$ref` (`#/components/<type>/<name>`) reaches it, starting from any place outside `components` (for example `paths` and `webhooks`). The rule follows `$ref`s inside components transitively. A string value in `discriminator.mapping` counts as a `$ref`. A used schema with a `discriminator` also uses each schema of `components/schemas` that references it in its `allOf` (finding QA-65-2). OAS calls this the implicit mapping: the discriminator value is the schema name. The rule reads the raw document (`SpecFile` raw `JsonNode` from [004](004-oas-loading.md)), because the parser replaces resolved references.

Checked component types: `schemas`, `responses`, `parameters`, `examples`, `requestBodies`, `headers`, `links`, `callbacks`, and `pathItems`. The rule does not check `securitySchemes`, because `security` references them by name.

```yaml
# Bad: Order is used only by OrderList, and OrderList is not used.
components:
  schemas:
    OrderList: { type: array, items: { $ref: "#/components/schemas/Order" } }
    Order: { type: object }
# Good: a response in paths references OrderList.
```

- Pointer: `/components/<type>/<name>`, for example `/components/schemas/Order`.
- Edge cases: in the bad example both `OrderList` and `Order` are unused. A schema that references only itself is unused. References to other files are ignored.
- A reference into a component, for example `#/components/schemas/OrderList/items`, uses the component `OrderList`.
- A `discriminator.mapping` value without `#` and without `/` is a schema name (OAS, Discriminator Object). The value `Dog` uses `#/components/schemas/Dog`.
- Implicit mapping: `Pet` has a `discriminator` and is used. `Cat` has `allOf: [{ $ref: "#/components/schemas/Pet" }]`. Then `Cat` is used, and so are the components that `Cat` references. The `allOf` entry MUST reference the schema itself: `#/components/schemas/Pet/properties/kind` does not count. A schema without `discriminator` does not use the schemas that reference it in `allOf`.

### `error-response-problem-json`

Checks the media types of error responses. Error responses are the keys `400` to `599`, `4XX`, `5XX`, and `default`. Each media type under `content` MUST be in the option `media-types` (default `[application/problem+json]`, see [027](027-rule-configuration.md)).

The comparison is case-insensitive and ignores media type parameters. `application/problem+json; charset=utf-8` matches `application/problem+json`.

```yaml
# Bad
"404":
  content: { application/json: { schema: { $ref: "#/components/schemas/Error" } } }
# Good
"404":
  content: { application/problem+json: { schema: { $ref: "#/components/schemas/Problem" } } }
```

- Pointer: `<operation pointer>/responses/<code>/content/<media type>`, with `/` escaped as `~1`. One violation per media type.
- Edge cases: an error response without `content` passes. A response with two media types where one is not accepted produces one violation.
- If the error response is a `$ref`, the rule checks the media types of the referenced response. The pointer stays below the operation, so that an operation-level `x-deviation` can suppress the violation. The CLI maps the pointer to the nearest line that exists in the file.
- The range keys are exactly `4XX` and `5XX`, as in the OAS specification.

### `servers-https-only`

Checks that each absolute server URL uses the `https` scheme. The rule checks each Server Object of the file (finding SEC-65-1):

- each element of a `servers` array, at any depth: root, path items, operations, `webhooks`, callbacks, and `components` (`pathItems`, `callbacks`);
- the `server` field of each Link Object, in responses and in `components/links`.

The rule walks the raw tree without recursion, with the same helper (`RawTree`) as `components-unused`. A `servers` array inside an example value or an extension is also checked. A team accepts such a violation with the baseline of [028](028-suppressions-and-baseline.md).

Before the check, the rule replaces each server variable with its `default` value. Then it reads the scheme as a URL parser does (WHATWG URL Standard): it removes each tab, CR, and LF, and removes control characters and spaces at the start and the end. So `" http://api.example.com"` and `"ht<tab>tp://api.example.com"` fail. The scheme comparison is case-insensitive.

A URL with a C0 control character (`U+0000` to `U+001F`), or with whitespace at the start or the end, also fails, also with `https` or without a scheme. Clients can read such a URL as another URL. The message shows each unsafe character as a Java escape, for example `\u0009`: control characters, Unicode line separators, and bidirectional controls (`PrintableText.printable` of [004](004-oas-loading.md), "Text in messages"). The message shows at most 200 characters of each URL, followed by `...` (finding QA-67-4).

```yaml
# Bad
servers:
  - url: http://api.example.com
# Good
servers:
  - url: https://api.example.com
```

- Pointer: the `url` field, as an RFC 6901 pointer, for example `/servers/0/url`, `/paths/~1orders/get/servers/0/url`, `/webhooks/orderCreated/post/servers/0/url`, or `/components/links/Self/server/url`.
- Edge cases: a relative URL (`/api`) passes. `http://localhost:8080` fails. `servers` does not change routing: the gateway uses `x-gateway.upstream`.
- Each scheme other than `https` fails, for example `ftp` and `wss`. A URL that starts with `//` (protocol-relative) has no scheme, so it is relative and passes (see "Open questions").
- A variable without a `default` stays as it is. OAS requires a `default` for each variable.
- The rule reads the raw document, so the pointer is the `url` field in the file.

### Implementation

The rules are in `com.johnowl.gateway.core.rules`, with the metadata helper `BestPracticeRule` of [029](029-naming-rules.md). All rules are configurable ([027](027-rule-configuration.md)). The helper `PathSegments` splits a path into literal segments, and a path or a segment into words. `path-kebab-case` ([029](029-naming-rules.md)) uses the literal segments. `path-no-verbs` uses the words of the path outside its variables. The helper `ComponentReferences` resolves a response `$ref` in the model of swagger-parser (named `ResponseReferences` before [031](031-documentation-rules.md)). The helper `MediaTypes` removes the parameters of a media type. Messages:

- `Path '/get-orders' contains verbs: get. Use nouns, and let the HTTP method name the action.`
- `Operation has no success response. Add a response from 200 to 299, or 2XX.`
- `GET operation has a request body. Use query parameters, or use POST.`
- `The 201 response has no Location header. Declare Location with the URL of the new resource.`
- `Component 'schemas/Order' is not used. Reference it from an operation, or remove it.`
- `Error response '404' has media type 'application/json'. Use application/problem+json.`
  The message shows the code and the media type with `PrintableText.printable` ([031](031-documentation-rules.md)): unsafe characters as Java escapes, and at most 200 characters (findings QA-66-3 and SEC-66-4). The pointer keeps the full media type.
- `Server URL 'http://api.example.com' does not use https. Clients can send credentials without TLS.` If the URL has variables, the message also shows the URL with the default values.
- `Server URL 'https://api.example.com ' has a control character, or whitespace at the start or the end. Clients can read it as another URL.`

The constant `PathItemMethods.NAMES` (package `com.johnowl.gateway.core.spec`) lists the operation fields of a path item: `get`, `put`, `post`, `delete`, `options`, `head`, `patch`, and `trace`. `SecurityRequirements` and `Deviations` ([028](028-suppressions-and-baseline.md)) use it (finding QA-65-5). `servers-https-only` walks the whole tree, so it needs no method list.

Rule options: `ErrorResponseProblemJsonRule` implements `RuleWithOptions` ([027](027-rule-configuration.md)). The method `withOptions(RuleOptions)` returns a rule with the configured media types. `RuleConfig.configure(rules)` calls it for each configurable rule with options. `configure` is a method of `SeverityPolicy` ([005](005-validation-engine.md)), and the engine calls it in its constructor. So the options always reach the rules, also when a caller creates the engine directly with a `RuleConfig` (finding QA-65-3). The CLI does not call `configure` itself. Before this change, the loader accepted the option, but the rule did not use it.

## Acceptance criteria

- [x] Each of the seven rules has fixtures `valid.yaml`, `invalid.yaml`, and `expected.txt`, and the fixture test passes.
- [x] Each rule has the id, category, and default severity of the table in "Scope".
- [x] `/get-orders`, `/orders/{id}/delete`, and `/createOrder` produce `path-no-verbs`. `/orders`, `/settings`, and `/orders/search` do not.
- [x] `/orders/{id}:delete` and `/orders/getAll.{format}` produce `path-no-verbs`. `/targets/{getTarget}` does not.
- [x] An operation without `responses` produces `oas-parse`, in OAS 3.0 and 3.1.
- [x] An operation with only a `default` response produces `operation-success-response`. An operation with `2XX` does not.
- [x] A GET operation with `requestBody` produces `get-no-request-body` at `<operation pointer>/requestBody`.
- [x] A POST with a `201` response without `Location` produces `post-201-location-header`. A header named `location` passes.
- [x] A schema used only by an unused schema produces `components-unused` for both schemas.
- [x] A schema used only through `discriminator.mapping` does not produce `components-unused`.
- [x] A schema whose `allOf` references a used schema with a `discriminator` does not produce `components-unused`. A schema whose `allOf` references a schema without `discriminator` is not used by it.
- [x] An unused `securitySchemes` entry does not produce `components-unused`.
- [x] A `404` response with `application/json` produces `error-response-problem-json`. With `application/problem+json; charset=utf-8` it does not.
- [x] With option `media-types: [application/vnd.error+json]`, `application/vnd.error+json` passes and `application/problem+json` fails.
- [x] An engine created directly with a `RuleConfig` that sets `media-types` uses the option, without a call to `configure` by the caller.
- [x] `http://api.example.com` produces `servers-https-only` at `/servers/0/url`. `https://...` and `/api` do not.
- [x] A server URL `{scheme}://api.example.com` with variable default `http` produces `servers-https-only`.
- [x] `" http://..."`, `"ht<tab>tp://..."`, and `"<U+0001>http://..."` produce `servers-https-only`. `"https://... "`, `" /api"`, and a URL with a line feed also do. `//api.example.com` does not.
- [x] `servers-https-only` reports `http` server URLs in a path item of `components/pathItems` (a same-file `$ref` to it is an `oas-parse` error, see [004](004-oas-loading.md)), in `webhooks`, in callbacks (also in `components/callbacks`), and in the `server` of a Link Object (also in `components/links`).
- [x] The fixture of `servers-https-only` contains `wss://...` (invalid) and `//api.example.com` (valid) (finding QA-65-4).

## Test plan

- Fixture tests for each rule in `openapi-gateway-core/src/test/resources/rules/<rule-id>/` (`valid.yaml`, `invalid.yaml`, `expected.txt`). Each fixture contains the good examples, the bad examples, and the edge cases above.
- Unit tests for the word split helper of `path-no-verbs` (kebab-case, snake_case, camelCase, dots, colons, variables inside a segment).
- Unit tests for the reachability walk of `components-unused` (transitive references, cycles, `discriminator.mapping`, external references).
- Unit test that `error-response-problem-json` reads its option through `RuleOptions` ([027](027-rule-configuration.md)).

## Delivery

Two PRs:

1. `path-no-verbs`, `operation-success-response`, `get-no-request-body`, `post-201-location-header`, the word split helper, and fixtures. Estimate: 250–300 changed lines without fixtures.
2. `components-unused`, `error-response-problem-json`, `servers-https-only`, and fixtures. Estimate: 300–380 changed lines without fixtures.

Each PR adds its rules to the profiles of [027](027-rule-configuration.md).

## Decisions (2026-11-14)

- The rules keep the default severities of the table in "Scope", also the three ERROR rules (`operation-success-response`, `get-no-request-body`, and `servers-https-only`). No CLI version is released yet. The rollout policy of [012](012-cli-distribution.md) and `docs/cli-release-policy.md` starts with the first release (`v0.1.0`), and the rules of that release keep the severity of their spec. This is the same decision as for `operation-id-required` in [029](029-naming-rules.md). If the rules ship after `v0.1.0`, the three ERROR rules MUST start as WARN, and the release notes MUST give the version in which they become ERROR.

## Review findings

| Finding | Resolution in this spec |
|---|---|
| QA-64-1 | "`operation-success-response`": the loader rejects an operation without `responses`. A test pins this. The rule keeps its null check for a model that does not come from the loader. |
| QA-64-2 | "`path-no-verbs`": the rule checks the path outside its variables, also inside a segment. This differs from the literal segment of [029](029-naming-rules.md). |
| SEC-65-1, QA-65-1 | "`servers-https-only`": each Server Object at any depth, also in `webhooks`, callbacks, components, and links. Control characters and whitespace at the start or the end. The scheme as a URL parser reads it. Protocol-relative URLs stay relative ("Open questions"). |
| QA-65-2 | "`components-unused`": the implicit mapping of a discriminator through `allOf`. |
| QA-65-3 | "Implementation" (rule options): the engine calls `SeverityPolicy.configure`. Spec [005](005-validation-engine.md) and [027](027-rule-configuration.md). |
| QA-65-4 | The fixture of `servers-https-only`: `wss://` and `//api.example.com`. |
| QA-65-5 | "Implementation": the constant `PathItemMethods.NAMES`. |
| QA-67-1 | "Acceptance criteria": a path cannot reference a path item of `components/pathItems`, because the loader rejects every path item `$ref` ([004](004-oas-loading.md), findings SEC-66-1 and SEC-67-1). `servers-https-only` still checks the component. |
| QA-67-4 | "`servers-https-only`": the message uses the shared helper `PrintableText.printable`. |
| QA-66-3, SEC-66-4 ([031](031-documentation-rules.md)) | "Implementation": the message of `error-response-problem-json` shows the code and the media type with `PrintableText.printable`. |

## Open questions

- Confirm the verb list of `path-no-verbs`. Should teams be able to extend it with a rule option?
- Should `operation-success-response` also accept `3XX` responses, for redirect endpoints?
- Should `error-response-problem-json` include `default`? This spec includes it.
- Should `error-response-problem-json` also report error responses without `content`?
- Should `servers-https-only` allow `http://localhost`?
- Should `servers-https-only` report a protocol-relative URL (`//api.example.com`)? A client uses the scheme of the page or of the request that loaded the spec, which can be `http`. Today the URL is relative and passes (finding SEC-65-1).
- `components-unused` and `servers-https-only` report outside operations. Operation-level `x-deviation` cannot suppress them (see [028](028-suppressions-and-baseline.md)).

## References

- [004 — OAS loading](004-oas-loading.md)
- [005 — Validation engine](005-validation-engine.md)
- [027 — Rule configuration](027-rule-configuration.md)
- [028 — Suppressions and baseline](028-suppressions-and-baseline.md)
- [029 — Naming rules](029-naming-rules.md)
- RFC 9457, Problem Details for HTTP APIs: <https://www.rfc-editor.org/rfc/rfc9457>
- OpenAPI Specification: <https://spec.openapis.org/oas/latest.html>
