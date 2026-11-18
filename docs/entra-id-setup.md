# Microsoft Entra ID setup

This page explains how to configure Microsoft Entra ID for the gateway: the app registration of the gateway (`gateway-bff`), one app registration for each backend, the App Roles, the admin consent, and the redirect URIs. Entra ID administrators, the gateway team, and backend teams read it. A wrong configuration causes failed logins, consent errors, or `403` responses.

This page follows the runbook in [spec 021](../specs/021-entra-app-registrations.md). Spec 021 has the status Draft: the identity team has not yet confirmed the steps in the test tenant. All ids on this page are placeholders. Never commit a real tenant id, client id, or secret.

## App registrations

| App registration | Owner | Purpose |
|---|---|---|
| `gateway-bff` | Gateway team | A confidential client. It signs in users with OpenID Connect (OIDC) and gets tokens for the backends on behalf of the user. |
| One app for each backend, for example `orders-api` | Backend team | Defines the permissions (App Roles) of its API. Its client id is the audience. |

Do not create copies of backend App Roles in `gateway-bff`. The gateway reads the roles from the token of each backend.

## Rules for all app registrations

| Rule | Value | Reason |
|---|---|---|
| Access token version | `2` | The issuer is `https://login.microsoftonline.com/<tenant-id>/v2.0`, and `aud` is the client id. The gateway rejects version 1 tokens. |
| Permission names | `^[A-Z][A-Za-z0-9]*(\.[A-Z][A-Za-z0-9]*)+$`, for example `Orders.Read` | The contract rule `permission-format` checks this format in the OAS files. |
| Scopes | Not used for authorization | The gateway checks only `aud` and `roles`. |

The name of the token version property depends on the manifest format. The Microsoft Graph manifest uses `api.requestedAccessTokenVersion`. The older Azure AD Graph manifest uses `accessTokenAcceptedVersion`. Set the value `2`.

## `gateway-bff` app registration

| Setting | Value |
|---|---|
| Supported account types | Single tenant. |
| Redirect URI (platform Web) | `https://<gateway-host>/login/oauth2/code/entra` |
| Post-logout redirect URI | `https://<gateway-host>/`. Register it also as a redirect URI. |
| Credential | A client secret from a secret store. Rotate it before it expires. Microsoft recommends a certificate or a federated credential instead of a client secret. The gateway configuration in `application.yaml` uses a client secret. |
| Implicit grant and hybrid flows | Off. Do not select "Access tokens" or "ID tokens". The post-logout redirect URI is also a redirect URI, so a hybrid flow could put an ID token in the URL fragment of `/`. |
| Allow public client flows | No. |
| API permissions | The delegated permission `access_as_user` of each backend app. |
| Admin consent | Granted for each `access_as_user` permission. |
| Access token version | `2` |

The gateway builds both URIs from `gateway.public-base-url`. With `https://gateway.example.com`, the redirect URI is `https://gateway.example.com/login/oauth2/code/entra`. If you change `gateway.security.logout.post-logout-redirect-path`, change the registered post-logout redirect URI too.

A deployed `gateway-bff` MUST NOT have an `http://localhost` redirect URI. Local runs use a separate app registration.

### Configure the gateway with `gateway-bff`

Set these environment variables in the deployment. See [Configuration reference](configuration.md#environment-variables).

| Variable | Value |
|---|---|
| `ENTRA_TENANT_ID` | `<tenant-id>` |
| `GATEWAY_BFF_CLIENT_ID` | `<gateway-bff-client-id>` |
| `GATEWAY_BFF_CLIENT_SECRET` | Injected by the platform from the secret store. Never type it in a shell or write it in a file. |
| `GATEWAY_PUBLIC_BASE_URL` | `https://<gateway-host>` |

The gateway requests the login scopes `openid`, `profile`, and `offline_access`. `offline_access` gives the refresh token that the gateway uses for the per-audience tokens.

## Backend app registration

| Setting | Value |
|---|---|
| Access token version | `2` |
| Application ID URI | Any value that the tenant policy allows, for example `api://orders-api`. The gateway does not use it. |
| Exposed scope | Exactly one: `access_as_user`, with the type `Admin`. Only an administrator can consent. |
| Pre-authorized applications | Do not list `gateway-bff`. |
| App Roles | One App Role for each permission. The `value` is the permission name, for example `Orders.Read`. |
| `allowedMemberTypes` of each App Role | `User` |
| Enterprise Application: Assignment required | Yes |

The gateway requests the scope `<client-id>/.default offline_access` for each backend. The `.default` scope gives a token with the permissions that `gateway-bff` has through admin consent. The `roles` claim then contains the App Roles of the user for that backend.

"Assignment required = Yes" makes Entra ID refuse tokens to users without an assignment. To assign App Roles to groups, the tenant needs Entra ID P1 or P2.

A token for `.default` has all scopes that `gateway-bff` has for that backend. Therefore:

- A backend MUST authorize requests only on the `roles` claim. It MUST NOT authorize on the `scp` claim.
- `gateway-bff` MUST have exactly one consented scope for each backend: `access_as_user`.

### Example with Terraform

Teams that use Terraform with the `azuread` provider can use this shape. Verify the attribute names against the version of the provider that you use.

```hcl
resource "azuread_application" "orders_api" {
  display_name    = "orders-api"
  identifier_uris = ["api://orders-api"]

  api {
    requested_access_token_version = 2

    oauth2_permission_scope {
      id                         = "<new-guid>"
      value                      = "access_as_user"
      type                       = "Admin"
      admin_consent_display_name = "Access orders-api as the signed-in user"
      admin_consent_description  = "Allows the gateway to call orders-api for the signed-in user."
      enabled                    = true
    }
  }

  app_role {
    id                   = "<new-guid>"
    value                = "Orders.Read"
    display_name         = "Read orders"
    description          = "Read orders."
    allowed_member_types = ["User"]
    enabled              = true
  }
}

resource "azuread_service_principal" "orders_api" {
  client_id                    = azuread_application.orders_api.client_id
  app_role_assignment_required = true
}
```

## Onboard a new backend

The backend team and the gateway team do these steps in this order.

1. Create the backend app registration. Set the access token version to `2`.
2. Set the Application ID URI, for example `api://payments-api`.
3. Expose the scope `access_as_user` with the type `Admin`. Do not add `gateway-bff` to the pre-authorized applications.
4. Create one App Role for each permission. Use the naming pattern. Set `allowedMemberTypes` to `User`.
5. In the Enterprise Application, set "Assignment required" to Yes.
6. Assign the App Roles to users or groups.
7. In `gateway-bff`, add the delegated permission `access_as_user` of the new app.
8. Grant admin consent for that permission.
9. Add the OAS file with `x-gateway.audience` equal to the client id of the new app. Declare `x-gateway-authorization` for each protected operation. See [Writing routes](writing-routes.md).
10. Run the CLI on the OAS folder. The result MUST have no `ERROR`. See [CLI reference](cli.md).
11. Compare the OAS file with Entra ID by hand. `x-gateway.audience` equals the client id of the new app. Each permission in `x-gateway-authorization` is an App Role of that app.
12. Open the pull request. The reviewer checks the audience, the permissions, and the `upstream`. The `upstream` MUST be the address of the backend of that audience.
13. After the deployment, verify one backend token. See [Verify a backend token](#verify-a-backend-token).
14. Configure the backend to accept traffic only from the gateway, for example with a network policy or mutual TLS.

The CLI checks only the format of the audience and the permissions. It does not check that they exist in Entra ID. That is why step 11 is manual.

## Verify a backend token

Get a token for the backend through the gateway in a test environment. Decode the token on your computer. Do not paste production tokens into online tools. Check these claims:

| Claim | Expected value |
|---|---|
| `ver` | `2.0` |
| `iss` | `https://login.microsoftonline.com/<tenant-id>/v2.0` |
| `aud` | The client id of the backend app, equal to `x-gateway.audience`. |
| `roles` | The App Roles of the test user for this backend only. |

## Limits of the admin consent

The admin consent of `gateway-bff` limits the backends that the gateway can get a token for. It is not a complete allowlist.

| Case | Example | Control |
|---|---|---|
| A Microsoft first-party API with consent | Microsoft Graph (`00000003-0000-0000-c000-000000000000`). The login scopes are Graph permissions. | The contract rule `audience-not-first-party`, in the CLI and in the gateway. The rule has a list of seven well-known apps only, for example Microsoft Graph and Azure Resource Manager. For any other first-party API, the controls are: no consent grant, and step 1 of the grant audit. |
| `gateway-bff` itself | `x-gateway.audience` is the client id of `gateway-bff`. | The gateway does not start. |
| User consent | A user consents to a scope of type `User`. | Each `access_as_user` scope has the type `Admin`. Set the tenant user consent setting to "Do not allow user consent". |
| Pre-authorization | A backend app lists `gateway-bff` in `preAuthorizedApplications`. | The grant audit. |
| An app of another publisher | A multi-tenant app with a consent for `gateway-bff`. | The grant audit. |

## Audit the grants of `gateway-bff`

The gateway team MUST audit the grants of `gateway-bff` at a regular interval. The audit checks:

1. Each delegated permission grant of the `gateway-bff` service principal is for one of these: a backend app with an OAS file, or the login scopes of Microsoft Graph.
2. Each grant for a backend app has exactly one scope: `access_as_user`.
3. No grant is a user consent (a grant for one user instead of all users).
4. No backend app lists `gateway-bff` in `preAuthorizedApplications`.

Remove each grant or pre-authorization that fails a check, and inform the owner of the app.

## App registrations for local runs

[`infra/azure-test`](../infra/azure-test/README.md) creates test app registrations with Terraform. It is not for production.

| App registration | Purpose |
|---|---|
| `gateway-bff-test` | The gateway. Redirect URIs `http://localhost:8080/login/oauth2/code/entra` and `http://localhost:8080/`. |
| `echo-api-test` | A test backend with the App Roles `Echo.Read` and `Echo.Admin`. |

The person who runs Terraform gets `Echo.Read` but not `Echo.Admin`, so a test can show a `200` and a `403`. Read the README of `infra/azure-test` for the requirements and the security rules of the Terraform state. Run `terraform destroy` when the tests are finished.

Set the outputs as environment variables, from the root folder of the repository. The command substitution keeps the client secret out of the terminal output and out of the shell history:

```bash
export ENTRA_TENANT_ID="$(terraform -chdir=infra/azure-test output -raw tenant_id)"
export GATEWAY_BFF_CLIENT_ID="$(terraform -chdir=infra/azure-test output -raw gateway_client_id)"
export GATEWAY_BFF_CLIENT_SECRET="$(terraform -chdir=infra/azure-test output -raw gateway_client_secret)"
terraform -chdir=infra/azure-test output -raw echo_api_client_id
```

Use the value of `echo_api_client_id` as `x-gateway.audience` in a local OAS file in `local-openapi/`. Git ignores this folder. Do not commit these values.

## Operational notes

- Each App Role makes the access token larger. The default limit for request headers is 8 KB (`server.max-http-request-header-size`). Do not create one permission for each field or each small action. Use one permission for a group of related actions, for example `Orders.Read` for all read operations on orders.
- When an administrator removes an App Role, the role stays in existing access tokens until they expire. The default lifetime is 60 to 90 minutes.
- Refresh tokens of web apps are valid for up to 90 days, and Entra ID rotates them.

## Open questions

These questions are open in spec 021:

1. Does the tenant policy allow Application ID URIs such as `api://orders-api`?
2. Which team owns `gateway-bff`, and who can grant admin consent?
3. Do backend teams manage their apps with Terraform or by hand?
4. What is the interval of the grant audit, and who owns it?
