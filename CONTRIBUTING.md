# Contributing to oas-spring-boot-gateway

This page explains how to contribute code and documentation to oas-spring-boot-gateway. It covers the build, the tests, the spec-first workflow, the pull request rules, and the checks of each pull request. Read it before you open a pull request, because the reviewers use these rules.

To report a vulnerability, do not open an issue or a pull request. Follow [SECURITY.md](SECURITY.md).

## Build and test

You need Java 25. The Maven wrapper `./mvnw` downloads Maven, so you do not need to install it. The Redis integration tests need Docker or a compatible container engine.

| Task | Command |
|---|---|
| Build all modules without tests | `./mvnw -q -DskipTests install` |
| Run the unit tests | `./mvnw test` |
| Run all unit tests and integration tests, as CI does | `./mvnw verify` |
| Run the container tests of the change-control workflow | `.github/test/run.sh` |

Always build from the root folder. A build of one module can use an old `SNAPSHOT` of `openapi-gateway-core` from your local Maven repository.

The integration tests have names that end with `IT`. CI does not run `.github/test/run.sh`. Run it when you change `.github/workflows/openapi-change-control.yml`. It needs Docker and network access.

[Getting started](docs/getting-started.md) explains the build, the tests, and a local run of the gateway in more detail.

## Spec-first workflow

Each feature starts with a design specification (spec) in [`specs/`](specs/README.md). Write or update the spec before the code.

1. Find the spec in the [spec index](specs/README.md#index). If no spec exists, copy [`specs/_template.md`](specs/_template.md) to `specs/<NNN>-<slug>.md`. Use the next free number.
2. Read the whole spec. Its "Acceptance criteria" section defines when the work is complete.
3. Implement the spec in one pull request, or in the pull request slices of its "Delivery" section.
4. If the design changes during the work, update the spec in the same pull request.
5. Change the status of the spec in the same pull request. The [status legend](specs/README.md#status-legend) defines each status.

Write specs and documentation in plain English: short sentences, active voice, present tense, and one term for each concept. Use the keywords MUST, SHOULD, and MAY in uppercase only for requirements.

## Pull requests

- Keep pull requests small. The target is fewer than 400 changed lines. Test fixtures and generated files do not count.
- Put one spec, or one slice of a spec, in each pull request.
- Put the tests in the same pull request as the code.
- If a pull request depends on another open pull request, target the branch of that pull request. After the merge of the parent, change the target of the child to `main`.
- Follow `.editorconfig`: UTF-8, LF line endings, 4 spaces for Java, 2 spaces for YAML and JSON.

## Commit messages

Write the subject line as one sentence in the imperative mood. Start with a capital letter and do not end with a period. Add the spec number in parentheses when the commit belongs to a spec.

```text
Add the CLI subcommands rules and explain (spec 032)
Bind every test server and client to 127.0.0.1
```

Explain the reason for the change in the body when the subject line does not make it clear.

## Checks on each pull request

Each pull request runs these GitHub Actions workflows. [CI and change control](docs/ci-change-control.md) explains each job.

| Workflow | Job | What it checks |
|---|---|---|
| `build.yml` | `verify` | Builds the project and runs all tests with `./mvnw -B verify`. |
| `openapi.yml` | `changes`, `validate` | Validates the OAS files in `gateway/src/main/resources/openapi/` with the CLI. |
| `openapi.yml` | `upload-sarif` | Uploads the SARIF report to code scanning. It runs only when the repository variable `CODE_SCANNING_ENABLED` is `true`. |
| `openapi-change-control.yml` | `breaking-changes` | Reports breaking changes to the OAS files. |
| `openapi-change-control.yml` | `access-changes` | Reports changes that loosen the access to an operation, and new or changed audience bindings. |

`openapi.yml` and `openapi-change-control.yml` run only for pull requests to `main`.

## Change-control labels

The jobs `breaking-changes` and `access-changes` fail when they find a change that needs approval. A maintainer approves the change with a label:

| Label | Job |
|---|---|
| `breaking-change-approved` | `breaking-changes` |
| `access-change-approved` | `access-changes` |

Only maintainers apply these labels. The workflow counts a label only when a user with the repository role `admin` or `maintain` adds it after the last push. A label from any other user does not count. Do not ask for the label before the review is complete. Each new push makes the label invalid. See [Approve a breaking change or an access change](docs/ci-change-control.md#approve-a-breaking-change-or-an-access-change).

## Review

A reviewer reads each change to `.github/workflows/**` and to `specs/**` with special care. A pull request can change its own workflow, so the checks alone do not protect these files. See [Limits of the checks](docs/ci-change-control.md#limits-of-the-checks).

## License of contributions

The project uses the [Apache License 2.0](LICENSE). Section 5 of the license applies to your contribution: a contribution that you submit is under the terms of this license, unless you state otherwise.
