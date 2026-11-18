variable "name_suffix" {
  description = "Suffix of the display names, for example 'test'."
  type        = string
  default     = "test"
}

variable "redirect_uris" {
  description = "Redirect URIs of the gateway (Spring Security OAuth2 login, registration id 'entra')."
  type        = list(string)
  default     = ["http://localhost:8080/login/oauth2/code/entra"]
}

variable "post_logout_redirect_uri" {
  description = "Post-logout redirect URI of the gateway (spec 022). Terraform adds it to the redirect URIs."
  type        = string
  default     = "http://localhost:8080/"
}

variable "client_secret_lifetime" {
  description = "Lifetime of the gateway client secret, as a Terraform duration."
  type        = string
  default     = "4320h" # 180 days
}
