# Test app registrations for the login flow (spec 021). Not for production.
#
# - echo-api-<suffix>: a test backend API with App Roles Echo.Read and Echo.Admin.
# - gateway-bff-<suffix>: the gateway (confidential client, client secret).
#
# The signed-in user gets Echo.Read only, so that a test can show both 200 and 403.

data "azuread_client_config" "current" {}

data "azuread_application_published_app_ids" "well_known" {}

data "azuread_service_principal" "msgraph" {
  client_id = data.azuread_application_published_app_ids.well_known.result["MicrosoftGraph"]
}

locals {
  graph_scopes = ["openid", "profile", "email", "offline_access"]
}

# --- echo-api-test: the backend ---------------------------------------------

resource "random_uuid" "access_as_user" {}
resource "random_uuid" "echo_read" {}
resource "random_uuid" "echo_admin" {}

resource "azuread_application" "echo_api" {
  display_name     = "echo-api-${var.name_suffix}"
  sign_in_audience = "AzureADMyOrg"
  owners           = [data.azuread_client_config.current.object_id]

  api {
    requested_access_token_version = 2

    oauth2_permission_scope {
      id                         = random_uuid.access_as_user.result
      value                      = "access_as_user"
      type                       = "Admin"
      admin_consent_display_name = "Access echo-api as the signed-in user"
      admin_consent_description  = "Allows the gateway to call echo-api for the signed-in user."
      enabled                    = true
    }
  }

  app_role {
    id                   = random_uuid.echo_read.result
    value                = "Echo.Read"
    display_name         = "Read echo"
    description          = "Call the read operations of echo-api."
    allowed_member_types = ["User"]
    enabled              = true
  }

  app_role {
    id                   = random_uuid.echo_admin.result
    value                = "Echo.Admin"
    display_name         = "Administer echo"
    description          = "Call the admin operations of echo-api."
    allowed_member_types = ["User"]
    enabled              = true
  }

  # azuread_application_identifier_uri.echo_api manages identifier_uris. Without this, each apply
  # removes the URI here, and the next apply adds it again.
  lifecycle {
    ignore_changes = [identifier_uris]
  }
}

# api://<client-id> avoids tenant policies that restrict other Application ID URIs.
resource "azuread_application_identifier_uri" "echo_api" {
  application_id = azuread_application.echo_api.id
  identifier_uri = "api://${azuread_application.echo_api.client_id}"
}

resource "azuread_service_principal" "echo_api" {
  client_id                    = azuread_application.echo_api.client_id
  app_role_assignment_required = true
  owners                       = [data.azuread_client_config.current.object_id]
}

resource "azuread_app_role_assignment" "current_user_echo_read" {
  app_role_id         = random_uuid.echo_read.result
  principal_object_id = data.azuread_client_config.current.object_id
  resource_object_id  = azuread_service_principal.echo_api.object_id
}

# --- gateway-bff-test: the gateway ------------------------------------------

resource "azuread_application" "gateway" {
  display_name     = "gateway-bff-${var.name_suffix}"
  sign_in_audience = "AzureADMyOrg"
  owners           = [data.azuread_client_config.current.object_id]

  api {
    requested_access_token_version = 2
  }

  # Entra accepts post_logout_redirect_uri only if it is a registered redirect URI. The gateway does
  # not implement front-channel logout (spec 022), so logout_url is not set.
  web {
    redirect_uris = distinct(concat(var.redirect_uris, [var.post_logout_redirect_uri]))
  }

  required_resource_access {
    resource_app_id = data.azuread_service_principal.msgraph.client_id

    dynamic "resource_access" {
      for_each = local.graph_scopes
      content {
        id   = data.azuread_service_principal.msgraph.oauth2_permission_scope_ids[resource_access.value]
        type = "Scope"
      }
    }
  }

  required_resource_access {
    resource_app_id = azuread_application.echo_api.client_id

    resource_access {
      id   = random_uuid.access_as_user.result
      type = "Scope"
    }
  }
}

resource "azuread_service_principal" "gateway" {
  client_id = azuread_application.gateway.client_id
  owners    = [data.azuread_client_config.current.object_id]
}

resource "azuread_application_password" "gateway" {
  application_id = azuread_application.gateway.id
  display_name   = "gateway-bff-${var.name_suffix} (Terraform)"
  end_date       = timeadd(timestamp(), var.client_secret_lifetime)

  lifecycle {
    ignore_changes = [end_date]
  }
}

# Admin consent, so that the user sees no consent screen and the gateway gets no consent_required.
resource "azuread_service_principal_delegated_permission_grant" "gateway_graph" {
  service_principal_object_id          = azuread_service_principal.gateway.object_id
  resource_service_principal_object_id = data.azuread_service_principal.msgraph.object_id
  claim_values                         = local.graph_scopes
}

resource "azuread_service_principal_delegated_permission_grant" "gateway_echo" {
  service_principal_object_id          = azuread_service_principal.gateway.object_id
  resource_service_principal_object_id = azuread_service_principal.echo_api.object_id
  claim_values                         = ["access_as_user"]
}
