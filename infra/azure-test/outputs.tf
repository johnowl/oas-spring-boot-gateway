output "tenant_id" {
  value = data.azuread_client_config.current.tenant_id
}

output "gateway_client_id" {
  value = azuread_application.gateway.client_id
}

output "gateway_client_secret" {
  description = "Read it with: terraform output -raw gateway_client_secret"
  value       = azuread_application_password.gateway.value
  sensitive   = true
}

output "echo_api_client_id" {
  description = "The audience of the echo-api OAS file (x-gateway.audience)."
  value       = azuread_application.echo_api.client_id
}

output "echo_api_scope" {
  description = "The scope of the token request of the gateway, without offline_access (spec 023, \"Scope form\")."
  value       = "${azuread_application.echo_api.client_id}/.default"
}

output "extra_backends" {
  description = "Client ids and Application ID URIs of the extra test backends (spec 023, \"Scope form\")."
  value = {
    for k, app in azuread_application.extra : k => {
      client_id = app.client_id
      uri       = azuread_application_identifier_uri.extra[k].identifier_uri
    }
  }
}
