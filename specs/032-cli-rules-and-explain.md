# 032 — CLI `rules` and `explain` subcommands

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 8 — Best-practice rules |
| Depends on | [005](005-validation-engine.md), [008](008-cli-basic.md), [027](027-rule-configuration.md) |
| Modules | `openapi-gateway-core`, `openapi-gateway-cli` |

## Summary

This spec adds two CLI subcommands. `rules` lists each rule with its id, category, and effective severity for a given configuration. It also has a JSON format for tools. `explain <rule-id>` prints the description, the rationale, the options, a bad example, and a good example of one rule. A developer who sees a violation in a pull request (PR) can then understand the rule. The developer does not need to read the source code of the CLI.

## Motivation

A violation message is short. It says what is wrong, but not why the rule exists or what a correct spec looks like. Without `explain`, developers ask the platform team or search the code. Without `rules`, a team cannot see the effect of its `.oas-gateway.yaml` before it runs a validation.

## Scope

### In scope

- The subcommand `rules [--config <file>] [--format text|json]`.
- The subcommand `explain <rule-id>`.
- Example resources (bad and good) for each rule, and a meta-test that checks them.
- The default value of each rule option (`RuleWithOptions.optionDefaults()`).

### Out of scope

- A JSON format for `explain`.
- Documentation pages generated from the rule metadata.
- Plugin rules loaded with `ServiceLoader<Rule>` (possible future extension).

## Design

### `rules`

```text
oas-gateway-validate rules [--config <file>] [--format text|json]
```

The subcommand prints one line per registered rule, sorted by id. The registered rules are the rules of `BuiltInRules.all()`, so the list contains `oas-parse`, `deviation-schema`, and `deviation-unused`. The columns are `ID`, `CATEGORY`, and `SEVERITY`. `SEVERITY` is the effective severity after the profile and the overrides of [027](027-rule-configuration.md). A disabled rule shows `off`. CONTRACT rules always show `error`. A rule with `configurable=false` always shows its default severity, for example `error` for `deviation-schema`. `ignore-files` does not change the table, because it depends on the file.

```text
$ java -jar oas-gateway-validate.jar rules --config .oas-gateway.yaml
ID                             CATEGORY       SEVERITY
audience-not-first-party       CONTRACT       error
...
components-unused              DESIGN         warn
...
error-response-problem-json    ERRORS         error
...
path-no-verbs                  DESIGN         off
...
security-no-empty-requirement  CONTRACT       error
...
```

The column widths fit the longest value, and two spaces separate the columns. A line has no white space at the end. The severity values are lowercase, the same values as in the configuration file.

`ignore-files` does not change the table, so the CLI prints the globs of `ignore-files` under the table (finding SEC-71-2). Without `ignore-files`, this part is missing:

```text
...
x-gateway-unknown-extension    CONTRACT       error

Best-practice rules do not check the files of ignore-files:
  legacy/**
  generated/*.yaml
```

The options come after `rules`. An option or a path before `rules` (for example `--config x.yaml rules`) is a usage error with exit code 2, the same as for `baseline create` ([028](028-suppressions-and-baseline.md)). The message names only the options that the subcommand has, and it says how to validate a directory with the name of the subcommand (finding QA-71-1):

```text
Write --config after 'rules'. Options before 'rules' do not apply to 'rules'. To validate a directory named rules, write ./rules.
```

### A path with the name of a subcommand

A directory named `rules` or `explain` must be written as `./rules` or `./explain` (or `rules/`, or an absolute path). A CI script that passes `rules` would otherwise list the rules and get exit code 0 without a validation. So `rules` and `explain` return 2 when a file or directory with their name exists in the working directory. A symbolic link with the name also counts. The subcommand then prints nothing on stdout and this message on stderr (finding SEC-71-1):

```text
Error: A path named 'rules' exists. Write ./rules to validate it, or run the command from another directory.
```

`--help` and `--version` still work, because picocli handles them before the check.

### JSON format of `rules`

`--format json` prints one JSON object on stdout. The layout is stable: a change that removes or renames a field increments `formatVersion`. A new field does not change it. The file uses two-space indentation and `\n` line ends on every operating system, the same as the JSON report of [009](009-cli-github-and-json-output.md).

```json
{
  "formatVersion": 1,
  "tool": { "name": "oas-gateway-validate", "version": "0.1.0" },
  "profile": "recommended",
  "ignoreFiles": [],
  "rules": [
    {
      "id": "error-response-problem-json",
      "category": "ERRORS",
      "configurable": true,
      "defaultSeverity": "warn",
      "profiles": { "recommended": "warn", "strict": "error" },
      "severity": "warn",
      "options": [
        {
          "name": "media-types",
          "type": "string-list",
          "default": ["application/problem+json"],
          "value": ["application/problem+json"]
        }
      ]
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `formatVersion` | The version of this layout. This spec defines version `1`. |
| `tool.version` | The CLI version from the jar manifest, or `development` outside the jar. |
| `profile` | The profile of the configuration (`extends`), or `recommended` without `--config`. |
| `ignoreFiles` | The globs of `ignore-files` in the order of the file. Empty without `--config` or without `ignore-files`. This field came after the first draft of version `1`. It is new, so `formatVersion` stays `1` (finding SEC-71-2). |
| `rules` | One object for each registered rule, sorted by `id`. |
| `id`, `category` | `RuleMeta.id` and `RuleMeta.category` (uppercase, as in the text table). |
| `configurable` | `RuleMeta.configurable`. |
| `defaultSeverity` | `RuleMeta.defaultSeverity`, lowercase. |
| `profiles` | The severity of the rule in each built-in profile, without overrides. A rule with `configurable=false` has its default severity in each profile. |
| `severity` | The effective severity, the same value as the `SEVERITY` column (`off` for a disabled rule). |
| `options` | The options of the rule, sorted by `name`. Empty for a rule without options and for a rule with `configurable=false`. |
| `options[].type` | The `OptionType` in lowercase with `-`, for example `string-list`. |
| `options[].default` | The value without configuration (`RuleWithOptions.optionDefaults()`). |
| `options[].value` | The value with the configuration, as the configuration file has it. |

The values of the configuration file come from the repository. The JSON writer therefore writes each character of `UnsafeCharacters` ([008](008-cli-basic.md)) as a JSON escape, for example `\u202E` or `\n`, and also each surrogate (`\uD83D\uDE00` for one emoji; a lone surrogate gets one escape). The output has no control character and no bidirectional control, and a JSON parser reads the same value back (finding SEC-71-4). The writer does not use `ConsoleText.printable`, because its escapes are text, and a parser cannot tell them apart from a value with a backslash.

### `explain`

```text
oas-gateway-validate explain <rule-id>
```

The subcommand prints these fields in this order:

| Field | Source |
|---|---|
| Id | `RuleMeta.id` |
| Category | `RuleMeta.category` |
| Default severity | `RuleMeta.defaultSeverity` (the `recommended` profile) |
| Strict severity | The severity in the `strict` profile ([027](027-rule-configuration.md)) |
| Configurable | `RuleMeta.configurable` (`yes` or `no`) |
| Description | `RuleMeta.description` |
| Rationale | `RuleMeta.rationale` |
| Options | `RuleWithOptions.optionTypes()` and `optionDefaults()`. Only for a configurable rule with options. |
| Bad example | resource `bad.yaml` |
| Good example | resource `good.yaml` |

```text
$ java -jar oas-gateway-validate.jar explain operation-id-camel-case
operation-id-camel-case
  Category:         NAMING
  Default severity: warn
  Strict severity:  error
  Configurable:     yes

Description
  operationId matches ^[a-z][a-zA-Z0-9]*$, for example getOrders.

Rationale
  One naming style gives predictable method names in generated clients.

Bad example
  paths:
    /orders:
      get:
        operationId: get_orders

Good example
  paths:
    /orders:
      get:
        operationId: listOrders
```

`explain` does not read a configuration file. It shows the default severity, not the effective severity. For a rule with options, an `Options` section comes after `Rationale`:

```text
Options
  media-types (string-list), default: [application/problem+json]
```

Each line of the description, the rationale, and the examples is indented by two spaces. The CLI escapes control characters, Unicode line separators, and bidirectional controls on each line, the same as in [008](008-cli-basic.md). The unknown rule id in the error message is escaped in the same way.

### Example resources

Each rule has two resources in `openapi-gateway-core`:

```text
openapi-gateway-core/src/main/resources/rule-examples/<rule-id>/bad.yaml
openapi-gateway-core/src/main/resources/rule-examples/<rule-id>/good.yaml
```

An example is a short excerpt of an OAS file, not a complete spec. It shows only the part that the rule checks. An example for a rule that compares files (for example `audience-unique`) has one YAML document for each file, separated by a `---` line. Each document starts with a comment that names the file, for example `# team-a/orders.yaml` or `# payments.yaml: the same audience`. The example of `oas-parse` is valid YAML, but not OpenAPI 3.0 or 3.1. The test fixtures of [005](005-validation-engine.md) stay separate, because they are complete specs and are not part of the jar.

### Example wrapper

The meta-test runs the rule on each example. The bad example gives at least one violation of the rule. The good example gives no violation of the rule. Neither example gives an `oas-parse` violation, except the bad example of `oas-parse`. The meta-test runs only the rule under test, the same as the fixture test of [005](005-validation-engine.md). `deviation-unused` runs with all rules, because it depends on them.

Before the run, a test class (`RuleExampleDocuments`) turns each YAML document of the example into a complete OAS 3.1 document. It adds only the fields that the document does not have:

| Field | Value that the wrapper adds |
|---|---|
| `openapi` | `3.1.0` |
| `info` | `title: Example`, `version: 1.0.0` |
| `x-gateway` | `upstream: http://example:8080`, only if the document has no `x-gateway` |
| `components.securitySchemes.entra` | An `openIdConnect` scheme |
| `responses` of an operation | `"200": { description: OK. }`, only if the operation has no `responses` |
| `parameters` of an operation | One `in: path` parameter for each path variable without a path parameter |

The wrapper never changes or removes a field of the example. It adds no root `security` and no field inside an `x-gateway` object, so it cannot hide a violation of `security-explicit` or `x-gateway-schema`. A document with the field `openapi` or `swagger` is a complete document, and the wrapper does not change it (the examples of `oas-parse`). A single document is the file `example.yaml`. In an example with several documents, the file names come from the comments.

### Classes

| Class | Module | Responsibility |
|---|---|---|
| `RuleExamples` | core | Loads `bad.yaml` and `good.yaml` for a rule id from the classpath. It rejects an id that is not kebab-case, so an id cannot select another resource. A missing resource throws `IllegalStateException` with the message `Rule '<id>' has no example rule-examples/<id>/bad.yaml`. |
| `RuleWithOptions.optionDefaults()` | core | The default value of each option, with the same keys as `optionTypes()`. |
| `RulesCommand` | cli | picocli subcommand `rules`, with the text table and the JSON format. |
| `RulesFormat` | cli | Enum `TEXT`, `JSON` of `rules --format`. |
| `ExplainCommand` | cli | picocli subcommand `explain`. |
| `RuleSettings` | cli | The effective severity, the profile severities, and the options of a rule, for both subcommands. |

## Behavior and errors

| Condition | Output | Exit code |
|---|---|---|
| `rules` without `--config` | Table with the `recommended` severities on stdout | 0 |
| `rules --config <file>` with a valid file | Table with the effective severities on stdout | 0 |
| `rules --config <file>` with an invalid file | Configuration errors on stderr ([027](027-rule-configuration.md)) | 2 |
| `rules --config <file>`, and the file does not exist or cannot be read | Error on stderr | 2 |
| `rules --config <file>`, and the file is a symbolic link, is not a regular file, or has more than 1 MB | Error on stderr ([027](027-rule-configuration.md)) | 2 |
| `rules --format json` | JSON object on stdout | 0 |
| `rules --format <unknown>` | `Invalid value for option '--format'` and the usage on stderr, nothing on stdout | 2 |
| An option or a path before `rules` or `explain` | Usage error on stderr | 2 |
| A file or directory named `rules` (or `explain`) exists in the working directory | `Error: A path named 'rules' exists. ...` on stderr, nothing on stdout | 2 |
| `rules --version`, `explain --version` | The CLI version on stdout, the same as the root command (finding QA-71-5) | 0 |
| `explain <known rule id>` | Explanation on stdout | 0 |
| `explain <unknown rule id>` | `Unknown rule id '<id>'. Run 'oas-gateway-validate rules' to list rule ids.` on stderr | 2 |
| `explain` without a rule id | picocli usage message on stderr | 2 |

## Acceptance criteria

- [x] `rules` prints one line for each registered rule, sorted by id, with the columns `ID`, `CATEGORY`, and `SEVERITY`.
- [x] `rules` without `--config` prints the `recommended` severities.
- [x] `rules --config` with `path-no-verbs: off` prints `off` for `path-no-verbs`.
- [x] `rules` prints `error` for each CONTRACT rule.
- [x] `rules --config` with an invalid file returns 2.
- [x] `rules --format json` prints the layout of "JSON format of `rules`" with each registered rule, its profile severities, and its options.
- [x] `rules --format json` writes control characters (also a line feed and a C0 control), bidirectional controls, Unicode line separators, and surrogates in option values as JSON escapes. A JSON parser reads the same value back.
- [x] `rules --format json --config strict.yaml` has the profile `strict` and the severity `error` for a rule with the default severity `warn`.
- [x] `rules` prints the globs of `ignore-files` under the table, and the JSON has them in `ignoreFiles`.
- [x] An option before `rules` or `explain` returns 2. The message names only the options of the subcommand and says how to validate a directory with its name.
- [x] `rules` and `explain` return 2 when a file or directory with their name exists in the working directory.
- [x] `./rules`, `./explain`, and `rules/` validate the directory: exit code 1 and the violation line (`CliJarIT`).
- [x] `rules --version` and `explain --version` print the CLI version.
- [x] `explain operation-id-camel-case` prints the id, category, default severity, configurable flag, description, rationale, bad example, and good example, and returns 0.
- [x] `explain` for a CONTRACT rule prints `Configurable: no`.
- [x] `explain error-response-problem-json` prints the option `media-types` and its default.
- [x] `explain` works for each registered rule.
- [x] `explain unknown-rule` returns 2 and prints the hint to run `rules`. The unknown id is escaped.
- [x] `explain` without a rule id returns 2.
- [x] A meta-test fails when a registered rule has no `bad.yaml` or no `good.yaml`.
- [x] A meta-test fails when `description` or `rationale` of a rule is blank.
- [x] A meta-test fails when an example is blank or not valid YAML, when both examples are equal, when an example directory has no rule, and when a rule option has no default value.
- [x] A meta-test fails when the default value of an option is `null` or does not have the `OptionType` of the option. A `string-list` default is a non-empty list of strings.
- [x] A meta-test runs each rule on its wrapped examples. It fails when the rule does not report the bad example, when the rule reports the good example, when an example does not parse, and when a document of a multi-file example has no file name comment.
- [x] The shaded jar contains the examples: `CliJarIT` runs `explain` with the jar.

## Test plan

- CLI command tests `RulesCommandTest` for `rules` (default, with configuration, strict profile, invalid configuration, JSON, JSON escapes, `ignore-files`, a configuration file that is a link, a directory, or larger than 1 MB, a path named `rules`) and `ExplainCommandTest` for `explain` (known rule, CONTRACT rule, rule with options, each registered rule, unknown rule, missing argument, a path named `explain`). The tests compare the output with expected files in `openapi-gateway-cli/src/test/resources/cli-rules/`. A test constructor `ValidateCommand(List<Rule>, Path)` sets the working directory of the check for a path with the name of the subcommand.
- `CliJarIT` runs the jar in a temporary working directory with the directories `rules/` and `explain/`.
- Meta-test in core (`RuleCatalogMetaTest` with `RuleExplanations`): each registered rule has both example resources, a non-blank description and rationale, and option defaults of the right type. The rule reports its bad example and does not report its good example. Fake rules and the resources in `src/test/resources/rule-examples-test/` prove that the meta-test reports each problem.
- Unit test for `RuleExampleDocuments`: the wrapper adds only missing fields, keeps a complete document, and names the documents of a multi-file example after their comments.
- Unit test for `RuleExamples`: loads both resources and fails with a clear message for a missing resource. It rejects each id that is not kebab-case, for example `../x`, `a/b`, `A`, an id with a line feed, and an id with a bidirectional control.

## Delivery

Two stacked PRs. The example resources for each rule are text files. The PRs add the resources for the rules that exist at that time. Each later rule spec adds its own resources.

1. Core: `RuleExamples`, the example resources of all registered rules, `RuleWithOptions.optionDefaults()`, and the meta-test. About 400 changed lines with tests, without the resources.
2. CLI: `rules`, `explain`, the JSON format of `rules`, and the command tests. About 600 changed lines with tests.

## Decisions (2026-11-15)

- `rules` has `--format json` (former open question). Tools need the rule list without parsing a table. The layout is in "JSON format of `rules`". The JSON also shows the severity in each profile and the options, so a team can see the effect of `extends: strict` before it changes the file. `explain` has only the text format.
- `rules` lists `oas-parse` (former open question). [005](005-validation-engine.md) registers it as a `SpecRule` in `BuiltInRules.all()`, and it can appear in a report.
- `explain` shows the options of a rule and their defaults (former open question). A new method `RuleWithOptions.optionDefaults()` gives the defaults, and the meta-test checks that each option has one. `explain` also shows the severity in the `strict` profile.
- A directory named `rules` or `explain` is written as `./rules` or `./explain`, the same as `./baseline` in [028](028-suppressions-and-baseline.md). The help of the root command says this. When a file or directory with the name of the subcommand exists in the working directory, `rules` and `explain` return 2 (fail closed, review of PR #71, findings QA-71-2 and SEC-71-1).
- The meta-test runs each example through its rule (former open question, review of PR #70). The wrapper of "Example wrapper" makes each excerpt a complete document. The wrapper adds no violation and hides no violation of the rule under test.

## Review findings

| Finding | Resolution |
|---|---|
| QA-70-1 | The examples of `schema-examples-present` define `components.schemas.Order`, so the reference resolves and the missing example is visible. The meta-test found the unresolved reference. |
| QA-70-2 | Both documents of the `route-id-unique` examples have an `x-gateway` with an `upstream`. Without it, the rule skips the file, and the bad example gave no violation. |
| QA-70-3 | The meta-test checks that each option default is not `null` and has the `OptionType` of the option. |
| Decision (QA-70-1, QA-70-2) | The meta-test runs each example through its rule. See "Example wrapper". |
| SEC-70-1 | `RuleExamples` keeps the kebab-case pattern. New tests show that `../x`, `a/b`, `A`, `x` with a line feed, and `x` with U+202E throw `IllegalArgumentException`. |
| QA-71-1 | "`rules`": the usage error before `rules` or `explain` names only the options of the subcommand and says `To validate a directory named rules, write ./rules.` |
| QA-71-2, SEC-71-1 | Decision: fail closed. "A path with the name of a subcommand": `rules` and `explain` return 2 when a file or directory with their name exists in the working directory. README and `docs/cli-release-policy.md`: CI scripts pass paths as `./dir` or as absolute paths. |
| QA-71-3 | `CliJarIT` validates `./rules`, `./explain`, and `rules/` (exit code 1 and the violation line). |
| QA-71-4 | JSON tests for a C0 control and a line feed in an option value, and for `extends: strict`. |
| QA-71-5 | `rules`, `explain`, `baseline`, and `baseline create` have the `ManifestVersionProvider` ([008](008-cli-basic.md)). |
| QA-71-6 | The test of an unknown `--format` checks the message, the usage, and the empty stdout. |
| SEC-71-2 | The text output lists the globs of `ignore-files`. The JSON has `ignoreFiles`. The field is new, so `formatVersion` stays `1`. `RuleConfig.ignoreFiles()` ([027](027-rule-configuration.md)). |
| SEC-71-3 | [008](008-cli-basic.md): the parameter exception handler prints the message with `ConsoleText.untrusted`, and `@file` expansion is off. |
| SEC-71-4 | "JSON format of `rules`": Jackson writes JSON escapes for unsafe characters and surrogates, so the value is the same after parsing. |
| SEC-71-5 | [027](027-rule-configuration.md): the configuration file is a regular file, not a symbolic link, with at most 1 MB. |

## Open questions

- Should validation move to a `validate` subcommand, so that no directory name conflicts with a subcommand ([008](008-cli-basic.md) has the same open question)? Until then, "A path with the name of a subcommand" fails closed.

## References

- [005 — Validation engine](005-validation-engine.md)
- [008 — CLI basic](008-cli-basic.md)
- [027 — Rule configuration](027-rule-configuration.md)
- [029 — Naming rules](029-naming-rules.md)
- [030 — Design rules](030-design-rules.md)
- [031 — Documentation rules](031-documentation-rules.md)
- picocli subcommands: <https://picocli.info/#_subcommands>
