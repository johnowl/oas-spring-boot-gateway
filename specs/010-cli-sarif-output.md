# 010 — CLI SARIF output

Note (2026-11-18): the repository is now public (`johnowl/oas-spring-boot-gateway`). The statements about a private repository in this spec describe the situation when the decision was made. For the current state, see [docs/ci-change-control.md](../docs/ci-change-control.md).

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 3 — CLI and pipeline |
| Depends on | [009](009-cli-github-and-json-output.md) |
| Modules | `openapi-gateway-cli` |

## Summary

This spec adds `--format sarif` to the command-line interface (CLI) `oas-gateway-validate`. The format is Static Analysis Results Interchange Format (SARIF) version 2.1.0. The workflow uploads the SARIF file with `github/codeql-action/upload-sarif`, so violations appear in the GitHub "Code scanning" tab. Code scanning keeps a history of alerts and shows when a violation is introduced or fixed. The inline annotations from `--format github` ([009](009-cli-github-and-json-output.md)) remain the main feedback in the pull request (PR).

## Motivation

Workflow command annotations exist only for one workflow run. They do not show trends, and they do not track an alert across commits. Code scanning stores alerts per rule and per location, links each alert to the rule documentation, and closes an alert when the violation disappears. SARIF is the input format of code scanning.

## Scope

### In scope

- `SarifReportFormatter` and the `SARIF` value in `OutputFormat`.
- Rule metadata in the SARIF file, from `RuleMeta` of each rule that reports a violation.
- A stable fingerprint for each result.
- The workflow step that uploads the SARIF file. [011](011-pr-validation-pipeline.md) adds this step to `.github/workflows/openapi.yml`.

### Out of scope

- The workflow file itself: [011](011-pr-validation-pipeline.md).
- The baseline fingerprint and the `--baseline` option: [028](028-suppressions-and-baseline.md).
- A SARIF reader. The CLI only writes SARIF.

## Design

### Output layout

The formatter writes one SARIF log with one run. It builds the document with Jackson and Java records. It does not use a SARIF library.

```json
{
  "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
  "version": "2.1.0",
  "runs": [
    {
      "tool": {
        "driver": {
          "name": "oas-gateway-validate",
          "version": "1.0.0",
          "rules": [
            {
              "id": "route-collision",
              "shortDescription": { "text": "Two operations have the same method and normalized path." },
              "fullDescription": { "text": "<RuleMeta.rationale>" },
              "defaultConfiguration": { "level": "error" },
              "properties": { "tags": ["CONTRACT"] }
            }
          ]
        }
      },
      "results": [
        {
          "ruleId": "route-collision",
          "ruleIndex": 0,
          "level": "error",
          "message": { "text": "GET /orders-api/orders/{id} collides with GET /orders-api/orders/{orderId} in payments.yaml." },
          "locations": [
            {
              "physicalLocation": {
                "artifactLocation": { "uri": "gateway/src/main/resources/openapi/orders.yaml", "uriBaseId": "%SRCROOT%" },
                "region": { "startLine": 5, "startColumn": 5 }
              },
              "logicalLocations": [ { "fullyQualifiedName": "/paths/~1orders~1{id}/get" } ]
            }
          ],
          "partialFingerprints": { "oasGatewayViolation/v1": "<sha-256 hex>" }
        }
      ]
    }
  ]
}
```

### Field mapping

| SARIF field | Source |
|---|---|
| `tool.driver.version` | Same value as `--version` |
| `tool.driver.rules[]` | `RuleMeta` of each rule that has at least one result, sorted by id |
| `rules[].shortDescription.text` | `RuleMeta.description` |
| `rules[].fullDescription.text` | `RuleMeta.rationale` |
| `rules[].defaultConfiguration.level` | `RuleMeta.defaultSeverity`, mapped with the table below |
| `rules[].properties.tags` | `RuleMeta.category` |
| `results[].level` | Effective severity of the violation, mapped with the table below |
| `artifactLocation.uri` | File path relative to the current working directory, with `/` separators, as an RFC 3986 relative reference: each byte that is not allowed in a path segment, and `:`, is percent-encoded (`api specs/a.yaml` gives `api%20specs/a.yaml`). `uriBaseId` is `%SRCROOT%`. A file outside the working directory has an absolute path. It gives a `file:` URI and no `uriBaseId`. |
| `region` | Position from `SourceLocator` ([009](009-cli-github-and-json-output.md)) |
| `logicalLocations[].fullyQualifiedName` | JSON pointer of the violation |
| `partialFingerprints` | SHA-256 of `ruleId + "\n" + file + "\n" + pointer`. The file uses `/` separators, so Windows and Linux give the same value. |

| Severity | SARIF level |
|---|---|
| ERROR | `error` |
| WARN | `warning` |
| INFO | `note` |

### Fingerprint

The fingerprint uses the rule id, the file, and the JSON pointer. It does not use the line number. A change above the violation moves the line, but the alert keeps its identity. This is the same rule as the baseline fingerprint in [028](028-suppressions-and-baseline.md).

### Location without position

Code scanning needs a region for each result. If `SourceLocator` returns no position, the formatter MUST write `"region": { "startLine": 1 }`. A violation with an empty file (a failing `CrossSpecRule`, see [005](005-validation-engine.md)) gets the URI `.`.

### Rule metadata

`SarifReportFormatter` receives the rule list of the command (`BuiltInRules.all()` in the CLI) and reads `RuleMeta` by rule id. `ruleIndex` is the index of the rule in `tool.driver.rules`. Fields with the value `null` are not written.

### Upload step

[011](011-pr-validation-pipeline.md) adds the SARIF steps. The `validate` job writes the SARIF file and keeps it as an artifact. The SARIF step runs also when the validation step fails, because the validation step fails exactly when there are violations. It does not run after a cancel or after a failed build. A separate job `upload-sarif` uploads the file to code scanning. Only this job has `security-events: write`, and it runs no code from the PR.

```yaml
validate:
  permissions:
    contents: read
  steps:
    # ... checkout, setup-java, build CLI (id: build), validate (see 011)
    - name: Validate OAS files (SARIF)
      id: sarif
      if: ${{ !cancelled() && steps.build.outcome == 'success' }}
      run: |
        # Keep the report only for exit code 0 or 1, and only if it is not empty.
        ...
    - name: Keep SARIF report
      if: ${{ !cancelled() && steps.sarif.outputs.sarif == 'true' }}
      uses: actions/upload-artifact@...

upload-sarif:
  needs: validate
  if: ${{ !cancelled() && vars.CODE_SCANNING_ENABLED == 'true' && needs.validate.outputs.sarif == 'true' }}
  permissions:
    actions: read
    contents: read
    security-events: write
  steps:
    # checkout (files only), download-artifact, then:
    - uses: github/codeql-action/upload-sarif@...
      with:
        sarif_file: oas-gateway.sarif
        category: oas-gateway-validate
```

The SARIF run uses `--fail-on info`, because the SARIF step only reports. The `--format github` step decides the check result.

The repository is private and has no code scanning yet. [011](011-pr-validation-pipeline.md) therefore keeps the SARIF file as a workflow artifact, and uploads it only when the repository variable `CODE_SCANNING_ENABLED` is `true`.

## Behavior and errors

- Exit codes do not change (see [008](008-cli-basic.md)).
- For exit code 2, the CLI writes nothing to stdout. The workflow then keeps no SARIF file and uploads nothing. The `--format github` step already reports the exit code 2 problem.
- The output MUST be valid SARIF also when there are no violations (`"results": []`). An empty result list closes old alerts in code scanning.

## Acceptance criteria

- [x] `--format sarif` output validates against the SARIF 2.1.0 JSON Schema.
- [x] Each result has `ruleId`, `level`, `message.text`, one `physicalLocation` with a `region`, and `partialFingerprints`.
- [x] ERROR, WARN, and INFO map to `error`, `warning`, and `note`.
- [x] `tool.driver.rules` contains exactly the rules that have results, with `shortDescription` and `fullDescription` from `RuleMeta`.
- [x] A result without a known position has `region.startLine` equal to 1.
- [x] The fingerprint of a violation does not change when lines are added above the violation.
- [x] The fingerprint is the same for `a\b.yaml` and `a/b.yaml`.
- [x] `artifactLocation.uri` is percent-encoded. A violation with an empty file has the URI `.` and the region `{"startLine": 1}`.
- [x] A run without violations writes a valid SARIF log with `"results": []`.
- [x] The exit code for `--format sarif` is the same as for `--format text` on the same input.
- [ ] After [011](011-pr-validation-pipeline.md) is merged, a PR with a WARN violation creates a code scanning alert.

## Test plan

- Unit tests for `SarifReportFormatter`: level mapping, rule list, fingerprint, missing position, empty report.
- Schema test: validate the output against the SARIF 2.1.0 JSON Schema. The copy in `openapi-gateway-cli/src/test/resources/sarif/sarif-schema-2.1.0.json` comes from `https://json.schemastore.org/sarif-2.1.0.json` (draft-07, `$id` of the OASIS repository). networknt json-schema-validator, which core already uses, validates it. The test also proves that an invalid log (wrong `version`) fails.
- Manual test in a PR: check the code scanning tab after the first run of the workflow.

## Delivery

One PR for the formatter and its tests (about 250 lines without the schema copy). The upload steps are part of the PR for [011](011-pr-validation-pipeline.md).

## Open questions

- Code scanning in a private repository needs GitHub Advanced Security (GitHub Code Security). Does the organization have it? If not, the upload step fails and must be removed.
- A PR from a fork gets a read-only token. The upload step then fails. Does this repository accept PRs from forks?
- Verify the current major version of `github/codeql-action` at implementation time. Pin the action to a commit SHA.
- `informationUri` and `helpUri` need a URL for rule documentation. Where does the rule documentation live? [032](032-cli-rules-and-explain.md) adds `explain`, but it gives no URL.

## References

- [009 — CLI GitHub and JSON output](009-cli-github-and-json-output.md)
- [011 — PR validation pipeline](011-pr-validation-pipeline.md)
- [028 — Suppressions and baseline](028-suppressions-and-baseline.md)
- SARIF 2.1.0 (OASIS): <https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html>
- GitHub SARIF support for code scanning: <https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning>
- Upload a SARIF file to GitHub: <https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/uploading-a-sarif-file-to-github>
