# 018 — Authorization extension

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 6 — Authorization contract (core and CLI) |
| Depends on | [004](004-oas-loading.md), [005](005-validation-engine.md), [006](006-x-gateway-extension.md), [007](007-route-model.md) |
| Modules | `openapi-gateway-core` |

## Summary

This spec defines how an OpenAPI Specification (OAS) file declares access control for each operation, and how the core validates it. The OAS `security` field decides if an operation is public or needs authentication. The `x-gateway-authorization` extension lists the required permissions. The root `x-gateway.audience` names the backend Microsoft Entra ID app registration. The core compiles each operation into an `AccessRule` at startup and adds nine CONTRACT rules. The security index of [024](024-authorization-manager.md) uses these rules. A spec that passes the CLI therefore has a complete and unambiguous access contract.

## Motivation

Access control that lives outside the OAS files drifts from the API. A missing declaration can expose an operation by accident. This spec makes the access contract explicit for each operation and applies default deny: the gateway returns 404 for an operation without an access rule ([024](024-authorization-manager.md)). Public operations need an explicit declaration. The CLI rejects an incomplete contract in the pull request (PR), and the gateway rejects it at startup.

## Scope

### In scope

- OAS security semantics: effective security, public operations, forbidden forms.
- The `x-gateway-authorization` operation extension and its JSON Schema.
- The `AccessRule` record and the `AccessRuleCompiler` that builds it.
- Rules: `security-explicit`, `security-no-scopes`, `security-no-empty-requirement`, `security-scheme-exists`, `authorization-required`, `authorization-schema`, `authorization-not-on-public`, `permission-format`, `audience-required`.
- Rules added on 2026-11-13 (see "Rules for audiences"): `audience-unique` and `audience-not-first-party`.

### Out of scope

- A check that an audience or a permission exists in Microsoft Entra ID. The permission catalog of [019](019-permission-catalog.md) is removed (decision of 2026-11-13), so no such check exists. `permission-format` checks only the form. See [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13).
- The security index and the authorization manager. See [024](024-authorization-manager.md).
- Token handling and the `aud` check at runtime. See [023](023-per-audience-tokens.md) and [024](024-authorization-manager.md).
- Review of access changes between two versions. See [034](034-access-change-review.md).

## Design

### Example

```yaml
x-gateway:
  upstream: http://orders-service:8080
  prefix: /orders-api
  audience: 6f1c2e8a-0000-0000-0000-000000000000
components:
  securitySchemes:
    entra:
      type: openIdConnect
      openIdConnectUrl: https://login.microsoftonline.com/<tenant-id>/v2.0/.well-known/openid-configuration
security:
  - entra: []                 # always an empty list: no scopes
paths:
  /orders/{id}:
    get:
      x-gateway-authorization:
        all-of: [Orders.Read]
    delete:
      x-gateway-authorization:
        all-of: [Orders.Read, Orders.Delete]
  /orders/{id}/refund:
    post:
      x-gateway-authorization:
        any-of: [Orders.Refund, Finance.Admin]
  /me:
    get:
      x-gateway-authorization:
        authenticated-only: true      # any authenticated user
  /health:
    get:
      security: []                    # public: explicit, never implicit
```

### OAS security semantics

1. **Effective security.** If the operation has a `security` field, that field is the effective security. The operation field replaces the root `security` field; the two fields do not merge. If the operation has no `security` field, the root `security` field is the effective security.
2. **Public operation.** An operation with `security: []` is public. The gateway does not require a session for it.
3. **Missing security.** If neither the operation nor the root declares `security`, the operation has no effective security. The rule `security-explicit` reports an error. The gateway never treats a missing declaration as public.
4. **No scopes.** Each security requirement maps a scheme name to an empty list, for example `entra: []`.
5. **Optional authentication is forbidden.** In OAS, an empty requirement object (`- {}`) means "anonymous access is also allowed". The gateway does not support optional authentication, so `- {}` is an error.

The project has not verified that the swagger-parser model distinguishes an absent `security` field from `security: []`. The compiler MUST read the presence of `security` from the raw `JsonNode` of `SpecFile` ([004](004-oas-loading.md)).

Decision (2026-11-12): a root-level `security: []` is not an explicit public declaration. It counts as a missing root field, so `security-explicit` reports each operation without its own `security`. Public is always explicit on the operation. This is the proposal of "Open questions".

`EffectiveSecurity.of(spec, oasPath, method)` returns the kind (`PUBLIC`, `REQUIRED`, or `MISSING`), the requirement array, and the pointer of the effective `security` field (`/security` or `/paths/<path>/<method>/security`). It reads the operation at `/paths/<escaped path>/<method>` of the raw document. An operation that is not in the raw document (for example a path item from an external `$ref`) has no own `security` field. The loader rejects a path item that has `$ref` and other fields ([004](004-oas-loading.md)). swagger-parser drops those other fields, so the raw document and the model would describe different operations.

### Allowed security scheme types

Decision (2026-11-12): each scheme in `components.securitySchemes` MUST have type `openIdConnect` or `oauth2`. Both describe the Microsoft Entra ID login that the gateway enforces. The rule `security-scheme-type` reports other types (`apiKey`, `http` with `basic` or `bearer`, `mutualTLS`), because the gateway does not enforce them. `http` with `bearer` becomes allowed only if [026](026-service-to-service-bearer.md) is accepted. A scheme with `$ref` has no `type` in the raw document. The rule reads the type of the resolved scheme from the swagger-parser model and reports the scheme at `/components/securitySchemes/<name>`. If the type of the resolved scheme is unknown, the rule also reports the scheme.

### Why permissions only, and no scopes

- A permission is an Entra App Role of the backend app registration, for example `Orders.Read`. Entra assigns App Roles to users or groups and writes them into the `roles` claim of the access token.
- A scope is a delegated permission. It says what the client app can do for the user. It does not say what the user can do.
- Each backend exposes one scope, `access_as_user`, only to enable the delegated token flow and the admin consent ([021](021-entra-app-registrations.md)). The gateway requests `{client-id}/.default` ([023](023-per-audience-tokens.md)). Every token for that backend carries the same scope, so a scope check adds no information.
- Permission names follow the Microsoft Graph style: `^[A-Z][A-Za-z0-9]*(\.[A-Z][A-Za-z0-9]*)+$`. `Orders.Read` is valid. `orders.read` and `Orders` are not valid.

### Why the audience is per spec

- Each backend API has its own Entra app registration and its own App Roles. One OAS file describes one backend, so one audience per file.
- An Entra access token has one audience (`aud`). Its `roles` claim contains only roles of that audience app.
- Role names can be the same in two apps, for example `Admin`. Without an `aud` check, a token for `payments-api` with `Admin` passes an `orders-api` operation that requires `Admin`. The gateway therefore checks the `aud` of the token against the audience of the route ([024](024-authorization-manager.md)).
- `x-gateway.audience` is the client id (GUID) of the backend app, because a v2 token carries the client id in `aud`.

### The `x-gateway-authorization` extension

| Key | Type | Meaning |
|---|---|---|
| `all-of` | non-empty list of permissions | The token MUST contain every permission. |
| `any-of` | non-empty list of permissions | The token MUST contain at least one permission. |
| `authenticated-only` | `true` | Any authenticated user with a token for the audience. |

- If `all-of` and `any-of` are both present, both conditions MUST hold.
- `authenticated-only: true` MUST NOT appear with `all-of` or `any-of`.
- The object MUST contain at least one key. `authenticated-only: false` alone is an error, because it states no condition.
- Other keys are errors (`additionalProperties: false`).

The JSON Schema is a `$defs/authorization` entry in `openapi-gateway-core/src/main/resources/x-gateway.schema.json` ([006](006-x-gateway-extension.md)). The rule `authorization-schema` validates against it.

The rules are declarative. The specs cannot contain expressions (no Spring Expression Language). The compiler turns the lists into sets at startup.

### `AccessRule`

Package: `com.johnowl.gateway.core.access`.

```java
public record AccessRule(boolean isPublic, String audience, Set<String> allOf, Set<String> anyOf) {
    public AccessRule {
        allOf = Set.copyOf(allOf);
        anyOf = Set.copyOf(anyOf);
        if (isPublic && (audience != null || !allOf.isEmpty() || !anyOf.isEmpty())) {
            throw new IllegalArgumentException("A public rule has no audience and no permissions");
        }
    }

    public boolean allows(Set<String> permissions) {
        if (isPublic) return true;
        if (!permissions.containsAll(allOf)) return false;
        return anyOf.isEmpty() || anyOf.stream().anyMatch(permissions::contains);
    }
}
```

| Declaration | `AccessRule` |
|---|---|
| `security: []` | `isPublic=true`, `audience=null`, empty sets |
| `authenticated-only: true` | `isPublic=false`, audience, empty sets |
| `all-of: [A.Read]` | `allOf={A.Read}`, `anyOf={}` |
| `any-of: [A.X, B.Y]` | `allOf={}`, `anyOf={A.X, B.Y}` |

`allows` does not check the audience. The caller checks `aud` first ([024](024-authorization-manager.md)).

`AccessRuleCompiler` produces `OperationAccess(String routeId, String publicPathPattern, String method, AccessRule rule)` for each operation. The engine calls it for each file with the routes of the same file. The compiler reads `x-gateway-authorization` from the swagger-parser model. A non-public operation without the extension compiles to an `authenticated-only` rule; the rule `authorization-required` reports it before the compiler runs. A file without `x-gateway.upstream` gives no access rules, as it gives no routes ([007](007-route-model.md)). `AccessRule.publicAccess()` creates the public rule. `routeId` is the id of the `GatewayRoute` that serves the operation ([007](007-route-model.md)). The security index of [024](024-authorization-manager.md) stores it with the access rule, so that logs can name the route. `publicPathPattern` is prefix + OAS path, the same value as in `GatewayRoute` ([007](007-route-model.md)). `ValidationReport` gets a new field `List<OperationAccess> accessRules`. The compiler runs only if the report has no ERROR.

### Rules

All rules have category `CONTRACT`, severity `ERROR`, `configurable=false`, and no suppression.

| Id | Kind | Checks | Pointer example |
|---|---|---|---|
| `security-explicit` | `OperationRule` | The operation has effective security (own or root). Public only with explicit `security: []`. | `/paths/~1orders~1{id}/get` |
| `security-no-scopes` | `SpecRule` | Each requirement list, at root and on each operation, is `[]`. | `/security/0/entra` |
| `security-no-empty-requirement` | `SpecRule` | No requirement object is empty (`- {}`). | `/paths/~1me/get/security/1` |
| `security-scheme-exists` | `SpecRule` | Each requirement is an object, and each scheme name in it exists in `components.securitySchemes`. | `/security/0` |
| `security-scheme-type` | `SpecRule` | Each scheme in `components.securitySchemes` has type `openIdConnect` or `oauth2`, also a scheme with `$ref`. | `/components/securitySchemes/apiKey/type` |
| `authorization-required` | `OperationRule` | Each non-public operation has `x-gateway-authorization`. | `/paths/~1orders~1{id}/delete` |
| `authorization-schema` | `OperationRule` | The extension matches `$defs/authorization`. | `/paths/~1me/get/x-gateway-authorization` |
| `authorization-not-on-public` | `OperationRule` | A public operation has no `x-gateway-authorization`. | `/paths/~1health/get/x-gateway-authorization` |
| `permission-format` | `OperationRule` | Each permission matches the naming pattern. | `.../x-gateway-authorization/all-of/1` |
| `audience-required` | `SpecRule` | A spec with at least one non-public operation declares `x-gateway.audience`. | `/x-gateway` |

Implementation notes:

- The rules are in `com.johnowl.gateway.core.rules`. The helper `SecurityRequirements` finds the root `security` array and each operation `security` array in the raw document.
- "Non-public" in `authorization-required` and `audience-required` means an operation with a declared security requirement (`EffectiveSecurity.Kind.REQUIRED`). An operation without security gets only the `security-explicit` violation, so each row of the table gives exactly one rule id.
- `authorization-schema` validates the extension from the parsed model against the `$defs/authorization` node of `x-gateway.schema.json`, loaded once. It maps each schema error to a message, for example `Unknown property 'alls-of'. Allowed: all-of, any-of, authenticated-only.` or `'authenticated-only' cannot be used with 'all-of' or 'any-of'.` The `not` part of the schema applies only to an object, so a value that is not an object (`null`, text, a list) gives only `x-gateway-authorization must be an object.`
- `audience-required` does nothing if `x-gateway` is missing or not an object, because `x-gateway-schema` reports that.

### Rules for audiences

Decision (2026-11-13, team default after the review of PR #48, findings SEC-48-1 and SEC-48-2): two new CONTRACT rules check the value of `x-gateway.audience`. Reason: the gateway derives the token request from this value ([023](023-per-audience-tokens.md)), and it relays the token to the `upstream` of the same file ([025](025-token-relay-and-header-hygiene.md)). Without a permission catalog, these rules are the only automatic checks of the audience. See [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13).

Both rules have category `CONTRACT`, severity `ERROR`, `configurable=false`, and no suppression, like the rules above. The CLI runs them. The gateway runs them at startup ([013](013-oas-route-locator.md)) and on reload ([014](014-route-reload.md)), because they are in `BuiltInRules.contract()`.

| Id | Kind | Checks | Pointer |
|---|---|---|---|
| `audience-unique` | `CrossSpecRule` | Each value of `x-gateway.audience` appears in exactly one OAS file of the validated set. | `/x-gateway/audience` |
| `audience-not-first-party` | `SpecRule` | `x-gateway.audience` is not the app id of a well-known Microsoft first-party app (table below). | `/x-gateway/audience` |

#### `audience-unique`

The rule binds each audience to one OAS file, and so to one `upstream`. Without the rule, a second OAS file can declare the audience of `payments-api` with another `upstream`. The gateway then relays `payments-api` tokens to that upstream.

- The rule reports one violation for each file that declares a repeated audience. The message names one other file: `Audience '6f1c2e8a-0000-0000-0000-000000000000' is also declared in payments.yaml. An audience can appear in one OAS file only.`
- If more than two files declare the audience, the message adds `and in <n> more file(s)` after the name of the other file, as in `route-collision` ([007](007-route-model.md)): `Audience '6f1c2e8a-0000-0000-0000-000000000000' is also declared in payments.yaml and in 1 more file(s). An audience can appear in one OAS file only.`
- The rule compares only an `audience` that is a text value. It skips a file where `x-gateway` is not an object, or where `audience` is missing or is not text (for example a number or a list). `x-gateway-schema` ([006](006-x-gateway-extension.md)) and `audience-required` report these files. Other errors in `x-gateway`, for example an invalid `upstream`, do not stop the comparison.
- The comparison is exact. `x-gateway-schema` already requires a lowercase GUID.
- The rule sees only the files of one run. The PR pipeline validates the complete OAS folder ([011](011-pr-validation-pipeline.md)), and the gateway validates the complete set at startup. A CLI run on a single file cannot find a duplicate in another file.
- Consequence: one backend API has exactly one OAS file. A team with two OAS files for one backend merges them into one file.

#### `audience-not-first-party`

An OAS file MUST NOT declare a Microsoft API as its audience. The login scopes `openid`, `profile`, `email`, and `offline_access` are Microsoft Graph permissions, and `gateway-bff` has consent for them (in the test tenant: `gateway-bff-test`, [`infra/azure-test`](../infra/azure-test/README.md)). The gateway could therefore get a Microsoft Graph token for the user and relay it to the `upstream`. The first implementation PR of [023](023-per-audience-tokens.md) tests this case ("Scope form", negative cases).

The rule compares the audience with the list in the class `FirstPartyApps` (package `com.johnowl.gateway.core.rules`):

| App | App id |
|---|---|
| Microsoft Graph | `00000003-0000-0000-c000-000000000000` |
| Azure Resource Manager | `797f4846-ba00-4fd7-ba43-dac1f8f63013` |
| Azure Key Vault | `cfa8b339-82a2-471a-a3c9-0fc0be7a4093` |
| Azure Storage | `e406a681-f3d4-42a8-90b6-c2b029497af1` |
| Exchange Online | `00000002-0000-0ff1-ce00-000000000000` |
| SharePoint Online | `00000003-0000-0ff1-ce00-000000000000` |
| Azure AD Graph | `00000002-0000-0000-c000-000000000000` |

Source: [Verify first-party Microsoft applications in sign-in reports](https://learn.microsoft.com/en-us/troubleshoot/entra/entra-id/governance/verify-first-party-apps-sign-in). The comparison ignores case.

Message: `Audience '00000003-0000-0000-c000-000000000000' is the Microsoft first-party app 'Microsoft Graph'. The gateway does not request tokens for Microsoft APIs.`

Decision (2026-11-13, review of PR #54, finding SEC-54-1): the list has the seven apps of the table. A later PR MAY add other Microsoft first-party apps. Each added app id MUST come from the Microsoft documentation, and the PR MUST link the source. The list cannot be complete. [023](023-per-audience-tokens.md) adds a check at startup for the other Microsoft apps ("Client registrations for each audience").

The gateway also rejects the client id of `gateway-bff` as an audience. This check is not a CONTRACT rule, because the CLI does not know the client id. See [023](023-per-audience-tokens.md), "Client registrations for each audience".

All fixtures that run the full rule set (the CLI fixtures, the gateway test OAS files) now declare `security: []` on each operation.

## Behavior and errors

| Input | Result |
|---|---|
| Example spec above | No violation. Five `OperationAccess` entries. |
| Operation without `security`, root without `security` | `security-explicit` |
| `security: [ { entra: [Orders.Read] } ]` | `security-no-scopes` |
| `security: [ { entra: [] }, {} ]` | `security-no-empty-requirement` |
| `security: [ { oauth: [] } ]`, no scheme `oauth` | `security-scheme-exists` |
| `security: [ entra ]` (the requirement is not an object) | `security-scheme-exists` |
| Non-public operation without `x-gateway-authorization` | `authorization-required` |
| `{ authenticated-only: true, all-of: [A.Read] }` | `authorization-schema` |
| `{ all-of: [] }` or `{ alls-of: [A.Read] }` | `authorization-schema` |
| `security: []` with `x-gateway-authorization` | `authorization-not-on-public` |
| `all-of: [orders.read]` | `permission-format` |
| Non-public operations, no `x-gateway.audience` | `audience-required` |
| `orders.yaml` and `reports.yaml` both declare `audience: 6f1c2e8a-0000-0000-0000-000000000000` | `audience-unique`, once in each file |
| `audience: 00000003-0000-0000-c000-000000000000` | `audience-not-first-party` |

## Acceptance criteria

- [x] An operation `security` field replaces the root `security` field for that operation.
- [x] An operation with `security: []` compiles to an `AccessRule` with `isPublic=true`.
- [x] Root `security: [ { entra: [] } ]` and an operation without `security` give a non-public rule.
- [x] Each row of the "Behavior and errors" table produces exactly the listed rule id at `ERROR`.
- [x] The compiler distinguishes an absent `security` field from `security: []` in OAS 3.0 and OAS 3.1 files.
- [x] `allows` returns true for `all-of: [A.X, A.Y]` only when both permissions are present.
- [x] `allows` returns true for `any-of: [A.X, B.Y]` when at least one permission is present.
- [x] With `all-of: [A.X]` and `any-of: [B.Y, B.Z]`, `allows({A.X})` is false and `allows({A.X, B.Z})` is true.
- [x] An `authenticated-only` rule allows an empty permission set.
- [x] Each `OperationAccess` has the `routeId` of the `GatewayRoute` that serves its operation.
- [x] A `.oas-gateway.yaml` entry or an `x-deviation` entry for any rule of this spec does not change the result.
- [x] Each rule has fixtures `valid.yaml`, `invalid.yaml`, and `expected.txt`.
- [x] `security-scheme-type` reports `apiKey`, `http`, and `mutualTLS` (OAS 3.1) schemes, and a scheme with an internal or external `$ref` to such a scheme.

Criteria for "Rules for audiences" (code PR 3 of the removal of the permission catalog):

- [x] Two files with the same `x-gateway.audience` give exactly two `audience-unique` violations, one in each file, at `/x-gateway/audience`. Each message names the other file.
- [x] Three files with the same audience give three violations. Each message names one other file and then `and in 1 more file(s)`.
- [x] Two files with different audiences give no `audience-unique` violation. Two files without `audience` (only public operations) give no violation.
- [x] `audience: 00000003-0000-0000-c000-000000000000` gives exactly one `audience-not-first-party` violation at `/x-gateway/audience` with the message of the rule.
- [x] Each app id of the `FirstPartyApps` table gives exactly one `audience-not-first-party` violation with the name of the app, also in uppercase.
- [x] `BuiltInRules.contract()` contains `audience-unique` and `audience-not-first-party`. The CLI and the gateway startup both report a fixture with a repeated audience.
- [x] A `.oas-gateway.yaml` entry or an `x-deviation` entry for `audience-unique` or `audience-not-first-party` does not change the result. A `.oas-gateway.yaml` entry is a configuration error (exit code 2), as for every CONTRACT rule ([027](027-rule-configuration.md)). An `x-deviation` entry gives a `deviation-schema` error, and the violations stay.
- [x] Each of the two rules has fixtures `valid.yaml`, `invalid.yaml`, and `expected.txt`. `audience-unique` uses the multi-file fixture format of [005](005-validation-engine.md).

## Test plan

- Unit tests: `AccessRuleTest` (constructor checks, `allows` truth table), `EffectiveSecurityTest` (absent, empty, operation override, OAS 3.0 and 3.1).
- Fixture tests: one folder per rule in `src/test/resources/rules/<rule-id>/`, run by the parameterized harness of [005](005-validation-engine.md).
- Exclusivity test: all CONTRACT rules run on the fixtures of this spec. Each `invalid.yaml` gives only the rule id of its folder, and each `valid.yaml` gives no violation.
- Compiler test: the example spec produces the expected five `OperationAccess` entries, each with the expected route id.
- Protection test: severity configuration and suppression do not affect these rules.

## Delivery

Two PRs:

1. Model and parsing: `AccessRule`, `OperationAccess`, `EffectiveSecurity`, `AccessRuleCompiler`, `$defs/authorization` in the schema, `accessRules` in `ValidationReport`, unit tests (about 300 lines).
2. Rules: the nine rules, registration in `BuiltInRules`, fixtures (about 350 lines without fixtures).

`audience-unique` and `audience-not-first-party` are part of code PR 3 of [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13), not of the two PRs above.

## Decisions (2026-11-13)

- Team default after the review of PR #48 (SEC-48-1): the CONTRACT rule `audience-unique`. An audience appears in exactly one OAS file. The CLI and the gateway (startup and reload) run it.
- Team default after the review of PR #48 (SEC-48-2): the CONTRACT rule `audience-not-first-party` with the list of well-known Microsoft first-party apps. The list has at least Microsoft Graph (`00000003-0000-0000-c000-000000000000`). After the review of PR #54 (SEC-54-1), it has seven apps (see "Rules for audiences"). The gateway also rejects the client id of `gateway-bff` at startup ([023](023-per-audience-tokens.md)).

## Open questions

- Must the gateway reject more than one security requirement object (alternatives)?
- Is a duplicate permission in one list (for example `all-of: [A.Read, A.Read]`) an error, or does the compiler remove it?

## References

- [006 — x-gateway extension](006-x-gateway-extension.md)
- [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)
- [021 — Entra app registrations](021-entra-app-registrations.md)
- [024 — Authorization manager](024-authorization-manager.md)
- OAS 3.1, Security Requirement Object: <https://spec.openapis.org/oas/v3.1.0#security-requirement-object>
- Microsoft identity platform, App Roles: <https://learn.microsoft.com/entra/identity-platform/howto-add-app-roles-in-apps>
