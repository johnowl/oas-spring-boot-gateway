# Security policy

This page explains how to report a security vulnerability in oas-spring-boot-gateway and what happens after the report. The gateway handles user sessions and access tokens, so a vulnerability can expose user data or backends. Report each vulnerability privately, so that a fix is ready before the details are public.

## Supported versions

The project has no release yet. Only the `main` branch gets security fixes.

| Version | Supported |
|---|---|
| `main` branch | Yes |
| Any other branch or commit | No |

This table changes with the first release. The [CLI release and rule rollout policy](docs/cli-release-policy.md) describes the versions of the CLI.

## Report a vulnerability

Do not report a vulnerability in a public issue, a pull request, or a discussion. Public details help attackers before a fix exists.

Use GitHub private vulnerability reporting:

1. Open the [Security tab](https://github.com/johnowl/oas-spring-boot-gateway/security) of the repository.
2. Select **Report a vulnerability**.
3. Fill in the form and submit it.

If you cannot use private vulnerability reporting, open a public issue with the title "Security contact request" and no details. A maintainer then gives you a private channel.

The report is not public. Only you, the maintainers, and the people that the maintainers add to the report can read it.

## What to include

Include as much of this information as you can:

- The affected component: `gateway`, `openapi-gateway-core`, `openapi-gateway-cli`, or a workflow in `.github/workflows/`.
- The commit SHA of `main` that you tested.
- The type of the problem, for example an authorization bypass, a token leak, or header spoofing.
- The steps to reproduce the problem. Add a minimal OAS file and the configuration, if they are necessary.
- The impact: what an attacker can do, and what the attacker needs first.
- A proposed fix, if you have one.

Do not include real secrets, tokens, tenant ids, or client ids in the report. Use placeholder values.

## What happens after the report

1. A maintainer confirms that the report arrived.
2. The maintainers reproduce the problem and assess its impact.
3. The maintainers fix the problem in a private fork or a private branch.
4. The maintainers merge the fix and publish a GitHub security advisory.
5. The advisory credits you, unless you ask to stay anonymous.

The maintainers keep you informed in the report thread during each step. Do not disclose the vulnerability publicly before the advisory is published.

## Scope

In scope: the code in `gateway/`, `openapi-gateway-core/`, and `openapi-gateway-cli/`, and the workflows in `.github/workflows/`.

Out of scope:

- `infra/azure-test/`. It creates test app registrations only and is not for production.
- A vulnerability in a dependency, for example Spring Boot. Report it to the project of that dependency. Also report it here if the gateway uses the affected feature.
- A deployment that does not follow the duties of operators. See [Duties of operators and backend teams](docs/security-model.md#duties-of-operators-and-backend-teams).

The [security model](docs/security-model.md) describes the controls of the gateway and its known limits.
