# Writing routes

This page explains how to write an OpenAPI Specification (OAS) file that the gateway turns into routes. It covers the `x-gateway` extension, access rules, public operations, and path templates. It also lists common mistakes. Backend teams that add or change an API read this page. The terms are defined in [Concepts](concepts.md#glossary).

## Where OAS files live

Put each OAS file in `gateway/src/main/resources/openapi/`. The file name ends with `.yaml`, `.yml`, or `.json`.

| Rule | Reason |
|---|---|
| Put the files directly in the folder, not in a subfolder. | The default location `classpath:openapi/*.yaml` (and `*.yml`, `*.json`) does not read subfolders. |
| Do not put OAS files in `src/main/resources/openapi/` of another module. | The gateway does not start if a classpath pattern finds a file outside the gateway module. The change-control checks also fail. |
| Write one OAS file for each backend. | The contract rule `audience-unique` allows each audience in one file only. |
| Use a unique file name. | The route id starts with the file name without the extension, for example `orders`. |

The checks of a pull request compare only `gateway/src/main/resources/openapi/`. A pull request that changes `gateway.openapi.specs` fails the `breaking-changes` and `access-changes` jobs. See [CI and change control](ci-change-control.md).

An operator can load OAS files from the file system with a `file:` location. See [`gateway.openapi.specs`](configuration.md#gateway-properties). OAS files outside `gateway/src/main/resources/openapi/` get no CI validation, no breaking-change review, and no access-change review. The gateway still runs the contract rules at startup. See [Duties of operators and backend teams](security-model.md#duties-of-operators-and-backend-teams).

## Structure of an OAS file

An OAS file has these parts:

1. `openapi`: version `3.0.x` or `3.1.x`. Swagger 2.0 is not supported.
2. `x-gateway`: the backend of the file.
3. `security` and `components.securitySchemes`: the login requirement.
4. `paths`: the operations, each with its access rule.

## The `x-gateway` object

The root `x-gateway` object tells the gateway where to send the requests of the file. The contract rule `x-gateway-schema` checks it against `openapi-gateway-core/src/main/resources/x-gateway.schema.json`.

| Property | Required | Format | Description |
|---|---|---|---|
| `upstream` | Yes | `^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$` | The address of the backend: `scheme://host[:port]`, without a path. |
| `prefix` | No | `^(/[a-z0-9-]+)+$` | The public path prefix. The gateway removes it before it calls the backend. |
| `upstream-base-path` | No | `^(/[A-Za-z0-9._-]+)+$` | The base path on the backend. The gateway adds it before the OAS path. |
| `audience` | Yes, if the file has a protected operation | Lowercase GUID | The client id of the backend app registration in Entra ID. |

No other property is allowed.

### How the gateway builds the backend path

The public path is the prefix followed by the OAS path. The backend path is the upstream base path followed by the OAS path.

| `prefix` | `upstream-base-path` | OAS path | Client calls | Backend receives |
|---|---|---|---|---|
| `/orders-api` | `/v1` | `/orders/{id}` | `GET /orders-api/orders/42` | `GET https://orders-service:8443/v1/orders/42` |
| `/orders-api` | none | `/orders/{id}` | `GET /orders-api/orders/42` | `GET https://orders-service:8443/orders/42` |
| none | none | `/orders/{id}` | `GET /orders/42` | `GET https://orders-service:8443/orders/42` |

Use a prefix for each backend. Without a prefix, the paths of different files can collide.

### `http` upstream and tokens

The gateway sends the access token of the audience to the `upstream`. An `http` upstream sends the token without Transport Layer Security (TLS). The gateway starts, but it writes one `WARN` line for each such route. Example for the upstream `http://orders-service:8080`:

```text
The route orders:/orders relays the access token of the audience 6f1c2e8a-3b4d-4c5e-8f60-718293a4b5c6 to http://orders-service:8080 without TLS. Use an https upstream, or keep the network between the gateway and the backend private (spec 025).
```

Use `https`, or keep the network between the gateway and the backend private.

## Declare the login requirement

Each protected operation needs a security requirement that names a security scheme of the file.

```yaml
security:
  - entra: []
components:
  securitySchemes:
    entra:
      type: openIdConnect
      openIdConnectUrl: https://login.microsoftonline.com/common/v2.0/.well-known/openid-configuration
```

Rules:

- The scheme type is `openIdConnect` or `oauth2` (`security-scheme-type`).
- Each scheme name in a requirement exists in `components.securitySchemes` (`security-scheme-exists`).
- Each requirement maps the scheme to an empty list. The gateway does not check scopes (`security-no-scopes`).
- No requirement is empty, for example `- {}` (`security-no-empty-requirement`).

The gateway does not read the security scheme. It always uses the Entra ID session. The scheme documents the login for API clients and tools.

### How the gateway decides that an operation is public

| Operation `security` | Root `security` | Result |
|---|---|---|
| `[]` | any | Public operation. |
| `[ {entra: []} ]` | any | Protected operation. The operation field replaces the root field. |
| not set | `[ {entra: []} ]` | Protected operation. |
| not set | not set, or `[]` | Error `security-explicit`. A root `security: []` does not make operations public. |

Public is always explicit on the operation. A forgotten `security` field never makes an operation public.

## Declare the access rule of a protected operation

Each protected operation has `x-gateway-authorization` (`authorization-required`). The extension has one of these forms:

| Form | Meaning |
|---|---|
| `all-of: [A.X, B.Y]` | The user has every listed permission. |
| `any-of: [A.X, B.Y]` | The user has at least one listed permission. |
| `all-of` and `any-of` together | The user has every `all-of` permission and at least one `any-of` permission. |
| `authenticated-only: true` | Any signed-in user with a valid token for the audience. Use it only when the operation needs no permission. |

Rules:

- Each list is not empty.
- `authenticated-only` is `true`, and it is alone. It cannot appear with `all-of` or `any-of`.
- Each permission matches `^[A-Z][A-Za-z0-9]*(\.[A-Z][A-Za-z0-9]*)+$`, for example `Orders.Read` (`permission-format`).
- Each permission is the value of an App Role of the backend app registration. The gateway compares the values exactly, with letter case.

The gateway reads the permissions from the `roles` claim of the token for the audience of the file. It does not check scopes. See [Security model](security-model.md#authorization-decision).

The CLI checks only the format of a permission. It does not check that the App Role exists in Entra ID. A typo such as `Orders.Raed` passes the CLI, and every request to that operation then gets `403`.

## Declare a public operation

Write `security: []` on the operation. Do not add `x-gateway-authorization` (`authorization-not-on-public`).

```yaml
paths:
  /health:
    get:
      operationId: getHealth
      summary: Check the health of the orders backend.
      security: []
      responses:
        "200":
          description: The backend is healthy.
```

The gateway sends a request to a public operation without an `Authorization` header. A public operation needs no session.

## Set a response timeout for one operation

The global response timeout is `10s` (`spring.cloud.gateway.server.webflux.httpclient.response-timeout`). To change it for one operation, use `x-gateway-response-timeout`. The value is an integer from `100` to `60000` milliseconds.

```yaml
paths:
  /orders/export:
    post:
      x-gateway-response-timeout: 30000
```

The operation keys `x-gateway-response-timeout` and `x-gateway-authorization` are the only operation extensions that start with `x-gateway`. Any other name, for example `x-gateway-respone-timeout`, is the error `x-gateway-unknown-extension`.

## Write path templates

The gateway supports a subset of the path syntax of OAS.

| Allowed | Example |
|---|---|
| Literal segments with letters, digits, `-`, `.`, `_`, and `~` | `/orders/latest` |
| A variable that is a whole segment | `/orders/{id}` |
| A variable name with letters, digits, `_`, and `-`, not starting with a digit | `/orders/{order_id}` |

| Not allowed | Example |
|---|---|
| A variable inside a segment | `/orders/{id}.json` |
| Wildcards and catch-all variables | `/files/**`, `/files/{*path}` |
| Regular expressions | `/orders/{id:[0-9]+}` |
| An empty segment or a trailing slash | `/orders/`, `/orders//items` |
| A dot segment | `/orders/../admin` |
| The same variable name twice | `/a/{id}/b/{id}` |

Declare each path variable as a path parameter. Otherwise the error is `oas-parse`.

```yaml
paths:
  /orders/{id}:
    parameters:
      - name: id
        in: path
        required: true
        schema:
          type: string
```

### Collisions between paths

No request can match two operations with the same method (`route-collision`). Variable names do not count.

| Paths | Result |
|---|---|
| `/orders/{id}` and `/orders/{key}` | Collision. |
| `/a/{x}/c` and `/a/b/{y}` | Collision. The templates overlap. |
| `/orders/{id}` and `/orders/latest`, in the same file | Allowed. The literal path wins. |
| `/orders/{id}` in `orders.yaml` and `/orders/latest` in `reports.yaml`, without prefixes | Collision. A literal path can specialize a template only in the same file. |

## Use only local references

The gateway reads each OAS file on its own. It accepts only local references, for example `$ref: '#/components/schemas/Order'`.

The CLI also accepts a reference to another file in the same folder, but the gateway does not. The gateway stops at startup with this message:

```text
oas-parse file:/.../hello.yaml#/paths/~1greeting/get/responses/200/$ref Reference 'common.yaml#/components/responses/Ok' is not allowed: only local references (#/...) are allowed in content that does not come from a file
```

Also, the CLI and the gateway treat every file in the folder as an OAS file, so a shared file without `x-gateway` fails `x-gateway-schema`. Keep all references local.

## Complete example

This file passes the CLI with `--fail-on info` and no violation. Copy it to `gateway/src/main/resources/openapi/orders.yaml` and change the values.

```yaml
openapi: 3.0.3
info:
  title: Orders API
  version: 1.0.0
x-gateway:
  upstream: https://orders-service:8443
  prefix: /orders-api
  upstream-base-path: /v1
  audience: 6f1c2e8a-3b4d-4c5e-8f60-718293a4b5c6
security:
  - entra: []
tags:
  - name: orders
paths:
  /orders:
    get:
      operationId: listOrders
      summary: List the orders of the user.
      tags: [orders]
      x-gateway-authorization:
        all-of: [Orders.Read]
      responses:
        "200":
          description: The orders.
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
    delete:
      operationId: deleteOrder
      summary: Delete one order.
      tags: [orders]
      x-gateway-authorization:
        all-of: [Orders.Read, Orders.Delete]
      responses:
        "204":
          description: The order is deleted.
  /orders/export:
    post:
      operationId: exportOrders
      summary: Export the orders as a file.
      tags: [orders]
      x-gateway-response-timeout: 30000
      x-gateway-authorization:
        any-of: [Orders.Export, Orders.Admin]
      responses:
        "200":
          description: The export file.
  /me:
    get:
      operationId: getProfile
      summary: Get the profile of the signed-in user.
      tags: [orders]
      x-gateway-authorization:
        authenticated-only: true
      responses:
        "200":
          description: The profile.
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

The audience `6f1c2e8a-3b4d-4c5e-8f60-718293a4b5c6` is an example. Use the client id of your backend app registration.

This file gives these operations:

| Client calls | Access rule | Backend receives |
|---|---|---|
| `GET /orders-api/orders` | `Orders.Read` | `GET /v1/orders` |
| `GET /orders-api/orders/42` | `Orders.Read` | `GET /v1/orders/42` |
| `DELETE /orders-api/orders/42` | `Orders.Read` and `Orders.Delete` | `DELETE /v1/orders/42` |
| `POST /orders-api/orders/export` | `Orders.Export` or `Orders.Admin` | `POST /v1/orders/export`, timeout 30 seconds |
| `GET /orders-api/me` | Any signed-in user | `GET /v1/me` |
| `GET /orders-api/health` | Public | `GET /v1/health` |
| `PUT /orders-api/orders/42` | No operation | Nothing. The gateway answers `404`. |

Validate the file before you open a pull request:

```bash
java -jar openapi-gateway-cli/target/oas-gateway-validate.jar gateway/src/main/resources/openapi
```

See [CLI reference](cli.md) for the options.

## Rule for `GET`, `HEAD`, and `OPTIONS`

An operation with the method `GET`, `HEAD`, or `OPTIONS` MUST NOT change state. The CSRF protection does not cover these methods, and the session cookie goes with a cross-site `GET` navigation. Example: use `POST /orders-api/orders/42/cancellation`, not `GET /orders-api/orders/42/cancel`.

No rule checks this. The review of the pull request is the control.

## Common mistakes

Each row shows the message of the CLI.

| Mistake | Rule | Message |
|---|---|---|
| A path in `upstream`, for example `http://orders-service:8080/v1` | `x-gateway-schema` | `'upstream' must be scheme://host[:port] without a path. Use 'upstream-base-path' for a backend path.` |
| A protected operation in a file without `audience` | `audience-required` | `The file has protected operations but no 'audience'. Add the client id of the backend app registration.` |
| A public operation without `security: []`, in a file with root `security` | `authorization-required` | `Operation has no x-gateway-authorization. Declare all-of, any-of, or authenticated-only: true.` |
| A permission in lowercase, for example `orders.read` | `permission-format` | `Permission 'orders.read' does not match ^[A-Z][A-Za-z0-9]*(\.[A-Z][A-Za-z0-9]*)+$. Example: Orders.Read.` |
| A trailing slash, for example `/orders/` | `path-pattern-supported` and `path-no-trailing-slash` | `Path '/orders/' has an empty segment. Remove the trailing or double slash.` and `Path '/orders/' ends with '/'. Remove the trailing slash.` |
| A path variable without a path parameter | `oas-parse`, at the pointer `#`, once for each operation of the path | `paths.'/orders/{id}'. Declared path parameter id needs to be defined as a path parameter in path or operation level` |
| The same audience in two files | `audience-unique` | Run `oas-gateway-validate explain audience-unique`. |
| The client id of a Microsoft app, for example Microsoft Graph, as audience | `audience-not-first-party` | Run `oas-gateway-validate explain audience-not-first-party`. |

The gateway also does not start when `x-gateway.audience` is the client id of `gateway-bff`.

To see the description and examples of any rule, run `explain`. See [CLI reference](cli.md#list-and-explain-the-rules).

## Related pages

- [CLI reference](cli.md): validate the files.
- [Microsoft Entra ID setup](entra-id-setup.md): create the backend app registration and its App Roles.
- [CI and change control](ci-change-control.md): what happens when a pull request changes an access rule.
- Specs: [006 — x-gateway extension](../specs/006-x-gateway-extension.md), [007 — Route model](../specs/007-route-model.md), [018 — Authorization extension](../specs/018-authorization-extension.md).
