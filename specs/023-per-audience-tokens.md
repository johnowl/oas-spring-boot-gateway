# 023 — Per-audience tokens

| Field | Value |
|---|---|
| Status | Draft |
| Phase | 7 — Session authentication and authorization in the gateway |
| Depends on | [021](021-entra-app-registrations.md), [022](022-session-login.md) |
| Modules | `gateway` |

## Summary

This spec gives the gateway one access token for each backend audience. The gateway derives each audience from the OpenAPI Specification (OAS) files only: `x-gateway.audience` is the client id of the backend app. On the first call to an API, the gateway redeems the login refresh token for an access token with the `aud` of that backend. The gateway stores the token in the session until shortly before it expires. The spec also decodes the permissions (`roles` claim) of each access token and caches them. [024](024-authorization-manager.md) uses these permissions for authorization, and [025](025-token-relay-and-header-hygiene.md) relays the token to the backend.

## Motivation

An Entra access token has exactly one audience. Its `roles` claim contains only the App Roles of that audience. The login gives the gateway an ID token (for `gateway-bff`) and an access token for Microsoft Graph. Neither token carries the permissions of `orders-api`. The gateway needs a token for each backend:

- to read the permissions of the user for that backend, and
- to send the backend a token with its own `aud`.

Entra allows a client to redeem a refresh token for an access token of another consented resource. Spring Security does not do this natively. This spec adds the custom code.

## Scope

### In scope

- Generation of one client registration for each audience at startup, from the loaded OAS files.
- The scope form of the token request and its verification with `infra/azure-test`.
- `AudienceRefreshTokenAuthorizedClientProvider`: a custom `ReactiveOAuth2AuthorizedClientProvider`.
- The `ReactiveOAuth2AuthorizedClientManager` bean that uses this provider.
- Token storage in the session and expiry with a clock skew of 60 seconds.
- Serialization of redemptions for each session (single-flight), on one gateway instance and across instances.
- Timeout of the token endpoint call.
- `TokenPermissionsResolver`: decoding of permissions with a shared `ReactiveJwtDecoder` and a Caffeine cache.
- Error handling for `invalid_grant`, missing consent, other required interaction, other token endpoint errors, timeouts, and unavailable token endpoint.
- A grant limit for each session and for the instance, and the memory of failed grants.

### Out of scope

- The `aud` check and the permission check. See [024](024-authorization-manager.md).
- The `Authorization` header to backends. See [025](025-token-relay-and-header-hygiene.md).
- Entra configuration (scopes, consent). See [021](021-entra-app-registrations.md).
- A check that an audience exists in Entra. No such check exists (decision of 2026-11-13). See [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13).

## Design

### Security invariants

- Permissions MUST come from the access token of the route audience. The gateway MUST NOT read permissions from the ID token, because its `roles` belong to `gateway-bff`.
- The gateway MUST validate each access token with the `ReactiveJwtDecoder` (signature, issuer, `ver`, `tid`, `exp`, `nbf`) before it uses its claims.
- The decoder MUST accept only the issuer `https://login.microsoftonline.com/{tenant-id}/v2.0`, as an exact string, where `{tenant-id}` is the tenant of the `entra` registration ([022](022-session-login.md)). It MUST check that `ver` is `2.0` and that `tid` equals the same tenant id.
- The issuer of the `entra` registration MUST use `https` and one of the Entra ID hosts `login.microsoftonline.com`, `login.microsoftonline.us`, or `login.partner.microsoftonline.cn`. `{tenant-id}` MUST be a GUID in lowercase. The JSON Web Key Set (`jwks_uri`) MUST be on the same host, under `/{tenant-id}/discovery/`. Otherwise the gateway does not start. Only a test bean allows another issuer; no property allows it (see "Permission decoding and cache").
- The gateway MUST validate a new access token before it stores it in the session (step 7 of "Custom authorized client provider"). If the validation fails, the gateway MUST discard the token. The request gets `500` (`INVALID_AUDIENCE_TOKEN`, [024](024-authorization-manager.md)), never `401`.
- Access tokens and refresh tokens MUST stay on the server (in the session store of [022](022-session-login.md)). They MUST NOT appear in logs. The log guard of [022](022-session-login.md) ("Log guard") also protects the logs of the libraries: it stops a wiretap, pins the token loggers to `INFO`, and masks tokens in each log line.
- Per-audience registrations MUST NOT start a login. Only the registration `entra` handles `/oauth2/authorization/{id}`.
- For one session, the gateway MUST NOT send two refresh token grants at the same time, also not from two gateway instances. This applies to all audiences of the session, because all audiences share the one `entra` refresh token.
- The gateway MUST NOT replace a stored refresh token with an older refresh token.
- After the gateway invalidates a session, no request MUST write the session back to the session store.
- A token call MUST use the `ClientRegistration` from the `ReactiveClientRegistrationRepository`, never the registration of an authorized client from the session. See "Registration for token calls".

### Client registrations for each audience

At startup, `AudienceClientRegistrations` creates one `ClientRegistration` for each audience that at least one protected operation of the loaded OAS files uses. The audience is the value of `x-gateway.audience`: the client id of the backend app. The gateway needs no other source. A file with only public operations needs no token, so its audience gets no registration (decision of the first implementation PR, 2026-11-13: fewer registrations, same behavior).

| Field | Value |
|---|---|
| Registration id | `entra-<client-id>`, for example `entra-6f1c2e8a-0000-0000-0000-000000000000`. The id is an internal string. The gateway does not send it to Entra. |
| Client id and client authentication | Same as the registration `entra` (`gateway-bff`) |
| Provider endpoints | Same as the registration `entra` |
| Scope | `{client-id}/.default`, for example `6f1c2e8a-0000-0000-0000-000000000000/.default`. The token request adds `offline_access`. See "Scope form". |
| Grant type | `refresh_token`. The `ClientRegistration` builder of Spring Security 7.1 accepts it (verified in the first implementation PR, 2026-11-13). |

The registration id needs only `x-gateway.audience`. The only verification is that Spring Security accepts the id (see "Acceptance criteria").

The registrations are not in YAML. The bean `ReactiveClientRegistrationRepository` combines `entra` (from properties) and the generated registrations. The prefix `entra-` is reserved: the gateway does not start if a registration from properties has an id with this prefix. A static helper `AudienceRegistrationIds.forAudience(String clientId)` returns the registration id. [025](025-token-relay-and-header-hygiene.md) uses the same helper.

The gateway does not check at startup that the audience exists in Entra. The contract rules check three things: the GUID format of `x-gateway.audience` ([006](006-x-gateway-extension.md)), one OAS file for each audience (`audience-unique`), and no Microsoft first-party app (`audience-not-first-party`, [018](018-authorization-extension.md)). An unknown audience fails at the first token request (see "Admin consent limits the audiences").

Decision (2026-11-13, team default after the review of PR #48, finding SEC-48-2): `AudienceClientRegistrations` MUST stop the startup if an audience equals the client id of the registration `entra` (`gateway-bff`). Reason: Entra can issue a token for `gateway-bff` itself, and the gateway would relay it to the `upstream`. The message is exactly:

```text
x-gateway.audience '<client-id>' is the client id of gateway-bff (registration 'entra'). The gateway does not request tokens for itself.
```

The comparison ignores case, because the property value of the client id can be uppercase. A reload ([014](014-route-reload.md)) applies the same check. This check is not a CONTRACT rule, because the CLI does not know the client id of `gateway-bff`.

Note (2026-11-13, review of PR #54, finding SEC-54-1, future work): the list of `audience-not-first-party` ([018](018-authorization-extension.md)) cannot contain every Microsoft app. The startup check of this spec therefore also rejects an audience whose service principal belongs to the Microsoft tenant `f8cdef31-a31e-4b4a-93e4-5f571e91255a`. The design of this check (how the gateway reads the owner tenant of the service principal, and the exact message) is open. No code exists yet.

#### Fallback for the grant type

Result of the verification (2026-11-13): the `ClientRegistration` builder of Spring Security 7.1 accepts the grant type `refresh_token`. The gateway does not use the fallback. A unit test of `AudienceClientRegistrations` keeps this result. The gateway still keeps the set of the per-audience registration ids, and the provider and the wrapper of "Provider chain" use this set, not the grant type.

If a later version of the builder does not accept `refresh_token`, each per-audience registration uses the grant type `authorization_code`. A gateway-side marker identifies the per-audience registrations: `AudienceClientRegistrations` keeps the set of their registration ids. `AudienceRefreshTokenAuthorizedClientProvider` handles only the registrations in this set. The authorization request resolver of [022](022-session-login.md) MUST reject each registration id other than `entra`, so that a per-audience registration never starts a login. The first implementation PR records the result of the verification in this spec.

### Scope form

The gateway requests the scope `{client-id}/.default offline_access` for the audience, for example `6f1c2e8a-0000-0000-0000-000000000000/.default offline_access`. `.default` asks Entra for a token with the permissions that `gateway-bff` has for that backend app through admin consent ([021](021-entra-app-registrations.md)). The `roles` claim contains the App Roles of the user for that backend. The scope uses the client id, never the Application ID URI, so a backend app may use any Application ID URI.

#### Verification with `infra/azure-test` (2026-11-13)

The team verified the scope form with a real browser login against the test tenant on 2026-11-13. The test used `gateway-bff-test` and these test backends of [`infra/azure-test`](../infra/azure-test/README.md):

| Test backend | Application ID URI | Admin consent of `gateway-bff-test` | Access token version | Purpose |
|---|---|---|---|---|
| `echo-api-test` | `api://{client-id}` | Yes | 2 | Scope with the client id, standard Application ID URI |
| `orders-api-test` | `api://orders-api-test` | Yes | 2 | The scope uses the client id, not the Application ID URI |
| `noconsent-api-test` | `api://{client-id}` | No | 2 | Missing consent |
| `v1-api-test` | `api://{client-id}` | Yes | 1 | Token validation |

All test apps were destroyed after the check (2026-11-13).

These are the results. Each result is a verified fact:

1. Plan A works. The refresh token grant with the scope `{client-id}/.default offline_access` returned a version 2 access token: `ver` `2.0`, `iss` `https://login.microsoftonline.com/{tenant-id}/v2.0`, `aud` the client id (GUID) of the backend, `roles` the App Roles of the user, and `scp` `access_as_user`. This is also true for `orders-api-test`, whose Application ID URI is `api://orders-api-test`: the `aud` of its token was its client id (a GUID), not the Application ID URI. Source of this GUID (QA-63-3): the Terraform output of `infra/azure-test` (`extra_backends.orders.client_id`), and the `aud` of the decoded token that the check printed on 2026-11-13. The maintainer saved this output.
2. Plan B fails. The scope `api://{client-id}/.default` for `orders-api-test` gave `400` with `error=invalid_resource` and `error_codes=[500011]`, because the Application ID URI of that app is not `api://{client-id}`. The team removed plan B. A backend app may use any Application ID URI.
3. Missing consent. For `noconsent-api-test`, Entra returned `400` with this error body (the fields that the gateway reads). The gateway maps exactly this body to `CONSENT_REQUIRED` (`403`):

   ```json
   {
     "error": "invalid_grant",
     "error_description": "AADSTS65001: The user or administrator has not consented to use the application ...",
     "error_codes": [65001],
     "suberror": "consent_required"
   }
   ```

   The check did not record whether a test user who is not an administrator can consent to the scope of `noconsent-api-test` (the second part of step 3 of the plan). This result is still open. It needs a manual login in a test tenant (see "Acceptance criteria").
4. Microsoft Graph (`00000003-0000-0000-c000-000000000000/.default`): Entra issues a token (version 1, no `roles` claim). The CONTRACT rule `audience-not-first-party` ([018](018-authorization-extension.md)) stops the startup for this audience.
5. The client id of `gateway-bff-test` as the audience: Entra issues a token (version 2, no `roles` claim). The startup check of "Client registrations for each audience" is therefore required.
6. `v1-api-test`: Entra returned `200` with a version 1 token: `ver` `1.0`, `iss` `https://sts.windows.net/{tenant-id}/`, `aud` the client id. The decoder of "Permission decoding and cache" rejects it (issuer pin and `ver`). The gateway does not store it, and the request gets `500` (`INVALID_AUDIENCE_TOKEN`).

### Admin consent limits the audiences

The Entra admin consent of `gateway-bff` for each backend app ([021](021-entra-app-registrations.md), onboarding) limits the audiences. The gateway cannot get a token for a backend without that consent. Step 3 of "Scope form" verifies this statement. The consent is not a complete allowlist: Microsoft first-party apps, `gateway-bff` itself, user consent, pre-authorization, and apps of other publishers are exceptions. [021](021-entra-app-registrations.md) ("Limits of the admin consent") lists them and their controls.

An OAS file with an unknown audience, for example a typo in the client id, passes the PR check. At runtime the token request fails closed. Each error body gives exactly one result:

| Entra error body | Result | Deny reason |
|---|---|---|
| A consent error (row 1 of "Token endpoint errors"), for example the body of step 3 of "Scope form" | `403` with the consent problem body | `CONSENT_REQUIRED` |
| Another `4xx` error, for example `invalid_resource` for a client id that does not exist in the tenant (row 4) | `500` with the generic problem body | `TOKEN_REQUEST_REJECTED` |

The gateway never sends a request to the backend in these cases.

### Custom authorized client provider

`AudienceRefreshTokenAuthorizedClientProvider` implements `ReactiveOAuth2AuthorizedClientProvider`. Its behavior:

1. If the registration is not a per-audience registration, return empty. Other providers handle it.
2. If the context has an authorized client for this registration, its principal name is the user of the request, and its access token expires more than 60 seconds from now, return empty. The existing token stays in use. A client of another user is treated as missing. If a grant of this session for this registration failed in the last 30 seconds, fail with the same error, without a grant (see "Failed grants").
3. Enter the single-flight redemption of the session (see "Single-flight redemption"). The next steps run inside it. Steps 4 to 8 have a timeout of 8 seconds together. After the timeout, the request fails with `TOKEN_ENDPOINT_TIMEOUT` (`504`).
4. Read the authorized clients of the session again from Redis. If another request stored a valid token for this registration and for the user of the request in the meantime, use it, and stop here. A client of another user is treated as missing.
5. Read the authorized client of the registration `entra` directly from `SessionAuthorizedClientRepository` (from Redis), not through the authorized client manager. If it has no refresh token, fail with `invalid_grant`.
6. If the grants of the instance are paused (see "Throttling") or the grant limit of the instance is reached (see "Grant limit for the instance"), fail with `TOKEN_ENDPOINT_THROTTLED` (`503`). Then count the grant in the grant limit of the session. Send a refresh token grant to the Entra token endpoint: `grant_type=refresh_token`, the `entra` refresh token, and `scope={client-id}/.default offline_access`. The scope `offline_access` asks Entra for a new refresh token. The building blocks are `OAuth2RefreshTokenGrantRequest` and `WebClientReactiveRefreshTokenTokenResponseClient`; verify their constructors in Spring Security 7.
7. Validate the new access token with the decoder of "Permission decoding and cache". The token MUST also have exactly one `aud`, the client id of the audience of the registration, and its `azp` MUST be the client id of `entra` (`gateway-bff`). If the validation fails, discard the access token, do not store it, and fail with `INVALID_AUDIENCE_TOKEN` ([024](024-authorization-manager.md)) after step 8. Otherwise, write the new `OAuth2AuthorizedClient` of the per-audience registration to the session in Redis, without a refresh token. The expiry of the stored access token is the earlier time of the token `exp` and the `expires_in` of the token response.
8. If the response has a new refresh token (Entra rotates refresh tokens), write it to the `entra` authorized client with a compare-and-set (see "Compare-and-set of the refresh token"). This step also runs when step 7 discards the access token, because Entra can already have rotated the refresh token.
9. Leave the single-flight redemption. Return the new authorized client.

Steps 7 and 8 write to the session store before step 9 releases the lock. A request that waits for the lock therefore reads the newest values in step 4.

Login of another user in the same session: a session can have a login after an earlier login, for example of another user. At login success, [022](022-session-login.md) removes all per-audience authorized clients (`entra-*`) of the session, in the session of the request and in the session store. The login gives the session a new id, so the gateway saves the session with the new id first, and then removes the stored clients under the new id. The new `entra` client of the login stays. Steps 2 and 4 also check the principal name, so a per-audience client of another user is never used.

#### Registration for token calls

Before each call to the token endpoint (step 6), the provider rebuilds the authorized client of `entra` with the `ClientRegistration` from the `ReactiveClientRegistrationRepository`. It keeps only the tokens of the stored authorized client. It never sends a grant with the registration that it read from the session. Reasons:

- The registration in the session has no client secret ([022](022-session-login.md), "Session serialization").
- With the store `redis`, a person or process with write access to Redis can change the registration in the session, for example its `tokenUri`. The gateway would then send the refresh token and the client secret to another host.

The same rule applies to the per-audience registration: the provider takes it from the repository, not from the session.

The call to the token endpoint has a timeout of 5 seconds (`gateway.security.token-endpoint.timeout`).

### Provider chain

The `ReactiveOAuth2AuthorizedClientManager` uses this provider chain, in this order:

| Order | Provider | Registrations |
|---|---|---|
| 1 | `AudienceRefreshTokenAuthorizedClientProvider` | Per-audience registrations only. |
| 2 | The authorization code provider of Spring Security, in a wrapper | `entra` only. For each other registration, the wrapper returns empty. |

Rules:

- The chain has no refresh token provider for `entra`. The `entra` refresh token is used only in step 6, inside the single-flight redemption and the Redis lock of the session. A refresh token provider of Spring Security would redeem it outside the lock. The gateway ignores the Graph access token of `entra` ([022](022-session-login.md)), so it never needs to refresh that token.
- The providers of Spring Security do not filter by registration. Without the wrapper, the authorization code provider would act on a per-audience registration that uses the fallback grant type `authorization_code` (see "Fallback for the grant type"). The wrapper and the order prevent this.
- A per-audience authorized client has no refresh token. The only refresh token of a session is in the `entra` authorized client. No provider can therefore redeem a refresh token of a per-audience client outside the lock.

### Compare-and-set of the refresh token

In step 8, the gateway writes the new refresh token only if the stored `entra` authorized client is still the value that step 5 read. A Redis script compares the stored session attribute with the serialized value of step 5 and writes the new value only if both are equal. The comparison and the write are one atomic operation in Redis. If the values differ, another redemption stored a newer refresh token, and the gateway keeps it.

The compare-and-set does not depend on the lock. If the lock key expires or is evicted during a redemption ([022](022-session-login.md), "Redis protection"), a second instance can send a second grant. The second grant can fail or succeed, but the stored refresh token is never replaced with an older one. A lost lock therefore allows only a duplicate grant, not a lost update.

### Storage of authorized clients

`WebSessionServerOAuth2AuthorizedClientRepository` of Spring Security keeps all authorized clients of a session in one session attribute (a map). Two parallel requests of one session each load their own copy of the session. If both requests change the map, the request that saves last removes the change of the other request (lost update).

The gateway therefore uses its own `ServerOAuth2AuthorizedClientRepository`, `SessionAuthorizedClientRepository`:

- It stores each authorized client in its own session attribute, for example `gateway.oauth2.client.entra` and `gateway.oauth2.client.entra-6f1c2e8a-0000-0000-0000-000000000000`.
- Spring Session with Redis writes only the changed attributes of a session. A request that does not change an attribute does not overwrite it. The implementer verifies this delta save in the Spring Session version of Spring Boot 4.1.
- Steps 4, 5, 7, and 8 of the provider read and write the attributes directly in Redis, not only in the copy of the request.
- The repository MUST NOT write to a session that no longer exists in Redis. Example: request A invalidates the session after `invalid_grant`. Request B of the same session finishes later. The end-of-request save of request B MUST NOT create the session key again. The implementer verifies the behavior of Spring Session. If it is needed, the implementer adds a check, for example a Redis script that writes only when the key exists.
- The session values use JSON serialization with an allowlist of types ([022](022-session-login.md), "Session serialization").

[022](022-session-login.md) uses this repository for `oauth2Login`.

### First API call with a per-audience token

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant G as Gateway
    participant R as Redis
    participant E as Entra ID
    participant O as orders-api
    B->>G: GET /orders-api/orders/42, Cookie: __Host-SESSION
    G->>R: Load session
    G->>G: Find access rule (audience = orders-api client id)
    G->>G: Authorized client for entra-<client-id>? None
    G->>R: Acquire session lock (SET NX PX 10000), read authorized clients again
    G->>E: POST token: grant_type=refresh_token, scope=<client-id>/.default offline_access
    E-->>G: Access token (aud = orders-api), new refresh token
    G->>R: Save access token; compare-and-set rotated refresh token; release lock
    G->>G: Decode and validate token; cache permissions until exp
    G->>G: Check aud and permissions (024)
    G->>O: GET /v1/orders/42, Authorization: Bearer <token> (025)
    O-->>G: 200
    G-->>B: 200
```

On the next call, steps 4 to 8 do not happen. The gateway reads the token from the session and the permissions from the cache.

### Session store

[022](022-session-login.md) has two session stores (`gateway.session.store`, "Session store"). The default is `memory`. Redis is optional. The token state of a session uses the store of the session:

| Item | Store `memory` (default) | Store `redis` |
|---|---|---|
| Authorized clients | Session attributes in the memory of the instance. | Session attributes in Redis. |
| Lock of a session | Not needed. A session exists on one instance only (sticky sessions, [022](022-session-login.md)), so the single-flight redemption of that instance protects it. | The Redis lock of "Single-flight redemption". |
| Compare-and-set of the refresh token | An atomic update of the session attribute in the memory store. | The Redis script of "Compare-and-set of the refresh token". |
| Grant counter | A counter for each session in the memory of the instance, with the same window of 60 seconds. The gateway removes it with the session. | The Redis key of "Grant limit for each session". |
| Redis scripts and Redis ACL | None. The gateway opens no Redis connection. | As described in this spec and in [022](022-session-login.md). |

The texts of this spec about Redis apply only to the store `redis`. With the store `memory`, "read from Redis" means "read from the session store".

### Single-flight redemption

All audiences of a session share one `entra` refresh token. Two parallel redemptions of one session, also for two different audiences, would send the same refresh token twice. The gateway therefore serializes all redemptions of a session. The key is the session, not the session and the audience.

On one gateway instance:

1. The gateway keeps one in-flight redemption for each session id.
2. A request for the same audience as the in-flight redemption does not call Entra. It waits for the result and uses it, also an error.
3. A request for another audience waits until the in-flight redemption completes. Then it starts its own redemption. Step 4 of the provider can then find a valid token and skip the call.
4. A redemption runs to completion also when the request that started it is cancelled, for example when the client disconnects. The token endpoint timeout limits the grant to 5 seconds, and the timeout of step 3 limits the work under the lock to 8 seconds, also when the JSON Web Key Set does not answer. The redemption stores its result in Redis. Reason: a cancelled grant can rotate the refresh token at Entra, and the gateway would lose the new token. Repeated cancellations would also cause repeated grants.
5. The gateway removes the entry of the session when the redemption ends: after success, after an error, and after the timeout. It removes the entry before the waiting requests get the result, so a request for another audience that starts its own redemption never finds the old entry. The cancellation of a waiting request does not remove the entry.

Across gateway instances, with the store `redis`, a Redis lock protects the session:

| Item | Value |
|---|---|
| Key | `spring:session:token-locks:<SHA-256 of the session id, hex>`. The key does not contain the session id, so a key name cannot be used as a session cookie. The key is in the Spring Session namespace, so the Redis ACL of [022](022-session-login.md) covers it. |
| Value | A random value for each acquisition, for example a UUID. |
| Acquire | `SET <key> <value> NX PX 10000`. The lock expires after 10 seconds, also when the instance stops. 10 seconds is longer than the token endpoint timeout of 5 seconds. |
| Release | Delete the key only if it still has the value of this acquisition. A script compares (`GET`) and deletes (`DEL`) in one atomic step. The gateway does not use `DELEX` or `DELIFEQ`: one code path works on every supported server, and the ACL user needs no extra command (decision of the first implementation PR, 2026-11-13). The release runs when the work ends: after success, an error, or a cancellation. |
| Lock taken | Retry the acquisition every 100 milliseconds, without blocking a thread. After 10 seconds without the lock, fail with `TOKEN_ENDPOINT_TIMEOUT` (`504`). The gateway then also runs the release, because a `SET` that was on its way can still take the lock. |
| Redis unavailable | The request fails with a server error, as in [022](022-session-login.md). |

The waiting MUST NOT block a thread. The implementer uses reactive constructs, for example a shared cached `Mono` for each session and `Mono.delay` for the retry.

Scripts: the gateway loads each Redis script once at startup (`SCRIPT LOAD`) and calls it with `EVALSHA`. If Redis answers `NOSCRIPT`, the gateway loads the script again. [022](022-session-login.md) ("Redis protection") lists the minimum Redis versions, because the script engine of older versions has known vulnerabilities.

The lock key can disappear before its 10 seconds end: Redis evicts it first under the eviction policy `volatile-ttl` ([022](022-session-login.md)). This is safe. The compare-and-set on the stored refresh token still prevents a lost update, and a lost lock only allows a duplicate grant (see "Compare-and-set of the refresh token").

The lock values (lifetime, retry interval, maximum wait) are fixed in production. The lock component receives them and a Reactor `Scheduler` through a package-private constructor. It needs no `Clock`: Redis measures the lifetime, and the scheduler measures the retry interval and the maximum wait (correction after the review of PR #57, QA-57-8). Tests use values of 100 milliseconds to 1 second, or virtual time.

### Grant limit for each session

A session can cause at most 10 refresh token grants in 60 seconds. The next grant in the same window is not sent. The request gets `429 Too Many Requests` with the generic problem body (deny reason `TOKEN_GRANT_LIMIT`, [024](024-authorization-manager.md)). The session stays valid.

- With the store `redis`, the counter is the Redis key `spring:session:token-grants:<SHA-256 of the session id, hex>`. A script reads it first: if the window already has 10 grants, the script does not change it. Otherwise the script increments it and sets a lifetime of 60 seconds on the first increment. With the store `memory`, the counter is in the memory of the instance (see "Session store"). With both stores, a request that the limit stops does not count.
- The limit protects Entra and the gateway from a client that makes the gateway redeem in a loop. Normal use needs one grant for each audience about once an hour.
- The counter metric `gateway.token.grants` counts each sent grant, with the tag `result` (`success`, `consent`, `reauthentication`, `invalid_grant`, `rejected`, `timeout`, `error`, `invalid_token`, `throttled`). `success` counts a grant only after the gateway stored the token. Each sent grant counts once. A grant that the redemption timeout ends counts as `timeout`. A grant whose token the gateway cannot store, because the session ended during the grant, counts as `invalid_grant`. The tag `limited` counts each request that the limit of the session, the limit of the instance, or the pause of "Throttling" stops. It counts each such request once, also a request that waited for the redemption of another request.

### Grant limit for the instance

One gateway instance sends at most 50 refresh token grants in one second, for all sessions (`gateway.security.token-endpoint.grants-per-second`). The limit is a token bucket in the memory of the instance: it holds one second of grants and refills continuously. When the bucket is empty, the grant is not sent. The request gets `503` with the generic problem body (`TOKEN_ENDPOINT_THROTTLED`). The session stays valid. The check comes before the grant limit of the session, so a stopped grant does not count for the session.

### Failed grants

When a sent grant fails and the session stays valid, the gateway remembers the failure for 30 seconds, for the session and the registration. During this time, a request of the session for the registration gets the same error and the same log fields, without a grant. This applies to `CONSENT_REQUIRED`, `TOKEN_REQUEST_REJECTED`, `TOKEN_ENDPOINT_TIMEOUT`, `TOKEN_ENDPOINT_ERROR`, and `INVALID_AUDIENCE_TOKEN`. It does not apply to `TOKEN_ENDPOINT_THROTTLED`: the pause of "Throttling" ends it. The memory is in the instance, with at most 10,000 entries. An evicted entry only allows one more grant.

Accepted risks (decision after the review of PR #57, SEC-57-3). The team can change them later:

- There is no limit for each user. A user with many sessions can cause 10 grants in 60 seconds in each session. The grant limit of the instance limits the total.
- With the store `redis` and the eviction policy `volatile-ttl` ([022](022-session-login.md)), Redis can evict a grant counter key before its 60 seconds end, because the key has a short lifetime. The session can then cause more grants in that window.

### Permission decoding and cache

`TokenPermissionsResolver` returns `Mono<TokenPermissions>`:

```java
public record TokenPermissions(String audience, Set<String> permissions, Instant expiresAt) {}

public Mono<TokenPermissions> resolve(Authentication user, String audience, ServerWebExchange exchange)
```

- `resolve` asks the authorized client manager for the client of the registration `entra-<audience>` and the user. The manager uses the provider of "Custom authorized client provider". The resolver reads only the access token of this client. It never reads the ID token, and it never reads a token from the request.
- `audience` of `TokenPermissions` is the `aud` of the token, not the argument. [024](024-authorization-manager.md) compares it with `x-gateway.audience` (`WRONG_AUDIENCE`). A token without exactly one `aud` fails the check `aud`.
- The `azp` of the token must be the client id of the registration `entra` (`gateway-bff`). Otherwise the token fails the check `azp`.
- The `oid` of the token must be the principal name of the user (`Authentication.getName()`). The login of [022](022-session-login.md) uses the `oid` claim of the ID token as the principal name (`user-name-attribute: oid`), and Entra writes the same `oid` into the access tokens of the user. A token of another user fails the check `oid`. This check runs for each request, after the cache.
- `expiresAt` is the `exp` of the token.
- `resolve` never completes empty. If the manager gives no client, the result is an `IllegalStateException`: a defect of the gateway.
- An error of the manager passes unchanged. If the stored token fails the validation, the result is `INVALID_AUDIENCE_TOKEN` with the name of the failed check. If the JSON Web Key Set does not load, the result is `TOKEN_ENDPOINT_TIMEOUT` or `TOKEN_ENDPOINT_ERROR` (rows 5 and 6 of "Token endpoint errors"). In these cases the resolver puts the deny reason and the log fields into the exchange attributes, as the provider does. The log fields have the status `0`.
- A decoding has a timeout: `gateway.security.token-endpoint.timeout`. A decoding that does not end in this time is a key set timeout (`TOKEN_ENDPOINT_TIMEOUT`), and its cache entry is removed.
- A stored token that fails the validation (`INVALID_AUDIENCE_TOKEN`) is removed from the session. The resolver takes the lock of the session ("Session store"), reads the stored client again, and removes it only if it still has the same token (compare-and-delete). The copy of the session of the request also loses the token, so the save at the end of the request does not store it again. A failed removal does not change the result; the next request with the token removes it. The resolver never removes a token when the JSON Web Key Set does not load: the token itself was not checked.

- One `ReactiveJwtDecoder` for all audiences, created from the issuer `https://login.microsoftonline.com/<tenant-id>/v2.0`. All apps in the tenant share the signing keys. Nimbus caches the JSON Web Key Set (JWKS).
- The accepted issuer is exactly the issuer of the registration `entra` (`spring.security.oauth2.client.provider.entra.issuer-uri`), and `{tenant-id}` is its path segment before `/v2.0`. The gateway does not start if this issuer does not have the form `https://<host>/<tenant-id>/v2.0`, with an Entra ID host of "Security invariants" and a tenant id in lowercase GUID form. The keys come from the `jwks_uri` of the same issuer. The gateway does not start if the `jwks_uri` does not start with `https://<host>/<tenant-id>/discovery/`. `AudienceTokenDecoder` wraps the decoder and gives the name of the failed check (`signature`, `iss`, `ver`, `tid`, `exp`, `nbf`, `aud`, or `azp`) for the log line. The resolver adds the checks `roles` and `oid`.
- The tests use a mock identity provider on `http://127.0.0.1:<port>/tenant/v2.0`. Only a bean of the package-private type `LocalIdentityProvider` lets the decoder accept such an issuer: any host, `http` or `https`, any tenant segment, and the `jwks_uri` on the same host under `/<tenant>/discovery/`. The tests register this bean from `src/test`. No production code registers it, and no property turns it on. The bean must be a class of the package `com.johnowl.gateway.security` and not a proxy, otherwise the gateway does not start.
- The decoder validates signature, issuer (exact string), `ver` (`2.0`), `tid` (the tenant id of the issuer), `exp`, and `nbf`. The `exp` claim is required: a token without `exp` fails the check `exp`. The decoder does not compare `aud` with the route; [024](024-authorization-manager.md) checks `aud` for each route. Step 7 of the provider checks `aud` and `azp` of a new token. The resolver checks exactly one `aud` and `azp` of a stored token.
- The decoder fetches the JSON Web Key Set with its own `WebClient` (see "Calls to Entra ID"). The connect timeout and the response timeout are the token endpoint timeout (`gateway.security.token-endpoint.timeout`, 5 seconds by default). The gateway loads the key set once at startup. If the key set does not load at startup, the gateway logs one `WARN` line and starts; the decoder then loads the key set at the first validation. A key set that does not load during a validation is not a failed check: a timeout gives `TOKEN_ENDPOINT_TIMEOUT` (row 5 of "Token endpoint errors"), and another error gives `TOKEN_ENDPOINT_ERROR` (row 6).
- A version 1 token has the issuer `https://sts.windows.net/{tenant-id}/` and `ver` `1.0`, so the decoder rejects it. A token of another tenant has another issuer and another `tid`, so the decoder rejects it.
- `permissions` is the `roles` claim. A missing claim gives an empty set. Entra writes the claim as a JSON array of strings. A malformed claim fails the check `roles`: a claim that is not an array of strings (for example one string, or an array with a number), an array with more than 200 values, or a value with more than 120 characters. The resolver never splits a string into permissions.
- Cache: Caffeine `AsyncCache`. The key is the SHA-256 hash of the token, not the token. Each entry expires at the token `exp`, but at most 1 hour after it was added (Caffeine `Expiry`). The cache has a maximum size of 10,000 entries (`gateway.security.permission-cache.maximum-size`, from `1` to `1,000,000`). Parallel requests with the same token share one decoding. The decoding starts after Caffeine created the entry, not inside the lock of the cache map. A failed decoding is not in the cache: the gateway removes the entry before the requests get the error. The cache uses a direct executor: the calling thread does the short maintenance and never waits.
- Invalid tokens: a token that fails the validation (`INVALID_AUDIENCE_TOKEN`) is remembered for 30 seconds, under the SHA-256 hash of the token, with the same maximum size. A request with this token gets the same error without a second decoding. A request that waited for the failed decoding also gets this error. A key set that does not load is not remembered: a request that waited for it decodes again, without the cache, and gets its own error.

Benefit: when an administrator changes App Role assignments, the change applies at the next token redemption. The user does not need to sign in again.

#### Calls to Entra ID

The gateway calls Entra ID with its own `WebClient`, not with the `WebClient.Builder` of Spring Boot. No customizer of the application changes it:

- Its own Reactor Netty `HttpClient`, with a connect timeout and a response timeout.
- No redirects. A `3xx` status is a response; the gateway never follows it to another host.
- No logging of request details. The form of a token request contains the refresh token, and the headers contain the client secret.
- Only the observation registry of the application.

### Token endpoint errors

An Entra error response has the fields `error`, `error_description`, `error_codes` (a list of numbers), and sometimes `suberror`. Example of missing consent:

```json
{
  "error": "invalid_grant",
  "error_description": "AADSTS65001: The user or administrator has not consented to use the application ...",
  "error_codes": [65001],
  "suberror": "consent_required"
}
```

The `OAuth2Error` of Spring Security keeps only `error`, `error_description`, and `error_uri`. The implementer therefore reads the error body with a custom error handler of the token response client, so that `suberror` and `error_codes` are available. The gateway never logs `error_description`, because it can contain user data.

Throttling comes first (see "Throttling"): a `429` status, or `error=temporarily_unavailable` with a `4xx` or `5xx` status, gives `503` with the generic problem body and the deny reason `TOKEN_ENDPOINT_THROTTLED`. The session stays valid. Then the gateway checks the rows in this order. The first condition that applies gives the result:

| # | Condition | Result | Deny reason ([024](024-authorization-manager.md)) |
|---|---|---|---|
| 1 | The response is a consent error: `error=invalid_grant` with `suberror=consent_required`, or `error_codes` contains `65001` (`AADSTS65001`) | `403` with the consent problem body. The session stays valid. | `CONSENT_REQUIRED` |
| 2 | Any other `error=interaction_required`, for example multifactor authentication or a Conditional Access policy | Remove all authorized clients and invalidate the session. [024](024-authorization-manager.md) calls the entry point: `302` to login for HTML, `401` otherwise. A new login lets Entra ask for the interaction. | `REAUTHENTICATION_REQUIRED` |
| 3 | Any other `error=invalid_grant` (refresh token expired, revoked, or user disabled) | Remove all authorized clients and invalidate the session. [024](024-authorization-manager.md) calls the entry point, as in row 2. | `SESSION_INVALID` |
| 4 | Any other `4xx` status, for example `invalid_scope`, `invalid_client`, or `unauthorized_client` | `500` with the generic problem body. The session stays valid. | `TOKEN_REQUEST_REJECTED` |
| 5 | No response within the timeout, no lock within 10 seconds, no end of steps 4 to 8 within 8 seconds, or a JSON Web Key Set that does not answer within the timeout | `504` with the generic problem body. The session stays valid. | `TOKEN_ENDPOINT_TIMEOUT` |
| 6 | Connection error, a `5xx` status, or another error of the JSON Web Key Set | `502` with the generic problem body. The session stays valid. | `TOKEN_ENDPOINT_ERROR` |

Rows 1 to 4 apply only to a `4xx` status. For a `5xx` status, the gateway reads the body only to find `temporarily_unavailable`; otherwise a `5xx` status gives row 6. Responses that no row names give row 6 (`502`, `TOKEN_ENDPOINT_ERROR`): a status other than `2xx`, `4xx`, or `5xx`, for example a `302` redirect, and a `2xx` response without a readable token response. The gateway never follows a redirect. A `4xx` body that is not JSON gives row 4. Row 1 checks all numbers of `error_codes`; the log fields keep at most 5 of them.

Row 1 comes before rows 2 and 3, because Entra reports missing consent with `error=invalid_grant`, and sometimes with `error=interaction_required`. Without this order, missing consent would end the session. The team MUST verify the exact consent error body with [`infra/azure-test`](../infra/azure-test/README.md) during the implementation. The test of row 1 uses that exact body.

Who writes the log line: the provider does not log. It stores the deny reason and the log fields in an exchange attribute. The fields are the registration id, the HTTP status, the `error` value, and the AADSTS codes of `error_codes`. The denial log of [024](024-authorization-manager.md) writes exactly one line for the request with these fields. The line never contains a token, a client secret, or `error_description`.

The gateway logs the `error` value only if it matches `^[a-z_]{1,64}$`. Otherwise it logs `(invalid)`. It logs at most 5 numbers of `error_codes`.

#### Throttling

Entra throttles token requests with `429 Too Many Requests` or with `error=temporarily_unavailable`, sometimes with a `Retry-After` header. After such a response, the gateway pauses all refresh token grants of the instance, for all sessions:

- The pause lasts the delay of `Retry-After` (seconds or an HTTP date), at most 60 seconds. Without a valid header, it lasts 5 seconds. A second throttled response can extend the pause, never shorten it.
- During the pause, a grant is not sent. The request gets `503` with the generic problem body (`TOKEN_ENDPOINT_THROTTLED`). The session stays valid. The log fields have the status `0`.
- The pause is in the memory of the instance. Other instances pause only after their own throttled response.

### Notes of the first implementation PR (2026-11-13)

- Spring Session 4.1 with Redis writes only the changed attributes of a session (delta save). Before it writes, it checks that the session key exists, and fails with "Session was invalidated" otherwise. The check and the write are two commands. If a deletion falls between them, the write creates a hash without the session metadata; [022](022-session-login.md) reads such a hash as a missing session and deletes it. The direct writes of this spec (steps 7 and 8, and the repository) use a script that checks the key and writes in one atomic step.
- With the store `memory`, the token state of a session (the attributes `gateway.oauth2.*`: the authorized clients and the grant counter `gateway.oauth2.grants`) belongs to the store. Only an atomic update of the store changes it. When the store saves the copy of a request, it keeps the stored token state. The store remembers the id of a deleted session for 10 minutes and never saves that session again. It remembers only the ids of sessions that it held, in the same atomic step as the removal, and at most `2 × gateway.session.memory.max-sessions` ids (SEC-57-1). A `POST /logout` with an unknown session id therefore uses no memory.
- `SessionAuthorizedClientRepository` writes a saved or removed client to the session of the request and also directly to the session store, only if the session exists.
- Step 5 also checks that the principal name of the `entra` client equals the user of the request. A missing `entra` client, a missing refresh token, or another principal gives `SESSION_INVALID` (row 3).
- For `SESSION_INVALID` and `REAUTHENTICATION_REQUIRED`, the redemption deletes the session in the store, and the provider invalidates the session of each request that gets the error.
- The grant limit uses a fixed window of 60 seconds from the first grant of the window, with both stores. A request that the limit stops does not count.
- The Redis ACL user of [022](022-session-login.md) needs `+get` and `+incr` for the scripts (lock release, grant counter).
- This PR has no consumer of the authorized client manager: [024](024-authorization-manager.md) maps the deny reasons to responses. The integration tests of this PR use a test stand-in (`TestAudienceTokens`) for that mapping. The criteria that name an HTTP status of a denied call stay open until [024](024-authorization-manager.md). PR 2 of [024](024-authorization-manager.md) removes the stand-in: the integration tests use the real authorization manager, and the criteria that it proves are marked.

### Notes of the second implementation PR (2026-11-13)

- `TokenPermissionsResolver.resolve` has the arguments of the sketch of [024](024-authorization-manager.md): the user, the audience, and the exchange. It gets the token from the authorized client manager. [024](024-authorization-manager.md) calls it and maps its errors. The test stand-in `TestAudienceTokens` now calls the resolver, so the integration tests of this spec use it.
- The rules for a `roles` claim of another form, for a token without exactly one `aud`, and for a stored token that fails the decoder are in "Permission decoding and cache". The spec did not name them before. After the review of PR #58, a malformed `roles` claim is an invalid token (check `roles`), not an empty set.
- `gateway.security.permission-cache.maximum-size` must be from `1` to `1,000,000`. Otherwise the gateway does not start.
- A failed decoding is not in the cache. Caffeine logs a warning for a failed load, so the gateway removes the entry, then completes it empty, and the request that started the decoding gets the error. A request that waited for the same failed decoding gets the remembered invalid token, or decodes again after a key set error.
- The resolver gets the session token store of the first implementation PR for the compare-and-delete. `SessionTokenStore.compareAndRemove` is new. The store `redis` runs the compare-and-set script of the refresh token with the empty value of a removed attribute, so the Redis ACL needs no new command.
- The mock identity provider of the tests now writes the `oid` of the user of the refresh token into the audience tokens. Before, all audience tokens had the `oid` of the first user.

## Behavior and errors

| Condition | Result |
|---|---|
| Valid token for the audience in the session, more than 60 s before `exp` | Use it. No call to Entra. |
| No token, or token less than 60 s before `exp` | Redeem the refresh token for the audience. |
| Token endpoint error | See "Token endpoint errors". |
| A second request of the same session while a redemption runs, same audience | The request waits for the running redemption and uses its result. No second call to Entra. |
| A second request of the same session while a redemption runs, other audience | The request waits until the running redemption completes. Then it redeems the token for its audience. |
| A request of the same session on another gateway instance while a redemption runs | The request waits for the Redis lock. Then it reads the session again and uses a stored token, or redeems. |
| The Redis lock is not free within 10 seconds | `504`, as a token endpoint timeout. |
| The session already caused 10 grants in the last 60 seconds | `429` with the generic problem body. No call to Entra. The session stays valid. |
| The instance already sent 50 grants in the last second, or the grants are paused after a throttled response | `503` with the generic problem body (`TOKEN_ENDPOINT_THROTTLED`). No call to Entra. The session stays valid. |
| A grant of the session for the audience failed in the last 30 seconds | The same error again. No call to Entra. See "Failed grants". |
| The session has a per-audience token of another user, for example after a second login in the same session | The login removes it. The provider also treats it as missing and redeems a token for the user of the request. |
| The client disconnects during a redemption | The redemption continues until it completes or times out, and stores its result. |
| A parallel request of the same session ended the session or removed its clients while this request runs | `SESSION_INVALID`, as for `invalid_grant`: `401` for a fetch request, the entry point for an HTML navigation ([024](024-authorization-manager.md), row 6). Never `500`. |
| Two parallel requests of one session read the session in Redis at the same time | Each request gets the response of this spec. A read can wait shortly for a lock in memory of Jackson ([002](002-gateway-bootstrap.md), "BlockHound"). |
| New token fails validation (signature, issuer, `ver`, `tid`, `exp`, `nbf`, `aud`, `azp`) | The token is not stored. `500` with the generic problem body (`INVALID_AUDIENCE_TOKEN`). The session stays valid. One `ERROR` log line with the registration id and the failed check, without the token. |
| Stored token fails the validation of the permission resolver (the checks of the decoder, exactly one `aud`, `azp`, `oid`, `roles`) | `500` with the generic problem body (`INVALID_AUDIENCE_TOKEN`). The resolver removes the token from the session with a compare-and-delete under the lock of the session. The session stays valid. The same token gets the same error for 30 seconds without a decoding. One `ERROR` log line with the registration id and the failed check. |

The provider stores the error and its log fields in an exchange attribute. [024](024-authorization-manager.md) maps it to the HTTP response and writes the log line.

All error bodies use `application/problem+json` (RFC 9457) with a fixed, generic `detail`. `CONSENT_REQUIRED` has its own problem `type`, so that the SPA can show a specific message. The bodies contain no detail from Entra:

```http
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{"type":"urn:oas-spring-boot-gateway:problem:consent-required","title":"Consent Required","status":403,"detail":"Consent required."}
```

```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/problem+json

{"type":"about:blank","title":"Internal Server Error","status":500,"detail":"Internal error."}
```

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json

{"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Too many requests."}
```

```http
HTTP/1.1 502 Bad Gateway
Content-Type: application/problem+json

{"type":"about:blank","title":"Bad Gateway","status":502,"detail":"Bad gateway."}
```

```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/problem+json

{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"Try again later."}
```

```http
HTTP/1.1 504 Gateway Timeout
Content-Type: application/problem+json

{"type":"about:blank","title":"Gateway Timeout","status":504,"detail":"Gateway timeout."}
```

## Configuration

| Property | Type | Default | Meaning |
|---|---|---|---|
| `gateway.security.token-clock-skew` | Duration | `60s` | A token is renewed this time before `exp`. |
| `gateway.security.token-endpoint.timeout` | Duration | `5s` | Maximum time for one call to the Entra token endpoint. Also the connect timeout and the response timeout of the JSON Web Key Set. |
| `gateway.security.token-endpoint.grants-per-second` | Integer | `50` | Grant limit for the instance: refresh token grants of this instance in one second, for all sessions. At least `1`. |
| `gateway.security.permission-cache.maximum-size` | Integer | `10000` | Maximum number of entries in the permission cache, and in the memory of invalid tokens. From `1` to `1000000`; another value stops the startup. One entry needs a few hundred bytes, so the upper bound keeps the cache below a few hundred megabytes. |

Fixed values (not configurable):

| Value | Setting |
|---|---|
| Maximum lifetime of a permission cache entry | 1 hour after it was added |
| Memory of an invalid audience token | 30 seconds |
| Maximum size of the `roles` claim | 200 values, each at most 120 characters |
| Maximum time of steps 4 to 8 of a redemption | 8 seconds |
| Memory of a failed grant | 30 seconds, at most 10,000 entries |
| Lifetime of the Redis lock of a session | 10 seconds |
| Retry interval for the Redis lock | 100 milliseconds |
| Maximum wait for the Redis lock | 10 seconds |
| Grant limit for each session | 10 grants in 60 seconds |
| Pause of all grants after a throttled response | The `Retry-After` delay, at most 60 seconds; 5 seconds without the header |

Tests change the lock values through the package-private constructor of the lock component (see "Single-flight redemption"). They are not properties.

## Acceptance criteria

Registrations and scope:

- [x] At startup, the gateway creates one registration `entra-<client-id>` for each audience of a protected operation of the loaded OAS files, and no other.
- [x] The gateway starts with an OAS file that has protected operations and `x-gateway.audience: 6f1c2e8a-0000-0000-0000-000000000000`, without a catalog. It creates the registration `entra-6f1c2e8a-0000-0000-0000-000000000000`, and Spring Security accepts this registration id.
- [x] The first call to an `orders-api` route sends one refresh token grant with `scope=6f1c2e8a-0000-0000-0000-000000000000/.default offline_access` to the mock identity provider.
- [x] The first implementation PR records the result of the scope verification with `infra/azure-test` in "Scope form". The record contains the plan (A or B) and the `aud` of the `orders-api-test` token. It also contains the error body for `noconsent-api-test` and the Entra results of the negative cases 4 to 6.
- [ ] Manual check in a test tenant: a test user who is not an administrator tries to consent to the scope of `noconsent-api-test`. Step 3 of "Scope form" records the result. Automated tests cannot do this check, because it needs a real browser login against Entra ID.
- [x] `infra/azure-test` has the backends `orders-api-test`, `noconsent-api-test`, and `v1-api-test` of "Scope form". Each `access_as_user` scope has the type `Admin`. The output `echo_api_scope` is `{client-id}/.default`.
- [x] The gateway does not start when an OAS file has the client id of the registration `entra` as its audience. The root cause message is the message of "Client registrations for each audience". The test also uses the client id in uppercase in the property.
- [x] `GET /oauth2/authorization/entra-6f1c2e8a-0000-0000-0000-000000000000` returns `404` ([024](024-authorization-manager.md)), and the mock identity provider receives no request.
- [x] For an audience without admin consent, the mock identity provider returns the consent error body of step 3 of "Scope form". The call gets `403` with the consent problem body (`CONSENT_REQUIRED`), and the backend receives no request.
- [x] For an audience that does not exist in the tenant, the mock identity provider returns `400` with `error=invalid_resource`. The call gets `500` with the generic problem body (`TOKEN_REQUEST_REJECTED`), and the backend receives no request.

Redemption and single-flight:

- [x] Two parallel first calls of one session to `orders-api` routes cause exactly one refresh token grant at the mock identity provider. Both calls get the backend response.
- [x] Two parallel first calls of one session, one to `orders-api` and one to `payments-api`, cause two grants that do not overlap in time at the mock identity provider. The second grant uses the refresh token that the first grant returned. After both calls, the session in Redis has both access tokens and the newest refresh token.
- [x] Two parallel requests of one session to `orders-api` arrive when the stored token is in the skew window (less than 60 seconds before `exp`). The mock identity provider receives exactly one grant.
- [x] A second user logs in on the same session after the first user got a token. The login removes the per-audience clients, and the next call sends a grant with the refresh token of the second user. The stored client has the principal name of the second user. The test runs with both stores.
- [x] Steps 2 and 4 treat a client of another principal as missing. A missing refresh token or an `entra` client of another principal gives `SESSION_INVALID` (unit test of the provider).
- [x] A JSON Web Key Set that answers after the timeout gives `504`. The session stays valid, the rotated refresh token is stored, and no redemption entry remains. A decoder that never answers ends the redemption after the timeout of steps 4 to 8.
- [x] After a `503` of the token endpoint and after `invalid_grant`, no lock key remains in Redis.
- [x] When the maximum wait for the lock ends while Redis is paused, the late lock is released.
- [x] A failed grant is remembered for 30 seconds for the session and the registration. A call for another registration sends its grant.
- [x] When the bucket of the instance is empty, the grant is not sent, the call gets `503` (`TOKEN_ENDPOINT_THROTTLED`), and `limited` counts it. The bucket refills after one second.
- [x] A `429` with `Retry-After: 1` gives `503` and pauses the grants of all sessions. After the pause, a grant goes to Entra again.
- [x] `limited` counts each stopped request, also a request that waited for the redemption of another request. `success` does not count a token that the gateway could not store.
- [x] With the store `memory`, a `POST /logout` with an unknown session id remembers nothing, and the store remembers at most `2 × max-sessions` deleted ids for at most 10 minutes.
- [x] Two gateway instances on the same Redis. The mock token endpoint has a fixed delay of 1 second. The test sends a first call of one session to instance A, waits until the mock receives the grant, and then sends a call of the same session to instance B. The mock receives exactly one grant, and the call to instance B uses the token that instance A stored.
- [x] The test holds the Redis lock of a session with its own client. A call of that session waits and then gets `504`. The test uses a short maximum wait through the package-private constructor.
- [x] The client of a call disconnects while the mock token endpoint delays the grant. The grant completes, and the session in Redis has the new access token and the new refresh token. The mock receives one grant.
- [x] The 11th grant of one session within 60 seconds is not sent. The call gets `429` with the generic problem body, and the session stays valid.
- [x] Each sent grant increments `gateway.token.grants` exactly once, with the tag `result` of its outcome: each row of "Token endpoint errors", `invalid_token`, a key set error, the redemption timeout, and a token that the gateway cannot store. When a success and the redemption timeout come at the same time, the first signal counts, and only once (QA-63-2). A grant that is not sent counts only as `limited`, also when the pause after a throttled response starts after the check of step 6 (QA-63-1).
- [x] The `ReactiveOAuth2AuthorizedClientManager` has no refresh token provider for `entra`. The authorization code provider returns empty for a per-audience registration, also with the fallback grant type `authorization_code`.
- [x] A per-audience authorized client in Redis has no refresh token.
- [x] The test changes the `tokenUri` of the `entra` authorized client in the session in Redis to another mock server. The next grant goes to the token endpoint of the configured registration, and the other mock server receives no request.
- [x] With the store `memory`, two parallel first calls of one session cause one grant at a time, and the grant limit applies. The test needs no Redis.
- [x] Parallel first calls of two sessions to `orders-api` routes cause one refresh token grant for each session.
- [x] The Redis lock key does not contain the session id.
- [x] After a redemption, also after an error or a timeout, no lock entry of the session remains in the gateway instance or in Redis.
- [x] A redemption that finds a stored `entra` authorized client other than the one it read in step 5 does not overwrite it. The test changes the stored value in Redis during the delay of the mock grant.
- [x] The test deletes the lock key in Redis during the delay of a grant, and a second instance sends a grant for the same session. Both grants read the same refresh token. The stored refresh token after both grants is the token of the first compare-and-set. The other grant writes no refresh token, because the value that it read is no longer stored. No grant writes an older refresh token.
- [x] Request A gets `invalid_grant` and invalidates the session. A parallel request B of the same session finishes later. After both requests, the session key does not exist in Redis.
- [x] A request whose session a parallel request ended before the redemption gets `SESSION_INVALID`. The mock identity provider receives no grant, and the session does not exist after the request.
- [x] A request whose session has no `entra` authorized client gets `SESSION_INVALID`. The mock identity provider receives no grant, and the session does not exist after the request.
- [x] The test deletes the session key in Redis, and a fetch request of the session gets `401`. The mock identity provider receives no grant.
- [x] The test holds the lock of the Jackson deserializer cache of the gateway while a request with the store `redis` reads its session. The request waits for the lock. After the test releases the lock, an `invalid_grant` gives `401`, and the session key does not exist in Redis.
- [x] On a second call within the token lifetime, the mock identity provider receives no request.
- [x] When the token expires in less than 60 seconds, the next call redeems a new token.
- [x] When the mock identity provider returns a new refresh token, the next redemption uses the new refresh token.

Permissions:

- [x] Permissions come from the `roles` claim of the audience token. A `roles` claim in the ID token has no effect.
- [x] A token with a wrong signature or a wrong issuer produces no permissions.
- [x] The decoder rejects a token without `exp` (check `exp`) and a token with an `nbf` 10 minutes in the future (check `nbf`).
- [x] Before the gateway stores a new token, the token must have the `aud` of the registration and the `azp` of `gateway-bff`. Otherwise the check is `aud` or `azp`.
- [x] The gateway does not start with an issuer that is not an Entra ID issuer: `http`, another host, a port, a user part, a tenant id in uppercase, or a tenant that is not a GUID. It does not start with a `jwks_uri` on another host or outside `/{tenant-id}/discovery/`.
- [x] Without the test bean `LocalIdentityProvider`, the issuer of the mock identity provider stops the startup. A proxy of the bean also stops the startup.
- [x] The gateway loads the JSON Web Key Set once at startup. A key set that does not load does not stop the startup. A key set that answers after the timeout gives a key set error, not a failed check.
- [x] The mock token endpoint returns a version 1 token (issuer `https://sts.windows.net/<tenant-id>/`, `ver` `1.0`). The call gets `500` (`INVALID_AUDIENCE_TOKEN`), and the session in Redis has no authorized client for that registration.
- [x] The mock token endpoint returns a token signed with a key of the mock JWKS, with `ver` `2.0`, but with the issuer and the `tid` of another tenant. The call gets `500` (`INVALID_AUDIENCE_TOKEN`), and the token is not stored.
- [x] When the response of a discarded token has a new refresh token, the `entra` authorized client in Redis has the new refresh token.
- [x] Two decodings of the same token within its lifetime cause one decoder call.
- [x] A permission cache entry for a token with a lifetime of 2 hours expires 1 hour after it was added (verified with a fake ticker).
- [x] The permission cache is built with the configured `maximum-size` (10,000 by default). A unit test with `maximum-size: 2`, a direct executor, and `cleanUp()` holds exactly 2 entries after 3 insertions, and a second decoding of the 3 tokens calls the decoder again. An application context with `maximum-size: 5` has a resolver with the maximum size 5. The values `0` and `1000001` stop the startup.
- [x] A stored token with a `roles` claim that is one string, an array with a number, an array with 201 values, or a value with 121 characters fails the check `roles`. 200 values with 120 characters pass.
- [x] A stored token without `aud` fails the check `aud`. A stored token with the `aud` `other-client-id` gives the permissions with this audience and no deny reason. A token with the `azp` of another client fails the check `azp`. A token with the `oid` of another user fails the check `oid`.
- [x] A stored token that fails the validation is removed from the session under the lock of the session, with both stores. A newer stored token stays. A key set error removes nothing.
- [x] An invalid token gets the same error for 30 seconds without a second decoding (fake ticker). Two parallel requests of an invalid token both get `INVALID_AUDIENCE_TOKEN`, with one decoding, and the cache has no entry. Two parallel requests of a key set error decode twice.
- [x] A decoder that never answers gives `TOKEN_ENDPOINT_TIMEOUT` after the timeout (virtual time), and the cache has no entry.
- [x] A key set timeout (a `WebClientRequestException` with a `ReadTimeoutException`) gives `TOKEN_ENDPOINT_TIMEOUT`, and a key set error gives `TOKEN_ENDPOINT_ERROR`. Both put the deny reason and `TokenError.withoutResponse` into the exchange attributes. An invalid token puts the status `0`.
- [x] `resolve` never completes empty. A decoding can ask the cache for the same token while it runs.

Errors:

- [x] `invalid_grant` without a consent marker invalidates the session, and the next fetch request returns `401`.
- [x] Each of these responses gives `403` with the problem `type` `urn:oas-spring-boot-gateway:problem:consent-required`, and the session stays valid: `error=invalid_grant` with `suberror=consent_required`; `error=invalid_grant` with `error_codes: [65001]`; `error=interaction_required` with `error_codes: [65001]`.
- [x] `error=interaction_required` with `error_codes: [50076]` invalidates the session and removes all authorized clients. The next fetch request returns `401`. The log line contains `50076`.
- [x] The test for `suberror=consent_required` uses the exact error body that Entra returned in the verification with `infra/azure-test`.
- [x] `invalid_scope` and `invalid_client` each give `500` with the generic problem body. The session stays valid. Exactly one `ERROR` log line is written, by the denial log of [024](024-authorization-manager.md). It contains the registration id and the `error` value, and no token, no client secret, and no `error_description`.
- [x] The test sets `gateway.security.token-endpoint.timeout` to 200 milliseconds. The mock token endpoint responds after 1 second. The gateway returns `504` with `Content-Type: application/problem+json`, and the session stays valid.
- [x] A unit test shows that the default of `gateway.security.token-endpoint.timeout` is 5 seconds.
- [x] When the mock token endpoint returns `500` or `503`, or refuses the connection, the gateway returns `502` with `Content-Type: application/problem+json`, and the session stays valid.
- [x] `error=invalid_grant` with `suberror=consent_required` and without `error_codes` gives `CONSENT_REQUIRED`.
- [x] A `400` with an HTML body gives `TOKEN_REQUEST_REJECTED`. A `200` with the body `{}` gives `TOKEN_ENDPOINT_ERROR`. A `302` gives `TOKEN_ENDPOINT_ERROR`, and the gateway does not follow it.
- [x] A `429` with `Retry-After: 2` gives `TOKEN_ENDPOINT_THROTTLED`. The next grant within 2 seconds is not sent and also gives `TOKEN_ENDPOINT_THROTTLED`. After 2 seconds, the gateway sends grants again.
- [x] `error=temporarily_unavailable` with `400` or `503` gives `TOKEN_ENDPOINT_THROTTLED` and a pause of 5 seconds. A `Retry-After` of one hour gives a pause of 60 seconds.
- [x] Every problem body of this spec has a `detail` with the generic text of "Behavior and errors".
- [x] The console output of the test JVM contains no token and no secret of the flow (SEC-63-4). The test `TokenLoggingIT` runs login, redemption, relay ([025](025-token-relay-and-header-hygiene.md)), a rejected audience token, and logout, in three cases:
  - With the level `DEBUG` for `com.johnowl.gateway`: no line contains the ID token, an access token, a refresh token, a relayed token, or `eyJ`.
  - With the level `TRACE` for the root logger, once with the store `memory` and once with the store `redis`: the test joins the whole output, and joins the ASCII columns of each hex dump to one line. The output contains none of these values: the ID token, the access tokens (also the rejected audience token), the refresh tokens (also the rotated ones), the relayed tokens, the session id before and after the login (raw, Base64, and base64url), the client secret (raw and in the HTTP Basic value), the Redis password, the authorization code, the PKCE code verifier, and any JSON Web Token. The output has the `WARN` line of the log guard of [022](022-session-login.md).

  The test proves this for the paths of this flow. It does not run each error path of the libraries; the pinned loggers and the masking of [022](022-session-login.md) cover them. The test starts the Redis container before the gateway, so the output of Testcontainers, with the ACL command of the test container, is not part of it.

## Test plan

- Unit tests for `AudienceRefreshTokenAuthorizedClientProvider` with a stub token response client: no token, valid token, token in skew window, token stored by another request (step 4), rotation, compare-and-set, `offline_access` in the scope, and each row of "Token endpoint errors" in order.
- Unit tests for the single-flight redemption: same session and same audience, same session and two audiences, two sessions, error shared with the waiting request, entry removed after success, error, and timeout. A cancelled request does not cancel the redemption.
- Unit tests for the Redis lock and the compare-and-set script with Redis in Testcontainers: acquire, lock taken, release only with the own value, expiry, maximum wait, `NOSCRIPT` reload. The tests use lock values of 200 to 500 milliseconds or virtual time, not the production values.
- Unit tests for the grant limit: 10 grants pass, the 11th gets `429`, the window ends after 60 seconds (virtual time).
- Unit tests for `SessionAuthorizedClientRepository`: one attribute for each registration, no write to a deleted session.
- Unit tests for `TokenPermissionsResolver`: tokens signed with a test key; cache hit; expiry at `exp`; expiry after 1 hour for a longer token; configured maximum size with a direct executor and `cleanUp()`; missing and malformed `roles`; `aud`, `azp`, and `oid`; the memory of invalid tokens; the timeout of a decoding; the compare-and-delete of a stored token.
- Unit test for `AudienceClientRegistrations`: audiences from OAS files only; registration id; scope; the client id of `gateway-bff` as an audience stops the startup.
- Unit tests for the decoder: a valid version 2 token, a version 1 token, a token with the issuer of another tenant, a token with another `tid`, a token without `exp`, a token that is not yet valid, a token with another `aud` or `azp`, the issuer pin and the `jwks_uri` rule, and a slow key set.
- Integration test with `WebTestClient`, a mock identity provider (WireMock) for the token endpoint and the JWKS, and Redis in Testcontainers.
  Cases: first call, second call, parallel first calls for one and two audiences, two application contexts on the same Redis, expiry, error cases, timeout, invalidated session not written back.
- BlockHound in integration tests.

## Delivery

Two PRs:

1. Registrations and provider: `AudienceClientRegistrations` (with the check for the client id of `gateway-bff`), `AudienceRegistrationIds`, `AudienceRefreshTokenAuthorizedClientProvider`, `SessionAuthorizedClientRepository`, single-flight redemption with the Redis lock, token endpoint timeout and error mapping, authorized client manager bean, error attributes, tests. The PR also adds the `ReactiveJwtDecoder` of "Permission decoding and cache", because step 7 of the provider validates each token before it stores it. The PR changes `infra/azure-test`: the new test backends, the scope type `Admin`, and the outputs. It verifies the grant type (see "Fallback for the grant type"), the scope form and the negative cases (see "Scope form"), and the consent error body. Estimate: 600 lines without Terraform.
2. Permissions: `TokenPermissions`, `TokenPermissionsResolver` with the decoder of PR 1, Caffeine cache, properties, tests. Estimate: 200 lines.

PR 1 needs code PR 2 of [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13). Reason: code PR 2 replaces the catalog check on `main` with the startup stop of [024](024-authorization-manager.md) ("Startup stop until the authorization manager is active"). The tests of this spec load protected operations. After code PR 2, they register a test implementation of `AudienceAuthorizationActive` from `gateway/src/test/java`. They do not need a catalog or a property. Production code of this spec MUST NOT register the bean. That section of the README lists all code PRs of the removal.

## Decisions (2026-11-13)

The product owner asked for conservative defaults so that the implementation can start. The team chose these defaults. The team can change each of them later.

- Grant type of per-audience registrations: the implementer verifies in the first implementation PR that the `ClientRegistration` builder of Spring Security 7 accepts `refresh_token`. If it does not, the per-audience registrations use `authorization_code` and a gateway-side marker (see "Fallback for the grant type"). This question is closed; the verification is part of the implementation.
- Catalog at runtime: the gateway reads the catalog from the location in `gateway.security.catalog`. The catalog is required. The optional-catalog mode of "Gateway change" in [035](035-entra-permission-validation.md) is not shipped before two preconditions of 035 are done. These are "the ruleset on `main`" and "the scope and registration-id verification". Superseded by the removal of the permission catalog (see the last list of this section).
- Permission cache: at most 10,000 entries. An entry lives until the token `exp`, but at most 1 hour.
- Token endpoint errors: the timeout is 5 seconds. A timeout gives `504`. A connection error or a `5xx` status from Entra gives `502`. Both responses use `application/problem+json`.
- `offline_access`: the refresh token grant includes the scope `offline_access`.
- Missing consent: the response has a distinct problem `type`, `urn:oas-spring-boot-gateway:problem:consent-required`, so that the single-page application (SPA) can show a specific message.

Defaults chosen by the team after the review of PR #47 (QA-47, SEC-47). The team can change each of them later:

- Application ID URI and registration id (QA-47-1): `catalog export --entra` writes the real Application ID URI, the single `api://` value of `servicePrincipalNames`. Zero or more than one such value gives an `audience-known` violation ([035](035-entra-permission-validation.md)). The registration id is `entra-<client-id>`. This answers the open question about the exported Application ID URI. The Application ID URI part is superseded by the removal of the permission catalog. The registration id stays.
- Other token endpoint `4xx` errors (QA-47-1, SEC-47-12): `500` with the generic problem body and one `ERROR` log line without a token or a secret. Deny reason `TOKEN_REQUEST_REJECTED`. This answers the open question about `invalid_client` and `invalid_scope`.
- Source of the catalog file (QA-47-2): the artifact `entra-catalog` of the latest successful scheduled run of `entra-drift.yml` on `main`. The release or deploy pipeline downloads it. No new Graph credential. The scheduled run keeps the artifact for 7 days (follow-up in [035](035-entra-permission-validation.md)). Superseded by the removal of the permission catalog (see the last list of this section).
- Parallel redemptions (QA-47-3, SEC-47-3): the single-flight key is the session. A Redis lock (`SET NX PX`, 10 seconds) protects the session across instances. The gateway writes the rotated refresh token with a compare-and-set, cleans up in `doFinally`, and never writes an invalidated session back. This answers the open question about a lock in Redis.
- Lock wait (QA-47-3): retry every 100 milliseconds for at most 10 seconds, then `504`. The lock key contains the SHA-256 hash of the session id, not the session id (SEC-47-1).
- Consent detection (QA-47-4, changed by SEC-47-19): `error=invalid_grant` with `suberror=consent_required`, or `65001` in `error_codes`. The gateway checks it before the other rows. The team verifies the body with `infra/azure-test` during the implementation.
- `file:` location (QA-47-10): a convention for production deployments. The gateway does not validate the prefix. Superseded by the removal of the permission catalog (see the last list of this section).
- Problem bodies (QA-47-13): every problem body has a generic `detail`.

Defaults chosen by the team after the third review round of PR #47. The team can change each of them later:

- Provider chain (QA-47-14, SEC-47-20): `AudienceRefreshTokenAuthorizedClientProvider` first, then the authorization code provider restricted to `entra`. No refresh token provider for `entra`. Per-audience clients have no refresh token. Step 5 reads `entra` from the repository directly. The `entra` refresh token is used only under the session lock.
- Other interaction (SEC-47-19): `error=interaction_required` without `65001` gives the new deny reason `REAUTHENTICATION_REQUIRED`. The gateway removes all authorized clients, invalidates the session, and logs the AADSTS codes. This answers the open question about `interaction_required`.
- Cancellation and grant limit (SEC-47-16): a redemption runs to completion after a cancellation. A session can cause at most 10 grants in 60 seconds; the next request gets `429` (`TOKEN_GRANT_LIMIT`). The counter `gateway.token.grants` counts the grants.
- Compare-and-set (SEC-47-15): an atomic Redis script on the `entra` session attribute. Under the eviction policy `volatile-ttl`, Redis evicts the lock keys first. This is safe, because the compare-and-set still prevents a lost update, and a lost lock only allows a duplicate grant.
- Redis scripts (SEC-47-14): loaded at startup and called with `EVALSHA`. The lock release is a script, also where the server has `DELEX IFEQ` or `DELIFEQ` (changed by the first implementation PR, 2026-11-13).
- Test values (QA-47-22): the lock values are fixed in production and injected through a package-private constructor with a `Clock` and a `Scheduler`.
- Log line (QA-47-23): the provider stores the log fields in an exchange attribute. The denial log of [024](024-authorization-manager.md) writes the single line.
- Catalog artifact (SEC-47-18): the deploy fails on an empty run id and filters by the event `schedule`. The export and the loader check the pattern of `application-id-uri`. Superseded by the removal of the permission catalog (see the last list of this section).

Removal of the permission catalog (product owner, 2026-11-13). See [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13):

- The gateway has no catalog and no property `gateway.security.catalog`. It derives the registrations from the loaded OAS files only.
- The audience is `x-gateway.audience`, the client id of the backend app. The registration id is `entra-{client-id}`.
- The token request uses the scope `{client-id}/.default offline_access`. The first implementation PR verified this form with `infra/azure-test` (2026-11-13). Plan B (`api://{client-id}/.default`) failed and is removed. See "Scope form".
- The admin consent of `gateway-bff` for each backend app limits the audiences ([021](021-entra-app-registrations.md)). Correction after the review of PR #48 (SEC-48-2): it is not a complete allowlist. See "Admin consent limits the audiences".
- Loss: a typo in an audience is not found in the PR. At runtime the token request fails closed with a consent error or another token endpoint error.

Defaults chosen by the team after the review of PR #48 (QA-48, SEC-48). The team can change each of them later:

- Startup stop (QA-48-1, SEC-48-4): the tests of this spec register a test implementation of `AudienceAuthorizationActive` from `src/test`. See [024](024-authorization-manager.md).
- Test backends (QA-48-5): the first implementation PR adds `orders-api-test` (Application ID URI `api://orders-api-test`), `noconsent-api-test` (no admin consent), and `v1-api-test` (version 1 tokens) to `infra/azure-test`, and changes the output `echo_api_scope`.
- User consent (QA-48-6): step 3 of "Scope form" also checks that a user who is not an administrator cannot consent.
- One status for each error body (QA-48-8): a consent body gives `403` (`CONSENT_REQUIRED`). `invalid_resource` or another `4xx` body for an unknown audience gives `500` (`TOKEN_REQUEST_REJECTED`).
- Limits of the admin consent (SEC-48-2): the startup check for the client id of `gateway-bff`, and the negative cases 4 to 6 of "Scope form".
- Issuer pin (SEC-48-3): the decoder accepts only the version 2 issuer of the tenant, and checks `ver` and `tid`. The provider validates each token before it stores it. A failure gives `INVALID_AUDIENCE_TOKEN` (`500`).
- Session store (decision of the product owner, 2026-11-13): the session store is in memory by default, and Redis is optional ([022](022-session-login.md)). The lock, the compare-and-set, and the grant counter use the store of the session. See "Session store".
- Registration for token calls (SEC-51-2): each token call uses the registration from the `ReactiveClientRegistrationRepository`, never the registration from the session.

Defaults chosen by the team after the review of the first implementation PRs #55, #56, and #57 (2026-11-13). The team can change each of them later:

- Issuer pin (SEC-55-1): only `https` and the three Entra ID hosts, a tenant id in lowercase GUID form, and the `jwks_uri` on the same host under `/{tenant-id}/discovery/`. The mock identity provider of the tests passes only with the test bean `LocalIdentityProvider` from `src/test`. There is no production property for it.
- Token expiry (SEC-55-2): the `exp` claim is required. The stored expiry is the earlier time of `exp` and `expires_in`.
- New token (SEC-55-3): step 7 requires the `aud` of the registration and the `azp` of `gateway-bff`.
- Key set (QA-57-1, SEC-57-2): its own `WebClient` with the token endpoint timeout, loaded at startup. A key set that does not load gives row 5 or row 6 of "Token endpoint errors". Steps 4 to 8 have a timeout of 8 seconds together, shorter than the lifetime of the lock. The timeout is on the work under the lock, not on the wait for the lock, so the maximum wait for the lock stays 10 seconds.
- Throttling (SEC-56-1): a `429` or `temporarily_unavailable` gives the new deny reason `TOKEN_ENDPOINT_THROTTLED` and `503`. The gateway pauses all grants of the instance until `Retry-After`, at most 60 seconds, and 5 seconds without the header.
- Token endpoint client (SEC-56-2): the token endpoint uses the `WebClient` of "Calls to Entra ID", with the token endpoint timeout as its connect timeout and response timeout.
- Tokens of another user (QA-57-2): steps 2 and 4 check the principal name, and the login success handler of [022](022-session-login.md) removes all per-audience clients.
- Metric (QA-57-4): `limited` counts each stopped request in the provider, after the single-flight redemption. `success` counts after the token is stored.
- Deleted session ids (SEC-57-1): the store `memory` marks only sessions that it holds, and remembers at most `2 × max-sessions` ids for at most 10 minutes.
- Grant limits (SEC-57-3): failed grants are remembered for 30 seconds for each session and registration. An instance limit of 50 grants in one second (`gateway.security.token-endpoint.grants-per-second`) gives `503` (`TOKEN_ENDPOINT_THROTTLED`). A limit for each user and the eviction of Redis counter keys are accepted risks (see "Failed grants").

Decisions of the product owner and defaults chosen by the team after the review of PR #58 (2026-11-13). The team can change each of them later:

- Malformed `roles` (choice 1, SEC-58-4): a `roles` claim that is not an array of strings, has more than 200 values, or has a value with more than 120 characters gives `INVALID_AUDIENCE_TOKEN` with the check `roles` (`500`, one `ERROR` log line). Before, it gave no permissions.
- Invalid stored token (choice 2): a stored token that fails the validation with `INVALID_AUDIENCE_TOKEN` is removed from the session with a compare-and-delete under the lock of the session, only if the session still has the same token. A key set error (`KeySetUnavailableException`) never removes a token.
- Memory of invalid tokens (SEC-58-1): 30 seconds for each token hash. The same token gets the same error without a decoding.
- Timeout of a decoding (SEC-58-2): `gateway.security.token-endpoint.timeout`. A timeout is a key set timeout, and it frees the cache entry.
- `azp` and `oid` (SEC-58-3): a stored token must have the `azp` of `gateway-bff` and the `oid` of the principal name. The principal name of [022](022-session-login.md) is the `oid` claim.
- Maximum size (SEC-58-4): from `1` to `1,000,000`.

## Review findings

| Finding | Resolution in this spec |
|---|---|
| QA-47-1 | "Client registrations for each audience" and "Token endpoint errors" (row 4). The exported catalog format is removed (2026-11-13). |
| QA-47-2 | Closed by the removal of the permission catalog (2026-11-13). The gateway reads no catalog. |
| QA-47-3 | "Storage of authorized clients", "Single-flight redemption", and the criterion for two audiences in parallel. |
| QA-47-4 | "Token endpoint errors" (row 1 before rows 2 and 3). |
| QA-47-9 | Closed by the removal of the permission catalog (2026-11-13). The preconditions of 035 no longer apply. |
| QA-47-10 | Closed by the removal of the permission catalog (2026-11-13). |
| QA-47-12 | The criteria for the timeout and for the cache size. |
| QA-47-13 | "Behavior and errors": every problem body has a `detail`. |
| SEC-47-3 | "Security invariants", "Storage of authorized clients", and "Single-flight redemption". |
| SEC-47-12 | "Token endpoint errors" (row 4). |
| QA-47-14, SEC-47-20 | "Custom authorized client provider" (steps 5 and 7), "Provider chain", and the criteria for the skew window and the provider chain. |
| QA-47-18 | Closed by the removal of the permission catalog (2026-11-13). 035 is superseded. |
| QA-47-20 | The criteria for two instances with a delayed grant and for a lock held by the test. |
| QA-47-22 | "Single-flight redemption": injected lock values. "Test plan". |
| QA-47-23 | "Token endpoint errors": who writes the log line. |
| SEC-47-14 | "Single-flight redemption": scripts, `EVALSHA`, `DELEX IFEQ`, and `DELIFEQ`. |
| SEC-47-15 | "Compare-and-set of the refresh token" and "Single-flight redemption": eviction of lock keys. |
| SEC-47-16 | "Single-flight redemption" (step 4) and "Grant limit for each session". |
| SEC-47-17 | "Storage of authorized clients": JSON serialization ([022](022-session-login.md)). |
| SEC-47-18 | Closed by the removal of the permission catalog (2026-11-13). The gateway downloads no catalog artifact. |
| SEC-47-19 | "Token endpoint errors" (row 2). |
| QA-48-1, SEC-48-4 | "Delivery": the reason for the dependency on code PR 2, and the test bean. |
| QA-48-5 | "Scope form": the new test backends and the output `echo_api_scope`. |
| QA-48-6 | "Scope form", step 3: the user consent case. |
| QA-48-8 | "Admin consent limits the audiences" and the two criteria with one status for each error body. |
| SEC-48-2 | "Client registrations for each audience" (client id of `gateway-bff`), "Admin consent limits the audiences", and "Scope form" (negative cases 4 to 6). |
| SEC-48-3 | "Security invariants", "Custom authorized client provider" (step 7), "Permission decoding and cache", "Behavior and errors", and the criteria for version 1 tokens and the issuer of another tenant. |
| SEC-51-2 | "Security invariants", "Registration for token calls", and the criterion with a changed `tokenUri`. |
| QA-55-1 | The criterion for `nbf` and the test plan of the decoder. |
| SEC-55-1 | "Security invariants" and "Permission decoding and cache": issuer pin, `jwks_uri` rule, and the test bean `LocalIdentityProvider`. |
| SEC-55-2 | "Permission decoding and cache" (`exp` required) and step 7 (stored expiry). |
| SEC-55-3 | Step 7 of "Custom authorized client provider": `aud` and `azp`. |
| QA-56-1, QA-56-2 | The criteria for `consent_required` without `error_codes`, for an HTML body, for `{}`, and for a redirect. |
| QA-56-3 | "Token endpoint errors": rows 1 to 4 apply only to a `4xx` status. |
| SEC-56-1 | "Token endpoint errors" and "Throttling": `TOKEN_ENDPOINT_THROTTLED`, `503`, and the pause of all grants. |
| SEC-56-2 | "Calls to Entra ID": the token endpoint uses its own `WebClient`. |
| QA-57-1, SEC-57-2 | "Custom authorized client provider" (step 3), "Single-flight redemption", "Permission decoding and cache", and "Token endpoint errors" (rows 5 and 6): timeouts of the key set and of the work under the lock. |
| QA-57-2 | "Custom authorized client provider" (steps 2 and 4, login of another user). |
| QA-57-3 | Test only: the disconnect test waits for all conditions together. |
| QA-57-4 | "Grant limit for each session": the metric tags `limited` and `success`. |
| QA-57-5 | The criteria for lock keys after errors and the unit test of the provider. |
| QA-57-6 | "Single-flight redemption" (lock taken): the release after the maximum wait. |
| QA-57-7 | "Grant limit for each session": the script reads the counter first. |
| QA-57-8 | "Single-flight redemption": the lock component has no `Clock`. |
| SEC-57-1 | "Notes of the first implementation PR": deleted session ids. |
| SEC-57-3 | "Grant limit for the instance", "Failed grants" (with the accepted risks), and "Configuration". |
| SEC-57-4 | [022](022-session-login.md), "Redis ACL". |
| QA-58-1 | Test only: the key set timeout, the attributes of the key set errors, and the status `0` of an invalid token. |
| QA-58-2 | "Permission decoding and cache" (`aud`) and its criterion. |
| QA-58-3 | "Permission decoding and cache": `resolve` never completes empty, and the requests that waited for a failed decoding. |
| QA-58-4 | "Configuration" and the criterion for the cache size. |
| QA-58-5 | "Permission decoding and cache": the decoding starts after Caffeine created the entry. |
| SEC-58-1 | "Permission decoding and cache" (invalid tokens), "Behavior and errors", and "Configuration" (fixed values). |
| SEC-58-2 | "Permission decoding and cache": the timeout of a decoding. |
| SEC-58-3 | "Permission decoding and cache": `azp` and `oid`, and the principal name of [022](022-session-login.md). |
| SEC-58-4 | "Permission decoding and cache" (`roles` limits) and "Configuration" (upper bound of the maximum size). |
| QA-63-1 | The criterion for `gateway.token.grants`: a pause that starts after the check of step 6. |
| QA-63-2 | The criterion for `gateway.token.grants`: the first signal of a sent grant counts, and only once. |
| QA-63-3 | "Scope form", result 1: the source of the client id of `orders-api-test`. |
| QA-63-4 | The criterion for the lost lock: the other grant writes no refresh token. |
| SEC-63-1, SEC-63-2, SEC-63-3, SEC-63-4 | "Security invariants" and the log criterion. The log guard is in [022](022-session-login.md), "Log guard". |
| Choice 1, choice 2 | "Decisions (2026-11-13)", "Permission decoding and cache", and "Behavior and errors". |
| CI-43712577572 | "Behavior and errors": a session that a parallel request ended, and two parallel reads of one session in Redis. Their criteria. The cause was BlockHound in the tests, not the provider: [002](002-gateway-bootstrap.md), "BlockHound". |
| QA-68-1 | The criteria for an ended session, a session without an `entra` client, and a deleted session key in Redis. |
| QA-68-2 | [002](002-gateway-bootstrap.md), "BlockHound": the scope of the entry, and the test for a blocking call in `deserialize()`. |

## Open questions

1. Does the team want a dereferenceable URL as the problem `type` of `CONSENT_REQUIRED`, instead of the URN?

## References

- [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)
- [021 — Entra app registrations](021-entra-app-registrations.md)
- [022 — Session login](022-session-login.md)
- [024 — Authorization manager](024-authorization-manager.md)
- [025 — Token relay and header hygiene](025-token-relay-and-header-hygiene.md)
- [Test app registrations (`infra/azure-test`)](../infra/azure-test/README.md)
- Spring Security reference: OAuth 2.0 Client, authorized client providers (reactive).
- Spring Session reference: reactive Redis.
- Microsoft identity platform: refresh tokens, access tokens, error codes (`AADSTS65001`).
- Redis documentation: `SET` with `NX` and `PX`, distributed locks.
- RFC 9457: Problem Details for HTTP APIs.
