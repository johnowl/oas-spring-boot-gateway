# 027 — Rule configuration

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 8 — Best-practice rules |
| Depends on | [005](005-validation-engine.md), [008](008-cli-basic.md) |
| Modules | `openapi-gateway-core`, `openapi-gateway-cli` |

## Summary

This spec adds the rule configuration file `.oas-gateway.yaml` and the CLI option `--config <file>`. A team uses the file to select a built-in profile (`recommended` or `strict`) and to change the severity of best-practice rules. The file also sets rule options and excludes files from best-practice rules. CONTRACT rules stay protected: a configuration that changes a CONTRACT rule is a configuration error with exit code 2. The configuration file is CLI-only; the gateway never reads it. The configuration lets each team adopt the best-practice rules at its own speed, without weakening the checks that keep the gateway safe.

## Motivation

The best-practice rules ([029](029-naming-rules.md), [030](030-design-rules.md), [031](031-documentation-rules.md)) express style decisions. Teams do not agree on all of them, and old specs often fail many of them. Without configuration, a team has two bad options: fix all old specs at once, or ignore the CLI. The CONTRACT rules are different. A CONTRACT violation can break the gateway at startup or open an access hole. The configuration MUST NOT be able to change them.

## Scope

### In scope

- The file format of `.oas-gateway.yaml`.
- The built-in profiles `recommended` and `strict`.
- Severity overrides, including `off`.
- Rule options (first user: `error-response-problem-json`).
- `ignore-files` for best-practice rules.
- The CLI option `--config <file>`.
- Validation of the configuration file. Each problem is a configuration error with exit code 2.

### Out of scope

- Inline suppressions and the baseline: [028](028-suppressions-and-baseline.md).
- The rules themselves: [029](029-naming-rules.md), [030](030-design-rules.md), [031](031-documentation-rules.md).
- The `rules` subcommand that prints the effective severity: [032](032-cli-rules-and-explain.md).
- Plugin rules loaded with `ServiceLoader<Rule>`. This is a possible future extension. Plugin rules can never be CONTRACT rules.
- Profiles defined by users, and `extends` with a file path or a list.
- Rule configuration in the gateway. The gateway runs only the CONTRACT rules with fixed severity ([005](005-validation-engine.md)).

## Design

### File format

```yaml
extends: recommended            # built-in profiles: recommended | strict
rules:
  operation-id-camel-case: error
  path-no-verbs: off
  error-response-problem-json:
    severity: error
    options:
      media-types: [application/problem+json]
ignore-files:
  - "legacy/**"
```

| Key | Type | Required | Meaning |
|---|---|---|---|
| `extends` | string, `recommended` or `strict` | No. Default: `recommended`. | The built-in profile that gives the base severities. |
| `rules` | map of rule id to rule setting | No | Overrides for single rules. |
| `ignore-files` | list of glob strings | No | Files that best-practice rules do not check. |

A rule setting has one of two forms:

| Form | Example | Meaning |
|---|---|---|
| Short | `path-no-verbs: off` | Sets the severity. Allowed values: `off`, `info`, `warn`, `error`. |
| Long | `severity: error` and `options: {...}` | Sets the severity and the rule options. Both keys are optional, but the object MUST NOT be empty. |

The severity `off` disables the rule. A disabled rule does not run and does not report violations. This is also true for a rule that checks all files together (a `CrossSpecRule` of [005](005-validation-engine.md)). If it is `off`, it does not run, so it cannot fail with `Rule failed: ...`.

Severity values are lowercase. The engine maps them to `Severity.INFO`, `Severity.WARN`, and `Severity.ERROR` from [005](005-validation-engine.md).

### Rule options

Each rule declares its own options and their defaults. An option that a rule does not declare is a configuration error. This spec defines the option mechanism. The only option in the initial rule catalog is:

| Rule | Option | Type | Default |
|---|---|---|---|
| `error-response-problem-json` | `media-types` | non-empty list of media type strings | `[application/problem+json]` |

[030](030-design-rules.md) defines how the rule uses the option.

### Precedence

The engine computes the effective severity of each best-practice rule in this order. A later step overrides an earlier step.

1. The profile selected by `extends` (default `recommended`).
2. The entry for the rule in `rules`.

There is no command-line option that changes a rule severity. `--fail-on` does not change severities. It only selects the threshold for exit code 1 ([008](008-cli-basic.md)).

### Profiles

The profile `recommended` uses the default severity of each rule from the brief. The `strict` column is a proposal (see "Open questions").

| Rule | Category | `recommended` | `strict` (proposal) |
|---|---|---|---|
| `operation-id-required` | NAMING | error | error |
| `operation-id-unique` | NAMING | error | error (Not implemented: `oas-parse` reports duplicates, see [029](029-naming-rules.md).) |
| `operation-id-camel-case` | NAMING | warn | error |
| `path-kebab-case` | NAMING | warn | error |
| `path-no-trailing-slash` | NAMING | error | error |
| `path-no-verbs` | DESIGN | warn | error |
| `operation-success-response` | DESIGN | error | error |
| `get-no-request-body` | DESIGN | error | error |
| `post-201-location-header` | DESIGN | info | warn |
| `components-unused` | DESIGN | warn | error |
| `deviation-unused` | DESIGN (proposal, see [028](028-suppressions-and-baseline.md)) | warn | error |
| `error-response-problem-json` | ERRORS | warn | error |
| `servers-https-only` | SECURITY | error | error |
| `operation-summary-required` | DOCUMENTATION | warn | error |
| `operation-tags-defined` | DOCUMENTATION | warn | error |
| `schema-examples-present` | DOCUMENTATION | info | warn |

The proposal for `strict`: each WARN rule becomes ERROR, each INFO rule becomes WARN, and each ERROR rule stays ERROR.

Implementation: `Profile` does not store a table. `RECOMMENDED` returns `RuleMeta.defaultSeverity()`. `STRICT` applies the transformation above to the default severity. Each new rule therefore gets a severity in both profiles from its `RuleMeta`, and the table above is the result for the rules of [029](029-naming-rules.md) to [031](031-documentation-rules.md).

`permissions-unused` of [019](019-permission-catalog.md) is removed with the permission catalog (decision of 2026-11-13, [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)). An entry `permissions-unused` in `rules` is an unknown rule id and returns exit code 2.

The profiles are part of the core jar. A pinned CLI version therefore gives a stable profile. A new rule enters a profile as WARN, following the rule rollout policy in [012](012-cli-distribution.md).

### CONTRACT protection

CONTRACT rules have `configurable=false` in `RuleMeta`. They always run, always have severity ERROR, and always check all files. A few non-CONTRACT rules also have `configurable=false`, for example `deviation-schema` ([028](028-suppressions-and-baseline.md)). The configuration loader MUST reject a `rules` entry for a rule with `configurable=false`. The CLI then returns exit code 2.

### `ignore-files`

- Each entry is a glob in the syntax of `java.nio.file.FileSystem.getPathMatcher("glob:...")`.
- The loader resolves each glob relative to the directory that contains the configuration file.
- A glob MUST NOT start with `/` and MUST NOT have a `..` segment. Files outside the directory of the configuration file are never ignored, so such a glob could never match. The loader reports it as an error.
- A glob has at most 200 characters and at most 4 wildcards (`*` or `**`). The JDK turns a glob into a regular expression, and many wildcards can make the match very slow (ReDoS). The loader reports a longer glob as an error.
- Best-practice rules do not check a matched file.
- CONTRACT rules still check a matched file. Otherwise a spec could pass the CLI and break the gateway at startup.

### Classes

| Class | Module | Responsibility |
|---|---|---|
| `RuleConfig` | core | Immutable result: effective severity per rule id, options per rule id, and ignore matchers. `ignoreFiles()` returns the globs in the order of the file, for the subcommand `rules` ([032](032-cli-rules-and-explain.md), finding SEC-71-2). |
| `RuleConfigLoader` | core | Reads YAML with Jackson, validates it against the rule registry, and returns `RuleConfig` or throws `RuleConfigException`. |
| `Profile` | core | Enum `RECOMMENDED`, `STRICT` with the severity of each rule id. |
| `RuleOptions` | core | Typed access to the options of one rule. Each rule declares the option names it accepts. |
| `RuleWithOptions` | core | Interface of a rule with options: `Map<String, OptionType> optionTypes()`, `Map<String, Object> optionDefaults()`, and `Rule withOptions(RuleOptions)`. `withOptions` returns a rule with the same id that uses the options. `optionDefaults` has the same keys as `optionTypes`. `rules` and `explain` show the defaults ([032](032-cli-rules-and-explain.md)). A rule without this interface accepts no options. |
| `OptionType` | core | `STRING_LIST`: a non-empty list of strings. |
| `RuleConfigException` | core | All problems of one file, for example `rules.x: unknown rule id.` |

The classes are in the package `com.johnowl.gateway.core.config`. `RuleConfig` implements `SeverityPolicy` of [005](005-validation-engine.md). The engine never asks the policy for a rule with `configurable=false`, so a configuration cannot change a CONTRACT rule, also through `ignore-files`. Without a configuration file, the CLI uses `RuleConfig.defaults()`, which equals the `recommended` profile. `RuleConfig.configure(rules)` gives each configurable `RuleWithOptions` its options. It overrides `SeverityPolicy.configure` ([005](005-validation-engine.md)). The engine calls it in its constructor and runs the configured rules. So each caller that gives the engine a `RuleConfig`, the CLI included, gets the options (finding QA-65-3).

The loader checks `rules` entries against `BuiltInRules.all()`.

YAML 1.1 reads an unquoted `off` as the boolean `false`. The loader accepts `false` as `off`, so `path-no-verbs: off` works without quotes. An empty configuration file equals `extends: recommended`. A key without a value (for example `rules:` with all entries commented out) is the same as a missing key. A repeated key is an error, for example `rules.path-kebab-case: duplicate key at line 3.` Otherwise the last value would win without a message.

## Behavior and errors

| Condition | Result | Exit code |
|---|---|---|
| No `--config` option | The CLI uses the `recommended` profile. | Not changed |
| Valid configuration file | The CLI uses the effective severities. | Not changed |
| `--config` file does not exist or cannot be read | Error on stderr | 2 |
| `--config` file is a symbolic link or not a regular file, for example a directory | Error on stderr: `Error: the configuration <file> is a symbolic link. Pass the file that it points to.` | 2 |
| `--config` file has more than 1 MB (1048576 bytes) | Error on stderr. The CLI reads at most 1 MB + 1 byte and does not parse the file. | 2 |
| Invalid YAML in the configuration file | Error on stderr with line and column | 2 |
| Unknown top-level key (for example `rule:`) | Error on stderr that names the key | 2 |
| `extends` is not `recommended` or `strict` | Error on stderr | 2 |
| Unknown rule id in `rules` | Error on stderr that names the rule id | 2 |
| `rules` entry for a rule with `configurable=false` (for example a CONTRACT rule) | Error on stderr: the rule cannot be configured | 2 |
| Severity is not `off`, `info`, `warn`, or `error` | Error on stderr | 2 |
| Unknown key in the long form (not `severity` or `options`) | Error on stderr | 2 |
| Option that the rule does not declare, or option with a wrong type | Error on stderr | 2 |
| `ignore-files` entry is not a valid glob, starts with `/`, has a `..` segment, has more than 200 characters, or has more than 4 wildcards | Error on stderr | 2 |
| A key appears twice | Error on stderr that names the key and the line | 2 |

The CLI does not follow a symbolic link to the configuration file, also when the link changes after the check, and it reads at most 1 MB (finding SEC-71-5). The configuration file comes from the repository under review, so a link could point to a file outside the repository, and a large file could use much memory. The CLI escapes control characters in these messages, the same as in [008](008-cli-basic.md). The CLI reports configuration errors before it validates any OAS file. The CLI reports all configuration errors in one run, not only the first one.

Example error output:

```text
.oas-gateway.yaml: rules.security-explicit: rule 'security-explicit' is a CONTRACT rule and cannot be configured.
.oas-gateway.yaml: rules.operation-id-camelcase: unknown rule id.
```

## Configuration

| Option | Type | Default | Meaning |
|---|---|---|---|
| `--config <file>` | path | none | Path of the rule configuration file. |

Example:

```bash
java -jar oas-gateway-validate.jar --config .oas-gateway.yaml gateway/src/main/resources/openapi
```

## Acceptance criteria

- [x] Without `--config`, each best-practice rule runs with the severity of the `recommended` column.
- [x] With `extends: strict`, each rule runs with the severity of the `strict` column.
- [x] A file without `extends` behaves as `extends: recommended`.
- [x] `path-no-verbs: off` removes all `path-no-verbs` violations from the report.
- [ ] `operation-id-camel-case: error` reports `operation-id-camel-case` violations as ERROR, and the CLI returns 1 with `--fail-on error`.
- [x] A `rules` entry overrides the profile severity of the same rule.
- [x] A `rules` entry for `security-explicit` returns exit code 2 and prints the rule id.
- [x] An unknown rule id in `rules` returns exit code 2 and prints the rule id.
- [x] An unknown top-level key returns exit code 2.
- [x] `extends: custom` returns exit code 2.
- [x] Severity `fatal` returns exit code 2.
- [x] An undeclared option (for example `options: {foo: 1}`) returns exit code 2.
- [x] `media-types: [application/vnd.error+json]` changes the accepted media types of `error-response-problem-json`.
- [x] A file that matches `ignore-files` has no best-practice violations in the report.
- [x] A file that matches `ignore-files` still has its CONTRACT violations in the report.
- [x] A configuration file with two errors prints both errors.
- [x] A `rules` entry `permissions-unused: off` returns exit code 2, because the rule id is unknown. (Code PR 1 of the removal of the permission catalog.)
- [x] A missing `--config` file returns exit code 2.
- [x] `rules:`, `ignore-files:`, or `extends:` without a value is the same as a missing key.
- [x] A repeated key returns exit code 2 and names the key.
- [x] An `ignore-files` glob with `..`, with a leading `/`, with more than 200 characters, or with more than 4 wildcards returns exit code 2.
- [x] A rule that checks all files together does not run when it is `off`.
- [x] `ignore-files` globs are relative to the directory of the configuration file, also when the CLI runs in another directory.

The criteria for `path-no-verbs`, `operation-id-camel-case`, and the `media-types` option of `error-response-problem-json` need the rules of [029](029-naming-rules.md) and [030](030-design-rules.md). The same mechanism is tested with test rules. The criteria for `path-no-verbs` and `media-types` are tested with the rules of [030](030-design-rules.md) (`RuleConfigEngineTest`, `ValidateCommandTest`). Code PR 1 of the removal of the permission catalog replaced the tests that used `permissions-unused: off` with tests that use `operation-id-camel-case: off` ([029](029-naming-rules.md)) or test rules.

## Test plan

- Unit tests for `RuleConfigLoader`: each row of the "Behavior and errors" table, both rule setting forms, and the default profile.
- Unit test for `Profile`: each profile has an entry for each configurable rule, and no entry for a rule with `configurable=false`. This test fails when a new rule has no profile entry.
- Unit tests for `ignore-files`: glob relative to the configuration file directory, nested directories, CONTRACT rules not affected.
- `ProfileTableTest`: the real `RuleMeta` of each implemented rule of the profile table gives the severity of both columns.
- CLI command tests with fixtures in `openapi-gateway-cli/src/test/resources/config/` (valid, unknown rule id, CONTRACT rule, invalid severity, invalid YAML).

## Delivery

One PR. Main files: `RuleConfig.java`, `RuleConfigLoader.java`, `Profile.java`, `RuleOptions.java`, engine changes, `ValidateCommand.java` (option `--config`), tests. Estimate: 300–400 changed lines without fixtures.

## Open questions

- Confirm the severities of the `strict` profile. The column above is a proposal.
- Does the CLI look for `.oas-gateway.yaml` in the current working directory when `--config` is not given? This spec says no: the configuration applies only with `--config`.
- Should the rule options be validated with a JSON Schema per rule, in the same way as `x-gateway.schema.json` ([006](006-x-gateway-extension.md))?

## References

- [005 — Validation engine](005-validation-engine.md)
- [008 — CLI basic](008-cli-basic.md)
- [012 — CLI distribution](012-cli-distribution.md)
- [028 — Suppressions and baseline](028-suppressions-and-baseline.md)
- [032 — CLI `rules` and `explain`](032-cli-rules-and-explain.md)
- [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)
- Java API documentation of `java.nio.file.FileSystem.getPathMatcher` (glob syntax), for Java 25 ([001](001-project-skeleton.md)).
