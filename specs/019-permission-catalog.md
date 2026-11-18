# 019 — Permission catalog

| Field | Value |
|---|---|
| Status | Superseded by the decision of 2026-11-13 ([Removal of the permission catalog](README.md#removal-of-the-permission-catalog-2026-11-13)) |
| Phase | 6 — Authorization contract (core and CLI) |
| Depends on | [008](008-cli-basic.md), [013](013-oas-route-locator.md), [018](018-authorization-extension.md) |
| Modules | `openapi-gateway-core`, `openapi-gateway-cli`, `gateway`, `catalog/` |

> **Superseded (2026-11-13).** The product owner removed the permission catalog. The catalog file, the CLI option `--catalog`, the gateway property `gateway.security.catalog`, and the rules `audience-known`, `permission-exists`, and `permissions-unused` are removed. The gateway derives the audience, the token scope, and the `aud` check from `x-gateway.audience` ([023](023-per-audience-tokens.md), [024](024-authorization-manager.md)). The admin consent of `gateway-bff` and the CONTRACT rules `audience-unique` and `audience-not-first-party` limit the audiences ([021](021-entra-app-registrations.md), [018](018-authorization-extension.md)). The code of this spec is still on `main`. Follow-up code PRs remove it. See [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13). The text below is the old design. Do not implement it.

## Summary

This spec adds the permission catalog, a versioned file `catalog/permissions.yaml` that lists each backend Microsoft Entra ID app registration and its App Roles. The CLI reads the catalog with the new option `--catalog`, and the gateway reads it at startup. Three rules use the catalog: `audience-known`, `permission-exists`, and `permissions-unused`. With these rules, an OAS file cannot reference an unknown backend or a permission that Entra never issues. The validation stays deterministic and needs no Azure credentials in the pull request (PR) pipeline.

## Motivation

[018](018-authorization-extension.md) checks the form of permissions, not their existence. A typo such as `Orders.Raed` passes the format check. No token ever contains that permission, so every user gets 403 on that operation. The same problem happens with a wrong audience. A call to Entra in each PR is slow, needs credentials, and gives different results over time. A versioned file gives the same answer for the same commit.

## Scope

### In scope

- Catalog file format and its JSON Schema.
- `PermissionCatalog` model and `PermissionCatalogLoader` in core.
- CLI option `--catalog <file>`.
- Gateway property `gateway.security.catalog` and catalog loading at startup.
- Rules `audience-known`, `permission-exists`, `permissions-unused`.
- An initial `catalog/permissions.yaml`.

### Out of scope

- Synchronization of the catalog with Entra. See [020](020-permission-catalog-sync.md).
- Generation of client registrations from the catalog. See [023](023-per-audience-tokens.md).
- Entra configuration of the apps. See [021](021-entra-app-registrations.md).
- Review rules for catalog changes. The repository has no `CODEOWNERS` file, so the normal PR review applies. See [034](034-access-change-review.md).

## Design

### File format

```yaml
audiences:
  - name: orders-api
    client-id: 6f1c2e8a-0000-0000-0000-000000000000
    application-id-uri: api://orders-api
    roles: [Orders.Delete, Orders.Read, Orders.Refund]
  - name: payments-api
    client-id: 0b7d3f10-0000-0000-0000-000000000000
    application-id-uri: api://payments-api
    roles: [Payments.Read, Finance.Admin]
```

| Field | Type | Required | Rule |
|---|---|---|---|
| `audiences` | list | yes | MAY be empty. `catalog export` ([035](035-entra-permission-validation.md)) writes an empty list when no audience has a service principal. Before 035, the schema required at least one entry. |
| `name` | string | yes | Pattern `^[a-z0-9-]+$`. Unique. Used in messages. `catalog export` ([035](035-entra-permission-validation.md)) writes the client id. [023](023-per-audience-tokens.md) does not use it: the registration id is `entra-<client-id>`. |
| `client-id` | string | yes | Lowercase GUID, the same pattern as `x-gateway.audience` in [006](006-x-gateway-extension.md). Unique. |
| `application-id-uri` | string | yes | Application ID URI of the backend app. Unique. Pattern `^api://[A-Za-z0-9][A-Za-z0-9._~:/-]*[A-Za-z0-9]$` (2026-11-13, SEC-47-18). The loader checks the pattern after the follow-up PR of [035](035-entra-permission-validation.md). [023](023-per-audience-tokens.md) builds the scope `<application-id-uri>/access_as_user`. `catalog export` writes the single value of `servicePrincipalNames` that starts with `api://` ([035](035-entra-permission-validation.md), follow-up PR). |
| `roles` | list of strings | yes | Each value matches `^[A-Z][A-Za-z0-9]*(\.[A-Z][A-Za-z0-9]*)+$`. No duplicates. MAY be empty for an API that uses only `authenticated-only`. |

Other keys are errors (`additionalProperties: false`). The JSON Schema is `openapi-gateway-core/src/main/resources/permission-catalog.schema.json`. The loader validates it with networknt json-schema-validator, the same library as [006](006-x-gateway-extension.md).

The catalog key for an audience is `client-id`, because `x-gateway.audience` contains the client id.

### Model

Package: `com.johnowl.gateway.core.catalog`.

```java
public record CatalogAudience(String name, String clientId, String applicationIdUri, Set<String> roles) {
    public static final Pattern ROLE_FORMAT;    // the role pattern of the schema
}

public final class PermissionCatalog {
    public PermissionCatalog(String file, List<CatalogAudience> audiences);
    public PermissionCatalog(String file, String label, List<CatalogAudience> audiences,
            List<MissingAudience> missing);     // 035
    public static PermissionCatalog empty();
    public Optional<CatalogAudience> byClientId(String clientId);
    public Collection<CatalogAudience> audiences();
    public String file();                       // path, used in violations
    public String label();                      // name in messages: the file, or "Microsoft Entra ID" (035)
    public Optional<MissingAudience> missing(String clientId);   // 035
}

public record MissingAudience(String clientId, Reason reason, String ownerTenantId, String tenantId) { }   // 035

public final class PermissionCatalogLoader {
    public PermissionCatalog load(String path, String content);   // throws CatalogException
}
```

The members with the comment `035` come from [035](035-entra-permission-validation.md). A catalog from a file has the file as its label and no missing audiences. `audience-known` uses `label()` and `missing(clientId)` in its message.

`CatalogException` carries a list of messages with JSON pointers, for example `/audiences/1/client-id: duplicate value '6f1c...' (also in /audiences/0)`. The loader checks the schema first. It checks duplicates (`name`, `client-id`, `application-id-uri`, and roles in one audience) only if the schema check passes. `CatalogAudience.roles()` keeps the file order, so the index in a `permissions-unused` pointer is the index in the file.

### Rules

`audience-known` and `permission-exists` have category `CONTRACT`, `configurable=false`, and no suppression. Decision (2026-11-10): `permissions-unused` is a best-practice rule, not a CONTRACT rule. It has category `DESIGN`, default severity WARN, and `configurable=true`. It runs only in the CLI, and only when the CLI has a catalog source: `--catalog`, or `--entra` of [035](035-entra-permission-validation.md).

The rule constructors take the `PermissionCatalog`. `BuiltInRules.all(PermissionCatalog)` and `BuiltInRules.contract(PermissionCatalog)` add the catalog rules to the rule sets of [005](005-validation-engine.md). `contract(PermissionCatalog)` contains `audience-known` and `permission-exists`, not `permissions-unused`.

Decision (2026-11-12): `BuiltInRules.all()` and `BuiltInRules.contract()` stay. They contain the rules without the catalog rules. A caller uses them only when it has no catalog, and then all operations must be public (see "Behavior and errors").

Decision (2026-11-12): a `CrossSpecRule` can report a violation in a file that is not an OAS file, for example the catalog. The engine ([005](005-validation-engine.md)) accepts the file name. The severity policy and the baseline apply to that violation. The suppression policy does not apply, because `x-deviation` is only in OAS files.

Decision (2026-11-12): a rule fixture directory can have the file `catalog.yaml`. The fixture harness loads it and gives it to the rules of that directory. The rule ids of the harness and of the meta-test come from `BuiltInRules.all(PermissionCatalog.empty())`.

| Id | Kind | Category | Default severity | Checks | Pointer example |
|---|---|---|---|---|---|
| `audience-known` | `SpecRule` | CONTRACT | ERROR | `x-gateway.audience` is a `client-id` in the catalog. | `/x-gateway/audience` |
| `permission-exists` | `OperationRule` | CONTRACT | ERROR | Each permission in `all-of` and `any-of` is in `roles` of the catalog entry for the spec audience. | `/paths/~1orders~1{id}/get/x-gateway-authorization/all-of/0` |
| `permissions-unused` | `CrossSpecRule` | DESIGN | WARN (configurable, CLI only) | Each role in the catalog appears in at least one spec with the same audience. | `/audiences/0/roles/2` in `catalog/permissions.yaml` |

Details:

- `permission-exists` checks the roles of the spec audience only. `Finance.Admin` of `payments-api` does not satisfy an `orders-api` operation. This check matches the `aud` check at runtime ([018](018-authorization-extension.md)).
- `permission-exists` reports nothing if the audience is unknown. `audience-known` already reports the root cause.
- `permissions-unused` reports one violation for each unused role. The violation file is the catalog file.
- The severity of `permissions-unused` is fixed at WARN in this spec. It becomes configurable in `.oas-gateway.yaml` when [027](027-rule-configuration.md) is implemented. This spec does not depend on 027.

Example messages:

```text
audience-known: Audience '6f1c2e8a-...' is not in catalog/permissions.yaml.
permission-exists: Permission 'Orders.Raed' is not an App Role of audience 'orders-api'.
permissions-unused: Role 'Orders.Refund' of audience 'orders-api' is not used by any spec.
```

### CLI

```bash
java -jar oas-gateway-validate.jar --catalog catalog/permissions.yaml --format github \
  gateway/src/main/resources/openapi
```

`ValidateCommand` loads the catalog before the engine runs. `--catalog` has no default value. An invalid catalog gives `Error: Invalid permission catalog <file>:` and then one line for each problem, for example `  /audiences/1/name: duplicate value 'orders-api' (also in /audiences/0)`. Keys and values come from the reviewed catalog, so the CLI escapes control characters in the file name and in each problem, the same as in [008](008-cli-basic.md). A line feed in a key cannot start a new log line.

Without `--catalog`, the CLI runs `BuiltInRules.all()`, so the catalog rules do not run. Before the engine runs, it checks each parsed file with `EffectiveSecurity.firstProtectedOperation` (core). For the first file with a declared security requirement it writes an error and returns 2. After [035](035-entra-permission-validation.md), the message is `Error: --catalog or --entra is required, because <file> has protected operations, for example GET /orders.`

The workflow `.github/workflows/openapi.yml` ([011](011-pr-validation-pipeline.md)) passes `--catalog catalog/permissions.yaml` when the file exists. After [035](035-entra-permission-validation.md), it passes the catalog file that `catalog export --entra` wrote in a separate job.

### Gateway

The gateway reads the catalog from `gateway.security.catalog` and passes it to the validator of [013](013-oas-route-locator.md). The gateway does not start if the catalog is missing or invalid, or if the validation has an ERROR.

Implementation: `GatewaySecurityProperties` (`gateway.security`) has the field `catalog` (a Spring resource location). `OpenApiRoutingConfiguration` loads the catalog and runs `BuiltInRules.contract(catalog)`. Without the property, it runs `BuiltInRules.contract()`, and `OpenApiRouteDefinitionLocator` stops the startup with `gateway.security.catalog is required, because GET /orders is a protected operation.` if an access rule is not public.

[035](035-entra-permission-validation.md) changes this startup rule. The catalog is optional only when reload is disabled, `gateway.openapi.specs` is the default list, and each resolved OAS file is inside the application archive. Then a protected operation no longer stops the startup. In all other cases (reload ([014](014-route-reload.md)), another pattern, or a file outside the application archive), the gateway does not start without `gateway.security.catalog`. See "Gateway change" in 035.

### Initial catalog file

Decision (2026-11-12): the repository has `catalog/permissions.example.yaml` with the example of this spec. It does not have `catalog/permissions.yaml` yet, because the real client ids and roles come from the Entra app registrations ([020](020-permission-catalog-sync.md), [021](021-entra-app-registrations.md)). Until the real file exists, OAS files can have only public operations.

## Behavior and errors

| Condition | CLI result | Gateway result |
|---|---|---|
| Valid catalog, all audiences and permissions known | Exit 0 | Starts |
| Unknown audience or permission | ERROR violation, exit 1 | Fails startup |
| Unused catalog role | WARN violation, exit 0 with `--fail-on error` | Starts. The gateway does not run `permissions-unused`. |
| Catalog file missing or unreadable | Message, exit 2 | Fails startup |
| Catalog fails the schema, or has duplicate `name`, `client-id`, or `application-id-uri` | Message with pointer, exit 2 | Fails startup |
| No `--catalog`, and a spec has a non-public operation | Message `--catalog is required` (after [035](035-entra-permission-validation.md): `--catalog or --entra is required`), exit 2 | Not applicable |
| No `--catalog`, and all operations are public | Catalog rules do not run, exit by violations | Not applicable |

## Configuration

| Name | Type | Default | Meaning |
|---|---|---|---|
| CLI `--catalog <file>` | path | none | Permission catalog file. |
| `gateway.security.catalog` | String (Spring resource location) | none | Permission catalog location at runtime. [023](023-per-audience-tokens.md) reads the same property. In production, a `file:` location with the artifact `entra-catalog` of the latest successful scheduled run of `entra-drift.yml` on `main` (decision of [023](023-per-audience-tokens.md), 2026-11-13). The `file:` prefix is a deployment convention. The gateway does not check it, and tests use `classpath:` locations. |

## Acceptance criteria

- [x] `PermissionCatalogLoader` loads the example file and returns two audiences.
- [x] Each of these catalogs fails with a pointer to the bad value: unknown key, duplicate `client-id`, duplicate `name`, invalid role name.
- [x] A spec with an audience that is not in the catalog produces `audience-known` at `/x-gateway/audience`.
- [x] A spec for `orders-api` that requires `Finance.Admin` produces `permission-exists`, even though `payments-api` has that role.
- [x] A spec with an unknown audience produces no `permission-exists` violation.
- [x] A catalog role that no spec uses produces one `permissions-unused` WARN with the catalog file and the pointer of the role.
- [x] The CLI returns exit code 2 for a missing or invalid catalog file.
- [x] The CLI returns exit code 2 without `--catalog` when a spec has a non-public operation.
- [x] The gateway fails startup when `gateway.security.catalog` points to a missing file.
- [x] Severity configuration and `x-deviation` do not change the result of `audience-known` and `permission-exists`.
- [x] `permissions-unused: off` in `.oas-gateway.yaml` removes all `permissions-unused` violations from the CLI report. (Verified with [027](027-rule-configuration.md).)
- [x] Without `--catalog`, the CLI does not run `permissions-unused`.
- [x] `BuiltInRules.contract(PermissionCatalog)` does not contain `permissions-unused`.
- [x] The gateway starts with a catalog role that no spec uses, and logs no `permissions-unused` violation.

## Test plan

- Unit tests: `PermissionCatalogLoaderTest` (valid file, each schema error, each duplicate).
- Fixture tests: `src/test/resources/rules/<rule-id>/` with `valid.yaml`, `invalid.yaml`, `expected.txt`, and a shared test catalog `catalog.yaml`. The harness of [005](005-validation-engine.md) needs a way to pass the catalog (see "Open questions").
- Exclusivity test: all CONTRACT rules with the fixture catalog run on the `audience-known` and `permission-exists` fixtures. Each `invalid.yaml` gives only the rule id of its folder. A file with an unknown audience and the permission `Orders.Raed` gives no `permission-exists` violation.
- CLI tests: exit codes for the table in "Behavior and errors".
- Gateway test: context startup fails with a missing catalog, with an invalid catalog (duplicate `name`), and with an unknown permission. With a valid catalog and an unused role, the startup log has no `permissions-unused` line.

## Delivery

Two PRs:

1. Core: schema, model, loader, three rules, fixtures (about 350 lines without fixtures). Done.
2. CLI option `--catalog`, gateway property, initial `catalog/permissions.yaml` (about 150 lines). Done, with `catalog/permissions.example.yaml` instead of the real file.

## Decisions (2026-11-12)

> Note: [035](035-entra-permission-validation.md) replaces the versioned catalog file in CI. A trusted job runs `catalog export --entra` and writes a catalog file from Microsoft Graph. The `validate` job reads that file with `--catalog`. The CLI option `--entra` builds the `PermissionCatalog` from Microsoft Graph for local use. [020](020-permission-catalog-sync.md) is superseded.

- The team prefers to validate against Microsoft Entra ID instead of a versioned catalog file (option B of the discussion). The CLI will read the App Roles of each audience from Microsoft Graph in CI. A new spec replaces the catalog file. Until that spec is implemented, the catalog of this spec stays in the code.
- Open point for the new spec (review finding SEC-36-2): the CLI runs on untrusted PR code. The Graph credentials in CI MUST NOT be available to workflows that run untrusted PR code. Never use `pull_request_target` for this job. Prefer a federated credential limited to this repository and event, with no client secret, and the smallest Graph permission. A separate job that does not run PR code reads the App Roles and passes only the App Role list to the CLI job.
- Status of SEC-36-2: [035](035-entra-permission-validation.md) satisfies the MUST above. Only the `export` job of the reusable workflow `entra-catalog.yml` on `main` has `id-token: write` and the Azure login. It builds no code and runs no PR code. A separate job without `id-token` builds the CLI from `main`, and the `export` job checks the SHA-256 checksum of the jar before it runs it. The federated credentials bind to that workflow on `refs/heads/main`. The `validate` job, which builds and runs PR code, has no Graph credential. It receives only the exported catalog file.
- Catalog at runtime (2026-11-13): [023](023-per-audience-tokens.md) answers the shared question. The gateway reads the catalog from the location in `gateway.security.catalog`. The file is the artifact `entra-catalog` of the latest successful scheduled run of `entra-drift.yml` on `main`, and the release or deploy pipeline downloads it. In production, the location is a `file:` location; the gateway does not check the prefix. The registration id of [023](023-per-audience-tokens.md) is `entra-<client-id>`, not `entra-<name>`. These are defaults chosen by the team after the review of PR #47 (QA-47-1, QA-47-2, QA-47-10) and can change.

## Decisions (2026-11-13)

- The product owner removed the permission catalog. This spec is superseded. The open questions below are closed without an answer, because the catalog no longer exists. See [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13).

## Open questions

- `permissions-unused` gives false results when the CLI validates only a subset of the specs, or when specs of one audience live in another repository. Does the rule run only on the full OAS folder?
- Should `--catalog` default to `catalog/permissions.yaml` when the file exists?
- `PermissionCatalogLoader` has no explicit limit for the file size or the YAML nesting depth today. The gateway reads the catalog from a trusted pipeline ([023](023-per-audience-tokens.md)). Does the loader need the limits of [004](004-oas-loading.md), for example 5 MB and no aliases?

## References

- [018 — Authorization extension](018-authorization-extension.md)
- [020 — Permission catalog sync](020-permission-catalog-sync.md)
- [021 — Entra app registrations](021-entra-app-registrations.md)
- [023 — Per-audience tokens](023-per-audience-tokens.md)
- [027 — Rule configuration](027-rule-configuration.md)
