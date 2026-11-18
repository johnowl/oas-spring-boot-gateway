# CLI release and rule rollout policy

This document describes how the team releases the CLI `oas-gateway-validate` and how a new rule reaches the pipelines of other teams. The design is in [spec 012](../specs/012-cli-distribution.md).

## Versions

The CLI uses semantic versioning (`MAJOR.MINOR.PATCH`).

| Change | Version part |
|---|---|
| A bug fix that adds no violation to a file that was valid before | PATCH |
| A new best-practice rule with severity WARN or INFO, a new option, a new output format | MINOR |
| A new CONTRACT rule | MAJOR |
| A best-practice rule changes from WARN to ERROR | MAJOR |
| A removed option, a changed exit code, a breaking change to the JSON or SARIF layout | MAJOR |

A CONTRACT rule is always ERROR and cannot be configured. It can fail a pipeline that passed before, so a new CONTRACT rule is always a MAJOR change.

## How to release

1. Merge all changes for the release into `main`.
2. Create a tag `vMAJOR.MINOR.PATCH` on a commit of `main` and push it. Example: `git tag v0.1.0 && git push origin v0.1.0`.
3. The workflow `.github/workflows/release.yml` starts. It has two jobs.
   The job `build` has only read permissions and no registry credentials:
   1. It checks that the tag has the format `vX.Y.Z` and that the commit is on `main`.
   2. It sets the Maven version to `X.Y.Z` and runs `./mvnw -B verify`. If a test fails, the workflow stops and publishes nothing.
   3. It builds the container image and checks `--version` and the non-root user.
   4. It uploads the jar, the POM files, and the image as the workflow artifact `release-files`.

   The job `publish` has the write permissions. It runs no code of the repository:
   1. It checks that the image tag `X.Y.Z` does not exist yet. A release never moves a version tag. If the registry returns an error, the job stops.
   2. It publishes the jar to GitHub Packages, pushes the image to `ghcr.io`, and creates the GitHub release with the jar. All three use the files of `build`.
4. Edit the GitHub release. Add the rule changes (see below).

If a step after the first publication fails, for example the image push, fix the problem and release a new PATCH version. Do not reuse the version.

## Rule rollout

The policy starts with the first release (`v0.1.0`). The rules of the first release keep the severity of their spec.

1. A new best-practice rule starts with severity WARN or INFO in a MINOR version.
2. The release notes list the new rule. If the rule will become ERROR, the release notes give the version in which this happens.
3. The change to ERROR happens in a later MAJOR version.
4. Teams fix the violations, or configure the rule in `.oas-gateway.yaml` ([spec 027](../specs/027-rule-configuration.md)), before they upgrade.

## Release notes

The GitHub release is the announcement channel. Each release MUST have a section "Rule changes" with:

- new rules, with id, category, and severity;
- severity changes, with the old and the new severity;
- removed rules.

The generated notes of the GitHub release list the merged pull requests. They do not replace the section "Rule changes".

## Use in other repositories

Pin an exact version. An upgrade is then a pull request in your repository, and you see new violations before the merge.

```yaml
- name: Validate OAS files
  run: >
    docker run --rm -v "$PWD:/work"
    ghcr.io/johnowl/oas-gateway-validate:0.1.0
    --format github --fail-on error openapi/
```

The image runs in `/work`, so the file paths in the annotations are relative to the root of your repository.

Pass each path as `./dir` or as an absolute path, for example `./rules` for a directory named `rules`. The names `rules`, `explain`, and `baseline` are subcommands. Since the release with `rules` and `explain` ([spec 032](../specs/032-cli-rules-and-explain.md)), these two subcommands return exit code 2 when a file or directory with their name exists in the working directory. The release notes of this release MUST say this, because a pipeline that passes `rules` or `explain` as a path now fails with exit code 2. Without the check, it would pass without a validation.

The repository is public, but each package has its own visibility. On 2026-11-18, no package is published, and the visibility of the packages is not decided.

| Package | Access |
|---|---|
| Image in `ghcr.io` | A public image needs no token. A private image needs a token with the permission `read:packages`, and your repository needs access to the package. |
| Maven artifact in GitHub Packages | Always needs a token with the permission `read:packages`, also for a public package. |
