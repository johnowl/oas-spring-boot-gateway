# Documentation

This folder contains the user documentation of oas-spring-boot-gateway. It explains how to build and run the gateway, write routes in OAS files, configure Microsoft Entra ID, use the CLI, and operate the gateway. Start with [Getting started](getting-started.md), then read [Concepts](concepts.md).

## Pages

| Page | Content |
|---|---|
| [Getting started](getting-started.md) | Build the project, run the tests, run the gateway locally with a first route. |
| [Concepts](concepts.md) | Architecture, request flow, login flow, and the glossary of terms. |
| [Writing routes](writing-routes.md) | Write an OAS file with `x-gateway`, access rules, public operations, and path templates. |
| [Configuration reference](configuration.md) | Every `gateway.*` property, the environment variables, and the Spring profiles. |
| [Microsoft Entra ID setup](entra-id-setup.md) | App registrations, App Roles, admin consent, and redirect URIs. |
| [Security model](security-model.md) | Sessions, CSRF, per-audience tokens, the authorization decision, header hygiene, the log guard, and the duties of operators. |
| [CLI reference](cli.md) | Commands, options, exit codes, output formats, rule configuration, deviations, baseline, and `diff`. |
| [CI and change control](ci-change-control.md) | The workflows, the approval labels, and the limits of the checks. |
| [Operations](operations.md) | Ports, health, memory or Redis session store, logs, and metrics. |
| [CLI release and rule rollout policy](cli-release-policy.md) | How the team releases the CLI and introduces new rules. |

## Other sources

- [Specs](../specs/README.md): the design and the decisions behind each feature. If a spec and this documentation disagree, the code is the reference.
- [`infra/azure-test`](../infra/azure-test/README.md): Terraform for test app registrations in Entra ID.
