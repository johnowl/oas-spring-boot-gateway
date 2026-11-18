# Security model

This page explains how the gateway protects users and backends: sessions and cookies, CSRF protection, per-audience tokens, the authorization decision, header hygiene, and the log guard. It also lists the duties of operators and backend teams, because the gateway cannot do this work alone. Security reviewers, operators, and backend teams read this page.

The terms are defined in [Concepts](concepts.md#glossary).

## Summary of the controls

| Threat | Control in the gateway | Section |
|---|---|---|
| Token theft in the browser | Tokens stay in the session. The browser gets only `HttpOnly` cookies for the session. | [Session and cookies](#session-and-cookies) |
| A script from one backend uses the session on all backends | No control in the gateway. All backends share one origin. Each backend has duties. | [Shared origin](#shared-origin) |
| Cross-Site Request Forgery (CSRF) | CSRF token in a cookie and a header. `SameSite=Lax` session cookie. | [CSRF protection](#csrf-protection) |
| An undocumented endpoint becomes reachable | Default deny: only operations of the OAS files are reachable. | [Authorization decision](#authorization-decision) |
| A token for one backend reaches another backend | One token for each audience, and an `aud` check for each request. | [Per-audience tokens](#per-audience-tokens) |
| A client spoofs an identity header | The gateway removes known identity and forwarding headers. | [Request headers](#request-headers) |
| A backend changes the browser policy of the gateway site | The gateway removes cookies, CORS headers, and browser policies of backend responses. | [Response headers](#response-headers) |
| A path that the router and the backend read differently | The path firewall rejects encoded slashes, dot segments, and matrix parameters. | [Path firewall](#path-firewall) |
| A token in a log file | The log guard and the log masking. | [Log guard](#log-guard) |

## Session and cookies

The gateway uses the Backend for Frontend (BFF) pattern. After login, the session holds the ID token, the refresh token, and the per-audience access tokens. No token goes to the browser.

The gateway sets two cookies:

| Cookie | Attributes | Purpose |
|---|---|---|
| `__Host-SESSION` | `HttpOnly`, `Secure`, `Path=/`, `SameSite=Lax`, no `Domain` | The session id. |
| `__Host-XSRF-TOKEN` | `Secure`, `Path=/`, no `Domain`. JavaScript can read it. | The CSRF token. |

Example:

```http
Set-Cookie: __Host-SESSION=YjY0...; Path=/; HttpOnly; Secure; SameSite=Lax
```

Facts about the session:

- The prefix `__Host-` stops a sibling subdomain from setting or replacing the cookies.
- `Secure` is always set, also when TLS ends at a load balancer and the gateway sees `http`.
- `SameSite=Lax`, not `Strict`: the Entra ID callback is a cross-site navigation, and it needs the session cookie.
- A session id has 256 random bits. The id changes at login and at logout.
- The gateway removes the `Cookie` header from every request to a backend.

### Session lifetime

| Limit | Property | Default | Behavior |
|---|---|---|---|
| Pre-login timeout | `gateway.security.session.pre-login-timeout` | `5m` | A session without a signed-in user expires after this inactive time. |
| Idle timeout | `gateway.security.session.idle-timeout` | `30m` | A session with a signed-in user expires after this inactive time. |
| Maximum lifetime | `gateway.security.session.max-lifetime` | `8h` | The gateway ends a session this time after the login, also when it is active. |

After a session ends, a request behaves as a request without a session.

### Login and logout

The login uses the authorization code flow with Proof Key for Code Exchange (PKCE), `state`, and `nonce`. [Concepts](concepts.md#login-flow) shows the sequence.

The gateway owns three endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /oauth2/authorization/entra` | Starts the login. |
| `GET /login/oauth2/code/entra` | The callback from Entra ID. |
| `POST /logout` | Ends the session. Needs the CSRF header. |

Any other method or registration id on these paths gets `404`.

A request without a session to a protected operation gets one of these responses:

| Request | Response |
|---|---|
| HTML navigation (`Accept` contains `text/html`) | `302` to `/oauth2/authorization/entra`. The gateway saves the request and redirects back to it after the login. |
| Any other request, for example `fetch` | `401` with a problem body. No redirect, and no new session. |

The gateway saves only an HTML `GET` with a path and query of at most 1,024 bytes. Otherwise, the browser goes to `/` after the login.

At logout, the gateway ends the session, writes a new CSRF token, and redirects to the Entra ID logout endpoint:

```http
HTTP/1.1 302 Found
Location: https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/logout?client_id=<gateway-bff-client-id>&post_logout_redirect_uri=https%3A%2F%2Fgateway.example.com%2F
```

The gateway sends no `id_token_hint`, so the ID token never appears in a URL. Entra ID can therefore show an account picker.

## CSRF protection

- The single-page application (SPA) reads the `__Host-XSRF-TOKEN` cookie.
- The SPA sends the value in the header `X-XSRF-TOKEN` on each state-changing request, for example `POST`, `PUT`, `PATCH`, or `DELETE`.
- The gateway reads the token only from this header. A form field or a query parameter has no effect.
- A missing or wrong token gets `403` with the generic problem body.
- The gateway writes a new token after login and after logout.

Example of a state-changing request:

```bash
curl -X DELETE https://gateway.example.com/orders-api/orders/42 \
  -H 'Cookie: __Host-SESSION=<session-id>; __Host-XSRF-TOKEN=<token>' \
  -H 'X-XSRF-TOKEN: <token>'
```

CSRF protection does not cover `GET`, `HEAD`, and `OPTIONS`. These methods MUST NOT change state. See [Writing routes](writing-routes.md#rule-for-get-head-and-options).

CSRF protection also does not stop a script that runs on the gateway origin. See [Shared origin](#shared-origin).

## Shared origin

The gateway serves the responses of all backends, and the SPA, from one origin. A script that runs on this origin has the rights of the signed-in user:

- It can read the `__Host-XSRF-TOKEN` cookie. JavaScript can read this cookie on purpose.
- It can call every operation of every backend with the session cookie, with the roles of the user.

Example: a backend returns an error page with unescaped user input as `text/html`. A script in that input runs on the gateway origin. It can then call `DELETE /payments-api/payments/7`, if the user has the role.

So a cross-site scripting (XSS) defect in one backend gives access to all backends for that user. The gateway sets `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and `Referrer-Policy: no-referrer`. It sets no `Content-Security-Policy` and no `Content-Disposition` on backend responses. The backend duties in [Backends](#backends) are the control.

## Per-audience tokens

Each backend has its own app registration in Entra ID. Its client id is the audience, in `x-gateway.audience`. The gateway gets one access token for each audience that a user calls.

| Step | Detail |
|---|---|
| Client registration | One registration `entra-<client-id>` for each audience, created at startup from the OAS files. |
| Token request | A refresh token grant with the scope `<client-id>/.default offline_access`. |
| Token validation | Signature, issuer `https://login.microsoftonline.com/<tenant-id>/v2.0` (exact), `ver` `2.0`, `tid`, `exp`, `nbf`, `aud`, and `azp`. A version 1 token fails. |
| Storage | The valid token goes into the session. A new refresh token replaces the old one. |
| Renewal | The gateway renews a token `gateway.security.token-clock-skew` (default `60s`) before its `exp`. |

Limits:

| Limit | Value | Response over the limit |
|---|---|---|
| Grants of one session | 10 in 60 seconds | `429` |
| Grants of one instance | `gateway.security.token-endpoint.grants-per-second` (default `50`) | `503` |
| Token endpoint call | `gateway.security.token-endpoint.timeout` (default `5s`) | `504` |
| Roles in one token | 200 roles, each at most 120 characters | `500` |

The gateway uses only the `roles` claim. It does not check scopes, and it never reads permissions from the ID token.

## Authorization decision

The gateway decides each request in this order. The first row that applies gives the result.

| Order | Condition | Result | Deny reason in the log |
|---|---|---|---|
| 1 | No operation matches the method and the path. | `404` | `NO_MATCH` |
| 2 | The operation is public. | Allowed | None |
| 3 | No signed-in user. | `302` or `401`, see [Login and logout](#login-and-logout) | `NOT_AUTHENTICATED` |
| 4 | The session cannot give a valid token for the audience. | See the next table | Several |
| 5 | The `aud` of the token is not the audience of the operation. | `500` | `WRONG_AUDIENCE` |
| 6 | The `roles` of the token do not satisfy the access rule. | `403` | `MISSING_PERMISSION` |
| 7 | Otherwise. | Allowed. The gateway sends the token to the backend. | None |

Row 1 runs before the gateway reads the session. So an undocumented path or method gets the same `404` for every client.

Token errors (row 4):

| Deny reason | Cause | Response |
|---|---|---|
| `SESSION_INVALID` | The refresh token is missing or not valid any more. The gateway ends the session. | `302` or `401` |
| `REAUTHENTICATION_REQUIRED` | The user needs to sign in again. The gateway ends the session. | `302` or `401` |
| `CONSENT_REQUIRED` | `gateway-bff` has no admin consent for the backend. | `403` with the problem type `urn:oas-spring-boot-gateway:problem:consent-required` |
| `TOKEN_GRANT_LIMIT` | The session reached its grant limit. | `429` |
| `TOKEN_ENDPOINT_TIMEOUT` | No answer from Entra ID in time. | `504` |
| `TOKEN_ENDPOINT_ERROR` | Connection error or `5xx` from Entra ID. | `502` |
| `TOKEN_ENDPOINT_THROTTLED` | Entra ID throttles, or the instance reached its grant limit. | `503` |
| `TOKEN_REQUEST_REJECTED` | Another `4xx` error from Entra ID. | `500` |
| `INVALID_AUDIENCE_TOKEN` | The new token failed the validation. | `500` |
| `INTERNAL_ERROR` | Another error during the decision. | `500` |

### Response bodies

Each error response of the security chain has `Content-Type: application/problem+json` and a fixed body. The body never names a permission or the reason of the decision.

| Status | Body |
|---|---|
| `401` | `{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Authentication required."}` |
| `403` | `{"type":"about:blank","title":"Forbidden","status":403,"detail":"Access denied."}` |
| `403`, missing consent | `{"type":"urn:oas-spring-boot-gateway:problem:consent-required","title":"Consent Required","status":403,"detail":"Consent required."}` |
| `404` | `{"type":"about:blank","title":"Not Found","status":404,"detail":"Not found."}` |
| `429` | `{"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Too many requests."}` |
| `500` | `{"type":"about:blank","title":"Internal Server Error","status":500,"detail":"Internal error."}` |
| `502` | `{"type":"about:blank","title":"Bad Gateway","status":502,"detail":"Bad gateway."}` |
| `503` | `{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"Try again later."}` |
| `504` | `{"type":"about:blank","title":"Gateway Timeout","status":504,"detail":"Gateway timeout."}` |

No response has a `WWW-Authenticate` header.

## Header hygiene

### Request headers

Before the gateway calls a backend, it removes these request headers. The comparison ignores letter case.

- `Cookie` and the `Authorization` header of the client.
- Every header whose name contains `_`, because some servers read `X_User_Id` as `X-User-Id`.
- These exact names: `X-Forwarded-User`, `X-Remote-User`, `Remote-User`, `X-User`, `X-UserId`, `X-Username`, `X-Principal`, `X-Roles`, `X-Groups`, `X-Remote-Group`, `X-Webauth-User`, `X-Id-Token`, `X-Access-Token`, `X-Auth-Token`, `X-Api-Key`, `X-Client-Cert`, `Ssl-Client-Cert`, `X-HTTP-Method-Override`, `X-HTTP-Method`, `X-Method-Override`, `X-Original-URL`, `X-Rewrite-URL`, `X-Envoy-Original-Path`, `X-Override-URL`, `X-Host`, `X-HTTP-Host-Override`, `X-Real-IP`, `X-Client-IP`, `True-Client-IP`, `CF-Connecting-IP`, `X-Cluster-Client-IP`, `Client-IP`, `X-XSRF-TOKEN`, `Forwarded`, `Connection`.
- Every header with one of these prefixes: `X-User-`, `X-Authenticated-`, `X-Ms-Client-Principal`, `X-Ms-Token-`, `X-Auth-Request-`, `X-Amzn-Oidc-`, `X-Goog-Authenticated-`, `X-Arr-`, `X-Waws-`, `X-Original-`, `X-Forwarded-`.

Add your own names and prefixes with `gateway.security.remove-request-headers`. See [Configuration reference](configuration.md#gateway-properties).

Then the gateway adds:

| Header | Value |
|---|---|
| `Authorization` | `Bearer <token of the audience>`, only for a protected operation. |
| `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Port` | From `gateway.public-base-url`, never from the request. |
| `X-Forwarded-For` | The address of the connection peer. With a load balancer in front of the gateway, this is the load balancer, not the client. |

### Response headers

The gateway changes each backend response before it sends it to the browser:

- It removes `Set-Cookie`, `Set-Cookie2`, `Clear-Site-Data`, `Strict-Transport-Security`, `NEL`, `Report-To`, `Reporting-Endpoints`, `Service-Worker-Allowed`, `Alt-Svc`, `Origin-Agent-Cluster`, `Accept-CH`, `Critical-CH`, `WWW-Authenticate`, and every `Access-Control-*` header.
- It sets `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, and `Referrer-Policy: no-referrer`.
- It sets `Strict-Transport-Security: max-age=31536000` when `gateway.public-base-url` uses `https`.
- It sets `Cache-Control: no-store` and `Pragma: no-cache` when the request has a signed-in user, or the response sets a gateway cookie.
- It rewrites an absolute `Location` that points at the upstream of the route to the public URL. It removes an absolute `Location` to any other host. It keeps a relative `Location`.

The gateway does not support Cross-Origin Resource Sharing (CORS). Serve the SPA from the same origin as the gateway. This shares the origin with all backends. See [Shared origin](#shared-origin).

## Path firewall

The gateway answers `400` without a body, before routing, when the raw path contains:

- `%2F` or `%5C` (an encoded `/` or `\`), in any letter case;
- `;`, or `%3B`;
- a percent-encoded unreserved character, for example `%65` for `e`;
- a `\`;
- a `.` or `..` segment, also encoded (`%2e`) or with matrix parameters (`..;x`);
- an invalid percent-encoding.

## Log guard

The log guard runs at startup. Without the Spring profile `local`, it does two things:

1. It stops the startup when a wiretap of the HTTP client or the HTTP server is on.
2. It sets the level `INFO` on each logger that can write a token at `DEBUG` or `TRACE`, and writes one `WARN` line that names them.

The loggers are:

| Logger | What it can write |
|---|---|
| `org.springframework.cloud.gateway.filter.headers.observation` | Request headers to the backend, with the relayed token. |
| `org.springframework.http.codec`, `org.springframework.web.HttpLogging` | Token responses. |
| `org.springframework.web.reactive.function.client` | Token requests. |
| `org.springframework.web.server.adapter.HttpWebHandlerAdapter`, `org.springframework.web.util`, `_org.springframework.web.util` | Request URIs with the authorization code. |
| `org.springframework.security.oauth2` | Login and token responses. |
| `io.lettuce.core.protocol` | Redis commands with sessions, tokens, and the password. |
| `reactor.netty.http.client`, `reactor.netty.http.server` | Connections and wiretap output. |

Always, also with `local`, the log masking replaces these values with `***` in each Logback appender that writes to an output stream:

- the value after `Bearer` or `Basic`, from 8 characters;
- the values of `access_token`, `refresh_token`, `id_token`, `client_secret`, and `code_verifier`;
- the value of `code=`;
- each JSON Web Token (`eyJ...`).

The masking needs Logback. With another logging system, the gateway writes a `WARN` line and does not mask.

## Duties of operators and backend teams

The gateway cannot enforce these rules. Each team MUST check them before production.

### Network and infrastructure

- Backends MUST accept traffic only from the gateway. Then a stolen token cannot reach a backend directly.
- Use an `https` upstream, or keep the network between the gateway and each backend private. See [Writing routes](writing-routes.md#http-upstream-and-tokens).
- Restrict the management port `8081` with a network policy. The actuator has no authentication. The network policy is the only control.
- Expose only `health`, and `metrics` if you need it. Never expose `heapdump`, `threaddump`, `env`, `configprops`, `loggers`, `sessions`, or `*`. The gateway does not check `management.endpoints.web.exposure.include`. See [Operations](operations.md#ports).
- Load OAS files only from `gateway/src/main/resources/openapi/` in production. If you use a `file:` location, the CI validation, the breaking-change review, and the access-change review do not apply. Then you MUST put these files under a review process of the same strength.
- The edge in front of the gateway is a load balancer or a web application firewall. It MUST limit the request rate for each client address on two kinds of requests: `/oauth2/authorization/**`, and HTML navigations without a session cookie. Both create a session.
- With the store `memory`, turn off heap dumps, or encrypt and restrict them. The heap holds the tokens of all sessions.
- With the store `redis`, follow the Redis rules in [Operations](operations.md#use-the-redis-session-store).

### Backends

- A backend MUST authorize only on the `roles` claim, not on `scp`.
- A backend MUST NOT use `X-Forwarded-For` to identify a client.
- A backend MUST NOT honor method or URL override inputs, for example the query parameter `_method`. The gateway removes only the known headers.
- A `GET`, `HEAD`, or `OPTIONS` operation MUST NOT change state.
- A backend MUST NOT return active content (HTML, SVG, or JavaScript) that it builds from user input. See [Shared origin](#shared-origin).
- A backend MUST set an exact `Content-Type` on each response, for example `application/json`.
- A backend MUST send each file download with `Content-Disposition: attachment`.
- A backend MAY validate the token again.

### Entra ID

- Configure the app registrations as described in [Microsoft Entra ID setup](entra-id-setup.md).
- Audit the grants of `gateway-bff` at a regular interval. See [Grant audit](entra-id-setup.md#audit-the-grants-of-gateway-bff).
- Rotate the client secret of `gateway-bff` before it expires.

### Pull request review

- Check that each `upstream` is the backend of its audience. The gateway sends the token of the audience to that upstream.
- Check each access rule change. See [CI and change control](ci-change-control.md).

## Known limits

- The gateway does not encrypt session values in Redis. This is an accepted risk in spec 022.
- The gateway does not revoke the refresh token at logout.
- When an administrator removes an App Role, the role stays in existing access tokens until they expire.
- The CLI does not check that an audience or a permission exists in Entra ID. With a typo, the gateway denies the requests at runtime. A wrong permission gives `403`. A wrong audience gives a token request error.
- The gateway sets no `Content-Security-Policy` on backend responses. See [Shared origin](#shared-origin).
- The actuator on the management port has no authentication, and the gateway does not limit which endpoints an operator exposes.
- The log guard runs only at startup. It does not stop a change of log levels at runtime, for example through an exposed `loggers` endpoint.
- The change-control checks are advisory, and they have gaps. See [Limits of the checks](ci-change-control.md#limits-of-the-checks).

## Related pages

- [Configuration reference](configuration.md)
- [Operations](operations.md)
- Specs: [022 — Session login](../specs/022-session-login.md), [023 — Per-audience tokens](../specs/023-per-audience-tokens.md), [024 — Authorization manager](../specs/024-authorization-manager.md), [025 — Token relay and header hygiene](../specs/025-token-relay-and-header-hygiene.md).
