# 021 — Entra app registrations

| Field | Value |
|---|---|
| Status | Draft |
| Phase | 7 — Session authentication and authorization in the gateway |
| Depends on | None |
| Modules | Documentation only |

## Summary

This spec is a runbook. It describes how to configure Microsoft Entra ID for the gateway app registration (`gateway-bff`) and for each backend API app registration. It also gives the onboarding checklist for a new backend API. The gateway code in specs 022 to 025 depends on this configuration. A wrong configuration causes login failures, consent screens, or tokens with the wrong audience.

## Motivation

The gateway authenticates browser users with Entra ID and gets one access token for each audience. This model works only if all app registrations follow the same rules:

- All apps issue version 2 tokens. Otherwise the issuer and the `aud` claim have a different format, and token validation fails.
- Each backend app exposes the `access_as_user` scope. Otherwise the gateway cannot redeem the login refresh token for a token of that backend.
- The `gateway-bff` app has admin consent for each backend. Otherwise the gateway cannot get a token for that backend. The admin consent limits the audiences of the gateway, but it is not a complete allowlist. See "Limits of the admin consent".
- Permissions are App Roles of the backend app. Otherwise the `roles` claim of the backend token does not contain them.

Without one written procedure, each team configures its app in a different way.

## Scope

### In scope

- Configuration of the `gateway-bff` app registration.
- Configuration of each backend app registration and its Enterprise Application.
- The tenant consent settings and the regular audit of the grants of `gateway-bff`.
- The onboarding checklist for a new backend API.
- Verification steps for a correct configuration.

### Out of scope

- Gateway code. See [022](022-session-login.md), [023](023-per-audience-tokens.md), [024](024-authorization-manager.md), and [025](025-token-relay-and-header-hygiene.md).
- A check in the PR that an audience or a permission exists in Entra. The permission catalog is removed (decision of 2026-11-13). See [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13).
- Service-to-service tokens (client credentials). See [026](026-service-to-service-bearer.md).

## Design

### App registrations and their roles

| App registration | Owner | Purpose |
|---|---|---|
| `gateway-bff` | Gateway team | Confidential client. Signs in users with OpenID Connect (OIDC). Requests tokens for backend APIs on behalf of the user. |
| One app for each backend API (for example `orders-api`) | Backend team | Resource. Defines the permissions (App Roles) of its API. Its client id is the audience. |

The gateway MUST NOT define copies of backend App Roles in `gateway-bff`. Copies duplicate role assignments, move ownership to the gateway team, give backends tokens with the wrong `aud`, and cause name collisions.

### Tenant-wide rules

All app registrations of the gateway model MUST follow these rules:

| Rule | Value | Reason |
|---|---|---|
| Access token version | `requestedAccessTokenVersion: 2` | The issuer is `https://login.microsoftonline.com/<tenant-id>/v2.0` and `aud` is the client id GUID. |
| Permission names | Pattern `^[A-Z][A-Za-z0-9]*(\.[A-Z][A-Za-z0-9]*)+$`, for example `Orders.Read` | Same convention as Microsoft Graph. The contract rule `permission-format` checks it. |
| Scope checks | None | The gateway checks only `aud` and `roles`. Scopes only enable the delegated flow. |

The property name in the app manifest depends on the manifest format. The Microsoft Graph manifest uses `api.requestedAccessTokenVersion`. The older Azure AD Graph manifest uses `accessTokenAcceptedVersion`. Both MUST have the value `2`.

### `gateway-bff` app registration

| Setting | Value |
|---|---|
| Supported account types | Single tenant. |
| Redirect URI (platform Web) | `https://<gateway-host>/login/oauth2/code/entra` |
| Post-logout redirect URI | `https://<gateway-host>/`. A deployed `gateway-bff` MUST NOT have an `http://localhost` redirect URI or post-logout redirect URI ([022](022-session-login.md)); local runs use a separate app registration. This is the default of [022](022-session-login.md) (decision of 2026-11-13). If [022](022-session-login.md) changes the path, change this value too. |
| Credential | Client secret (see "Decisions"). The value comes from a secret store. Rotate the secret before it expires. |
| API permissions | Delegated permission `access_as_user` of each backend app. |
| Admin consent | Granted for each backend `access_as_user` permission. For each backend, `access_as_user` is the only consented scope. |
| User consent | Not possible for the backend scopes: each `access_as_user` scope has the type `Admin` (see "Backend app registration"). |
| Access token version | `2` |

Admin consent is required. The gateway requests the scope `{client-id}/.default` for each backend ([023](023-per-audience-tokens.md), "Scope form"). Without admin consent, the gateway cannot get a token for that backend. It receives a consent error or another token endpoint error ([023](023-per-audience-tokens.md), "Token endpoint errors").

### Limits of the admin consent

Decision (2026-11-13, team default after the review of PR #48, findings SEC-48-2 and QA-48-6). The admin consent of `gateway-bff` limits the audiences that the gateway can get a token for. It is not a complete allowlist. The gateway can also get a token in these cases:

| Case | Example | Control |
|---|---|---|
| A Microsoft first-party API for which `gateway-bff` has consent | Microsoft Graph (`00000003-0000-0000-c000-000000000000`). The login scopes `openid`, `profile`, `email`, and `offline_access` are Graph permissions. | The CONTRACT rule `audience-not-first-party` ([018](018-authorization-extension.md)), in the CLI and in the gateway |
| `gateway-bff` itself | `x-gateway.audience` is the client id of `gateway-bff` | The gateway does not start ([023](023-per-audience-tokens.md), "Client registrations for each audience") |
| User consent | A user consents to a scope of type `User` for `gateway-bff` | Each `access_as_user` scope has the type `Admin`. The tenant user consent setting SHOULD be "Do not allow user consent". |
| Pre-authorization | A backend app lists `gateway-bff` in `preAuthorizedApplications`. Entra then issues tokens without consent. | The grant audit (below) |
| An app of another publisher | A multi-tenant app with a service principal in the tenant, and a consent for `gateway-bff` | The grant audit (below) |

The PR review and `audience-unique` ([018](018-authorization-extension.md)) control which audience an OAS file uses. These Entra controls limit what the gateway can get for an audience.

### Grant audit

The gateway team MUST audit the grants of `gateway-bff` at a regular interval (see "Open questions"). The audit checks:

1. Each delegated permission grant of the `gateway-bff` service principal is for a backend app that has an OAS file, or for the login scopes of Microsoft Graph.
2. Each grant for a backend app has exactly one scope: `access_as_user`.
3. No grant is a user consent (a grant for one user instead of all users).
4. No backend app lists `gateway-bff` in `preAuthorizedApplications`.

The gateway team removes each grant or pre-authorization that fails a check, and informs the owner of the app.

### Backend app registration

| Setting | Value |
|---|---|
| Access token version | `2` |
| Application ID URI | Any value that the tenant policy allows, for example `api://orders-api`. The gateway does not use it: it requests the scope `{client-id}/.default`. Verified on 2026-11-13 ([023](023-per-audience-tokens.md), "Scope form"). |
| Exposed scope | Exactly one: `access_as_user`, with the type `Admin` (only an administrator can consent). Admin consent display name and description are required by Entra. The gateway does not request this scope by name. It requests `.default`. The scope exists so that `gateway-bff` can have a delegated permission and admin consent for the backend. |
| Pre-authorized applications | `gateway-bff` MUST NOT be listed. The admin consent is the record of each backend that the gateway can call. |
| App Roles | One App Role for each permission. `value` is the permission name, for example `Orders.Read`. |
| `allowedMemberTypes` of each App Role | `User`. Add `Application` only if service-to-service traffic uses the role ([026](026-service-to-service-bearer.md)). |
| Enterprise Application: Assignment required | Yes |

"Assignment required = Yes" makes Entra refuse tokens to users without an assignment. Assignment of App Roles to groups requires Entra ID P1 or P2.

Decision (2026-11-13, team default after the review of PR #48, finding SEC-48-5). A token for the `.default` scope has all scopes that `gateway-bff` has consent for on that backend. Therefore:

- A backend MUST authorize requests only on the `roles` claim. It MUST NOT authorize on the `scp` claim.
- `gateway-bff` MUST have exactly one consented scope for each backend: `access_as_user`. The grant audit checks this.

### Example Terraform

Teams that use Terraform with the `azuread` provider can use this shape. Verify the attribute names against the provider version in use.

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

### Verification of a backend token

After the configuration, get a token for the backend through the gateway in a test environment. Decode the token (do not paste production tokens into online tools). Check these claims:

| Claim | Expected value |
|---|---|
| `ver` | `2.0` |
| `iss` | `https://login.microsoftonline.com/<tenant-id>/v2.0` |
| `aud` | The client id of the backend app (GUID), equal to `x-gateway.audience` in the OAS file. |
| `roles` | The App Roles that the test user has for this backend only. |

### Onboarding checklist for a new backend API

The backend team and the gateway team do these steps in this order:

1. Create the backend app registration. Set the access token version to `2`.
2. Set the Application ID URI. Any allowed value works, for example `api://payments-api`. See "Backend app registration".
3. Expose the scope `access_as_user` with the type `Admin`. Do not add `gateway-bff` to the pre-authorized applications.
4. Create one App Role for each permission. Use the naming pattern. Set `allowedMemberTypes` to `User`.
5. In the Enterprise Application, set "Assignment required" to Yes.
6. Assign the App Roles to users or groups.
7. In `gateway-bff`, add the delegated permission `access_as_user` of the new app.
8. Grant admin consent for that permission.
9. Add the OpenAPI Specification (OAS) file with `x-gateway.audience` equal to the client id. Declare `x-gateway-authorization` for each non-public operation. The backend has exactly one OAS file (`audience-unique`, [018](018-authorization-extension.md)).
10. Run `oas-gateway-validate` on the OAS folder. The result MUST have no ERROR. The CLI checks the format of the audience and of the permissions. It also checks that the audience is in one OAS file only and is not a Microsoft first-party app. It does not check that the audience and the permissions exist in Entra.
11. Compare the OAS file with Entra by hand. `x-gateway.audience` equals the client id of the new app. Each permission in `x-gateway-authorization` is an App Role of that app.
12. Open the PR. A reviewer checks the OAS file in the normal PR review: the audience, the permissions, and the `upstream`. The reviewer checks that the `upstream` is the address of the backend of that audience. Reason: the gateway sends the token of the audience to the upstream. The `diff` job lists the new audience binding ([034](034-access-change-review.md), "Audience bindings"). The repository has no `CODEOWNERS` file ([034](034-access-change-review.md)).
13. After deployment, verify one backend token with the table above.
14. Configure the backend to accept traffic only from the gateway (network policy or mutual TLS).

### Operational notes

- Token size: each App Role adds to the size of the access token. The Netty default limit for request headers is 8 KB (`server.max-http-request-header-size`). Keep permissions medium-grained, for example `Orders.Read` and not one permission for each field.
- Revocation latency: when an administrator removes an App Role, the role stays in existing access tokens until they expire. The default lifetime is 60 to 90 minutes. If a use case needs immediate revocation, evaluate Continuous Access Evaluation or shorter token lifetimes.
- Refresh tokens of web apps are valid for up to 90 days, and Entra rotates them.

## Acceptance criteria

- [ ] The runbook lists every setting of `gateway-bff` from the brief: redirect URI, credential type, delegated permissions, and admin consent.
- [ ] The runbook lists every setting of a backend app: token version 2, `access_as_user` scope, App Roles with `allowedMemberTypes`, and "Assignment required".
- [ ] The onboarding checklist contains the CLI validation step, the comparison of the OAS file with Entra, and the review of the `upstream`. It contains no catalog step.
- [x] [023](023-per-audience-tokens.md) records plan A (2026-11-13). The runbook has no rule for the Application ID URI of a backend app.
- [ ] The runbook and the example Terraform give `access_as_user` the type `Admin`.
- [ ] The runbook recommends the tenant user consent setting "Do not allow user consent".
- [ ] The runbook lists the four checks of "Grant audit", and the rule that a backend authorizes only on `roles`.
- [ ] "Limits of the admin consent" lists each case of the table and its control.
- [ ] A reviewer from the identity team confirms that the steps work in the test tenant.
- [ ] For one test backend, a token obtained with these steps has `ver` `2.0` and the expected `iss`.
- [ ] The same token has `aud` equal to the client id of the backend app and contains only roles of that backend.

## Test plan

This spec has no Java code. The verification is manual:

- Configure one test backend app in the test tenant with the checklist.
- Sign in through the gateway in the test environment and call one route of the test backend.
- Decode the access token that the backend receives. Compare the claims with the verification table.
- Remove the admin consent in the test tenant and confirm that the gateway gets no token for that backend ([023](023-per-audience-tokens.md)). Record the exact error body (`error`, `suberror`, `error_codes`) for the test of [023](023-per-audience-tokens.md).
- Sign in as a test user who is not an administrator and request the scope of a backend without admin consent. Confirm that the user cannot consent, because the scope has the type `Admin`. The first implementation PR of [023](023-per-audience-tokens.md) records the result ("Scope form", step 3).

## Delivery

One PR. It adds this spec and can add a short link to this runbook from the root `README.md`. No Java code changes. A separate PR adds the Terraform configuration for the test app registrations in `infra/azure-test/` (see "Decisions").

## Decisions (2026-11-12)

- The gateway authenticates to Microsoft Entra ID with a **client secret**. The secret is never in the repository. The gateway reads it from an environment variable; in production the value comes from a secret store. The team rotates the secret before it expires (at most 24 months). Note: a certificate credential, or workload identity federation, is safer than a client secret. A stolen secret works from any network location until it expires, and a secret is easier to copy. The team can change to a certificate later. A shorter secret lifetime (for example 6 to 12 months) reduces the risk while the secret stays.
- A separate set of test app registrations exists only to test the login flow in Azure: `gateway-bff-test` (the gateway) and `echo-api-test` (a test backend with App Roles). Terraform creates them (`infra/azure-test/`).

## Decisions (2026-11-13)

- The product owner removed the permission catalog ([Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)). The onboarding checklist has no catalog step. The CLI checks only the format of the audience and of the permissions.
- The admin consent of `gateway-bff` for each backend app limits the audiences. The gateway cannot get a token for a backend without that consent. Correction after the review of PR #48 (SEC-48-2): the consent is not a complete allowlist. See "Limits of the admin consent".
- The gateway requests the scope `{client-id}/.default`. The verification in [023](023-per-audience-tokens.md) (2026-11-13) confirmed this form, also for an Application ID URI other than `api://{client-id}`. Plan B (`api://{client-id}/.default`) failed for such a backend (`invalid_resource`, `AADSTS500011`) and is removed. A backend app may use any Application ID URI.
- The app registration `gateway-oas-validator` is destroyed. Its runbook entry is removed.

Defaults chosen by the team after the review of PR #48 (QA-48, SEC-48). The team can change each of them later:

- Scope type (QA-48-6): each `access_as_user` scope has the type `Admin`, so only an administrator can consent. The example Terraform and `infra/azure-test` use `Admin` (the change in `infra/azure-test` is part of the first implementation PR of [023](023-per-audience-tokens.md)). The tenant user consent setting SHOULD be "Do not allow user consent".
- Limits of the admin consent (SEC-48-2): the CONTRACT rule `audience-not-first-party`, the gateway check for the client id of `gateway-bff`, no pre-authorization of `gateway-bff`, and a regular grant audit.
- Roles only (SEC-48-5): backends authorize only on `roles`. `gateway-bff` has exactly one consented scope for each backend, `access_as_user`. The grant audit checks it.
- Plan B (SEC-48-6): superseded. Plan B is removed (2026-11-13, see above), so the onboarding does not check the Application ID URI. The `aud` check of [024](024-authorization-manager.md) stays a MUST.

## Open questions

1. Does the tenant policy allow Application ID URIs like `api://orders-api`, or does it require `api://<client-id>` or a verified domain? The test tenant accepted `api://orders-api-test` (2026-11-13). The gateway works with any value ([023](023-per-audience-tokens.md), "Scope form"), so the answer affects only the backend teams.
2. Which team owns `gateway-bff` and who can grant admin consent?
3. Do backend teams manage their apps with Terraform, or by hand?
4. What is the interval of the grant audit, and does a script or a scheduled job run it? Who owns the audit?

## References

- [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)
- [022 — Session login](022-session-login.md)
- [023 — Per-audience tokens](023-per-audience-tokens.md)
- [026 — Service-to-service bearer](026-service-to-service-bearer.md)
- Microsoft identity platform documentation: app manifest, App Roles, access tokens, and refresh tokens.
