# Getting started

This page shows how to build the project, run the tests, and run the gateway on your computer with a first route. Developers who are new to the project read it first. At the end, a request goes through the gateway to a small local backend.

## Prerequisites

| Tool | Version | Used for |
|---|---|---|
| Java Development Kit (JDK) | 25 | Build and run. The project sets `java.version` to `25`. |
| Docker, or a compatible container engine | Any current version | The Redis integration tests start the image `redis:8.2.2` with Testcontainers. |
| Python 3 | 3.7 or later | Only for the small test backend in this page. |
| A Microsoft Entra ID test tenant | Not applicable | Only to run the gateway. See [Run the gateway on your computer](#run-the-gateway-on-your-computer). |

You do not need to install Maven. The Maven wrapper `./mvnw` downloads Maven 3.10.0.

## Build the project

1. Clone the repository and open its root folder.
2. Build all modules without tests:

   ```bash
   ./mvnw -q -DskipTests install
   ```

3. Check the two jar files:

   | File | Content |
   |---|---|
   | `openapi-gateway-cli/target/oas-gateway-validate.jar` | The CLI `oas-gateway-validate`. |
   | `gateway/target/gateway-0.0.1-SNAPSHOT.jar` | The gateway application. |

Always build the full reactor from the root folder. A build of one module can use an old `SNAPSHOT` of `openapi-gateway-core` from your local Maven repository.

## Run the tests

Run all unit tests and integration tests, as the `build` workflow does:

```bash
./mvnw verify
```

The integration tests have names that end with `IT`. The Redis integration tests need a running container engine. The other tests use a mock identity provider, so they need no Entra ID tenant.

To run only the unit tests:

```bash
./mvnw test
```

## Validate an OAS file with the CLI

1. Create the folder `local-openapi` in the root folder of the repository.
2. Create the file `local-openapi/hello.yaml` with this content:

   ```yaml
   openapi: 3.0.3
   info:
     title: Hello API
     version: 1.0.0
   x-gateway:
     upstream: http://127.0.0.1:9000
     prefix: /hello-api
   tags:
     - name: greetings
   paths:
     /greeting:
       get:
         operationId: getGreeting
         summary: Get a greeting.
         tags: [greetings]
         security: []
         responses:
           "200":
             description: The greeting.
   ```

3. Validate the file:

   ```bash
   java -jar openapi-gateway-cli/target/oas-gateway-validate.jar local-openapi
   ```

   The output is:

   ```text
   1 file checked: 0 errors, 0 warnings, 0 info.
   ```

The file has one public operation. Requests to `/hello-api/greeting` go to `http://127.0.0.1:9000/greeting`. The file has no audience, so no token goes over this `http` connection. See [Writing routes](writing-routes.md) for all options.

Git ignores the folder `local-openapi/` (see `.gitignore`).

## Run the gateway on your computer

The gateway reads the OpenID Connect metadata of Entra ID at startup. It does not start without a reachable Entra ID tenant and a valid `gateway-bff` app registration. A mock identity provider exists only in the tests.

### Before you start

You need a test app registration for the gateway with:

- the redirect URI `http://localhost:8080/login/oauth2/code/entra`;
- the redirect URI `http://localhost:8080/` (the post-logout redirect URI);
- a client secret.

[`infra/azure-test`](../infra/azure-test/README.md) creates such an app registration with Terraform. See also [Microsoft Entra ID setup](entra-id-setup.md#app-registrations-for-local-runs). Never use the app registration of a deployed gateway for local runs.

### Steps

1. Start a small backend on port 9000 that answers `GET /greeting`. The `--bind` option makes it listen only on your computer, not on the network:

   ```bash
   mkdir -p /tmp/hello-backend
   echo 'Hello from the backend' > /tmp/hello-backend/greeting
   python3 -m http.server 9000 --bind 127.0.0.1 --directory /tmp/hello-backend
   ```

2. In a second terminal, in the root folder of the repository, set the environment variables. Replace the placeholders with the values of your test app registration. `read -rs` reads the client secret without showing it, so it does not go into the shell history:

   ```bash
   export ENTRA_TENANT_ID='<tenant-id>'
   export GATEWAY_BFF_CLIENT_ID='<gateway-bff-client-id>'
   read -rs GATEWAY_BFF_CLIENT_SECRET && export GATEWAY_BFF_CLIENT_SECRET
   export GATEWAY_PUBLIC_BASE_URL='http://localhost:8080'
   export GATEWAY_OPENAPI_SPECS="file:$PWD/local-openapi/*.yaml"
   ```

3. Start the gateway with the Spring profile `local`:

   ```bash
   java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
   ```

   The profile `local` allows `http://localhost:8080` as the public base URL. The log shows these lines:

   ```text
   gateway.openapi.specs: [file:/.../local-openapi/*.yaml]
   Created 1 route(s) from 1 OAS file(s)
   Profile 'local' is active. Do not use it in a deployed gateway.
   ```

4. Call the route through the gateway:

   ```bash
   curl -i http://localhost:8080/hello-api/greeting
   ```

   The response is `200` with the text `Hello from the backend`.

5. Call a path that no OAS file declares:

   ```bash
   curl -i http://localhost:8080/hello-api/unknown
   ```

   The response is `404` with this body:

   ```json
   {"type":"about:blank","title":"Not Found","status":404,"detail":"Not found."}
   ```

6. Check the health endpoint on the management port:

   ```bash
   curl http://localhost:8081/actuator/health
   ```

Do not commit your environment variables or the client secret. With the test app registrations of `infra/azure-test`, you can set the variables from the Terraform outputs. See [App registrations for local runs](entra-id-setup.md#app-registrations-for-local-runs).

## Add your first real route

1. Create the OAS file of your backend in `gateway/src/main/resources/openapi/`. Follow [Writing routes](writing-routes.md). This repository is public: keep the OAS files of real backends, with their host names and client ids, in a private fork or a private deployment repository, not in a pull request to this repository.
2. Validate the folder:

   ```bash
   java -jar openapi-gateway-cli/target/oas-gateway-validate.jar gateway/src/main/resources/openapi
   ```

3. Fix each `ERROR`. Fix the `WARN` violations, or accept them with a reason. See [CLI reference](cli.md).
4. For a protected operation, create the backend app registration and its App Roles. See [Microsoft Entra ID setup](entra-id-setup.md#onboard-a-new-backend).
5. Open a pull request. The workflows validate the file and compare it with the base branch. See [CI and change control](ci-change-control.md).

## Next steps

- [Concepts](concepts.md): the request flow and the terms.
- [Configuration reference](configuration.md): all properties.
- [Operations](operations.md): run the gateway in an environment.
