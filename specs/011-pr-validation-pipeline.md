# 011 — PR validation pipeline

Note (2026-11-18): the repository is now public (`johnowl/oas-spring-boot-gateway`). The statements about a private repository in this spec describe the situation when the decision was made. For the current state, see [docs/ci-change-control.md](../docs/ci-change-control.md).

| Field | Value |
|---|---|
| Status | Draft |
| Phase | 3 — CLI and pipeline |
| Depends on | [001](001-project-skeleton.md), [009](009-cli-github-and-json-output.md), [010](010-cli-sarif-output.md) |
| Modules | `.github/workflows` |

## Summary

This spec adds the GitHub Actions workflow `.github/workflows/openapi.yml`. The workflow builds the command-line interface (CLI) from the same commit and validates the OpenAPI Specification (OAS) files of the repository in each pull request (PR). Violations appear as inline annotations and in code scanning. The workflow fails when there is at least one ERROR violation. The validation job is a required status check on `main`, so an invalid OAS file cannot reach `main` and cannot break the gateway at startup.

## Motivation

The gateway validates its OAS files at startup and stops on any ERROR ([013](013-oas-route-locator.md)). Without a PR check, an invalid OAS file is merged and the problem appears only at deployment. The CLI from [008](008-cli-basic.md) runs the same CONTRACT rules as the gateway, plus the best-practice rules. This workflow runs the CLI on every relevant change and blocks the merge on failure.

## Scope

### In scope

- The workflow `.github/workflows/openapi.yml` with the change detection job and the validation job.
- The `--format github` step and the SARIF steps from [010](010-cli-sarif-output.md).
- The branch protection configuration for `main` (a manual step, documented here).

### Out of scope

- The build workflow `.github/workflows/build.yml` (`./mvnw -B verify`): [001](001-project-skeleton.md).
- A check of audiences and permissions against Microsoft Entra ID. The permission catalog of [019](019-permission-catalog.md) and [035](035-entra-permission-validation.md) is removed (decision of 2026-11-13). See [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13).
- Breaking change detection with oasdiff: [033](033-breaking-change-detection.md).
- Access change review with `diff`: [034](034-access-change-review.md).
- Publication of the CLI: [012](012-cli-distribution.md). This workflow builds the CLI from source. A change to core or to the CLI must be tested on the OAS files in the same PR.

## Design

### Relevant changes

The validation MUST run when a PR changes one of these paths:

| Path | Reason |
|---|---|
| `gateway/src/main/resources/**` | OAS files of this repository, and `application*.yaml`, which sets the location of the OAS files (`gateway.openapi.specs`) |
| `openapi-gateway-core/**` | Validation rules |
| `openapi-gateway-cli/**` | CLI |
| `pom.xml` | Parent build and dependency versions |
| `mvnw`, `.mvn/**` | Maven wrapper that builds the CLI |
| `.oas-gateway.yaml` | Rule configuration ([027](027-rule-configuration.md)) |
| `.oas-baseline.json` | Baseline ([028](028-suppressions-and-baseline.md)) |
| `.github/workflows/openapi.yml` | This workflow |

### Required check and path filters

GitHub keeps a required check in the "Pending" state when a `paths` filter skips the whole workflow. The PR then cannot be merged. A job that a job-level `if` condition skips reports "Success" instead.

For this reason, the workflow has no `paths` filter on the trigger. A first job, `changes`, compares the PR head with the base and sets an output. The `validate` job runs only when the output is `true`. The required check is `validate`.

The `changes` job fails closed. It runs `git diff` with `set -euo pipefail` and writes the list to a file before `grep` reads it. If `git diff` fails, the job fails. The `validate` job is then not skipped with a "Success" result.

### Workflow

The file `.github/workflows/openapi.yml` has three jobs. This outline shows the structure. The file is the reference.

```yaml
name: OpenAPI validation

on:
  pull_request:
    branches: [main]
    types: [opened, synchronize, reopened, edited]

permissions:
  contents: read

concurrency:
  group: openapi-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  changes:            # 5 minutes; checkout with fetch-depth 0; sets relevant=true|false
  validate:           # 20 minutes; needs changes; runs if relevant == 'true'
    permissions:
      contents: read
    steps:            # checkout, setup-java, build CLI, validate (--format github), SARIF, keep SARIF artifact
  upload-sarif:       # 10 minutes; needs validate; only if CODE_SCANNING_ENABLED == 'true'
    permissions:
      actions: read
      contents: read
      security-events: write
    steps:            # checkout (files only), download artifact, upload-sarif
```

Notes:

- The trigger lists `edited` in addition to the default types `opened`, `synchronize`, and `reopened`. Without `edited`, a change of the base branch of a PR does not run the workflow again, and the result of the old base stays on the PR. `edited` also runs the workflow after a change of the title or the description. The jobs have no condition on the event type: a skipped job reports "Success", and `cancel-in-progress` would cancel the run that does the work.
- The build skips tests (`-DskipTests`), because `build.yml` runs all tests on every PR.
- Each job has `timeout-minutes`, so that a hanging build or CLI does not hold a runner for 6 hours.
- Each checkout sets `persist-credentials: false`. The `validate` job builds and runs code from the PR, so it has only `contents: read`. The job `upload-sarif` has `security-events: write`, and it runs no code from the PR.
- The action versions in the example show the structure only. The file `.github/workflows/openapi.yml` pins each action to a commit SHA (versions of 2026-11-12):

  | Action | Version |
  |---|---|
  | `actions/checkout` | v7.0.1 |
  | `actions/setup-java` | v6.2.0 |
  | `actions/upload-artifact` | v7.0.2 |
  | `actions/download-artifact` | v8.0.2 |
  | `github/codeql-action/upload-sarif` | v4.39.3 |

- The Java version comes from [001](001-project-skeleton.md).
- The `diff` step reads `github.base_ref` through an environment variable, not inside the script, so that the value cannot inject shell code.
- `actionlint` (Docker image `rhysd/actionlint`) reports no problems for the workflow.

### Folder without OAS files

The folder `gateway/src/main/resources/openapi` does not exist until the team adds the first real OAS file. [013](013-oas-route-locator.md) uses only test OAS files. Until then, the "Validate OAS files" step writes a `::notice` and succeeds when the folder does not exist. The PR that adds the first real OAS file MUST remove this check. After that, a missing folder is a CLI exit code 2 and fails the job, as the table in "Behavior and errors" says.

### SARIF steps

Decision (2026-11-12): the repository is private, and code scanning is not enabled for it (the API returns `403 Code scanning is not enabled`). Code scanning on a private repository needs GitHub Advanced Security (GitHub Code Security). The workflow therefore:

1. Writes the SARIF file (`--format sarif --fail-on info`) also when the validation step fails, but not after a cancel or a failed build. The file is kept only for CLI exit code 0 or 1 and only if it is not empty.
2. Keeps the file as the workflow artifact `oas-gateway-sarif` for 14 days.
3. Uploads it to code scanning in the separate job `upload-sarif`, only if the repository variable `CODE_SCANNING_ENABLED` is `true`. An administrator sets this variable after code scanning is enabled. No change to the workflow is necessary.

### Branch protection

A repository administrator configures the rule for `main` in the repository settings (branch protection rule or ruleset):

1. Require status checks to pass before merging.
2. Add the required checks `validate` (from `openapi.yml`), `breaking-changes` (from `openapi-change-control.yml`, [033](033-breaking-change-detection.md)), `access-changes` (from `openapi-change-control.yml`, [034](034-access-change-review.md)), and the verify job of `build.yml` (name from [001](001-project-skeleton.md)).
3. Require branches to be up to date before merging.

This configuration is not in the repository. The PR description MUST list the steps, and an administrator MUST confirm them.

### Advisory checks

Decision (2026-11-15, finding SEC-72-1): the checks `validate`, `breaking-changes`, and `access-changes` are advisory until an administrator configures the rule above. A check is advisory when it shows a result on the PR, but GitHub does not block the merge on a failure. Today the repository is private and on the GitHub Free plan, which has no branch protection rules and no rulesets for private repositories. All checks are therefore advisory now.

A required check alone is not enough:

- A `pull_request` workflow runs the workflow file of the PR. A PR can change or remove the check in its own workflow file, and the check then passes.
- GitHub matches a required check by its name. A second workflow with a job named `breaking-changes` or `access-changes` can report "Success" for the same name.

The recommended configuration is a ruleset with a required workflow. GitHub then runs the workflow file from the default branch, not from the PR. Until then, a reviewer MUST read each change to `.github/workflows/**` and to `specs/**` in a PR.

## Behavior and errors

| Condition | `changes` | `validate` | PR can merge |
|---|---|---|---|
| PR does not change a relevant path | `relevant=false` | Skipped (reports Success) | Yes |
| Relevant change, no ERROR violation | `relevant=true` | Success; WARN and INFO appear as annotations | Yes |
| Relevant change, at least one ERROR violation | `relevant=true` | Failure; annotations on the lines | No |
| CLI exit code 2 (for example, the OAS folder is missing) | `relevant=true` | Failure; error in the log | No |
| The CLI does not compile | `relevant=true` | Failure in "Build CLI" | No |

## Acceptance criteria

- [ ] A PR that changes only `README.md` shows the `validate` check as skipped, and the PR can merge.
- [ ] A PR that adds an OAS file with an ERROR violation fails the `validate` check.
- [ ] The failing PR shows an inline annotation on the line of the violation in the "Files changed" view.
- [ ] A PR that adds an OAS file with only WARN violations passes the `validate` check and shows warning annotations.
- [ ] A PR that changes only a file under `openapi-gateway-core/` runs the `validate` job.
- [x] The workflow passes no `--catalog` argument, and `catalog/**` is not a relevant path. (Code PR 1 of the removal of the permission catalog.)
- [x] The workflow token has only `contents: read`, plus `security-events: write` (and `actions: read`) in the `upload-sarif` job, which runs no PR code.
- [x] Each job has `timeout-minutes`. Each checkout has `persist-credentials: false`.
- [x] The `changes` job fails when `git diff` fails.
- [ ] Branch protection on `main` lists `validate` as a required check.

## Test plan

- Workflow lint: run `actionlint` on the workflow locally or in the PR. Done with `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:latest`.
- The workflow triggers only on PRs to `main`. The test PRs below are possible only after the CLI ([008](008-cli-basic.md) to [010](010-cli-sarif-output.md)) is on `main`.
- Test PRs (closed without merge after the test):
  1. A PR with an invalid OAS file (ERROR expected).
  2. A PR with a WARN-only change.
  3. A PR that changes only documentation.
- Check the annotations, the check status, and the merge button for each test PR.

## Delivery

One PR: `.github/workflows/openapi.yml` (about 80 lines), including the SARIF steps from [010](010-cli-sarif-output.md). The branch protection change happens after the merge.

Note (2026-11-13): code PR 1 of [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13) removed the argument `--catalog catalog/permissions.yaml` and the relevant path `catalog/` from `openapi.yml`.

## Review findings

- Security review of PR #75 ([034](034-access-change-review.md), the job `access-changes`), unrelated note: `build.yml` used the actions `actions/checkout@v7` and `actions/setup-java@v6` with a tag, and a tag can move. Fix: `build.yml` pins each action to the commit SHA of its release, like the other workflows: `actions/checkout` v7.0.1 (`3d3c42e5aac5ba805825da76410c181273ba90b1`) and `actions/setup-java` v6.2.0 (`77dd5b2ce225ea11469928ac317850245a74ae78`). The SHAs are the commits of the release tags in the GitHub API. The tag `v6` of `actions/setup-java` now points to v6.3.0. An update of all workflows to v6.3.0 is a separate change.

## Open questions

- The brief says "triggered on changes" to the listed paths. This spec uses a change detection job instead of a `paths` filter, because of the required check behavior. Confirm this choice.
- Must the workflow also run on `push` to `main`, so that code scanning has a baseline for the default branch?
- Should the validation also run in a merge queue (`merge_group` event), if the repository uses one?
- Who is allowed to change branch protection, and where is the change recorded?
- Should `CODEOWNERS` require a review by a code owner for `.github/workflows/**` and `specs/**`? The decision of 2026-11-12 is "no `CODEOWNERS`". Finding SEC-72-1 asks the product owner to decide again, because a PR can change its own checks.

## References

- [008 — CLI basic](008-cli-basic.md)
- [009 — CLI GitHub and JSON output](009-cli-github-and-json-output.md)
- [010 — CLI SARIF output](010-cli-sarif-output.md)
- [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)
- [033 — Breaking change detection](033-breaking-change-detection.md)
- [034 — Access change review](034-access-change-review.md)
- GitHub: troubleshooting required status checks: <https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/collaborating-on-repositories-with-code-quality-features/troubleshooting-required-status-checks>
- GitHub: about protected branches: <https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches>
