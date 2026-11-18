# Extra test backends for the scope check of spec 023 ("Scope form"):
#
# - orders-api-<suffix>: an Application ID URI that is not api://<client-id>.
# - noconsent-api-<suffix>: no admin consent for the gateway.
# - v1-api-<suffix>: issues version 1 access tokens.
#
# The signed-in user has one App Role on each, so a token, when Entra issues one, has a roles claim.

locals {
  extra_backends = {
    orders = {
      name          = "orders-api-${var.name_suffix}"
      token_version = 2
      custom_uri    = true
      consent       = true
      role          = "Orders.Read"
    }
    noconsent = {
      name          = "noconsent-api-${var.name_suffix}"
      token_version = 2
      custom_uri    = false
      consent       = false
      role          = "NoConsent.Read"
    }
    v1 = {
      name          = "v1-api-${var.name_suffix}"
      token_version = 1
      custom_uri    = false
      consent       = true
      role          = "V1.Read"
    }
  }
}

resource "random_uuid" "extra_scope" {
  for_each = local.extra_backends
}

resource "random_uuid" "extra_role" {
  for_each = local.extra_backends
}

resource "azuread_application" "extra" {
  for_each = local.extra_backends

  display_name     = each.value.name
  sign_in_audience = "AzureADMyOrg"
  owners           = [data.azuread_client_config.current.object_id]

  api {
    requested_access_token_version = each.value.token_version

    oauth2_permission_scope {
      id                         = random_uuid.extra_scope[each.key].result
      value                      = "access_as_user"
      type                       = "Admin"
      admin_consent_display_name = "Access ${each.value.name} as the signed-in user"
      admin_consent_description  = "Allows the gateway to call ${each.value.name} for the signed-in user."
      enabled                    = true
    }
  }

  app_role {
    id                   = random_uuid.extra_role[each.key].result
    value                = each.value.role
    display_name         = each.value.role
    description          = "Test role of ${each.value.name}."
    allowed_member_types = ["User"]
    enabled              = true
  }

  lifecycle {
    ignore_changes = [identifier_uris]
  }
}

resource "azuread_application_identifier_uri" "extra" {
  for_each = local.extra_backends

  application_id = azuread_application.extra[each.key].id
  identifier_uri = each.value.custom_uri ? "api://${each.value.name}" : "api://${azuread_application.extra[each.key].client_id}"
}

resource "azuread_service_principal" "extra" {
  for_each = local.extra_backends

  client_id                    = azuread_application.extra[each.key].client_id
  app_role_assignment_required = true
  owners                       = [data.azuread_client_config.current.object_id]
}

resource "azuread_app_role_assignment" "current_user_extra" {
  for_each = local.extra_backends

  app_role_id         = random_uuid.extra_role[each.key].result
  principal_object_id = data.azuread_client_config.current.object_id
  resource_object_id  = azuread_service_principal.extra[each.key].object_id
}

resource "azuread_service_principal_delegated_permission_grant" "gateway_extra" {
  for_each = { for k, v in local.extra_backends : k => v if v.consent }

  service_principal_object_id          = azuread_service_principal.gateway.object_id
  resource_service_principal_object_id = azuread_service_principal.extra[each.key].object_id
  claim_values                         = ["access_as_user"]
}
