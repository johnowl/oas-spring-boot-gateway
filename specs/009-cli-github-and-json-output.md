# 009 — CLI GitHub and JSON output

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 3 — CLI and pipeline |
| Depends on | [008](008-cli-basic.md) |
| Modules | `openapi-gateway-cli` |

## Summary

This spec adds two output formats to the command-line interface (CLI) `oas-gateway-validate`: `--format github` and `--format json`. The `github` format prints GitHub Actions workflow commands, so each violation appears as an inline annotation on the changed line of the pull request (PR). The `json` format gives a stable, machine-readable report for other tools. Both formats need a line and a column for each violation. For this reason, this spec also adds a component that maps a JSON pointer to a position in the original file.

## Motivation

The text format from [008](008-cli-basic.md) shows violations only in the job log. A reviewer must open the log and find the file and the location by hand. Inline annotations put the message on the line in the PR diff.

A `Violation` contains a JSON pointer, not a line number. swagger-parser does not keep source positions, so the CLI cannot get the line from the parsed `OpenAPI` object. The CLI must read the raw file again with a parser that reports locations.

## Scope

### In scope

- `SourceLocator`: map a JSON pointer to a line and a column.
- `GithubReportFormatter` for `--format github`.
- `JsonReportFormatter` for `--format json`.
- New values `GITHUB` and `JSON` in `OutputFormat`.

### Out of scope

- SARIF output: [010](010-cli-sarif-output.md).
- The workflow that uses `--format github`: [011](011-pr-validation-pipeline.md).
- Line numbers in the gateway startup log.

## Design

### Pointer to line and column

`SourceLocator` reads the raw file with Jackson (`YAMLFactory` for `*.yaml` and `*.yml`, `JsonFactory` for `*.json`). It walks the token stream once and records the location of each value. It stores the result in a map from JSON pointer to `SourcePosition(int line, int column)`. Lines and columns start at 1.

```java
public final class SourceLocator {
    public Optional<SourcePosition> locate(Path file, String jsonPointer);
}
```

Rules:

| Case | Result |
|---|---|
| The pointer exists in the file | Position of the element that the pointer selects (see the key or value choice below). |
| The pointer does not exist (for example, a missing `x-gateway-authorization`) | Position of the nearest existing parent pointer. |
| The pointer does not start with `/`, for example `x-gateway` | Position of the document root. A rule should not report such a pointer, but the CLI MUST NOT fail. |
| The file cannot be parsed, and the parse error has a location | The document root `""` maps to the location of the parse error. Each other pointer maps to it as its parent. |
| No parent exists, or the file cannot be read or parsed without a location | `Optional.empty()`. |

Example: in this file, the pointer `/paths/~1orders~1{id}/get` maps to line 5. The key `get` starts at column 5.

```yaml
openapi: 3.1.0
info: {title: Orders, version: "1"}
paths:
  /orders/{id}:
    get:
      summary: Get order
```

For an object value in block style, the start token of the value can be on the line after the key. The annotation is more useful on the key line.

Decision (2026-11-12): for a member of an object, `SourceLocator` returns the position of the key. For an array element, it returns the start of the element, for example the column after `- ` in YAML. For the empty pointer (document root), it returns the start of the document. The Javadoc of `SourceLocator` documents this choice, and `SourceLocatorTest` locks it for YAML and JSON.

`SourceLocator` and `SourcePosition` are package-private classes in `com.johnowl.gateway.cli`. They stay in the CLI module until the gateway needs line numbers.

The map stores pointers with RFC 6901 escapes (`~1` for `/`, `~0` for `~`), the same form as the pointers of violations. A key `/orders/{id}` is therefore found with `~1orders~1{id}`, and a key `a~b` with `a~0b`. It caches the map per file for one CLI run, because one file often has many violations. If the file cannot be read, the map is empty, and each pointer of the file has no position. If the file cannot be parsed, the map has only the document root, at the location of the parse error (if the error has one). The `oas-parse` violation of such a file uses the root pointer, so it gets the line of the error.

### GitHub format

Each violation becomes one workflow command on stdout:

```text
::error file=gateway/src/main/resources/openapi/orders.yaml,line=5,col=5,title=route-collision::GET /orders-api/orders/{id} collides with GET /orders-api/orders/{orderId} in payments.yaml.
::warning file=gateway/src/main/resources/openapi/orders.yaml,line=12,col=7,title=operation-summary-required::Operation has no summary.
```

| Severity | Command |
|---|---|
| ERROR | `::error` |
| WARN | `::warning` |
| INFO | `::notice` |

Rules:

- `file` MUST be relative to the current working directory. The workflow runs the CLI from the repository root, so the path is relative to the repository root.
- If `SourceLocator` returns no position, the command omits `line` and `col`.
- The formatter MUST escape the message: `%` to `%25`, carriage return to `%0D`, line feed to `%0A`.
- The formatter MUST also escape property values: `:` to `%3A` and `,` to `%2C`, in addition to the message escapes.
- After the commands, the formatter prints the same summary line as the text format.
- A violation with an empty file (a failing `CrossSpecRule`, see [005](005-validation-engine.md)) has no position. The command then also omits `file`: `::error title=route-collision::Rule failed: ...`.
- The messages that the CLI writes to stderr escape control characters, as in the text format ([008](008-cli-basic.md)). The runner also reads workflow commands from stderr.

### JSON format

The JSON format writes one object to stdout. Field names use camelCase.

```json
{
  "formatVersion": 1,
  "tool": { "name": "oas-gateway-validate", "version": "1.0.0" },
  "summary": { "files": 2, "error": 1, "warn": 1, "info": 0 },
  "violations": [
    {
      "severity": "ERROR",
      "ruleId": "route-collision",
      "file": "gateway/src/main/resources/openapi/orders.yaml",
      "pointer": "/paths/~1orders~1{id}/get",
      "line": 5,
      "column": 5,
      "message": "GET /orders-api/orders/{id} collides with GET /orders-api/orders/{orderId} in payments.yaml."
    }
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `formatVersion` | integer | Version of this JSON layout. A breaking change to the layout increments it. |
| `tool.version` | string | Same value as `--version`. |
| `summary` | object | Number of checked files and number of violations per severity. |
| `violations[].line`, `violations[].column` | integer or `null` | `null` when `SourceLocator` returns no position. |

The violations use the same order as the text format. The output MUST be valid JSON also when there are no violations (`"violations": []`). The output is indented with two spaces. A field is written as `"key": value`, an empty array as `[]`, and each line ends with `\n` on every platform. A violation with an empty file has `"file": ""` and no position. `tool.version` is `development` when the CLI does not run from the jar.

## Behavior and errors

- Exit codes do not change. They depend only on `--fail-on` (see [008](008-cli-basic.md)).
- For exit code 2, the CLI writes the error to stderr. It does not write a partial JSON document to stdout.
- A file with an `oas-parse` violation has no position map. Its violations have no line and column, unless the parse error itself reports a location. Invalid YAML or JSON reports a location, so its violation gets that line and column.

## Acceptance criteria

- [x] `SourceLocator` returns the correct line and column for each of these pointers: a path item, an operation, a parameter in an array, and the root-level `x-gateway` key.
- [x] `SourceLocator` decodes `~1` and `~0` in pointers.
- [x] `SourceLocator` returns the parent position for a pointer that does not exist in the file.
- [x] `SourceLocator` returns the document root for a pointer that does not start with `/`.
- [x] `SourceLocator` gives the same positions for equivalent YAML and JSON fixtures, adjusted for their layout.
- [x] `--format github` prints `::error`, `::warning`, and `::notice` for ERROR, WARN, and INFO.
- [x] `--format github` prints `file`, `line`, `col`, and `title` with the rule id.
- [x] `--format github` omits `line` and `col` when the position is unknown.
- [x] A message that contains `%`, a line feed, `:`, or `,` is escaped as specified.
- [x] `--format json` output parses as JSON and contains `formatVersion`, `tool`, `summary`, and `violations`.
- [x] `--format json` prints `"violations": []` when there are no violations.
- [x] Invalid YAML gets the line and column of the parse error in `--format github` and `--format json`.
- [x] `--format github` omits `file` for a violation with an empty file.
- [x] For each format, the exit code is the same as for `--format text` on the same input.

## Test plan

- Unit tests for `SourceLocator` with YAML and JSON fixtures. Cases: flow style (`{...}`), block style, arrays, quoted keys, keys with `/` and `~`, missing pointers, and an unparsable file.
- Unit tests for `GithubReportFormatter`: severity mapping, escaping, missing positions.
- Unit tests for `JsonReportFormatter`: parse the output with Jackson and compare it with an expected JSON file.
- Command tests: run `ValidateCommand` with `--format github` and `--format json` on the fixtures from [008](008-cli-basic.md).

## Delivery

Two PRs:

1. `SourceLocator`, `SourcePosition`, and their tests (about 200 lines).
2. `GithubReportFormatter`, `JsonReportFormatter`, the `OutputFormat` values, and their tests (about 250 lines).

## Open questions

- Should `SourceLocator` move to `openapi-gateway-core`, so that the gateway startup log can also show line numbers?
- GitHub limits the number of annotations that it shows for each step and each job. Verify the current limits. If a PR has more violations than the limit, the full list is only in the log.
- Is `formatVersion` enough as a compatibility promise for the JSON format, or does the JSON format need a published JSON Schema?

## References

- [008 — CLI basic](008-cli-basic.md)
- [010 — CLI SARIF output](010-cli-sarif-output.md)
- [011 — PR validation pipeline](011-pr-validation-pipeline.md)
- GitHub Actions workflow commands: <https://docs.github.com/en/actions/reference/workflow-commands-for-github-actions>
- RFC 6901, JSON Pointer: <https://www.rfc-editor.org/rfc/rfc6901>
- Jackson dataformats text (YAML): <https://github.com/FasterXML/jackson-dataformats-text>
