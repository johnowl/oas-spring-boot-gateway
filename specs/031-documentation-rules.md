# 031 — Documentation rules

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 8 — Best-practice rules |
| Depends on | [005](005-validation-engine.md), [027](027-rule-configuration.md) |
| Modules | `openapi-gateway-core` |

## Summary

This spec adds three best-practice rules in the DOCUMENTATION category. They check that each operation has a summary and tags declared at the root of the spec. They also check that JSON request and response bodies have examples. Generated API documentation is only as good as the OpenAPI Specification (OAS) file. These rules make sure that each API behind the gateway has a minimum level of documentation. All three rules are configurable with [027](027-rule-configuration.md) and can be suppressed with [028](028-suppressions-and-baseline.md).

## Motivation

Documentation tools show the `summary` as the title of an operation and group operations by tag. An operation without a summary shows only its path. A tag that is not declared at the root has no description and no defined order. Examples help client developers more than a schema alone.

## Scope

### In scope

| Id | Category | Default severity | Rule type |
|---|---|---|---|
| `operation-summary-required` | DOCUMENTATION | warn | `OperationRule` |
| `operation-tags-defined` | DOCUMENTATION | warn | `OperationRule` |
| `schema-examples-present` | DOCUMENTATION | info | `OperationRule` |

### Out of scope

- Checks of `description` fields.
- Examples for parameters and headers.
- Examples for schemas in `components` that no operation uses ([030](030-design-rules.md) reports unused components).
- Validation of example values against their schema.
- Operations in `webhooks` and in callbacks. The engine of [005](005-validation-engine.md) gives an `OperationRule` only the operations under `paths`.

## Design

The operation pointer has the definition of [029](029-naming-rules.md). The rules check each operation that the engine visits: each operation under `paths`. The loader rejects every path item `$ref`, local or to another file, with `oas-parse` ([004](004-oas-loading.md), "Path item references", findings SEC-66-1 and SEC-67-1). So no rule runs on such a file, and the operations of each path item are inline.

### `operation-summary-required`

Checks that the operation has a `summary` that is not empty after trimming white space. A `description` does not replace the `summary`.

```yaml
# Bad
get:
  operationId: getOrder
  description: Returns one order.
# Good
get:
  operationId: getOrder
  summary: Get an order
```

- Pointer: the operation pointer.
- Edge cases: `summary: ""` and `summary: "  "` fail.

### `operation-tags-defined`

Checks two conditions:

1. The operation has at least one tag.
2. Each tag of the operation is equal to a `name` in the root `tags` list. The comparison is case-sensitive.

```yaml
# Bad
tags:
  - name: orders
paths:
  /orders:
    get:
      tags: [Orders]        # not declared: the case is different
  /refunds:
    get: {}                 # no tag
# Good
tags:
  - name: orders
    description: Order management
paths:
  /orders:
    get:
      tags: [orders]
```

- Pointer for condition 1: the operation pointer.
- Pointer for condition 2: `<operation pointer>/tags/<index>` for each undeclared tag. The index is the index in the file.
- Edge cases: `tags: []` fails condition 1. A spec without a root `tags` list fails condition 2 for each tag.
- A tag that is not a string, for example `1`, is not declared. It fails condition 2.
- The rule reads the tags of the operation in the raw document (`SpecFile` raw `JsonNode` from [004](004-oas-loading.md)). swagger-parser drops a tag that is not a string, and then the index in the model is not the index in the file. The loader checks that the raw document and the model have the same operations ([004](004-oas-loading.md), "Operations of the model"), so the raw document has each operation.
- If a declared name differs from the tag only in case, the message names the declared name. If two declared names differ only in case, the message names the first one in document order.
- The rule reads the root `tags` list once for each file, not once for each operation (finding SEC-66-3).

### `schema-examples-present`

Checks each JSON media type object in the request body and in the responses of the operation. A media type is JSON when it is `application/json` or ends with `+json`, ignoring parameters and case.

The media type passes when at least one of these is present and not empty:

1. `example` or `examples` on the media type object.
2. `example` or `examples` on its `schema`, after `$ref` resolution, at the top level only.

A value is empty when it is `null`, an empty string, an empty list, or an empty object. So `example: null`, `examples: {}` on the media type, and `examples: []` on the schema do not count. The rule does not look inside the entries of `examples`.

`$ref` resolution follows several references, for example a schema `OrderAlias` that is only `$ref: "#/components/schemas/Order"`. A request body `$ref` and a response `$ref` are also resolved, also through several references. The pointer stays below the operation, as in [030](030-design-rules.md), so that an operation-level `x-deviation` can suppress the violation.

The rule resolves only references of the form `#/components/<type>/<name>`. Two cases stop the resolution:

- A reference cycle. For a schema, the media type then fails. For a request body or a response, the rule skips it, because it has no content to check.
- A reference that the rule cannot resolve, for example to `$defs` inside a schema (`#/components/schemas/Orders/$defs/Order`, OAS 3.1), or a reference that swagger-parser leaves unchanged ([004](004-oas-loading.md), "Relative external references"). The rule skips the media type. It does not guess, so it reports no missing example that may exist (finding QA-66-1).

An `example` or `examples` next to a schema `$ref` counts in OAS 3.1, because the Schema Object of OAS 3.1 applies the fields next to `$ref`. It does not count in OAS 3.0, because OAS 3.0 ignores them.

```yaml
# Bad
"200":
  content:
    application/json:
      schema: { $ref: "#/components/schemas/Order" }   # Order has no example
# Good
"200":
  content:
    application/json:
      schema: { $ref: "#/components/schemas/Order" }
      example: { id: "A-1001", status: OPEN }
```

- Pointer: `<operation pointer>/requestBody/content/<media type>` or `<operation pointer>/responses/<code>/content/<media type>`, with `/` escaped as `~1`.
- Edge cases:
  - A media type without `schema` is not checked.
  - `application/octet-stream` and `text/plain` are not checked.
  - Examples on properties inside the schema do not count.
  - `application/problem+json` is a JSON media type and is checked.
  - `application/json; charset=utf-8` and `Application/JSON` are JSON media types.

### Implementation

The rules are in `com.johnowl.gateway.core.rules`, with the metadata helper `BestPracticeRule` of [029](029-naming-rules.md). All three rules are configurable ([027](027-rule-configuration.md)). Helpers:

- `MediaTypes`: `essence` removes the parameters and gives lowercase. `isJson` uses `essence`. `error-response-problem-json` ([030](030-design-rules.md)) uses `essence` too, so both specs share the helper.
- `ComponentReferences`: resolves a `$ref` to a response, a request body, or a schema in the model of swagger-parser. It replaces `ResponseReferences` of [030](030-design-rules.md).
- `PrintableText.printable` (package `com.johnowl.gateway.core.text`, [004](004-oas-loading.md), "Text in messages"): shows a value of the file in a message. It shows each unsafe character as a Java escape, for example `\u0009`, so that a message stays on one line and shows its text in the order of its characters. The unsafe characters are those of `UnsafeCharacters` (package `com.johnowl.gateway.core.text`): C0 and C1 control characters, `U+007F`, the Unicode line and paragraph separators `U+2028` and `U+2029`, and the bidirectional controls `U+061C`, `U+200E`, `U+200F`, `U+202A` to `U+202E`, and `U+2066` to `U+2069`. The CLI ([008](008-cli-basic.md)) and the denial log of the gateway ([024](024-authorization-manager.md)) use the same characters (finding SEC-66-4). The result has at most 200 characters, followed by `...` if the value is longer. The cut is never inside an escape or a surrogate pair (finding SEC-66-2). The pointer of a violation keeps the full value. `servers-https-only` and `error-response-problem-json` ([030](030-design-rules.md)) and the loader use it too.

A shared response or request body with many media types gives violations in each operation that references it. The engine therefore reports at most 100 violations of each rule in each file, and one more violation with the number of the others ([005](005-validation-engine.md), "Violation limit", finding SEC-66-2).

Messages:

- `Operation has no summary. Add a short title, for example 'Get an order'.`
- `Operation has no tags. Add a tag that the root tags list declares.`
- `Tag 'items' is not declared in the root tags list. Declare it, or use a declared tag.`
- `Tag 'Orders' is not declared in the root tags list. Did you mean 'orders'? The comparison is case-sensitive.`
- `Media type 'application/json' of response '200' has no example. Add example or examples to the media type or to its schema.`
- `Media type 'application/json' of the request body has no example. Add example or examples to the media type or to its schema.`

## Acceptance criteria

- [x] Each of the three rules has fixtures `valid.yaml`, `invalid.yaml`, and `expected.txt`, and the fixture test passes.
- [x] Each rule has the id, category, and default severity of the table in "Scope".
- [x] An operation with only a `description` produces `operation-summary-required`.
- [x] `summary: "  "` produces `operation-summary-required`.
- [x] An operation without `tags` produces `operation-tags-defined` at the operation pointer.
- [x] `tags: [Orders]` with a root tag `orders` produces `operation-tags-defined` at `<operation pointer>/tags/0`.
- [x] A JSON response whose referenced schema has a top-level `example` produces no `schema-examples-present` violation.
- [x] A JSON response without any example produces `schema-examples-present` at the media type pointer.
- [x] A `text/plain` response without an example produces no `schema-examples-present` violation.
- [x] An `application/problem+json` response without an example produces `schema-examples-present`.
- [x] A two-step request body `$ref` chain and a two-step response `$ref` chain to a JSON media type without an example produce `schema-examples-present` (finding QA-66-2).
- [x] A request body that references itself produces no violation, and the rule does not fail (finding QA-66-2).
- [x] A schema `$ref` into `$defs` (OAS 3.1) and a schema reference that swagger-parser leaves unchanged (OAS 3.0, another file) produce no `schema-examples-present` violation (finding QA-66-1).
- [x] `PrintableText.printable` escapes C1 control characters, `U+2028`, `U+2029`, and bidirectional controls, and cuts a value after 200 characters with `...`.
- [x] The message of `error-response-problem-json` shows the code and the media type with `PrintableText.printable` (finding QA-66-3).

## Test plan

- Fixture tests for each rule in `openapi-gateway-core/src/test/resources/rules/<rule-id>/` (`valid.yaml`, `invalid.yaml`, `expected.txt`), with the good examples, bad examples, and edge cases above.
- Unit test for the JSON media type helper (`application/json`, `application/problem+json`, parameters, case, `text/plain`).
- The fixture of `schema-examples-present` uses OAS 3.1: `example` and `examples` on the media type, `example` and `examples` on the schema, and `examples` next to a schema `$ref`. Unit tests (`DocumentationRulesTest`) cover OAS 3.0: `example` on the media type and on the schema, and an `example` next to a schema `$ref` that does not count.
- Unit tests for the exact messages, a tag with a control character, a spec without a root `tags` list, a path item `$ref` to another file (an `oas-parse` violation and no rule violation), and a schema reference cycle.
- Unit tests (`DocumentationRulesTest`): two-step request body and response chains, a request body cycle, a schema `$ref` into `$defs`, a schema reference that swagger-parser leaves unchanged, and the root tags of several files in one run.
- Unit tests `PrintableTextTest`, `UnsafeCharactersTest`, and a test of the escaped and cut media type in `ErrorResponseProblemJsonRuleTest`.

## Delivery

One PR. Main files: three rule classes, the JSON media type helper, registration in the rule registry, profile entries ([027](027-rule-configuration.md)), and fixtures. Estimate: 200–300 changed lines without fixtures.

## Decisions (2026-11-14)

- The rules keep the default severities of the table in "Scope": WARN for `operation-summary-required` and `operation-tags-defined`, INFO for `schema-examples-present`. This follows `docs/cli-release-policy.md`: a new best-practice rule starts with WARN or INFO. No CLI version is released yet, so the rules are part of the first release (`v0.1.0`). None of the rules is ERROR, so no rule needs a later change of severity. The same reasoning as in [029](029-naming-rules.md) and [030](030-design-rules.md).
- This spec and [030](030-design-rules.md) share the media type helper `MediaTypes` (former open question).
- Empty values do not count as examples. "Not empty" in "Design" has this meaning.
- The loader rejects every path item `$ref`, local or to another file, with `oas-parse` (former open question, findings SEC-66-1 and SEC-67-1). PR `fix/path-item-ref` (#67) makes this change from `main` and updates [004](004-oas-loading.md).
- One helper shows file text in messages: `PrintableText` of [004](004-oas-loading.md). It replaces `RuleText` of this PR, which had the same code (finding QA-67-4).

## Review findings

| Finding | Resolution |
|---|---|
| QA-66-1 | "`schema-examples-present`": a schema reference that the rule cannot resolve skips the media type. A cycle still fails. [004](004-oas-loading.md) notes the reference that swagger-parser leaves unchanged. |
| QA-66-2 | "Test plan": two-step request body and response chains, and a request body cycle. |
| QA-66-3 | `error-response-problem-json` ([030](030-design-rules.md)) shows the code and the media type with `PrintableText.printable`. |
| SEC-66-1 | Not in this PR. PR `fix/path-item-ref` (#67) rejects every path item `$ref` in the loader (also SEC-67-1). "Design" and "Decisions" describe the result. `operation-tags-defined` therefore reads the tags only from the raw document. |
| SEC-66-2 | "Implementation": `PrintableText.printable` cuts a value after 200 characters. [005](005-validation-engine.md), "Violation limit": at most 100 violations of each rule in each file. A limit for media type keys in the loader is an open question. |
| SEC-66-3 | "`operation-tags-defined`": the rule reads the root `tags` list once for each file. |
| SEC-66-4 | "Implementation": `UnsafeCharacters` is the one set of unsafe characters for rule messages, the CLI, and the denial log. |

## Open questions

- `operation-tags-defined` checks two conditions in one rule. Should "at least one tag" be a separate rule?
- Should `schema-examples-present` accept examples on properties inside the schema?
- Should `schema-examples-present` skip error responses, because `error-response-problem-json` ([030](030-design-rules.md)) already defines their shape?
- Should the rules also check the operations in `webhooks` and in callbacks? The engine of [005](005-validation-engine.md) does not give these operations to an `OperationRule`.
- Should the loader of [004](004-oas-loading.md) reject a media type key that is longer than 255 characters with `oas-parse`? The message of a violation cuts the key after 200 characters, but the pointer keeps the full key (finding SEC-66-2).

## References

- [005 — Validation engine](005-validation-engine.md)
- [027 — Rule configuration](027-rule-configuration.md)
- [028 — Suppressions and baseline](028-suppressions-and-baseline.md)
- [029 — Naming rules](029-naming-rules.md)
- [030 — Design rules](030-design-rules.md)
- OpenAPI Specification, Media Type Object and Tag Object: <https://spec.openapis.org/oas/latest.html>
