# 026 — Service-to-service bearer

| Field | Value |
|---|---|
| Status | Proposed |
| Phase | 7 — Session authentication and authorization in the gateway |
| Depends on | [024](024-authorization-manager.md) |
| Modules | `gateway` |

## Summary

**Deferred (2026-11-13).** This spec is out of scope for phase 7. The implementation of phase 7 covers browser sessions only ([022](022-session-login.md) to [025](025-token-relay-and-header-hygiene.md)). The status stays "Proposed", because the status list in [README](README.md) has no status for a deferred spec. The decision below is still open.

This spec is a proposal. It is pending a decision: must services call backend APIs through the gateway? If the answer is yes, the gateway adds a second security chain for requests with an `Authorization: Bearer` header. This chain validates the token as an OAuth 2.0 resource server, without a session and without CSRF. It applies the same access rules as browser requests: the `aud` check for each route and the permission check on the `roles` claim. Do not implement this spec before the decision.

## Motivation

The session chain of [022](022-session-login.md) serves browsers only. A service (for example a batch job) has no browser and no session. It can get an access token from Entra with the client credentials grant. Today, such a request gets `401` or a redirect to login. The team must decide if services use the gateway or call backends directly.

## Decision needed

| Option | Description | Consequences |
|---|---|---|
| A. No service traffic through the gateway | Services call backends directly. Backends validate tokens themselves. | No change in the gateway. Backends must accept traffic from services, so the rule "backends accept only gateway traffic" ([025](025-token-relay-and-header-hygiene.md)) needs exceptions. Access rules in OpenAPI Specification (OAS) files do not protect service calls. |
| B. Bearer chain in the gateway (this proposal) | The gateway validates the service token and applies the access rules. | One entry point and one set of access rules for all clients. More code and a second chain to test. Incoming header size matters. |

Owner of the decision: see open question 1. When the team chooses option B, the status changes to Draft. When the team chooses option A, the status changes to a rejected state (see open question 2).

## Scope

### In scope (option B)

- `BearerSecurityConfiguration`: a second `SecurityWebFilterChain`, selected when the request has an `Authorization: Bearer` header.
- Resource server validation of the token with the shared `ReactiveJwtDecoder` of [023](023-per-audience-tokens.md).
- Reuse of the security index and the decision logic of [024](024-authorization-manager.md), with permissions from the incoming token.
- Forwarding of the validated token to the backend.

### Out of scope

- Entra configuration for service clients (App Roles with `allowedMemberTypes` `Application`). See [021](021-entra-app-registrations.md).
- Token exchange or on-behalf-of flows.
- mTLS client authentication.

## Design

### Security invariants (option B)

- The bearer chain MUST be stateless. It MUST NOT create or read a session.
- The bearer chain MUST check the token `aud` against the audience of the access rule for each route.
- The bearer chain MUST read permissions from the `roles` claim of the validated token.
- The bearer chain MUST NOT accept a session cookie as authentication. A request with an `Authorization` header never uses the session.
- A 403 response MUST NOT reveal the required permissions. It uses the generic body of [024](024-authorization-manager.md).
- The gateway MUST NOT use `BearerTokenServerAccessDeniedHandler`.

### Chain selection

```java
@Bean
@Order(1)   // before the session chain of 022
SecurityWebFilterChain bearerSecurityChain(ServerHttpSecurity http,
        ReactiveJwtDecoder decoder, GatewayAuthorizationManager authorization) {
    return http
        .securityMatcher(BearerHeaderMatcher.INSTANCE)       // "Authorization: Bearer ..."
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtDecoder(decoder)))
        .authorizeExchange(ex -> ex.anyExchange().access(authorization))
        .exceptionHandling(e -> e.accessDeniedHandler(gatewayAccessDeniedHandler))
        .build();
}
```

The sketch shows the intent. Verify method names against Spring Security 7 ([001](001-project-skeleton.md)).

| Element | Behavior |
|---|---|
| `BearerHeaderMatcher` | Matches when the `Authorization` header starts with `Bearer ` (case-insensitive scheme). |
| CSRF | Disabled. The browser does not send this header automatically, so CSRF does not apply. |
| Entry point | `401` with `WWW-Authenticate: Bearer`. Never a redirect. |
| Permission source | `roles` claim of the incoming token, through a variant of the resolver of [023](023-per-audience-tokens.md). The permission cache MAY be shared. |

### Decision logic

The bearer chain uses the decision table of [024](024-authorization-manager.md) with these differences:

| Condition | Session chain | Bearer chain |
|---|---|---|
| No access rule for method and path | `404` from `UndocumentedOperationWebFilter`, before the chain | `404` from the same filter, before the chain |
| No authenticated user | `302` or `401` | `401`, `WWW-Authenticate: Bearer` |
| Token invalid (signature, issuer, `exp`) | Not applicable | `401`, `WWW-Authenticate: Bearer error="invalid_token"` |
| Token `aud` differs from rule audience | `500` generic, `ERROR` log (configuration error) | `401` `invalid_token` |
| Missing permission | `403` generic | `403` generic |
| Token redemption errors | `invalid_grant`, consent error ([023](023-per-audience-tokens.md)) | Not applicable |

The `aud` mismatch has different meanings. In the session chain, the gateway obtained the token itself, so a mismatch is a configuration error ([024](024-authorization-manager.md)). In the bearer chain, the client sent the token, so a mismatch is a client error. The bearer chain therefore needs an access denied handler that maps `WRONG_AUDIENCE` to `401` with `WWW-Authenticate: Bearer error="invalid_token"`.

### Token forwarding

The incoming token already has the `aud` of the backend. The route filters of [025](025-token-relay-and-header-hygiene.md) remove the incoming `Authorization` header and add `TokenRelay`. For bearer requests, the gateway forwards the validated incoming token instead. See open question 3.

### Example request

```http
GET /orders-api/orders/42 HTTP/1.1
Host: app.example.com
Authorization: Bearer eyJ...   (aud = orders-api client id, roles = [Orders.Read])
```

The gateway validates the token, finds the rule `all-of: [Orders.Read]`, checks `aud`, and forwards the request with the same token.

## Behavior and errors

| Condition | Result |
|---|---|
| Valid token, correct `aud`, required permissions | Request reaches the backend with the same token. |
| `Authorization` header without the `Bearer` scheme | See open question 5. |
| Request with both `Authorization` and `__Host-SESSION` cookie | Bearer chain only. The cookie is ignored and removed ([025](025-token-relay-and-header-hygiene.md)). |
| Token larger than `server.max-http-request-header-size` | Netty rejects the request before the security chain. |

## Acceptance criteria

These criteria apply only after the decision for option B.

- [ ] A request with a valid bearer token, correct `aud`, and the required permissions reaches the backend.
- [ ] The backend receives the same token value as the client sent.
- [ ] A bearer token with the `aud` of another audience returns `401` with `WWW-Authenticate: Bearer error="invalid_token"`.
- [ ] A bearer request to an undocumented path or method returns `404`.
- [ ] A bearer token without a required permission returns the generic 403 body of [024](024-authorization-manager.md).
- [ ] An expired token, or a token with a wrong signature or issuer, returns `401`.
- [ ] A request without a token to a non-public operation through the bearer chain never returns a redirect.
- [ ] Bearer requests create no session in Redis and receive no `Set-Cookie` header.
- [ ] A state-changing bearer request without a CSRF token is not rejected for CSRF.
- [ ] A request with an `Authorization` header and a valid `__Host-SESSION` cookie is not authenticated by the session.

## Test plan

- `WebTestClient` tests with `mockJwt()` and claims `aud` and `roles`: all rows of the decision logic.
- Integration test with tokens signed by a test key and a mock JWKS endpoint (WireMock): signature, issuer, expiry.
- Test that Redis receives no write for bearer requests (Testcontainers Redis with a key count check).
- Mock backend (WireMock) test that records the forwarded `Authorization` header.

## Delivery

One PR after the decision: `BearerSecurityConfiguration`, `BearerHeaderMatcher`, resolver variant, forwarding change in the route filters, tests. Estimate: 300 lines.

## Open questions

1. Is service-to-service traffic through the gateway needed? Who decides, and by which date?
2. Which status does a rejected proposal get? The status list in [README](README.md) has no "Rejected" status.
3. How does the gateway forward the incoming token? The route filters of [025](025-token-relay-and-header-hygiene.md) remove `Authorization` and use `TokenRelay`. The bearer chain needs a different filter, or a filter that selects the source of the token.
4. Must an OAS operation declare if application tokens (client credentials, no user) are allowed? Today the access rule cannot tell a user token from an application token.
5. What does the gateway do with an `Authorization` header that uses another scheme, for example `Basic`: reject with `401`, or use the session chain?
6. Does the bearer chain need rate limits or other controls that the session chain does not need?

## References

- [021 — Entra app registrations](021-entra-app-registrations.md)
- [022 — Session login](022-session-login.md)
- [023 — Per-audience tokens](023-per-audience-tokens.md)
- [024 — Authorization manager](024-authorization-manager.md)
- [025 — Token relay and header hygiene](025-token-relay-and-header-hygiene.md)
- Spring Security reference: OAuth 2.0 Resource Server (reactive), multiple `SecurityWebFilterChain` beans.
- RFC 6750: Bearer token usage.
- Microsoft identity platform: client credentials flow.
