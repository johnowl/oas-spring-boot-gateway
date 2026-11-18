# Test app registrations in Microsoft Entra ID

This Terraform configuration creates the app registrations to test the login flow in Azure (spec 021). It is not for production.

| App registration | Purpose |
|---|---|
| `echo-api-test` | Test backend API. App Roles `Echo.Read` and `Echo.Admin`, scope `access_as_user` with the type `Admin`, Application ID URI `api://<client-id>`, "Assignment required" is on. |
| `gateway-bff-test` | The gateway: a confidential client with a client secret. Redirect URIs `http://localhost:8080/login/oauth2/code/entra` and `http://localhost:8080/` (the post-logout redirect URI of spec 022; Entra accepts it only as a registered redirect URI). No front-channel logout URL. Admin consent for `openid`, `profile`, `email`, `offline_access`, and `access_as_user` of `echo-api-test`. |

The person who runs Terraform gets the App Role `Echo.Read`, but not `Echo.Admin`. A test can then show a `200` and a `403`.

`backends.tf` adds three test backends for the scope check of spec 023 ("Scope form"). Each has the scope `access_as_user` with the type `Admin`, one App Role for the person who runs Terraform, and "Assignment required" on.

| App registration | Application ID URI | Admin consent of `gateway-bff-test` | Access token version | Purpose |
|---|---|---|---|---|
| `orders-api-test` | `api://orders-api-test` | Yes | 2 | Shows that the scope `{client-id}/.default` works when the Application ID URI is not `api://{client-id}`. |
| `noconsent-api-test` | `api://<client-id>` | No | 2 | Shows the error body of the token endpoint for a backend without admin consent. |
| `v1-api-test` | `api://<client-id>` | Yes | 1 | Shows that Entra issues a version 1 token, which the gateway must reject. |

The output `echo_api_scope` is the scope of the token request of the gateway, `{client-id}/.default`. The output `extra_backends` has the client id and the Application ID URI of each extra backend.

### Status (2026-11-13)

The team ran the scope check of spec 023 on 2026-11-13 and recorded the results in spec 023, "Scope form". After the check, the team destroyed all test apps of this configuration (`terraform destroy`). No test app exists in the tenant now. Run `terraform apply` again for a new test.

## Requirements

- Terraform 1.5 or later, and the Azure CLI.
- `az login` with a **user account** that can create app registrations and grant admin consent in the tenant (for example the role Cloud Application Administrator).
- A service principal cannot run this configuration. Terraform assigns the App Role `Echo.Read` to the account that runs it, and `Echo.Read` allows only users (`allowed_member_types = ["User"]`). With `az login --service-principal`, the role assignment fails.

## Use

```bash
cd infra/azure-test
terraform init
terraform plan
terraform apply

# Values for the gateway. Do not commit them.
terraform output tenant_id
terraform output gateway_client_id
terraform output -raw gateway_client_secret
terraform output echo_api_client_id
terraform output echo_api_scope
terraform output extra_backends
```

## Security

- The Terraform state file contains the client secret. It stays on the machine that runs Terraform. `.gitignore` excludes `*.tfstate` and `*.tfstate.*`. Keep the file readable only by you (`chmod 600 terraform.tfstate`). Do not copy, archive, or sync the folder.
- Saved plan files (`terraform plan -out=...`) and `crash.log` can also contain the secret. `.gitignore` excludes `*.tfplan`, `tfplan`, `crash.log`, `*.tfvars`, and `*.tfvars.json`. Prefer `terraform plan` without `-out`. Delete a saved plan after `apply`.
- The client secret expires after 180 days (`client_secret_lifetime`). Run `terraform apply -replace=azuread_application_password.gateway` to create a new one.
- Run `terraform destroy` when the tests are finished.

### Accepted risks of this test setup

- `gateway-bff-test` does not require user assignment, and the admin consent covers all users (`AllPrincipals`). Any member or guest of the tenant can sign in through the gateway and receives tokens for the Graph scopes `openid profile email offline_access`. Tokens for `echo-api-test` stay limited, because `echo-api-test` requires assignment. This is acceptable for a test tenant only. Do not reuse this configuration for production: there, set "Assignment required" on the gateway and assign only the intended users or groups.
- The redirect URIs use plain `http://localhost:8080`. A local process that binds port 8080 before the gateway can receive the authorization code. It also needs the client secret to redeem the code. PKCE for the confidential client makes a stolen code useless also with the secret; PKCE belongs to spec 022. Never add a redirect URI with `http` and a host other than `localhost`.
