# CLI reference

This page describes the command-line tool (CLI) `oas-gateway-validate`. The CLI validates OAS files before they reach the gateway, lists and explains the rules, and compares the access rules of two versions. Backend teams use it on their computers, and the workflows use it in pull requests.

## Get the CLI

Build the CLI from the root folder of the repository:

```bash
./mvnw -q -DskipTests install
```

The jar is `openapi-gateway-cli/target/oas-gateway-validate.jar`. It needs Java 25.

```bash
java -jar openapi-gateway-cli/target/oas-gateway-validate.jar --version
```

The release workflow publishes a jar and a container image for each tag `vX.Y.Z`. On 2026-11-17, the repository has no release tag. See [CLI release and rule rollout policy](cli-release-policy.md).

## Commands

| Command | Purpose |
|---|---|
| `oas-gateway-validate [options] <files-or-dirs>...` | Validates OAS files. |
| `oas-gateway-validate rules [--config <file>] [--format <format>]` | Lists each rule with its category and severity. |
| `oas-gateway-validate explain <rule-id>` | Prints the description, the rationale, and examples of one rule. |
| `oas-gateway-validate baseline create [--config <file>] <files-or-dirs>...` | Writes a baseline of the current violations to standard output. |
| `oas-gateway-validate diff [--format <format>] <base-dir> <head-dir>` | Reports access loosening changes and new or changed audience bindings. |

Write the options of a subcommand after the subcommand. Options before the subcommand are a usage error with exit code `2`.

## Validate OAS files

```bash
java -jar oas-gateway-validate.jar [--format <format>] [--fail-on <severity>] \
  [--config <file>] [--baseline <file>] <files-or-dirs>...
```

| Option | Values | Default | Description |
|---|---|---|---|
| `<files-or-dirs>` | Paths | Required | OAS files or folders. The CLI searches folders recursively for `*.yaml`, `*.yml`, and `*.json`. |
| `--format` | `text`, `github`, `json`, `sarif` | `text` | The output format. |
| `--fail-on` | `info`, `warn`, `error` | `error` | The lowest severity that returns exit code `1`. |
| `--config` | File | None | The rule configuration, for example `.oas-gateway.yaml`. |
| `--baseline` | File | None | The baseline, for example `.oas-baseline.json`. |
| `-h`, `--help` | | | Prints the help. |
| `-V`, `--version` | | | Prints the version. |

Example:

```bash
java -jar oas-gateway-validate.jar --fail-on warn gateway/src/main/resources/openapi
```

### Paths with the name of a subcommand

`baseline`, `rules`, `explain`, and `diff` are subcommands. To validate a folder with one of these names, write `./` in front, for example `./rules`.

`rules`, `explain`, and `diff` return exit code `2` when a file or folder with their name exists in the working directory. So a CI script that passes `rules` as a path fails, and does not pass without a validation.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | No violation at or above the `--fail-on` severity. |
| `1` | At least one violation at or above the `--fail-on` severity. |
| `2` | Usage error, I/O error, invalid configuration, invalid baseline, or unexpected error. |

The CLI writes errors with exit code `2` to standard error, before any output on standard output.

### Output formats

| Format | Use |
|---|---|
| `text` | A person reads the result in a terminal. |
| `github` | GitHub Actions shows each violation as an annotation on the line of the file. |
| `json` | A program reads the result. |
| `sarif` | GitHub code scanning or another SARIF 2.1.0 tool reads the result. |

The examples below use a file `openapi/orders.yaml` with two errors: a path in `upstream`, and the permission `orders.read`.

Example of `text`:

```text
ERROR permission-format openapi/orders.yaml#/paths/~1orders/get/x-gateway-authorization/all-of/0
  Permission 'orders.read' does not match ^[A-Z][A-Za-z0-9]*(\.[A-Z][A-Za-z0-9]*)+$. Example: Orders.Read.
ERROR x-gateway-schema openapi/orders.yaml#/x-gateway/upstream
  'upstream' must be scheme://host[:port] without a path. Use 'upstream-base-path' for a backend path.

1 file checked: 2 errors, 0 warnings, 0 info.
```

Each violation has a severity, a rule id, the file, and a JSON pointer after `#`. In a pointer, `~1` stands for `/`.

Example of `github`:

```text
::error file=openapi/orders.yaml,line=20,col=18,title=permission-format::Permission 'orders.read' does not match ^[A-Z][A-Za-z0-9]*(\.[A-Z][A-Za-z0-9]*)+$. Example: Orders.Read.
::error file=openapi/orders.yaml,line=6,col=3,title=x-gateway-schema::'upstream' must be scheme://host[:port] without a path. Use 'upstream-base-path' for a backend path.
1 file checked: 2 errors, 0 warnings, 0 info.
```

Example of `json`:

```json
{
  "formatVersion": 1,
  "tool": {
    "name": "oas-gateway-validate",
    "version": "0.0.1-SNAPSHOT"
  },
  "summary": {
    "files": 1,
    "error": 2,
    "warn": 0,
    "info": 0
  },
  "violations": [
    {
      "severity": "ERROR",
      "ruleId": "permission-format",
      "file": "openapi/orders.yaml",
      "pointer": "/paths/~1orders/get/x-gateway-authorization/all-of/0",
      "line": 20,
      "column": 18,
      "message": "Permission 'orders.read' does not match ^[A-Z][A-Za-z0-9]*(\\.[A-Z][A-Za-z0-9]*)+$. Example: Orders.Read."
    },
    {
      "severity": "ERROR",
      "ruleId": "x-gateway-schema",
      "file": "openapi/orders.yaml",
      "pointer": "/x-gateway/upstream",
      "line": 6,
      "column": 3,
      "message": "'upstream' must be scheme://host[:port] without a path. Use 'upstream-base-path' for a backend path."
    }
  ]
}
```

The report shows at most 100 violations of each rule in each file. When a rule has more violations in a file, the report adds one violation with the number of the others.

## List and explain the rules

The CLI has two kinds of rules:

| Kind | Categories | Can you configure it? | Who runs it |
|---|---|---|---|
| Contract rule | `CONTRACT` | No. Always `error`. No deviation and no baseline. | The CLI and the gateway. |
| Best-practice rule | `NAMING`, `DESIGN`, `DOCUMENTATION`, `ERRORS`, `SECURITY` | Most of them. | Only the CLI. |

`oas-parse` is also a contract rule: it reports a file that the parser cannot read. `deviation-schema` is a best-practice rule that you cannot configure.

List the rules with their effective severity:

```bash
java -jar oas-gateway-validate.jar rules
java -jar oas-gateway-validate.jar rules --config .oas-gateway.yaml
java -jar oas-gateway-validate.jar rules --format json
```

The output of `rules` has the columns `ID`, `CATEGORY`, and `SEVERITY`. The JSON format also lists the default severity, the severity in each profile, and the options of each rule.

Explain one rule:

```bash
java -jar oas-gateway-validate.jar explain operation-id-camel-case
```

`explain` prints the category, the default severity, the severity in the `strict` profile, and whether the rule is configurable. Then it prints the description, the rationale, the options, a bad example, and a good example. `explain` reads no configuration file. An unknown rule id returns exit code `2`.

This page does not copy the rules. `rules` and `explain` always show the rules of the version that you run.

`rules` and `explain` exit codes:

| Code | `rules` | `explain` |
|---|---|---|
| `0` | The rules are listed. | The rule is explained. |
| `2` | Usage error, invalid configuration file, or a path named `rules` in the working directory. | Usage error, unknown rule id, or a path named `explain` in the working directory. |

## Configure the rules

A rule configuration file changes the best-practice rules. The CLI reads it only with `--config`. The gateway never reads it.

Example `.oas-gateway.yaml`:

```yaml
extends: strict
rules:
  path-no-verbs: off
  schema-examples-present: warn
  error-response-problem-json:
    severity: error
    options:
      media-types: [application/problem+json, application/json]
ignore-files:
  - legacy/**
```

| Key | Values | Description |
|---|---|---|
| `extends` | `recommended` or `strict` | The profile. Default: `recommended`. |
| `rules.<rule-id>` | `off`, `info`, `warn`, `error` | The severity of one rule. |
| `rules.<rule-id>.severity` | `off`, `info`, `warn`, `error` | The same, in the object form. |
| `rules.<rule-id>.options` | Map | The options of the rule. `rules --format json` and `explain` list them. |
| `ignore-files` | List of glob patterns | Best-practice rules do not check these files. Contract rules still check them. |

Profiles:

| Profile | Effect |
|---|---|
| `recommended` | Each rule has its default severity. |
| `strict` | `warn` becomes `error`. `info` becomes `warn`. `error` stays `error`. |

Rules for the file:

- The file is a regular file, not a symbolic link, with at most 1 MB.
- A repeated key is an error.
- A contract rule or an unknown rule id in `rules` is an error.
- A glob has at most 200 characters and at most 4 wildcards. It is relative to the folder of the configuration file and does not contain `..`.

An invalid file returns exit code `2`, with one line for each problem. Example:

```text
badcfg.yaml: rules.security-explicit: rule 'security-explicit' is a CONTRACT rule and cannot be configured.
```

## Accept one violation with a deviation

A deviation accepts the violation of one best-practice rule on one operation. Add `x-deviation` to the operation, with the rule id and a reason.

```yaml
paths:
  /orders/find:
    post:
      operationId: findOrders
      summary: Find orders with a search filter.
      tags: [orders]
      x-gateway-authorization:
        all-of: [Orders.Read]
      x-deviation:
        - rule: path-no-verbs
          reason: A partner contract fixes this public URL until 2027-06-30.
      responses:
        "200":
          description: The matching orders.
```

Rules:

- A deviation covers violations of its rule at the operation and below it.
- The `reason` is not blank (`deviation-schema`).
- A deviation cannot cover a contract rule or a rule that you cannot configure (`deviation-schema`).
- A deviation that covers no violation is the warning `deviation-unused`.

## Accept known violations with a baseline

A baseline lets a team adopt the rules for existing files. The CLI does not report a violation that is in the baseline. A new violation is still reported.

1. Create the baseline:

   ```bash
   java -jar oas-gateway-validate.jar baseline create gateway/src/main/resources/openapi > .oas-baseline.json
   ```

   With a configuration file, write the option after `baseline create`:

   ```bash
   java -jar oas-gateway-validate.jar baseline create --config .oas-gateway.yaml gateway/src/main/resources/openapi > .oas-baseline.json
   ```

2. Commit `.oas-baseline.json`.
3. Validate with the baseline:

   ```bash
   java -jar oas-gateway-validate.jar --baseline .oas-baseline.json gateway/src/main/resources/openapi
   ```

Example of a baseline file:

```json
{
  "version" : 1,
  "entries" : [ {
    "ruleId" : "operation-summary-required",
    "file" : "gateway/src/main/resources/openapi/orders.yaml",
    "pointer" : "/paths/~1orders/get"
  } ]
}
```

Facts:

- An entry matches a violation by rule id, file, and JSON pointer. The line, the severity, and the message do not count.
- `baseline create` writes only violations of configurable rules. It has no limit of 100 violations.
- `baseline create` returns exit code `0`, also when the files have contract errors. Run the validation to see them.
- A baseline with a contract rule, an unknown rule id, or a `version` other than `1` is an error with exit code `2`.

## Compare access rules with `diff`

`diff` compares the OAS files of two folders: the base (before the change) and the head (after the change). It reports each change that loosens the access to an operation, and each new or changed audience binding.

```bash
java -jar oas-gateway-validate.jar diff base-openapi head-openapi
```

In this example, `base-openapi/orders.yaml` is the [complete example](writing-routes.md#complete-example) of Writing routes. `head-openapi/orders.yaml` removes `Orders.Delete` from the `DELETE` operation and changes the `upstream` to `https://orders-v2-service:8443`. The output is:

```text
LOOSENING permission-removed-from-all-of DELETE /orders-api/orders/{id}
  head-openapi/orders.yaml#/paths/~1orders~1{id}/delete
  Orders.Delete removed from all-of
BINDING binding-changed audience=6f1c2e8a-3b4d-4c5e-8f60-718293a4b5c6
  head-openapi/orders.yaml#/x-gateway/upstream
  base: upstream=https://orders-service:8443, upstream-base-path=/v1, prefix=/orders-api; head: upstream=https://orders-v2-service:8443, upstream-base-path=/v1, prefix=/orders-api

1 access loosening change in 1 operation. 1 new or changed audience binding to review.
```

| Option | Values | Default |
|---|---|---|
| `--format` | `text`, `github`, `json` | `text` |

With `--format github`, `diff` can also append tables to the job summary file. The environment variable `GITHUB_STEP_SUMMARY` names this file.

- `diff` writes the table of loosening changes only if it has a row. It writes the table of bindings only if it has a row.
- Without changes and without bindings, `diff` writes nothing to the file.
- If the variable is not set, or is empty, `diff` writes no job summary.
- If `diff` cannot write the file, it returns exit code `2`.

Change ids:

| Change id | Meaning |
|---|---|
| `became-public` | A protected operation becomes public. |
| `audience-changed` | The audience of a protected operation changes. |
| `authenticated-only-replaced-permissions` | `authenticated-only: true` replaces a permission list. |
| `permission-removed-from-all-of` | A permission leaves `all-of`. |
| `permission-added-to-any-of` | A permission joins a non-empty `any-of`. |
| `any-of-removed` | A non-empty `any-of` is removed. |
| `new-public-operation` | A new public operation. |
| `shadows-protected-template` | A new path with a weaker rule takes requests of a protected template. |
| `literal-removed-to-template` | A removed literal path sends its requests to a template with a weaker rule. |
| `head-weaker-than-get` | A new `HEAD` or `OPTIONS` operation has a weaker rule than the `GET` operation. |
| `binding-new` | A new audience binding. |
| `binding-changed` | The `upstream`, `prefix`, or `upstream-base-path` of an audience changes. |

[Spec 034](../specs/034-access-change-review.md) gives the exact conditions of each change.

`diff` exit codes:

| Code | Meaning |
|---|---|
| `0` | No access loosening change and no new or changed audience binding. |
| `1` | At least one access loosening change, or a new or changed audience binding. |
| `2` | Usage error or I/O error. A file with a parse error or a contract error. A head folder without OAS files. A path named `diff` in the working directory. With `--format github`, a job summary file that `diff` cannot write. |

An empty base folder is allowed: then each head operation is new.

## Use the CLI in a pipeline

Use `--format github` in GitHub Actions. Pin an exact version of the CLI. See [CLI release and rule rollout policy](cli-release-policy.md#use-in-other-repositories).

```bash
java -jar oas-gateway-validate.jar --format github --fail-on error ./gateway/src/main/resources/openapi
```

## Related pages

- [Writing routes](writing-routes.md)
- [CI and change control](ci-change-control.md)
- Specs: [008 — CLI basic](../specs/008-cli-basic.md), [027 — Rule configuration](../specs/027-rule-configuration.md), [028 — Deviations and baseline](../specs/028-suppressions-and-baseline.md), [032 — CLI rules and explain](../specs/032-cli-rules-and-explain.md), [034 — Access change review](../specs/034-access-change-review.md).
