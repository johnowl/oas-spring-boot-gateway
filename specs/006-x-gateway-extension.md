# 006 — x-gateway extension

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 2 — OAS core |
| Depends on | [005](005-validation-engine.md) |
| Modules | `openapi-gateway-core` |

## Summary

This spec defines the contract of the `x-gateway` OAS extension and adds three contract rules that check it: `x-gateway-schema`, `x-gateway-unknown-extension`, and `x-gateway-response-timeout`. The root-level `x-gateway` object tells the gateway where to send requests and which public prefix to use. One JSON Schema file in the core defines the object. The rules find typos and invalid values in pull requests, before the gateway reads the file at startup.

## Motivation

The gateway builds routes from the `x-gateway` object ([007](007-route-model.md)). A wrong value can send traffic to the wrong backend or create a wrong public path. A typo in an extension name is silent by default. For example, `x-gateway-respone-timeout` is a valid OAS extension. The gateway would ignore it and use the global timeout. The rules in this spec turn these mistakes into `ERROR` violations.

## Scope

### In scope

- The JSON Schema file `openapi-gateway-core/src/main/resources/x-gateway.schema.json`.
- Rule `x-gateway-schema`: validates the root-level `x-gateway` object against the schema.
- Rule `x-gateway-unknown-extension`: rejects unknown `x-gateway-*` keys on operations.
- Rule `x-gateway-response-timeout`: checks the operation-level timeout value.
- Fixtures for the three rules.

### Out of scope

- The content of `x-gateway-authorization` and the rule `audience-required`. See [018](018-authorization-extension.md).
- A check that the audience exists in Microsoft Entra ID. The permission catalog of [019](019-permission-catalog.md) is removed (decision of 2026-11-13), so no such check exists. This spec checks only the format of `audience`.
- The content of `x-deviation`. See [028](028-suppressions-and-baseline.md).
- Using these values to build routes. See [007](007-route-model.md).

## Design

### Root-level `x-gateway` object

```yaml
x-gateway:
  upstream: http://orders-service:8080
  prefix: /orders-api
  upstream-base-path: /v1
  audience: 6f1c2e8a-0000-0000-0000-000000000000
```

| Field | Required | Format | Meaning |
|---|---|---|---|
| `upstream` | Yes | `scheme://host[:port]`, no path | Backend address. Spring Cloud Gateway ignores the URI path of HTTP routes, so a path is not allowed. |
| `prefix` | No | `^(/[a-z0-9-]+)+$` | Public path prefix. The gateway removes it before it calls the backend. |
| `upstream-base-path` | No | `^(/[A-Za-z0-9._-]+)+$` | Base path on the backend. The gateway adds it before the OAS path. |
| `audience` | Conditional | GUID | Client id of the backend Microsoft Entra ID app registration. Required when the file has at least one non-public operation. Rule `audience-required` in [018](018-authorization-extension.md) checks this. Since 2026-11-13, `audience-unique` and `audience-not-first-party` ([018](018-authorization-extension.md), "Rules for audiences") also check the value. |

Additional properties are not allowed. A typo such as `upstrem` is therefore an error.

### JSON Schema

The file `x-gateway.schema.json` is the only definition of the object. The rule loads it once.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://github.com/johnowl/oas-spring-boot-gateway/x-gateway.schema.json",
  "type": "object",
  "additionalProperties": false,
  "required": ["upstream"],
  "properties": {
    "upstream": {
      "type": "string",
      "pattern": "^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$"
    },
    "prefix": {
      "type": "string",
      "pattern": "^(/[a-z0-9-]+)+$"
    },
    "upstream-base-path": {
      "type": "string",
      "pattern": "^(/[A-Za-z0-9._-]+)+$"
    },
    "audience": {
      "type": "string",
      "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    }
  }
}
```

The `upstream` pattern and the `audience` pattern are proposals. See "Open questions".

The schema file in the code also has `title` and `description` fields. networknt requires an absolute URI as `$id`, so the `$id` is the repository URL.

The rule validates the raw `JsonNode` of `SpecFile` ([004](004-oas-loading.md)) with the networknt json-schema-validator library, version `2.0.8`, draft 2020-12. Version 2.0.x uses Jackson 2, like swagger-parser. Version 3.x moved to Jackson 3. The parent `pom.xml` manages the version with the property `json-schema-validator.version`.

The rule reads the error location from `Error.getInstanceLocation()` and builds the JSON pointer from its elements. The rule maps each error keyword to a message of the table below. The schema is a static field of `XGatewaySchemaRule`, so it is loaded once for each JVM.

The rules are in the package `com.johnowl.gateway.core.rules` ([005](005-validation-engine.md)).

### Operation-level extensions

| Key | Status | Owner spec |
|---|---|---|
| `x-gateway-response-timeout` | Allowed | This spec |
| `x-gateway-authorization` | Allowed | [018](018-authorization-extension.md) |
| `x-deviation` | Allowed (not an `x-gateway-*` key) | [028](028-suppressions-and-baseline.md) |
| Any other key that starts with `x-gateway` | Error | This spec |

### Rules

| Id | Kind | Category | Severity | Checks |
|---|---|---|---|---|
| `x-gateway-schema` | `SpecRule` | CONTRACT | ERROR | The root has an `x-gateway` object, and the object is valid against `x-gateway.schema.json`. |
| `x-gateway-unknown-extension` | `OperationRule` | CONTRACT | ERROR | Each operation key that starts with `x-gateway` is in the allowlist. |
| `x-gateway-response-timeout` | `OperationRule` | CONTRACT | ERROR | If present, `x-gateway-response-timeout` is an integer from 100 to 60000 (milliseconds). |

All three rules have `configurable=false` and cannot have a deviation ([005](005-validation-engine.md)).

### Violation pointers and messages

| Rule | Example input | Pointer | Message |
|---|---|---|---|
| `x-gateway-schema` | No `x-gateway` object | empty (document root) | `Missing required object 'x-gateway'.` |
| `x-gateway-schema` | `upstream: http://orders:8080/v1` | `/x-gateway/upstream` | `'upstream' must be scheme://host[:port] without a path. Use 'upstream-base-path' for a backend path.` |
| `x-gateway-schema` | `prefix: /Orders` | `/x-gateway/prefix` | `'prefix' does not match ^(/[a-z0-9-]+)+$.` |
| `x-gateway-schema` | `upstrem: http://orders:8080` | `/x-gateway` | `Unknown property 'upstrem'.` |
| `x-gateway-schema` | No `upstream` | `/x-gateway` | `Missing required property 'upstream'.` |
| `x-gateway-schema` | `prefix: 42` | `/x-gateway/prefix` | `'prefix' must be a string.` |
| `x-gateway-schema` | `x-gateway: some-text` | `/x-gateway` | `'x-gateway' must be an object.` |
| `x-gateway-unknown-extension` | `x-gateway-respone-timeout: 500` on `GET /reports` | `/paths/~1reports/get/x-gateway-respone-timeout` | `Unknown extension 'x-gateway-respone-timeout'. Allowed: x-gateway-response-timeout, x-gateway-authorization.` |
| `x-gateway-response-timeout` | `x-gateway-response-timeout: 50` | `/paths/~1reports/get/x-gateway-response-timeout` | `Value 50 is out of range 100–60000 ms.` |
| `x-gateway-response-timeout` | `x-gateway-response-timeout: "500"` | `/paths/~1reports/get/x-gateway-response-timeout` | `Value must be an integer (milliseconds).` |

A schema error MUST produce one violation for each schema error, with the pointer of the invalid value.

### Examples

Valid operation:

```yaml
paths:
  /reports:
    get:
      x-gateway-response-timeout: 30000
```

Invalid values for `x-gateway-response-timeout`: `50`, `60001`, `1500.5`, `1000.0`, `"500"`, `true`.

The rule reads the value from the swagger-parser model. A number with a decimal point is not an integer, also if the fraction is zero.

## Acceptance criteria

- [x] `x-gateway.schema.json` exists in `openapi-gateway-core/src/main/resources/` and has `additionalProperties: false`.
- [x] An OAS file without `x-gateway` produces one `x-gateway-schema` violation with an empty pointer.
- [x] Each invalid example in the "Violation pointers and messages" table produces exactly the listed pointer.
- [x] `upstream: http://orders-service:8080` is valid, and `upstream: http://orders-service:8080/v1` is invalid.
- [x] An unknown property in `x-gateway` produces an `x-gateway-schema` violation.
- [x] `x-gateway-respone-timeout` on an operation produces an `x-gateway-unknown-extension` violation.
- [x] `x-gateway-authorization` and `x-deviation` on an operation produce no `x-gateway-unknown-extension` violation.
- [x] `x-gateway-response-timeout` values `100` and `60000` are valid. The values `99`, `60001`, `1500.5`, and `"500"` are invalid.
- [x] All three rules have category `CONTRACT`, severity `ERROR`, and `configurable=false`.
- [x] Each rule has `valid.yaml`, `invalid.yaml`, and `expected.txt` in `src/test/resources/rules/<rule-id>/`, and `RuleFixtureTest` passes.
- [x] The rule loads the schema file once, in a static field, not once for each OAS file.

## Test plan

- Fixture tests for `x-gateway-schema`, `x-gateway-unknown-extension`, and `x-gateway-response-timeout` with the harness of [005](005-validation-engine.md).
- Unit test `XGatewaySchemaRuleTest`: one case for each field and each pattern (valid and invalid), missing object, unknown property, several errors in one object.
- Unit test `XGatewayResponseTimeoutRuleTest`: boundary values `99`, `100`, `60000`, `60001`, and wrong types.
- Unit test `XGatewayUnknownExtensionRuleTest`: allowed keys, other `x-` keys, an unknown `x-gateway-*` key.

## Delivery

One PR. Main files: `x-gateway.schema.json`, `XGatewaySchemaRule`, `XGatewayUnknownExtensionRule`, `XGatewayResponseTimeoutRule`, registration in `BuiltInRules`, tests. Estimate: fewer than 350 changed lines without fixtures.

## Decisions (2026-11-12)

- `upstream` has no host restriction. Loopback, link-local, and private addresses, and the `http` scheme are allowed. The gateway team reviews the upstream of each OAS file. This closes the review findings SEC-10-1 and SEC-21-2.
- The repository has no `CODEOWNERS` file ([034](034-access-change-review.md)). The upstream review is therefore a normal PR review: one approval from any person with write access can merge a new or changed `upstream`. Nothing enforces a review by the gateway team (review finding SEC-36-1).

## Decisions (2026-11-13)

- The product owner removed the permission catalog ([Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)). `x-gateway.audience` is the only source of the audience. The gateway derives the client registration, the token scope, and the `aud` check from it ([023](023-per-audience-tokens.md), [024](024-authorization-manager.md)). The schema of this spec checks only the GUID format. A typo in a valid GUID is not found in the PR. At runtime the token request fails closed.
- Risk: an OAS file can declare the audience of one backend with any `upstream`. Reason: `upstream` has no host restriction (decision of 2026-11-12). The gateway then relays the token of that backend to that upstream ([025](025-token-relay-and-header-hygiene.md)). The catalog did not control this risk either: it checked that the audience exists, not its `upstream`.
- Team default after the review of PR #48 (SEC-48-1): the CONTRACT rule `audience-unique` ([018](018-authorization-extension.md)) allows each audience in one OAS file only. A second file cannot use the audience of another backend. A change of `upstream` in the existing file of an audience is still possible. `diff` ([034](034-access-change-review.md)) reports it, and the PR review MUST check it.

## Open questions

- Which URI schemes does `upstream` allow? The proposal allows `http` and `https`. The brief does not say if other schemes, for example `lb://`, are allowed.
- Does `upstream` allow IPv6 addresses? The proposed pattern does not.
- Must `audience` be lowercase? The proposed pattern requires lowercase. Entra writes the client id in lowercase in v2 tokens, but this is not verified.
- Does `x-gateway-unknown-extension` also apply to the root level and the path item level? The brief defines the allowlist only for operations. A root key such as `x-gateway-upstream` is not detected today.
- A typo in the `x-gateway` prefix itself, for example `x-gatway-response-timeout`, is not detected. Is this acceptable?
- `oasdiff` reads the extension `x-since-date` to choose between two duplicate operations, and it drops the other operation without an error. The breaking change check of [033](033-breaking-change-detection.md) therefore fails when an OAS file contains `x-since-date` (finding QA-72-1, 2026-11-15). No CLI rule checks this key: the decision is a workflow check only. Is a CLI rule useful later, so that the author sees the problem before the PR?
- Should a CONTRACT rule warn about literal path segments that a backend can read as another operation, for example two sibling literals that differ only in case? See [024](024-authorization-manager.md), "Open questions" (finding SEC-59-1). No code exists yet.

## References

- [004 — OAS loading](004-oas-loading.md)
- [005 — Validation engine](005-validation-engine.md)
- [007 — Route model](007-route-model.md)
- [018 — Authorization extension](018-authorization-extension.md)
- [028 — Suppressions and baseline](028-suppressions-and-baseline.md)
- networknt json-schema-validator: https://github.com/networknt/json-schema-validator
- JSON Schema: https://json-schema.org
