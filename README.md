<img src="assets/icon.svg" width="64" height="64" alt="">

# oas-spring-boot-gateway

An API gateway for browser applications that creates its routes and access rules from OpenAPI Specification (OAS) files.

[![build](https://github.com/johnowl/oas-spring-boot-gateway/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/johnowl/oas-spring-boot-gateway/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

oas-spring-boot-gateway is a non-blocking gateway on Spring Boot 4.1.2, Spring Cloud Gateway Server WebFlux, and Java 25. Each backend team describes its API in an OAS file. The gateway reads these files at startup and exposes only the documented operations. A command-line tool (CLI) checks the same files in each pull request.

## Why this project exists

A gateway with hand-written routes can drift from the API contract. A forgotten route can expose an endpoint, and a forgotten access rule can make an operation public. This project makes the OAS file the only source of the routes and the access rules. It checks the file before the merge and at startup.

## Key features

- **Routes from OAS files.** Only documented operations are reachable. Any other path or method gets `404`.
- **Access rules in OAS files.** Each operation declares its required permissions, or declares that it is public.
- **Session login with Microsoft Entra ID.** The browser gets only a session cookie. The tokens stay in the gateway (Backend for Frontend, BFF).
- **Per-audience tokens.** Each backend gets a token for its own audience. The gateway checks the `aud` claim and the `roles` claim.
- **Validation in two places.** The CLI checks the OAS files in pull requests. The gateway checks the contract rules again at startup and does not start with an invalid file.
- **Change control in pull requests.** Workflows report breaking changes and access changes, and ask for an approval label.

See [Concepts](docs/concepts.md) for the architecture and the request flow.

## Project status

The project is before version 1.0. There is no release yet, and the version in `pom.xml` is `0.0.1-SNAPSHOT`. The design, the configuration, and the CLI output can change before version 1.0.

Some parts are still Draft:

- [Spec 023 (per-audience tokens)](specs/023-per-audience-tokens.md): the code is merged. One acceptance criterion needs a manual check in a test tenant: the user consent case of "Scope form".
- [Spec 033 (breaking change detection)](specs/033-breaking-change-detection.md) and [spec 034 (access change review)](specs/034-access-change-review.md): the workflow jobs exist. Some acceptance criteria need test pull requests on GitHub.
- The change-control checks are advisory. They block a merge only when a ruleset or a branch protection rule makes them required. See [Limits of the checks](docs/ci-change-control.md#limits-of-the-checks).

The [spec index](specs/README.md#index) shows the status of each spec.

## Requirements

| Tool | Version | Used for |
|---|---|---|
| Java Development Kit (JDK) | 25 | Build and run. |
| Maven wrapper `./mvnw` | Included | Build. You do not need to install Maven. |
| Docker, or a compatible container engine | Any current version | Only for the Redis integration tests (Testcontainers). |

To run the gateway, you also need a Microsoft Entra ID test tenant. See [Getting started](docs/getting-started.md#run-the-gateway-on-your-computer).

## Quick start

1. Build all modules:

   ```bash
   ./mvnw -q -DskipTests install
   ```

2. Validate an OAS file with the CLI. Save the [example](#example-oas-file) as `local-openapi/orders.yaml`, then run:

   ```bash
   java -jar openapi-gateway-cli/target/oas-gateway-validate.jar local-openapi
   ```

   The output is `1 file checked: 0 errors, 0 warnings, 0 info.`

3. Run all tests:

   ```bash
   ./mvnw verify
   ```

4. Run the gateway on your computer with a first route. Follow [Getting started](docs/getting-started.md). It shows the Entra ID settings, the environment variables, and a small test backend.

Git ignores the folder `local-openapi/`.

## Example OAS file

This file declares one backend with one protected operation and one public operation. It passes the CLI without a violation.

```yaml
openapi: 3.0.3
info:
  title: Orders API
  version: 1.0.0
x-gateway:
  upstream: https://orders-service:8443
  prefix: /orders-api
  audience: 6f1c2e8a-3b4d-4c5e-8f60-718293a4b5c6
security:
  - entra: []
tags:
  - name: orders
paths:
  /orders/{id}:
    parameters:
      - name: id
        in: path
        required: true
        schema:
          type: string
    get:
      operationId: getOrder
      summary: Get one order.
      tags: [orders]
      x-gateway-authorization:
        all-of: [Orders.Read]
      responses:
        "200":
          description: The order.
  /health:
    get:
      operationId: getHealth
      summary: Check the health of the orders backend.
      tags: [orders]
      security: []
      responses:
        "200":
          description: The backend is healthy.
components:
  securitySchemes:
    entra:
      type: openIdConnect
      openIdConnectUrl: https://login.microsoftonline.com/common/v2.0/.well-known/openid-configuration
```

| Client calls | Access rule | Backend receives |
|---|---|---|
| `GET /orders-api/orders/42` | The user has the App Role `Orders.Read` of the audience. | `GET https://orders-service:8443/orders/42` |
| `GET /orders-api/health` | Public. No session is necessary. | `GET https://orders-service:8443/health` |
| `DELETE /orders-api/orders/42` | No operation. | Nothing. The gateway answers `404`. |

The audience is an example value. Use the client id of your backend app registration. [Writing routes](docs/writing-routes.md) explains each field.

## Project layout

| Path | Content |
|---|---|
| `openapi-gateway-core/` | OAS parsing, validation rules, route model, access rules, and path trie. No Spring dependency. |
| `openapi-gateway-cli/` | The CLI `oas-gateway-validate`. It builds `oas-gateway-validate.jar`. |
| `gateway/` | The Spring Cloud Gateway application. It uses `openapi-gateway-core`. |
| `docs/` | User documentation. |
| `specs/` | Design specifications and decisions. |
| `infra/azure-test/` | Terraform for test app registrations in Entra ID. Not for production. |

## Documentation

- [Documentation index](docs/README.md): getting started, concepts, writing routes, configuration, Entra ID setup, security model, CLI, CI, and operations.
- [Specifications](specs/README.md): the design of each feature and the decisions.
- [CLI release and rule rollout policy](docs/cli-release-policy.md): versions and the rollout of new rules.

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before you open a pull request. It explains the build, the tests, the spec-first workflow, and the checks of a pull request.

## Security

Do not report a vulnerability in a public issue. Use GitHub private vulnerability reporting. See [SECURITY.md](SECURITY.md).

## License

This project uses the Apache License 2.0. See [LICENSE](LICENSE).
