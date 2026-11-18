# CI and change control

This page explains the GitHub Actions workflows of the repository: what each job checks, how to approve a breaking change or an access change with a label, and the limits of the checks. Authors and reviewers of pull requests read it. The checks find problems in a pull request, but they block a merge only when an administrator makes them required.

## Workflows

| Workflow file | Trigger | Jobs | Purpose |
|---|---|---|---|
| `.github/workflows/build.yml` | Each pull request, and each push to `main` | `verify` | Builds the project and runs all tests with `./mvnw -B verify`. |
| `.github/workflows/openapi.yml` | Each pull request to `main` | `changes`, `validate`, `upload-sarif` | Validates the OAS files with the CLI. |
| `.github/workflows/openapi-change-control.yml` | Each pull request to `main`, also when a label changes | `breaking-changes`, `access-changes` | Compares the OAS files of the pull request with the base branch. |
| `.github/workflows/release.yml` | A tag `vX.Y.Z` | `build`, `publish` | Releases the CLI. See [CLI release and rule rollout policy](cli-release-policy.md). |

All workflows compare the OAS files in `gateway/src/main/resources/openapi/`.

## OpenAPI validation (`openapi.yml`)

### Job `changes`

The job lists the files that the pull request changes. It sets `relevant=true` if a file matches one of these paths:

- `gateway/src/main/resources/`
- `openapi-gateway-core/`
- `openapi-gateway-cli/`
- `.mvn/`, `mvnw`, `pom.xml`
- `.oas-gateway.yaml`, `.oas-baseline.json`
- `.github/workflows/openapi.yml`

If `git diff` fails, the job fails. The `validate` job is then not skipped.

### Job `validate`

The job runs only when `relevant` is `true`. A skipped job reports "Success".

1. It builds the CLI from the pull request.
2. It runs the CLI with `--format github --fail-on error` on `gateway/src/main/resources/openapi`. Each violation appears as an annotation on the line of the file.
3. It writes a SARIF report and keeps it as the artifact `oas-gateway-sarif` for 14 days.

The job fails when the CLI returns exit code `1` or `2`. If the folder does not exist, the job writes a notice and passes. A comment in the workflow says that this check is temporary until the first real OAS file exists.

The job passes no `--config` and no `--baseline` option. So it uses the `recommended` profile, also when `.oas-gateway.yaml` or `.oas-baseline.json` exists.

### Job `upload-sarif`

The job uploads the SARIF report to GitHub code scanning. It runs only when the repository variable `CODE_SCANNING_ENABLED` is `true`. The repository is public, so code scanning needs no GitHub Advanced Security license.

## OpenAPI change control (`openapi-change-control.yml`)

Both jobs read the OAS files of the pull request as data. They do not run code from the pull request.

Both jobs start with the same steps:

1. Check that the checkout is the merge commit of the pull request.
2. Check the location of the OAS files. See [Location check](#location-check).
3. Extract the OAS files of the base commit and of the merge commit. A symbolic link or a submodule in the folder fails the job. A file with `x-since-date` fails the job.
4. If the OAS files did not change, the job passes.

### Location check

The gateway loads only the OAS files that `gateway.openapi.specs` names. The checks compare only `gateway/src/main/resources/openapi/`. So the job fails when a pull request makes one of these changes, which can move the OAS files that the gateway loads:

- a change of `gateway.openapi.specs` in an `application*.yaml`, `application*.yml`, or `application*.properties` file outside `src/test`;
- a change of `spring.config.import`, `spring.config.location`, or `spring.config.additional-location`;
- a new or changed YAML or properties file in `gateway/src/main/resources` whose name does not start with `application`, outside the OAS folder;
- a change of `GatewayOpenApiProperties.java`, which holds the default value;
- a change of a Maven resource with a `targetPath` that names `openapi` in `gateway/pom.xml`;
- a change of `OAS_DIR` in the workflow;
- a file in a folder `src/main/resources/openapi` outside `gateway/src/main/resources/openapi`.

The label cannot change the result of this check. Such a change needs a dedicated pull request that also updates spec 033 and the check.

The location check does not cover these changes, which can also move the OAS files:

- Java code of the gateway, for example `GatewayApplication.java` or `OpenApiRouteDefinitionLocator.java`. Only `GatewayOpenApiProperties.java` is checked.
- `META-INF/spring.factories`, for example a new `EnvironmentPostProcessor`.
- New resources in `gateway/src/main/resources` that are not YAML or properties files, for example a `.json` file outside the OAS folder.

A reviewer MUST treat a change to gateway Java code or to `META-INF/spring.factories` as a change that can move the OAS files.

### Job `breaking-changes`

The job runs `oasdiff breaking` (oasdiff 1.38.0) on the base files and the head files. It compares the files of each `x-gateway.prefix` as one group, because the gateway exposes each group under its prefix.

| Result | Job |
|---|---|
| No breaking change with the level `ERR` | Passes. |
| A breaking change with the level `ERR`, without a valid label | Fails. Annotations show the changes. |
| A breaking change with the level `ERR`, with a valid label `breaking-change-approved` | Passes. |

See [spec 033](../specs/033-breaking-change-detection.md) for the details.

### Job `access-changes`

The job builds the CLI from the base commit, not from the pull request. Then it runs `diff --format github` with the base files and the head files. See [Compare access rules with `diff`](cli.md#compare-access-rules-with-diff).

| Result of `diff` | Job |
|---|---|
| Exit code `0`: no loosening change and no new or changed audience binding | Passes. |
| Exit code `1`: at least one loosening change or binding, without a valid label | Fails. |
| Exit code `1`, with a valid label `access-change-approved` | Passes. |
| Exit code `2`: a file cannot be compared | Fails. The label does not change this result. |
| The pull request removes all OAS files, without a valid label | Fails. |
| The pull request removes all OAS files, with a valid label | Passes. |

The job summary lists all changes and bindings. GitHub shows at most 10 annotations, so read the job summary when the job reports more.

The CLI of the base commit checks the head files with the contract rules of the base. If a pull request changes the contract rules and the OAS files, `diff` can return `2`. Merge the change of the contract rules in a separate pull request first.

## Approve a breaking change or an access change

The labels are:

| Label | Job |
|---|---|
| `breaking-change-approved` | `breaking-changes` |
| `access-change-approved` | `access-changes` |

A label counts only when all these conditions are true:

- The label is on the pull request.
- A user with the repository role `admin` or `maintain` added it.
- The user added it after the last push to the pull request.
- The head of the pull request is the head of the run.

The job reads the label state through the GitHub API. If the API call fails, the label does not count.

### Steps

1. The author opens the pull request. The job fails and lists the changes.
2. A reviewer reads each change. For a binding, the reviewer checks that the `upstream` is the backend of the audience.
3. The author pushes all fixes. Each push after the label makes the label invalid.
4. A user with the role `admin` or `maintain` adds the label. This user SHOULD NOT be the author: the workflow does not check it. Adding the label starts the workflow again.
5. The job passes. Its log shows a notice, for example `Label access-change-approved counts: <user> (maintain) added the label after the last push.`

If the author pushes again, remove the label and add it again after the review.

## Limits of the checks

- **The checks are advisory.** On 2026-11-18, no ruleset and no branch protection rule requires them on `main`. A failed check does not block the merge. The repository is public, so its GitHub plan supports rulesets and branch protection rules. An administrator makes the checks required. See [spec 011](../specs/011-pr-validation-pipeline.md#advisory-checks).
- **A pull request can change its own workflow.** A `pull_request` workflow runs the workflow file of the pull request. A reviewer MUST read each change to `.github/workflows/**` and to `specs/**`.
- **A check name can be copied.** GitHub matches a required check by its name. Another workflow with a job named `access-changes` can report "Success".
- **Recommended setup.** Use a ruleset with a required workflow, so that GitHub runs the workflow file of the default branch. On 2026-11-18, it is not verified that a repository of a personal account can use a required workflow.
- **An author can approve their own pull request.** The label check does not compare the labeling user with the author or the committers. A user with the role `admin` or `maintain` can label their own pull request. Another person MUST review each access change and each breaking change before the label is added.
- **The location check has gaps.** See [Location check](#location-check).
- **No code owners.** The repository has no `CODEOWNERS` file. One approval of any person with write access is enough for the merge.
- **No check of Entra ID.** The checks do not compare the OAS files with Entra ID. See [Microsoft Entra ID setup](entra-id-setup.md#onboard-a-new-backend), step 11.
- **No configuration file in `validate`.** The job does not use `.oas-gateway.yaml` or `.oas-baseline.json`.

## Related pages

- [CLI reference](cli.md)
- [Writing routes](writing-routes.md)
- Specs: [011 — PR validation pipeline](../specs/011-pr-validation-pipeline.md), [033 — Breaking change detection](../specs/033-breaking-change-detection.md), [034 — Access change review](../specs/034-access-change-review.md).
