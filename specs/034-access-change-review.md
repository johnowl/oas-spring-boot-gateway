# 034 — Access change review

Note (2026-11-18): the repository is now public (`johnowl/oas-spring-boot-gateway`). The statements about a private repository in this spec describe the situation when the decision was made. For the current state, see [docs/ci-change-control.md](../docs/ci-change-control.md).

| Field | Value |
|---|---|
| Status | Draft |
| Phase | 9 — Change control |
| Depends on | [007](007-route-model.md), [008](008-cli-basic.md), [009](009-cli-github-and-json-output.md), [011](011-pr-validation-pipeline.md), [018](018-authorization-extension.md), [033](033-breaking-change-detection.md) |
| Modules | `openapi-gateway-core`, `openapi-gateway-cli`, `.github/workflows` |

## Summary

This spec adds the CLI subcommand `diff <base-dir> <head-dir>`. The subcommand compares the access rules of the operations between two versions of the OpenAPI Specification (OAS) files and reports each change that loosens access. A pull request (PR) job runs `diff` and fails unless a reviewer adds the label `access-change-approved`. Access rules live in the OAS files, so any developer can change them. This spec makes sure that each loosening of access is visible and approved. `diff` also lists each new or changed audience binding: the audience with the `upstream`, the `prefix`, and the `upstream-base-path` of its file. Reason: the gateway sends the token of an audience to the `upstream` of its file. A binding also fails the job without the label.

## Motivation

The CONTRACT rules of [018](018-authorization-extension.md) check that each access rule is complete and valid. They cannot see that a valid rule is weaker than before. For example, a PR changes `DELETE /orders/{id}` from `all-of: [Orders.Read, Orders.Delete]` to `all-of: [Orders.Read]`. Both versions pass validation, but every user with `Orders.Read` can now delete orders. A human reviewer can miss this one-line change in a large PR.

## Scope

### In scope

- The subcommand `diff` with the formats `text`, `github`, and `json`.
- The ten loosening changes in the table below.
- The job `access-changes` in `.github/workflows/openapi-change-control.yml` ([033](033-breaking-change-detection.md)).
- The label `access-change-approved`.
- The report of new and changed audience bindings (decision of 2026-11-13, see "Audience bindings").

### Out of scope

- New protected operations that do not take requests or a backend target from a base operation (see "Operation matching"). The normal rules check them, not `diff`.
- Removed operations. They do not loosen access. [033](033-breaking-change-detection.md) reports them as breaking changes.
- Changes that tighten access (for example a permission added to `all-of`).
- Changes of App Roles and of admin consent in Microsoft Entra ID. See the note below.
- A `CODEOWNERS` file (see "Decisions").
- The `sarif` format for `diff`.

> **Note (decision of 2026-11-13).** The permission catalog is removed ([Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)). A change of an App Role or of an admin consent in Entra does not go through a PR review. `diff` does not see it. No check compares the OAS files with Entra. `diff` still reports each loosening change in the OAS files, and `audience-changed` for an existing operation. Since the review of PR #48, `diff` also lists each new or changed audience binding (see "Audience bindings"). Examples: a new OAS file with an audience, or a changed `upstream`.

## Design

### Command

```text
oas-gateway-validate diff [--format text|github|json] <base-dir> <head-dir>
```

The subcommand loads both directories with `SpecFinder` and `SpecLoader` ([004](004-oas-loading.md)). It runs the CONTRACT rules and compiles the access rules of each directory with `AccessRuleCompiler` ([018](018-authorization-extension.md)). It does not read `.oas-gateway.yaml`. Best-practice rules do not run. `diff` reads and checks both directories before it writes anything on stdout.

Both arguments must be directories. The options come after `diff`, the same as for `rules` ([032](032-cli-rules-and-explain.md)). An option or a path before `diff` is a usage error with exit code 2.

A directory named `diff` must be written as `./diff`, the same as `rules` and `explain` ([032](032-cli-rules-and-explain.md), "A path with the name of a subcommand"). A CI script that validates `diff base head` without `./` would otherwise run the access diff and could get exit code 0 without a validation. So `diff` returns 2 when a file or directory named `diff` exists in the working directory:

```text
Error: A path named 'diff' exists. Write ./diff to validate it, or run the command from another directory.
```

`AccessDiff` (core) reads the file and the JSON pointer of each operation itself. `OperationAccess` of [018](018-authorization-extension.md) does not change.

### Operation matching

`diff` compares each head operation with base operations in three ways (decisions 2, 5, and 8 of 2026-11-15, see "Review findings (PR 1)"). All three use the same HTTP method. Two more comparisons follow the list.

1. **Public path.** The base operation with the same normalized public path: prefix + OAS path, with each `{variable}` replaced by `{}`, as in `route-collision` ([007](007-route-model.md)). A file can move, or the prefix can move: `diff` compares the public path that exists on both sides.
2. **Backend target.** Each base operation with the same backend target: the canonical `upstream`, the `upstream-base-path`, and the OAS path, with each `{variable}` replaced by `{}`, in lowercase, and without a trailing `/`. The canonical `upstream` has the scheme and the host in lowercase, and has no default port (`:80` for `http`, `:443` for `https`). So `http://Orders-Service:80` and `http://orders-service` are the same backend. `diff` cannot see that two host names (DNS aliases) or a host name and its IP address are the same backend. The gateway sends the requests of both operations to the same backend endpoint. So an operation that moves to a new prefix or path, and keeps its backend, is compared with its base rule. Example: `/orders-api` + `/orders/{id}` → `/shop` + `/orders/{orderId}` with the same `upstream`.
3. **Overlapped template.** Only for a head operation with a new public path: each base template that it overlaps. A template is an operation with a `{variable}` in its public path. Two paths overlap when they have the same number of segments, and at each segment the literals are equal or one segment is a variable, as in `route-collision`. Example: the new `/orders-api/orders/export` overlaps the base template `/orders-api/orders/{id}`. In the head, the literal path wins, so it takes requests that the template served.

The base can reach one backend target through two public paths with different rules. The backend target match therefore reports a change only if each base operation with that backend target has a stricter rule than the head operation. A head that keeps both public paths without a change gives no change.

Two more comparisons (findings SEC-73-5 and SEC-73-7):

- **Removed literal path.** The head no longer has a base literal path (a path without a `{variable}`). Then a head template with the same method that overlaps it takes its requests. Example: the head removes `/orders-api/orders/export`, so `/orders-api/orders/{id}` serves the request `GET /orders-api/orders/export`. `diff` compares the rule of the removed literal path with the rule of that head template. The template can be an existing template or a new one.
- **New HEAD or OPTIONS operation.** A head `HEAD` or `OPTIONS` operation without a base operation of the same method and public path is compared with the base `GET` operation of the same public path, and with each base `GET` operation of the same backend target. Many backends answer `HEAD` with the `GET` handler. For the backend target, the same rule as above applies: no change if one of these `GET` operations is not stricter.

A head operation without a match is new. A new public operation is always a loosening change (`new-public-operation`). `diff` ignores a new protected operation without a match. A removed base operation without a head template that takes its requests gives no change.

### Loosening changes

`diff` runs checks 1 to 6 for each matched pair of operations, in this order. When one of checks 1 to 3 reports a change, the later checks do not run for that pair. Checks 7 and 8 run only for a head operation with a new public path. Check 9 runs for each removed base literal path, and check 10 for each new `HEAD` or `OPTIONS` operation.

| Order | Change id | Condition (base → head) | Example |
|---|---|---|---|
| 1 | `became-public` | Base is not public. Head is public (`security: []`). | `all-of: [Orders.Read]` → `security: []` |
| 2 | `audience-changed` | Both are not public. The audience differs. | `orders-api` client id → `payments-api` client id |
| 3 | `authenticated-only-replaced-permissions` | Base has a non-empty `all-of` or `any-of`. Head has `authenticated-only: true` (both sets empty). | `any-of: [Orders.Refund]` → `authenticated-only: true` |
| 4 | `permission-removed-from-all-of` | A permission is in the base `all-of` and not in the head `all-of`. One change per permission. | `all-of: [Orders.Read, Orders.Delete]` → `all-of: [Orders.Read]` |
| 5 | `permission-added-to-any-of` | The base `any-of` is not empty. A permission is in the head `any-of` and not in the base `any-of`. One change per permission. | `any-of: [Orders.Refund]` → `any-of: [Orders.Refund, Finance.Admin]` |
| 6 | `any-of-removed` | The base `any-of` is not empty. The head has no `any-of`, and its `all-of` has no permission of the base `any-of`. | `all-of: [Orders.Read], any-of: [Orders.Refund]` → `all-of: [Orders.Read]` |
| 7 | `new-public-operation` | No base operation has the public path. Head is public. | none → `GET /orders-api/status` with `security: []` |
| 8 | `shadows-protected-template` | No base operation has the public path. The head operation overlaps a base template, and checks 1 to 6 report a change for the template rule and the head rule. One change per template. | base `GET /orders-api/orders/{id}` with `all-of: [Orders.Read]` → new `GET /orders-api/orders/export` with `authenticated-only: true` |
| 9 | `literal-removed-to-template` | The head removes a base literal path. A head template with the same method overlaps it, and checks 1 to 6 report a change for the literal rule and the template rule. The change is at the head template. | base `GET /orders-api/orders/export` with `all-of: [Orders.Export]` and `GET /orders-api/orders/{id}` with `all-of: [Orders.Read]` → head without `/orders/export` |
| 10 | `head-weaker-than-get` | A new `HEAD` or `OPTIONS` operation, and checks 1 to 6 report a change for the rule of the base `GET` and the head rule. One change per `GET`. | base `GET /orders-api/orders/{id}` with `all-of: [Orders.Read]` → new `HEAD /orders-api/orders/{id}` with `authenticated-only: true` |

Checks 4, 5, and 6 all run for a pair when checks 1 to 3 report nothing. A head operation can get changes from more than one match. `diff` reports each change once.

Precise cases:

- Base without `any-of`, head with `any-of: [A.X]`: not reported. A new `any-of` adds a condition, so access is tighter.
- `all-of: [Orders.Delete]` → `all-of: [Orders.Admin]`: reported as `permission-removed-from-all-of` for `Orders.Delete`. A reviewer must confirm the replacement.
- `authenticated-only: true` → `all-of: [Orders.Read]`: not reported (tighter).
- A change in the order of permissions in a list: not reported, because the lists are sets.
- `all-of: [Orders.Read], any-of: [Orders.Refund]` → `all-of: [Orders.Read]`: reported as `any-of-removed`.
- `all-of: [Orders.Read, Orders.Write], any-of: [Orders.Refund]` → `all-of: [Orders.Read]`: reported as `any-of-removed` and as `permission-removed-from-all-of` for `Orders.Write`.
- `all-of: [Orders.Read], any-of: [Orders.Refund, Orders.Admin]` → `all-of: [Orders.Read, Orders.Refund]`: not reported. The head needs `Orders.Refund`, so the base `any-of` condition still holds.
- A new public operation that overlaps a protected base template: reported as `new-public-operation` and as `shadows-protected-template`.
- A new literal path with a rule that is not weaker than the template rule, for example `all-of: [Orders.Read, Orders.Export]` over `all-of: [Orders.Read]`: not reported.
- The prefix moves, and the public path stays: `/orders-api` + `/orders/{id}` → `/orders-api/orders` + `/{id}`. The public path match compares the rules. The binding gives `binding-changed` at `/x-gateway/prefix`.
- The operation moves to `/shop/orders/{id}` with the same backend target, and becomes public: reported as `became-public` (backend target match) and as `new-public-operation`.
- A change from a backend target match ends with `(the same backend as <METHOD> <public path> in the base)`, so that the reviewer sees the base operation.

### Output

The public path of a change is the public path of the operation in the head. The message of a change is one of these texts:

| Change id | Message |
|---|---|
| `became-public`, `audience-changed`, `authenticated-only-replaced-permissions`, `any-of-removed` | `base: <rule>; head: <rule>`. A rule is `public`, or `audience=<audience>` followed by `, all-of=[...]`, `, any-of=[...]`, or `, authenticated-only`. The permissions are sorted. |
| `permission-removed-from-all-of` | `<permission> removed from all-of` |
| `permission-added-to-any-of` | `<permission> added to any-of` |
| `new-public-operation` | `base: none; head: public` |
| `shadows-protected-template` | `overlaps <METHOD> <public path of the template> in the base; base: <rule>; head: <rule>` |
| `literal-removed-to-template` | `takes the requests of the removed <METHOD> <public path of the literal>; base: <rule>; head: <rule>` |
| `head-weaker-than-get` | `weaker than GET <public path> in the base; base: <rule>; head: <rule>` |

A change from a backend target match adds ` (the same backend as <METHOD> <public path> in the base)` to the message (see "Operation matching").

Text format. Three lines for each change, then the bindings (see "Audience bindings"), an empty line, and a summary:

```text
LOOSENING permission-removed-from-all-of DELETE /orders-api/orders/{id}
  gateway/src/main/resources/openapi/orders.yaml#/paths/~1orders~1{id}/delete
  Orders.Delete removed from all-of
LOOSENING became-public GET /orders-api/orders/{id}
  gateway/src/main/resources/openapi/orders.yaml#/paths/~1orders~1{id}/get
  base: audience=6f1c2e8a-..., all-of=[Orders.Read]; head: public

2 access loosening changes in 2 operations.
```

If there are bindings, the summary adds their number, for example `2 access loosening changes in 2 operations. 1 new or changed audience binding to review.` Without changes and bindings, the output is the summary line only: `0 access loosening changes in 0 operations.`

The summary line is a contract between versions of the CLI (finding QA-75-2). The job `access-changes` accepts exit code 1 only when the last line of stdout matches the summary pattern in the workflow, and the job runs the CLI of the base commit. So the line is stable. A change of the line takes two PRs: the first PR widens the pattern in the workflow, and the second PR changes the line. `DiffReportTest` reads the pattern from the workflow file and checks the summary line against it.

GitHub format ([009](009-cli-github-and-json-output.md)). One `::error` line for each change, at the operation in the head file, then the `::error` lines of the bindings, then the summary line:

```text
::error file=gateway/src/main/resources/openapi/orders.yaml,line=14,col=5::Access loosening (permission-removed-from-all-of) of DELETE /orders-api/orders/{id}: Orders.Delete removed from all-of.
```

JSON format. The layout is stable: a change that removes or renames a field increments `formatVersion`. A new field does not change it. The file uses two-space indentation and `\n` line ends, the same as the JSON report of [009](009-cli-github-and-json-output.md). `permission` is `null` for each change id except `permission-removed-from-all-of` and `permission-added-to-any-of`.

```json
{
  "formatVersion": 1,
  "tool": { "name": "oas-gateway-validate", "version": "0.1.0" },
  "changes": [
    {
      "changeId": "permission-removed-from-all-of",
      "method": "DELETE",
      "publicPath": "/orders-api/orders/{id}",
      "file": "gateway/src/main/resources/openapi/orders.yaml",
      "pointer": "/paths/~1orders~1{id}/delete",
      "permission": "Orders.Delete",
      "message": "Orders.Delete removed from all-of"
    }
  ],
  "bindings": []
}
```

The output is sorted by public path, then method, then change id, then permission.

The values in the output come from the files under review. Each format escapes them:

- Text format: the control characters, the Unicode line separators, and the bidirectional controls become Java escapes, for example `\n` and `\u202E` ([008](008-cli-basic.md)). No line can start a GitHub workflow command: a `::` at the start of a value and each `##[` are escaped ([032](032-cli-rules-and-explain.md)).
- GitHub format: the same Java escapes, and then the escapes of workflow commands ([009](009-cli-github-and-json-output.md)). Each annotation stays on one line.
- JSON format: the same characters, and each surrogate, become JSON escapes `\uXXXX`. A JSON parser reads the original value back ([032](032-cli-rules-and-explain.md)).

### Audience bindings

Decision (2026-11-13, team default after the review of PR #48, finding SEC-48-1). An audience binding is the `x-gateway.audience` of one OAS file with the `x-gateway.upstream`, the `x-gateway.prefix`, and the `x-gateway.upstream-base-path` of the same file. Each file with at least one operation has a binding. A file without an audience (only public operations) has a binding without an audience (finding SEC-73-6): the gateway sends its public requests to its `upstream`, so a new `upstream` needs a review too. The gateway sends the token of the audience to the `upstream` of the same file ([025](025-token-relay-and-header-hygiene.md)). The CONTRACT rule `audience-unique` ([018](018-authorization-extension.md)) allows one file for each audience. It cannot see a change of `upstream` in that file. `diff` therefore lists the bindings that a PR adds or changes, so that the reviewer sees them.

`diff` collects the bindings of the base and of the head. `diff` compares the `upstream`, the `prefix`, and the `upstream-base-path` of each binding (see "Implementation notes", and decision 6 of 2026-11-15). It reports each head binding that is not in the base:

| Kind | Condition (base → head) | Example |
|---|---|---|
| `binding-new` | The head has a binding with an audience that no base file has. Example: a new OAS file. | none → `6f1c2e8a-...` with `http://orders-service:8080` |
| `binding-changed` | The base has the same audience with another `upstream`, `prefix`, or `upstream-base-path`. | `6f1c2e8a-...` with `http://orders-service:8080` → `6f1c2e8a-...` with `http://reporting-service:8080` |

A binding without an audience gives no entry if a base binding (with or without an audience) has the same `upstream`, `prefix`, and `upstream-base-path`. Otherwise it gives `binding-changed` for the first base binding without an audience with the same `prefix`, and `binding-new` if there is none. Example: the public-only `docs.yaml` changes its `upstream` from `http://docs-service:8080` to `http://169.254.169.254`. This gives `binding-changed`, and `diff` returns 1.

`diff` compares the values as text. The pointer of an entry is the first changed field in this order: `/x-gateway/upstream`, `/x-gateway/prefix`, `/x-gateway/upstream-base-path`. If the head file no longer has the changed field, the pointer is `/x-gateway`. A `binding-new` entry has the pointer `/x-gateway/upstream`. A binding in a moved or renamed file, with the same three values, gives no entry.

Reason for the prefix and the upstream base path: with a new prefix, the public paths of the file change. With a new upstream base path, the gateway sends the token to another path of the backend, for example `/admin` instead of `/v1`. The `binding-changed` entry shows the change to the reviewer.

A change of the audience of an existing file gives `binding-new` for the new audience. `audience-changed` reports the same change for each operation.

A binding fails the job like a loosening change (decision 3 of 2026-11-15, finding SEC-74-1). With a binding, `diff` returns 1, so the job needs the label `access-change-approved`. Reason: a new `upstream`, `prefix`, or `upstream-base-path` can send the token of the audience to another backend. The first PR with an audience therefore needs the label once, also with an empty base. The reviewer MUST check that each listed `upstream` is the address of the backend of the audience ([021](021-entra-app-registrations.md), onboarding step 12).

Text format: three lines for each binding, after the loosening changes. The base of a `binding-new` entry is `none`. The `upstream-base-path` and the prefix are missing for a file without them. A binding without an audience has the first line `BINDING <kind> without an audience`:

```text
BINDING binding-changed audience=6f1c2e8a-0000-0000-0000-000000000000
  gateway/src/main/resources/openapi/orders.yaml#/x-gateway/upstream
  base: upstream=http://orders-service:8080, upstream-base-path=/v1, prefix=/orders-api; head: upstream=http://reporting-service:8080, upstream-base-path=/v1, prefix=/orders-api
```

GitHub format: one `::error` line for each binding, at the changed field of the head file. For the pointer `/x-gateway`, the location is the `x-gateway` key:

```text
::error file=gateway/src/main/resources/openapi/orders.yaml,line=6,col=3::Audience binding changed: 6f1c2e8a-0000-0000-0000-000000000000 now sends tokens to http://reporting-service:8080 with the upstream base path /v1 for the prefix /orders-api (base: http://orders-service:8080 with the upstream base path /v1 for the prefix /orders-api). Check that this upstream is the backend of the audience.
::error file=gateway/src/main/resources/openapi/payments.yaml,line=6,col=3::New audience binding: 7a2d3f9b-0000-0000-0000-000000000000 sends tokens to http://payments-service:8080 for the prefix /payments-api. Check that this upstream is the backend of the audience.
```

A file without a prefix shows `without a prefix` instead of `for the prefix ...`. A file without an upstream base path has no `with the upstream base path ...`. A binding without an audience has another message:

```text
::error file=gateway/src/main/resources/openapi/docs.yaml,line=6,col=3::Binding without an audience changed: the public operations now go to http://169.254.169.254 for the prefix /docs (base: http://docs-service:8080 for the prefix /docs). Check that this upstream is the right backend.
```

A new binding without an audience starts with `New binding without an audience: the public operations go to`.

### Job summary

With `--format github`, the CLI also appends Markdown tables to the job summary: the file in the environment variable `GITHUB_STEP_SUMMARY`. It writes the table of the loosening changes if there is at least one change, and the table of the bindings if there is at least one binding. It writes nothing if the variable is not set. The `text` and `json` formats write no job summary. If the CLI cannot write the file, it returns 2, because the tables are a control for reviewers (the same rule as in [035](035-entra-permission-validation.md)).

GitHub shows only the first 10 error annotations of a step. So the job summary is the complete list of the changes and the bindings (finding SEC-75-3). The table of the loosening changes comes first:

```markdown
### Access loosening changes

Each change makes an operation easier to call. Check that each change is intended.

| Change | Method | Public path | Details | File | Pointer |
|---|---|---|---|---|---|
| permission-removed-from-all-of | ` DELETE ` | ` /orders-api/orders/{id} ` | ` Orders.Delete removed from all-of ` | ` gateway/src/main/resources/openapi/orders.yaml ` | ` /paths/~1orders~1{id}/delete ` |
```

The column "Details" has the message of the change. Then the table of the bindings:

```markdown
### Audience bindings

The gateway sends the token of each audience to its upstream. Check that each upstream is the backend of the audience.

| Change | Audience | Upstream (base) | Upstream (head) | Prefix (base) | Prefix (head) | Upstream base path (base) | Upstream base path (head) | File |
|---|---|---|---|---|---|---|---|---|
| binding-changed | ` 6f1c2e8a-0000-0000-0000-000000000000 ` | ` http://orders-service:8080 ` | ` http://reporting-service:8080 ` | ` /orders-api ` | ` /orders-api ` | ` /v1 ` | ` /v1 ` | ` gateway/src/main/resources/openapi/orders.yaml ` |
| binding-new | ` 7a2d3f9b-0000-0000-0000-000000000000 ` | none | ` http://payments-service:8080 ` | none | ` /payments-api ` | none | none | ` gateway/src/main/resources/openapi/payments.yaml ` |
```

The values come from the files under review. A file name can contain Markdown, a link, or HTML. The CLI makes each value of both tables inert, with the rules of [035](035-entra-permission-validation.md). Only the change id and the binding kind come from the CLI, so they are not in a code span:

1. The CLI escapes control characters, Unicode line separators, and bidirectional controls as Java escapes, the same as in the text format. So each value stays in its table row.
2. Each value is in a code span. Markdown shows a code span as literal text, so a link or HTML in the value has no effect.
3. A backslash cannot escape a backtick inside a code span. The CLI therefore uses a delimiter of backticks that is one backtick longer than the longest run of backticks in the value. It puts one space after the opening delimiter and before the closing delimiter. Example: the value ``a`b`` gives ``` `` a`b `` ```.
4. The CLI escapes each `|` as `\|`, so the value cannot add a table column. GitHub accepts this escape also inside a code span in a table.

A missing value (the base of `binding-new`, a missing audience, a missing prefix, or a missing upstream base path) is the text `none`, without a code span.

### JSON format of bindings

A second top-level array `bindings`, with the fields `kind`, `audience` (`null` without an audience), `baseUpstream` (`null` for `binding-new`), `headUpstream`, `basePrefix` (`null` for `binding-new` and without a prefix), `headPrefix` (`null` without a prefix), `baseUpstreamBasePath` (`null` for `binding-new` and without an upstream base path), `headUpstreamBasePath` (`null` without an upstream base path), `file`, and `pointer`:

```json
  "bindings": [
    {
      "kind": "binding-changed",
      "audience": "6f1c2e8a-0000-0000-0000-000000000000",
      "baseUpstream": "http://orders-service:8080",
      "headUpstream": "http://reporting-service:8080",
      "basePrefix": "/orders-api",
      "headPrefix": "/orders-api",
      "baseUpstreamBasePath": "/v1",
      "headUpstreamBasePath": "/v1",
      "file": "gateway/src/main/resources/openapi/orders.yaml",
      "pointer": "/x-gateway/upstream"
    }
  ]
```

The bindings are sorted by audience, then file.

### Workflow job

The job `access-changes` is in the workflow of [033](033-breaking-change-detection.md). It has the same trigger, including `edited`, `labeled`, and `unlabeled`. It reads the base and the head in the same way as the job `breaking-changes`:

- The checkout has `fetch-depth: 2` and `persist-credentials: false`. The job does not run `git fetch`.
- The job has the same job env, and runs the same scripts, as the job `breaking-changes` for the steps "Install oasdiff and yq", "Check the merge commit", "Check the location of the OAS files", "Extract base and head OAS files", and "Read the label approval". YAML anchors share the scripts (`&name` in `breaking-changes`, `*name` in `access-changes`), so the two jobs cannot drift apart.
- The step "Check the merge commit" checks that the second parent of the merge commit is the head of the PR. The base is the first parent, and the head is the merge commit.
- The job reads the OAS files of both commits from the Git objects with `git ls-tree -z` and `git cat-file blob`, not with `git archive` or the checkout. It fails on a symbolic link, a submodule, a path component of the OAS folder that is not a folder, `x-gateway.prefix` that is not one string, and `x-since-date`. It also fails when the PR changes the location of the OAS files. The label does not change these results.
- Each action is pinned to a commit SHA, like in 033. No `run:` script contains `${{ }}`: each value from the event comes through an environment variable.

```yaml
  access-changes:     # 20 minutes; contents: read, pull-requests: read; env: *tools-env
    steps:
      # checkout: fetch-depth 2, persist-credentials false
      # Install oasdiff and yq                (*install-tools)
      # Check the merge commit (id merge)     (*check-merge): sets base and merge
      # Check the location of the OAS files   (*check-location)
      # Extract base and head OAS files (id oas) (*extract-oas): sets result=unchanged|compare
      # setup-java: Temurin 25, without cache, overwrite-settings false. Only if result == 'compare'
      # Build the CLI from the base commit (id cli): only if result == 'compare'; sets jar and sha256
      # Read the label approval (id label)    (*read-label), with LABEL access-change-approved: only if result == 'compare'
      # Access diff: reads RESULT, APPROVED, CLI_JAR, and CLI_SHA256 from env
```

#### CLI of the base commit

Decision 9 of 2026-11-15 (finding SEC-74-2): the job never builds or runs a CLI of the PR head. A PR can change the code of `diff`, so a CLI of the PR head could hide its own loosening changes. No release of the CLI exists yet, so the step "Build the CLI from the base commit" builds it from the base:

1. `git archive` of the base commit writes the tree of the base to `$RUNNER_TEMP/cli-source`. `git archive` reads the `.gitattributes` of the base tree only, not of the checkout. So no file of the PR is on the build path: not the code, not `mvnw`, not `.mvn/`, and not `pom.xml`.
2. `./mvnw -B -pl openapi-gateway-cli -am package -DskipTests` runs in that folder. `mvnw` of the base checks the SHA-256 of the Maven distribution.
3. The build uses an empty Maven folder in `$RUNNER_TEMP/maven` (`MAVEN_USER_HOME` and `maven.repo.local`). `-s` and `-gs` replace the user settings and the global settings with the empty file `$RUNNER_TEMP/maven/settings.xml` (`<settings/>`), and `setup-java` runs with `overwrite-settings: false` (finding SEC-75-4). So the build reads no `settings.xml` of the runner. `setup-java` has no Maven cache. Reason: the other jobs of the PR build the code of the PR with `cache: maven`, and the cache key comes from the `pom.xml` files of the PR. So a PR can save a cache with changed Maven plugins, and the base build would load it. Without a cache, the build downloads its dependencies on each run (about one minute).
4. The step copies the jar to `$RUNNER_TEMP/cli/oas-gateway-validate.jar` and writes its SHA-256 to the log and to the output `sha256`. The step "Access diff" checks this SHA-256 before it runs the jar.

When a release of the CLI exists, the job can download it with a pinned version and SHA-256 instead.

The CLI of the base checks the head files with the CONTRACT rules of the base (finding QA-75-1). This has two effects:

- A PR that changes the CONTRACT rules and also uses the change in an OAS file fails with exit code 2, because the rules of the base reject the new use. Land a change of the CONTRACT rules in its own PR, before any OAS file uses it.
- When the base cannot build the CLI (a broken base), the job fails for every PR that changes an OAS file. An administrator merges the fix of the base without this check, and the PRs then pass again.

#### Step "Access diff"

| Condition | Result |
|---|---|
| `result` is `unchanged`: the PR does not change the OAS files | Pass. The job does not build the CLI. |
| The head has no OAS file (the PR removes all of them), and the label counts | Pass with a notice. The job does not run `diff`, because `diff` returns 2 for an empty head. |
| The head has no OAS file, and the label does not count | Fail (finding SEC-75-1). The gateway does not start without an OAS file, and an empty `$OAS_DIR` can mean that the OAS files moved to a place that no check reads. |
| The jar does not have the SHA-256 of the build step | Fail |
| `diff` returns 0 | Pass |
| `diff` returns 1, its last line is the summary line, and the label counts | Pass with a notice. The annotations and the job summary stay. |
| `diff` returns 1, its last line is the summary line, and the label does not count | Fail |
| `diff` returns 1 without the summary line as the last line | Fail. The JVM and an uncaught exception also return 1, so only the summary line shows the exit code 1 of `diff`. |
| `diff` returns 2 or another code | Fail. The label does not change this result. On exit code 2, a notice says that the CLI of the base checks the head files with the CONTRACT rules of the base (see "CLI of the base commit"). |
| `diff` reports more than 10 changes and bindings | A warning says that GitHub shows only 10 annotations, and that the reviewer reads the complete list in the job summary (finding SEC-75-3). The result does not change. |

The step runs `diff --format github ../base/$OAS_DIR $OAS_DIR` in `$RUNNER_TEMP/oas/head`. This folder has only the head OAS folder. So the annotations name the head files with their paths in the repository, and no path named `diff` exists in the working directory. The runner sets `GITHUB_STEP_SUMMARY`, so the CLI writes the tables of the changes and of the bindings to the job summary. Until the CLI of the base has the table of the changes, the job summary has only the table of the bindings.

#### Pull requests from forks

The same as in [033](033-breaking-change-detection.md), "Pull requests from forks". The job needs no secret and only read permissions, so it runs the same for a fork. The CLI comes from the base, also for a fork. When the token cannot read the activity of the fork, the label does not count (fail closed). A maintainer can then push the commits to a branch of the repository and open a new PR.

#### Enforcement

The check is advisory until a repository administrator configures a ruleset or a branch protection rule that requires `access-changes` on `main` ([011](011-pr-validation-pipeline.md), "Advisory checks"). The repository is private and on the GitHub Free plan, which has neither. Also with a required check, a `pull_request` workflow runs the workflow file of the PR. A PR can change or remove this job, or build the CLI from its own code, in its own copy of the file. The recommended configuration is a ruleset with a required workflow. Until then, a reviewer MUST read each change to `.github/workflows/**` and `specs/**`.

### Label `access-change-approved`

The label counts only under the rules of [033](033-breaking-change-detection.md), "Label `breaking-change-approved`" (decision of 2026-11-15, finding SEC-72-2). The step "Read the label approval" runs the same script with `LABEL: access-change-approved`.

| Rule | Value |
|---|---|
| Name | `access-change-approved` |
| Effect | Exit code 1 of `diff` does not fail the job. Annotations and the job summary stay visible. Exit code 2 and the failures of the extraction steps still fail the job. |
| Who adds it | A user with the role admin or maintain, after the review of each change and each binding. The security team does the review. |
| Live state | The step reads the label, the timeline, the activity of the head branch, and the role of the actor through the GitHub API. When a call fails, the label does not count. |
| New commits | The label counts only when it was added after the last push. After a new push, remove the label and add it again. |
| Removal | Removing the label runs the job again, and the job fails again on exit code 1. |

## Behavior and errors

| Condition | Exit code |
|---|---|
| No loosening change and no binding | 0 |
| At least one loosening change, or at least one `binding-new` or `binding-changed` entry | 1 |
| Usage error, missing directory, an argument that is not a directory, or I/O error | 2 |
| An option or a path before `diff`, or `--format sarif` | 2 (usage error) |
| A file or directory named `diff` exists in the working directory | 2 |
| A file in either directory has a parse error or a CONTRACT ERROR, so access rules cannot be compiled | 2 (stderr names the directory, and each violation with its file and pointer) |
| Base directory is empty or has no OAS file | 1 if a head file has an operation: each such file gives `binding-new`. 0 if no head file has an operation. |
| Head directory is empty or has no OAS file | 2 (a wrong head directory must not pass; see "Implementation notes") |
| `--format github`, `GITHUB_STEP_SUMMARY` is set, and the CLI cannot write the file | 2 |

All errors go to stderr. On exit code 2, stdout is empty, except for an error of the job summary: the CLI writes the job summary after stdout.

## Acceptance criteria

- [x] Each of the ten change ids is reported for the example in its table row, and `diff` returns 1.
- [x] A removed literal path that a weaker head template now serves gives `literal-removed-to-template`, for an existing and for a new template.
- [x] A new `HEAD` or `OPTIONS` operation with a weaker rule than the base `GET` gives `head-weaker-than-get`.
- [x] A changed `upstream` in a file without an audience gives `binding-changed` without an audience, and `diff` returns 1.
- [x] `http://Orders-Service:80` and `http://orders-service` give the same backend target.
- [x] Removing the whole `any-of` while `all-of` stays gives `any-of-removed`. With a permission removed from `all-of`, both changes are reported.
- [x] A new literal path with a weaker rule than the base template that it overlaps gives `shadows-protected-template`.
- [x] A new public operation gives `new-public-operation` and returns 1.
- [x] An operation that moves to a new prefix or path with the same backend target, and gets a weaker rule, is reported with the id of the weaker rule.
- [x] A prefix move with the same public path and a weaker rule gives the loosening change and `binding-changed` at `/x-gateway/prefix`.
- [x] A removed prefix gives `binding-changed` at `/x-gateway`. An added prefix gives `binding-changed` at `/x-gateway/prefix`.
- [x] A changed `upstream-base-path` gives `binding-changed` at `/x-gateway/upstream-base-path`.
- [x] A binding without a prefix shows `without a prefix` in the text and GitHub formats, and `basePrefix` and `headPrefix` are `null` in JSON.
- [x] Removing two permissions from `all-of` produces two `permission-removed-from-all-of` changes.
- [x] A change from protected to public produces only `became-public` for that operation.
- [x] Adding `any-of` to an operation that had no `any-of` produces no change and returns 0.
- [x] Adding a permission to `all-of` produces no change and returns 0.
- [x] A new protected operation without a match in the head and a removed operation in the base produce no change.
- [x] Renaming `{id}` to `{orderId}` without an access change produces no change.
- [x] Moving an operation to another file with the same prefix, without an access change, produces no change.
- [x] A head file with a CONTRACT ERROR returns 2.
- [x] An empty base directory returns 1 if a head file has an operation, because the file gives `binding-new`.
- [x] A head directory without OAS files returns 2.
- [x] `--format json` output matches the format above.
- [x] `--format github` prints one `::error` line per change with the head file, line, and column.
- [ ] In the workflow, a loosening change fails `access-changes` without the label, and passes with `access-change-approved`. (Checked in the container harness with a stub of `gh api`. Needs a test PR on GitHub.)
- [ ] A changed `upstream` fails `access-changes` without the label, passes with the label, and the job summary of the run shows the table of the bindings. (Checked in the container harness. Needs a test PR on GitHub.)
- [x] The job `access-changes` has the same trigger as `breaking-changes`, `contents: read` and `pull-requests: read`, and actions pinned by commit SHA. No `run:` script contains `${{ }}`. actionlint reports no error.
- [x] The job runs the same env and the same scripts as `breaking-changes` for the install, the merge commit check, the location check, the extraction, and the label check.
- [x] A PR without a change of the OAS files passes, and the job does not build the CLI.
- [x] The job builds the CLI from `git archive` of the base commit, and runs only the jar with the SHA-256 of the build step. A jar that changes after the build step fails the job, also with the label.
- [x] A PR that changes `DiffCommand` (core and CLI) so that `diff` returns 0, and also changes `mvnw`, `.mvn/`, and `.gitattributes`, still fails the job on its loosening change. The CLI of the head returns 0 for the same files.
- [x] A label that was added before the last push, by a user with the role triage, or that cannot be read, does not count. The label `breaking-change-approved` does not count for this job.
- [x] A head file with a CONTRACT error fails the job with exit code 2 of `diff`, also with a valid label.
- [x] Exit code 1 without the summary line of `diff` (for example an error of the JVM) fails the job, also with a valid label.
- [x] The first OAS file with an audience, on an empty base, fails the job without the label (`binding-new`).
- [x] A PR that removes all OAS files fails `access-changes` without the label, and passes with the label (SEC-75-1).
- [x] A file in `src/main/resources/openapi` of another module, a change of `spring.config.import`, and a new YAML file in `gateway/src/main/resources` fail the location step of both jobs (SEC-75-1, SEC-75-2; see [033](033-breaking-change-detection.md)).
- [x] More than 10 changes give a warning, and the job summary has one row for each change (SEC-75-3).
- [x] The build of the CLI passes with a `~/.m2/settings.xml` that Maven cannot parse, because it reads only the empty settings of `-s` and `-gs` (SEC-75-4).
- [x] Exit code 2 of `diff` prints a notice about the CONTRACT rules of the base (QA-75-1).
- [x] `DiffReportTest` checks the summary line against the pattern of the workflow (QA-75-2).
- [x] A symbolic link in the OAS folder, a changed location of the OAS files, and a `HEAD` that is not the merge commit of the PR head fail the job.
- [ ] A PR from a fork runs the job with a read-only token, and the label does not count when the activity of the fork cannot be read. (Needs a fork PR on GitHub.)
- [ ] An administrator requires `access-changes` on `main` with a ruleset. (Needs a plan with rulesets. Until then, the check is advisory.)
- [x] A new head file with `x-gateway.audience` gives one `binding-new` entry with the audience, the `upstream`, and the file. `diff` returns 1, also without a loosening change.
- [x] `--format github` prints one `::error` line per binding at the changed field of the head file.
- [x] A changed `upstream` in a file with an audience gives one `binding-changed` entry with the base and the head `upstream`.
- [x] A changed `prefix` in a file with an audience gives one `binding-changed` entry at `/x-gateway/prefix`.
- [x] A changed `upstream` in a file with only public operations, and without an audience, gives `binding-changed` (SEC-73-6).
- [x] An unchanged binding in a moved or renamed file gives no binding entry.
- [x] With `--format github` and `GITHUB_STEP_SUMMARY` set to a temporary file, the file contains the table of "Audience bindings" with one row for each binding, and the table of the loosening changes with one row for each change. Without the variable, `diff` writes no summary and does not fail.
- [x] A job summary that cannot be written returns 2.
- [x] `--format json` has the array `bindings` with the fields of "Audience bindings".
- [x] In each format, a file name or a value with a line break, `::`, `##[`, a bidirectional control, a backtick, or `|` cannot start a workflow command, add a table row or column, or leave its code span.
- [x] A file or directory named `diff` in the working directory returns 2. `./diff` validates the directory.

## Test plan

- Unit tests for the comparison (`AccessDiffTest`): one test per change id, the precedence order, each precise case above, the sort order, and each binding kind.
- CLI command tests (`DiffCommandTest`) with fixture pairs in `openapi-gateway-cli/src/test/resources/diff/<case>/base/` and `.../head/`, with expected output files for each format (`expected.txt`, `expected-github.txt`, `expected.json`, `expected-summary.md`). `DiffReportTest` checks the escapes of each format and of the Markdown table.
- `CliJarIT` runs `diff` with the shaded jar, and validates `./diff`.
- Workflow lint with `actionlint`, and manual test PRs for the label behavior.
- The container harness of "Test harness" runs each `run:` script of the job on Git repositories with real merge commits.

### Test harness

The folder `.github/test/` has the container harnesses of the workflow `openapi-change-control.yml` (finding QA-75-3):

| File | Content |
|---|---|
| `run.sh` | Builds the image, packs the project of the working tree, and runs the harnesses. |
| `Dockerfile` | The image: `ubuntu:24.04` with `git`, `jq`, `curl`, `unzip`, and OpenJDK 25. |
| `lib.sh` | The shared functions: the Git repositories with real merge commits, the label fixtures, and the job runner. |
| `breaking-changes.sh` | The cases of the job `breaking-changes` ([033](033-breaking-change-detection.md)). |
| `access-changes.sh` | The cases of the job `access-changes`. Each repository holds the project, so the job really builds the CLI from the base commit. |
| `stub/gh` | A stub of `gh api`. It serves the label fixtures from files. A missing file is an HTTP error. |

The job runner reads the steps of the job from the workflow, with `explode(.)`, in their order. It skips a step whose `if:` condition is false, and it stops at the first failed step, like GitHub Actions. The env of each step is the env of the workflow, of the job, and of the step. The runner resolves the `${{ }}` expressions of the env. It stops with an error on an `if:` condition or an expression that it does not know. The harness does the checkout, the JDK of the image replaces `setup-java`, and the install step runs once, with the pinned checksums.

Run the harnesses from the root of the repository. They need Docker and the network:

```sh
.github/test/run.sh                         # both harnesses
.github/test/run.sh access-changes          # one harness
ONLY='c_location' .github/test/run.sh access-changes    # one case, after the install
```

Each harness prints one line `PASS` or `FAIL` for each case, and the log of each failed case. The exit code is 0 only when all cases pass. The Docker volume `oas-change-control-maven` keeps the Maven folder of the build step between the runs. CI does not run the harnesses, because they need Docker. Run them after each change of the workflow, and list the result in the PR description.

## Delivery

Two PRs:

1. Core comparison (`AccessDiff`, `AccessChange`, `AudienceBinding`) and the `diff` subcommand with the three formats and the job summary. Estimate: 380–460 changed lines without fixtures. The PR is about three times larger, so it comes as two stacked branches: `feat/034-access-diff` (the core comparison) and `feat/034-diff-command` (the subcommand, the formats, and the job summary).
2. The `access-changes` job (branch `feat/034-access-changes-job`). About 160 lines in the workflow. It builds on the workflow of [033](033-breaking-change-detection.md) and reads the OAS files of the base and of the head the same way as 033 (`git ls-tree -z` and `git cat-file blob` of the parents of the merge commit, not `git fetch`). It builds the CLI from `git archive` of the base commit (see "CLI of the base commit").

   Decision 9 of 2026-11-15 (finding SEC-74-2): the job builds the CLI from the BASE commit, or uses a pinned release of the CLI. It never builds the CLI from the PR head. Reason: a PR can change the code of `diff`, so a CLI of the PR head could hide its own loosening changes. The label `access-change-approved` passes only exit code 1. Exit code 2 always fails the job.

## Implementation notes (PR 1)

The spec was not precise in these points. PR 1 chooses the safer reading:

| Point | Choice | Reason |
|---|---|---|
| A head directory without OAS files | Exit code 2. The base can still be empty. | A wrong head path in a workflow must not pass without a comparison. |
| The values of a binding | `diff` compares the `upstream`, the `prefix`, and the `upstream-base-path`. | A new prefix changes the public paths. A new upstream base path sends the token to another path of the backend. The `binding-changed` entry shows the change. |
| The backend target | Lowercase, without a trailing `/`, and with the canonical `upstream`. | Many backends ignore the case of a path. A wider match only adds checks. |
| The binding of a file without an audience | It matches the first base binding without an audience with the same `prefix`. | The base and head directories have different paths, so the file path cannot identify the file. The public paths of a public-only file start with its prefix. |
| `head-weaker-than-get` for `OPTIONS` | The same change id as for `HEAD`. | The finding chose one dedicated id. |
| `any-of-removed` | Not reported if the head `all-of` has a permission of the base `any-of`. | Then each token that the head allows also meets the base `any-of`. |
| The job summary | Only with `--format github`, and only with at least one binding. An I/O error returns 2. | The same rule as for the job summary of [035](035-entra-permission-validation.md). |
| The GitHub message | `Access loosening (<id>) of <method> <path>: <message>.` for each change id. | One sentence pattern also fits the messages of `became-public` and `audience-changed`. |
| The JSON layout | The fields `formatVersion` and `tool`, the same as the other JSON outputs. | A consumer can detect a breaking change of the layout. |
| A path named `diff` | Exit code 2, as for `rules` and `explain`. | The same risk as SEC-71-1 of [032](032-cli-rules-and-explain.md). |

## Implementation notes (PR 2)

| Point | Choice | Reason |
|---|---|---|
| The source of the CLI | `git archive` of the base commit, built in `$RUNNER_TEMP/cli-source`. | Decision 9 (SEC-74-2). No release of the CLI exists yet. |
| The Maven cache | None. The build uses an empty Maven folder in `$RUNNER_TEMP/maven`. | A PR can save a Maven cache with the same key from another job. The cache could change the plugins of the base build. |
| The jar between the steps | The build step writes the SHA-256 of the jar. The step "Access diff" checks it. | The jar that runs is the jar of the base build. |
| The shared scripts | YAML anchors on the job env and on five scripts of `breaking-changes`. | The base and the head are read in exactly the same way. A fix of one script fixes both jobs. |
| The checks of the extraction | The job also fails on `x-since-date`, on `x-gateway.prefix` that is not one string, and on a change of the location of the OAS files. | The same script as in 033. A changed location would make `diff` compare the wrong folder. |
| A head without OAS files | Fail, unless the label counts. The job does not run `diff`. | `diff` returns 2 for an empty head, and the label does not pass exit code 2. So the job decides itself. The review decision SEC-75-1 replaced the first choice (pass with a notice): an empty `$OAS_DIR` can hide OAS files in another place. |
| Exit code 1 | Counts as exit code 1 of `diff` only when the last line of stdout is the summary line of `diff`. | The JVM and an uncaught exception also return 1. The label must not pass them. |
| The label step | After the build step, just before "Access diff". | The live state is read as late as possible. |
| No OAS change | Pass, without the build and without the label step. | `diff` of two equal folders gives no change and no binding. |

## Review findings (PR 1)

Decisions of the maintainer of 2026-11-15, after the review of PR #73 (core comparison):

| Finding | Decision | Change |
|---|---|---|
| QA-73-1, SEC-73-3 | 1 | New change id `any-of-removed`. |
| QA-73-2, SEC-73-2 | 5 | A new operation that overlaps a base template is compared with the template rule: `shadows-protected-template`. A new public operation is always reported: `new-public-operation`. |
| SEC-73-1 | 5, 8 | Operations also match by backend target. A move to a new prefix or path with a weaker rule is reported. |
| QA-73-3 | 2 | Operations match by public path, so a prefix move with the same public path is compared. Tests for this case. |
| QA-73-4 | 4 | A `binding-changed` entry for a removed field has the pointer `/x-gateway`. Tests for a removed and an added prefix. |
| QA-73-5, SEC-73-4 | 6 | `upstream-base-path` is part of the binding. |

Security re-review of PR #73 (2026-11-15):

| Finding | Change |
|---|---|
| SEC-73-5 | New change id `literal-removed-to-template`: a removed literal path is compared with the head template that takes its requests. |
| SEC-73-6 | Each file with at least one operation has a binding, also without an audience. |
| SEC-73-7 | New change id `head-weaker-than-get` for a new `HEAD` or `OPTIONS` operation. |
| SEC-73-8 | The backend target uses the canonical `upstream`. DNS aliases are not detected. |

Decisions of the maintainer of 2026-11-15, after the review of PR #74 (`diff` subcommand):

| Finding | Decision | Change |
|---|---|---|
| QA-74-1, SEC-74-1 | 3, 7 | `binding-new` and `binding-changed` return 1. The GitHub format shows them as `::error`. |
| QA-74-2 | 7 | An empty base with a binding in the head returns 1. |
| QA-74-3 | — | A fixture with a binding without a prefix, in the text, GitHub, and JSON formats. |
| SEC-74-2 | 9 | Recorded for PR 2 in "Delivery": the job does not build the CLI from the PR head. |

## Review findings (PR 2)

Decisions of the maintainer of 2026-11-15, after the review of PR #75 (the job `access-changes`):

| Finding | Change |
|---|---|
| SEC-75-1 | (a) The gateway stops at startup when a classpath pattern matches an OAS file outside its own classes ([013](013-oas-route-locator.md), "OAS file location"). (b) The location step of both jobs fails on a file in `src/main/resources/openapi` of another module ([033](033-breaking-change-detection.md)). (c) A head without OAS files fails unless the label counts. |
| SEC-75-2 | The location step fails on a change of `spring.config.import`, `spring.config.location`, `spring.config.additional-location`, and `GatewayOpenApiProperties.java`, and on an added or changed YAML or properties file in `gateway/src/main/resources` that is not `application*`. `yq` runs `explode(.)` before each query. The gateway logs `gateway.openapi.specs` at startup. |
| SEC-75-3 | The job summary also has the table of the loosening changes. More than 10 annotations give a warning. |
| SEC-75-4 | The build of the CLI uses `-s` and `-gs` with an empty settings file, and `setup-java` has `overwrite-settings: false`. |
| SEC-75 (note) | `build.yml` pins its actions by commit SHA ([011](011-pr-validation-pipeline.md)). |
| QA-75-1 | "CLI of the base commit" describes the effect of the CONTRACT rules of the base. Exit code 2 prints a hint. |
| QA-75-2 | The summary line is stable, and a change takes two PRs (see "Output"). `DiffReportTest` checks it against the pattern of the workflow. |
| QA-75-3 | The harnesses are in `.github/test/` (see "Test harness"). The job runner reads the `if:` condition of each step. |

## Decisions (2026-11-12)

- The repository has no `CODEOWNERS` file. Changes to the permission catalog, `.oas-gateway.yaml`, the baseline, and the workflows need the normal review only. The access change report of this spec stays, without the CODEOWNERS part. This closes the review findings SEC-17-3, SEC-26-2, and SEC-28-2 for now.

## Decisions (2026-11-13)

- The product owner removed the permission catalog ([Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)). The catalog is no longer a reviewed file.
- Team default after the review of PR #48 (SEC-48-1): `diff` lists each new or changed audience binding in its output and in the job summary ("Audience bindings"). A new binding does not fail the job. (Superseded on 2026-11-15 by decision 3: a binding fails the job without the label.) With `audience-unique` ([018](018-authorization-extension.md)), a second file cannot use the audience of another backend. A change of `upstream` in the existing file of an audience is visible in the `diff` output and needs review. The repository still has no `CODEOWNERS` file (decision of 2026-11-12).

## Open questions

- ~~The brief does not list some other loosening changes, so `diff` does not detect them. Examples: removing the whole `any-of` while `all-of` stays, or moving an operation to a new prefix with weaker access. Should `diff` detect them?~~ Answered on 2026-11-15: yes. See `any-of-removed`, `new-public-operation`, `shadows-protected-template`, and "Operation matching".
- ~~A new template can take requests from a removed base literal path, for example a new `/orders/{id}` after the removal of `/orders/export`. Should `diff` check this case?~~ Answered on 2026-11-15 (SEC-73-5): yes, `literal-removed-to-template`.
- ~~`OperationAccess` in [018](018-authorization-extension.md) has no file and no pointer. `diff` needs both for the output. Does 018 add them, or does this spec?~~ Answered in PR 1: `AccessDiff` reads the file and the pointer itself. `OperationAccess` does not change.
- ~~Should the workflow remove `access-change-approved` on new commits, and should it check who added the label?~~ Answered in PR 2: the job uses the label rules of [033](033-breaking-change-detection.md). The label counts only when a user with the role admin or maintain added it after the last push. The workflow does not remove the label: it has no write permission.
- ~~Should the base extraction step be shared between the two jobs of [033](033-breaking-change-detection.md) and this spec?~~ Answered in PR 2: yes. YAML anchors share the scripts of the steps (see "Workflow job").
- ~~Should a `binding-changed` entry fail the job without a label, like a loosening change? Today it is a notice only.~~ Answered on 2026-11-15 (decision 3): yes, `binding-new` and `binding-changed` return 1.

## References

- [007 — Route model](007-route-model.md)
- [009 — CLI GitHub and JSON output](009-cli-github-and-json-output.md)
- [018 — Authorization extension](018-authorization-extension.md)
- [021 — Entra app registrations](021-entra-app-registrations.md)
- [025 — Token relay and header hygiene](025-token-relay-and-header-hygiene.md)
- [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)
- [033 — Breaking change detection](033-breaking-change-detection.md)
- GitHub, About code owners: <https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners>
