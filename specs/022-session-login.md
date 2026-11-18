# 022 — Session login

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 7 — Session authentication and authorization in the gateway |
| Depends on | [013](013-oas-route-locator.md), [018](018-authorization-extension.md), [021](021-entra-app-registrations.md) |
| Modules | `gateway` |

## Summary

This spec adds browser login to the gateway with the Backend for Frontend (BFF) pattern. The gateway signs in users with OpenID Connect (OIDC) against Microsoft Entra ID. It keeps all tokens on the server, in a Spring Session. By default, the session store is the memory of the gateway instance. Redis is optional (see "Session store"). The browser receives only the `__Host-SESSION` cookie and the `__Host-XSRF-TOKEN` cookie. The spec also adds the entry point (redirect or 401), Cross-Site Request Forgery (CSRF) protection, and OIDC logout. Until [024](024-authorization-manager.md) arrives, a temporary rule applies: every non-public operation requires an authenticated user.

## Motivation

Tokens in browser storage are exposed to cross-site scripting. With the BFF pattern, JavaScript never sees a token. The gateway needs a login flow, a session store, and protections that cookie authentication requires (CSRF, cookie attributes). Later specs ([023](023-per-audience-tokens.md), [024](024-authorization-manager.md), [025](025-token-relay-and-header-hygiene.md)) build on this session.

## Scope

### In scope

- `SecurityWebFilterChain` for session authentication (`SessionSecurityConfiguration`).
- `oauth2Login` with the client registration `entra` (app `gateway-bff`), Proof Key for Code Exchange (PKCE), state, and nonce.
- Spring Session with an in-memory store (default) or with reactive Redis (optional). Session cookie attributes.
- `ServerOAuth2AuthorizedClientRepository` stored in the session.
- Entry point: redirect for HTML navigation, 401 for other requests. Request cache for HTML navigation only.
- Pre-login session timeout, session idle timeout, and maximum session lifetime.
- Login failure redirect.
- CSRF protection with `CookieServerCsrfTokenRepository` and the header `X-XSRF-TOKEN` only.
- Logout: local session invalidation and redirect to Entra logout.
- Limits of the in-memory store, protection of Redis, and limits for anonymous sessions.
- Temporary rule: public operations are open; all other requests require authentication.

### Out of scope

- Tokens for backend audiences. See [023](023-per-audience-tokens.md).
- Permission checks, `aud` checks, and 403 responses. See [024](024-authorization-manager.md).
- Token relay and removal of headers. See [025](025-token-relay-and-header-hygiene.md).
- Bearer token authentication for services. See [026](026-service-to-service-bearer.md).
- Serving the single-page application (SPA) files.
- Rate limiting in the gateway. This spec asks for rate limiting at the edge (see "Anonymous sessions").

## Design

### Security invariants

- The browser MUST receive only the `__Host-SESSION` cookie and the `__Host-XSRF-TOKEN` cookie. Responses MUST NOT contain an ID token, access token, or refresh token.
- The `__Host-SESSION` cookie MUST have the attributes `HttpOnly`, `Secure`, `Path=/`, and `SameSite=Lax`, and no `Domain` attribute. See "Session and cookies" for the reason for `Lax`.
- The gateway MUST change the session id after a successful login (protection against session fixation).
- The ID token MUST NOT be forwarded to backends, MUST NOT be used for authorization, and MUST NOT appear in a URL. The gateway uses it only for identity.
- State-changing requests (`POST`, `PUT`, `PATCH`, `DELETE`) MUST have a valid CSRF token in the header `X-XSRF-TOKEN`. The gateway MUST NOT accept the token from a form field or a query parameter.
- The gateway MUST create a new CSRF token at login and at logout.
- A `GET`, `HEAD`, or `OPTIONS` operation MUST NOT change state. See "Rule for safe methods".
- The login failure path and the post-logout redirect path MUST follow the rules of "Rules for redirect paths". The gateway does not start if one of them breaks a rule.
- The gateway MUST build the OAuth2 redirect URI, the post-logout redirect URI, and the login failure redirect from `gateway.public-base-url`. It MUST NOT build them from request headers (`Host`, `Forwarded`, `X-Forwarded-*`).
- The gateway MUST NOT serve the default login page of Spring Security.
- The `__Host-SESSION` cookie and the `__Host-XSRF-TOKEN` cookie MUST NOT reach a backend. A backend MUST NOT set a cookie in the browser. See "Cookies and backends".
- The SPA MUST be served from the same site as the gateway. Decision (2026-11-10): the gateway does not support Cross-Origin Resource Sharing (CORS). An undocumented `OPTIONS` request gets `404` from `UndocumentedOperationWebFilter` ([024](024-authorization-manager.md)).

### Login flow

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant G as Gateway
    participant R as Session store
    participant E as Entra ID
    B->>G: GET /app (Accept: text/html), no session
    G->>R: Save request in a pre-login session (TTL 5 min)
    G-->>B: 302 Location: /oauth2/authorization/entra
    B->>G: GET /oauth2/authorization/entra
    G->>R: Save authorization request (state, nonce, PKCE verifier)
    G-->>B: 302 to Entra authorize endpoint (code_challenge, state, nonce)
    B->>E: User signs in
    E-->>B: 302 /login/oauth2/code/entra?code&state
    B->>G: GET /login/oauth2/code/entra?code&state
    G->>E: POST token endpoint (code, code_verifier, client credential)
    E-->>G: ID token, Graph access token, refresh token
    G->>G: Validate ID token (signature, iss, aud, nonce, exp)
    G->>R: New session id, idle timeout 30 min; store security context and authorized client
    G-->>B: 302 to saved request, Set-Cookie: __Host-SESSION, new __Host-XSRF-TOKEN
```

The login response from Entra contains three tokens:

| Token | `aud` | Use in the gateway |
|---|---|---|
| ID token | `gateway-bff` client id | Identity (`oid`, `name`, `preferred_username`, `tid`). Never forwarded. Never used for authorization. Never put in a URL. |
| Access token | Microsoft Graph | Ignored. Third parties cannot validate it. |
| Refresh token | Not applicable | Stored in the session. [023](023-per-audience-tokens.md) redeems it for backend tokens. |

The principal name is the `oid` claim (`user-name-attribute: oid`).

A session can have a second login, for example of another user on the same browser. The login success handler removes all per-audience authorized clients (`entra-*`, [023](023-per-audience-tokens.md)) of the session, so the new user never gets a token of the earlier user. The new `entra` client of the login stays. The handler saves the session with its new id first, because the session store keeps the token state under the old id until the save (finding QA-57-2 of the review of PR #57).

### Security chain sketch

This sketch shows the intent. The project uses Spring Security 7 ([001](001-project-skeleton.md)). The implementer verifies the method names against this version.

```java
@Bean
SecurityWebFilterChain sessionSecurityChain(ServerHttpSecurity http,
        ReactiveClientRegistrationRepository registrations,
        ServerOAuth2AuthorizedClientRepository authorizedClients,
        PublicOperationsMatcher publicOperations) {
    return http
        .authorizeExchange(ex -> ex
            .matchers(publicOperations).permitAll()        // temporary; replaced in 024
            .anyExchange().authenticated())
        .oauth2Login(login -> login
            .authorizationRequestResolver(entraOnlyPkceResolver(registrations))
            .authorizedClientRepository(authorizedClients))
        .requestCache(rc -> rc.requestCache(htmlOnlyRequestCache()))
        .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint()))
        .csrf(csrf -> csrf
            .csrfTokenRepository(hostPrefixedCookieCsrfRepository())
            .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler()))
        .logout(logout -> logout
            .logoutSuccessHandler(entraLogoutSuccessHandler(registrations)))
        .build();
}
```

| Element | Implementation |
|---|---|
| PKCE | `EntraAuthorizationRequestResolver` wraps `DefaultServerOAuth2AuthorizationRequestResolver` with `OAuth2AuthorizationRequestCustomizers.withPkce()`, so PKCE does not depend on a client setting. State, nonce, and code verifier come from `SecureRandom`, which can read `/dev/urandom`. The resolver therefore runs on the `boundedElastic` scheduler, not on the event loop. Session ids do not come from this resolver. See "Session and cookies". |
| Registration check | The resolver accepts only the registration id `entra`. For each other registration id, it returns an empty result, and the request continues the filter chain (verified in Spring Security 7.1). [024](024-authorization-manager.md) then returns `404` (`NO_MATCH`). |
| State and nonce | Default behavior of `oauth2Login` with the `openid` scope. |
| Authorized client repository | Stored in the session. [023](023-per-audience-tokens.md) defines how the gateway avoids lost updates between parallel requests. |
| `PublicOperationsMatcher` | Matches method and path of each operation with `security: []`. Built once at startup from the access rules of [018](018-authorization-extension.md). PR 1 of [024](024-authorization-manager.md) replaces it with `SecurityIndex` and the chain uses `anyExchange().access(gatewayAuthorizationManager)`. `SecurityIndex.isNonPublicGet` checks the redirect paths. |
| Request cache | `WebSessionServerRequestCache` with a save matcher (`SavedRequestMatcher`) for `GET` requests that accept `text/html`. The matcher does not save a request with the deny reason `NO_MATCH`, `SESSION_INVALID`, or `REAUTHENTICATION_REQUIRED` ([024](024-authorization-manager.md)). It saves only the path and the query, and only if they have at most 1,024 bytes. It does not save a path that starts with `//`, because after login the browser reads `//host` as another host (open redirect). See "Anonymous sessions". |
| Login failure | `RedirectServerAuthenticationFailureHandler` with `gateway.public-base-url` plus the login failure path (default `/?login=failed`). See "Login failure". |
| CSRF token repository | `CookieServerCsrfTokenRepository` with the cookie name `__Host-XSRF-TOKEN`, `HttpOnly=false`, `Secure`, `Path=/`, and no `Domain`. |
| CSRF token request handler | `HeaderOnlyCsrfTokenRequestHandler`: reads the token only from the header `X-XSRF-TOKEN`. The default handler also reads the form field `_csrf`. |
| CSRF access denied handler | Writes the generic 403 problem body of [024](024-authorization-manager.md). The default handler of Spring Security writes `text/plain`. |
| Logout success | `EntraLogoutSuccessHandler`: a custom handler that sends no `id_token_hint`. See "Logout". |
| Logout handlers | The default handler that clears the security context, and `WebSessionServerLogoutHandler`, which invalidates the session. |
| User info | The gateway takes the identity from the ID token only. It does not call the user info endpoint: for Entra ID it is in Microsoft Graph, and the gateway ignores the Graph access token. |
| Startup checks | The registration `entra` must exist, use `authorization_code` and the scope `openid`, and have a client secret. Its redirect URI must be `gateway.public-base-url` plus `/login/oauth2/code/{registrationId}`. The provider metadata must have an `end_session_endpoint` without a query. The gateway does not start otherwise. |

### Entry point

`DelegatingServerAuthenticationEntryPoint` selects the response:

| Request | Response |
|---|---|
| The deny reason is `NO_MATCH` ([024](024-authorization-manager.md)) | `404` with the generic 404 body of [024](024-authorization-manager.md). The entry point checks this first. |
| `Accept` contains `text/html` (navigation). Matcher: `MediaTypeServerWebExchangeMatcher(TEXT_HTML)` with `MediaType.ALL` ignored. | `302 Found`, `Location: /oauth2/authorization/entra`. The request cache saves the request. |
| Any other request (fetch or XHR) | `401 Unauthorized` with the problem body below. No redirect. The request cache does not save the request. |

The SPA handles a 401 and navigates the browser to `/oauth2/authorization/entra`. A backend can also return `401`. The gateway forwards it without `WWW-Authenticate`, and a new login does not fix it ([025](025-token-relay-and-header-hygiene.md), "Response headers"). How the SPA tells it from the gateway `401` is an open question of [025](025-token-relay-and-header-hygiene.md).

The deny reason is in the exchange attribute `GatewaySecurityAttributes.DENY_REASON`. This spec defines the attribute and the enum `DenyReason` with the values that the entry point and the request cache read: `NO_MATCH`, `SESSION_INVALID`, and `REAUTHENTICATION_REQUIRED`. [024](024-authorization-manager.md) adds the other values and sets the attribute. Before [024](024-authorization-manager.md), no component sets it.

The 401 body uses the format `application/problem+json` of RFC 9457. The body is the same for every 401 of this chain. Its `detail` is a fixed generic text. It contains no detail about the reason, the session, or the gateway internals:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json

{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Authentication required."}
```

### Login failure

The login fails when the callback has a wrong `state`, a wrong `nonce`, or an invalid ID token. It also fails when Entra returns an error, for example when the user cancels the sign-in. It also fails when the pre-login session expired before the callback (see "Session lifetime"). Then the gateway does these steps:

1. It does not create an authenticated session.
2. It responds `302 Found` with `Location: <public base URL>/?login=failed`, for example `https://gateway.example.com/?login=failed`. The path is the value of `gateway.security.login.failure-path`.

The SPA reads the query parameter `login=failed` and shows a message.

The gateway does not use the default login page of Spring Security (`/login`). `UndocumentedOperationWebFilter` ([024](024-authorization-manager.md)) does not exclude `/login`, so a request to `/login` gets `404`. The implementer verifies how to turn off the default login page in Spring Security 7.

### Rules for redirect paths

These rules apply to `gateway.security.login.failure-path` and `gateway.security.logout.post-logout-redirect-path`. The gateway checks them at startup and does not start if a value breaks a rule.

| Rule | Reason |
|---|---|
| The path part (before `?`) is a `path-absolute` of RFC 3986: it starts with one `/`, and it does not start with `//`. | Prevents an open redirect to another host. |
| An optional query follows the path part after one `?`. | Allows `/?login=failed`. |
| The value contains no scheme, no host, no fragment (`#`), no `\`, no `%`, no `;`, no space, and no control character. It contains only printable ASCII characters. | A browser can read `\` as `/`. `%` can hide these characters. A non-ASCII character cannot be percent-encoded without `%`. Spring removes a path parameter that starts with `;` before it matches a path, so `/login;x` is `/login`. |
| The path part has no `.` or `..` segment. | A browser removes dot segments, so `/../oauth2/authorization/entra` is a Spring Security path. |
| The path part is not `/login` or `/logout`, and it does not start with `/oauth2/`, `/login/`, or `/logout/`. | These paths belong to Spring Security. A redirect there can start a loop. |
| A `GET` of the path part does not match a non-public operation in the access rules ([018](018-authorization-extension.md)). | The redirect target must be reachable without a session. A browser follows a redirect with `GET`. |

Examples:

| Value | Result |
|---|---|
| `/?login=failed` | Accepted |
| `/signed-out` | Accepted |
| `//evil.example.com/` | Rejected: starts with `//` |
| `/\evil.example.com` | Rejected: contains `\` |
| `/%2F%2Fevil.example.com` | Rejected: contains `%` |
| `https://evil.example.com/` | Rejected: contains a scheme |
| `/oauth2/authorization/entra` | Rejected: Spring Security path |
| `/../oauth2/authorization/entra` | Rejected: contains a `..` segment |
| `/login;x` | Rejected: contains `;` |

### Session and cookies

The session uses Spring Session. "Session store" describes where the sessions are. The cookie is configured with an explicit `CookieWebSessionIdResolver` bean:

```java
@Bean
WebSessionIdResolver webSessionIdResolver() {
    var resolver = new CookieWebSessionIdResolver();
    resolver.setCookieName("__Host-SESSION");
    resolver.addCookieInitializer(c -> c.httpOnly(true).secure(true).sameSite("Lax").path("/"));
    return resolver;
}
```

`Secure` is set explicitly because TLS can end at a load balancer. Then the gateway sees `http` and the default would omit `Secure`.

Both cookies use the prefix `__Host-`. A browser accepts a `__Host-` cookie only with `Secure`, `Path=/`, and no `Domain`. A sibling subdomain therefore cannot set or replace these cookies (cookie tossing). Browsers treat `http://localhost` as a secure context. The implementer verifies that local runs on `http://localhost` keep the cookies in the browsers that the team uses.

Session ids have 256 random bits from the `DRBG` generator of the JDK (`DrbgSessionIdGenerator`), in both session stores. The generator asks for a security strength of 256 bits (`DrbgParameters.instantiation(256, RESEED_ONLY, null)`); the default strength of the JDK is 128 bits. The generator takes its seed once, at startup. The session id changes at login and at logout, on the event loop. The default generator of Spring Session can read `/dev/urandom` for each id, and that read blocks the event loop.

Reason for `SameSite=Lax` and not `Strict`: the Entra callback (`GET /login/oauth2/code/entra`) is a cross-site top-level navigation from the Entra sign-in page. With `Strict`, the browser does not send the session cookie with the callback. The gateway then cannot find the saved authorization request, the `state` check fails, and every login fails. `Lax` sends the cookie on a cross-site top-level `GET`, and not on a cross-site `POST`. The CSRF token protects the state-changing methods, and "Rule for safe methods" protects the rest.

Example response header after login:

```http
Set-Cookie: __Host-SESSION=YjY0...; Path=/; HttpOnly; Secure; SameSite=Lax
```

### Session store

Decision of the product owner (2026-11-13): the basic gateway keeps the sessions in memory. Redis is optional. The property `gateway.session.store` selects the store:

| Value | Store |
|---|---|
| `memory` (default) | The memory of each gateway instance. No Redis. |
| `redis` | Redis, shared by all gateway instances. |

The gateway does not start with another value.

Both stores have the same behavior for the user: the same cookies, the same session ids ("Session and cookies"), and the same limits ("Session lifetime").

#### Store `memory`

- `MemorySessionRepository` is the reactive session repository of Spring Session. It works like `ReactiveMapSessionRepository` of Spring Session and adds a limit. Spring Session uses it through `@EnableSpringWebSession`.
- A new session gets the pre-login timeout. A session expires after its maximum inactive interval, as in "Session lifetime".
- A task on its own thread removes the expired sessions every minute. It never runs on the event loop. A request never finds an expired session: the repository removes it when it reads it.
- The store holds at most `gateway.session.memory.max-sessions` sessions (default 100,000). With parallel requests, it can hold a few sessions more for a short time.
- When the store is full and needs room for a new session, it removes the oldest pre-login session first. A pre-login session has no authenticated user. A removed pre-login session behaves like an expired session.
- When the store is full and holds only authenticated sessions, it refuses the new session. The request gets `503` with the problem body below. The response has no `Location` header and no `__Host-SESSION` cookie. The gateway writes one `WARN` log line each minute while this happens.
- A request that needs no new session still works when the store is full: a public operation, a fetch request that gets `401`, and a request with a valid session.
- The gateway opens no Redis connection. `RedisAutoConfigurationFilter` turns off the Redis auto-configurations of Spring Boot, so the gateway has no Redis connection factory and starts without Redis. The Redis libraries stay on the class path.

Reason for the behavior of a full store: anonymous traffic can create pre-login sessions (see "Anonymous sessions"). If the store removed authenticated sessions to make room, anonymous traffic could log out real users. The store therefore removes only pre-login sessions. A user who is in the middle of a login can lose the pre-login session and gets `?login=failed`. When only authenticated sessions remain, no anonymous request can remove them, and a new user gets `503` until a session ends.

The `503` body uses the format of the other problem bodies:

```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/problem+json

{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"Try again later."}
```

Implementation: the session manager of Spring WebFlux saves a session just before the response starts. `SessionStoreFullWebFilter`, the first `WebFilter`, starts the response itself. A refused save therefore reaches this filter, and the filter writes the `503` body instead of the response that the chain prepared.

Limitations of `memory`:

- Sessions are per instance. A session exists only on the instance that created it.
- With several instances, the load balancer MUST send all requests of a browser to the same instance (sticky sessions, also called session affinity). Without affinity, a request on another instance has no session: the user gets `401` or a new login, and a login fails when the callback reaches another instance.
- A restart or a deploy of an instance ends all sessions of that instance. Its users must log in again.
- Use `redis` for several instances without affinity, or to keep sessions during a deploy.
- The tokens of all sessions are in the heap of the gateway process. Heap dumps MUST be off, or encrypted and restricted like access to Redis (see "Redis protection").

#### Store `redis`

- Spring Session with reactive Redis (`spring-session-data-redis` and `spring-boot-starter-data-redis-reactive`) and the non-indexed reactive repository.
- These sections apply only to this store: "Redis protection", "Redis ACL", and "Session serialization". They include the TLS connection, the ACL user, the JSON serialization, the Redis connection at startup, and the minimum Redis version.
- The `spring.data.redis.*` settings apply only to this store.

### Cookies and backends

The gateway removes the `Cookie` header from every request to a backend. Each route from an OAS file has the filter `RemoveRequestHeader=Cookie`. The gateway also removes `Set-Cookie` and `Set-Cookie2` from every backend response. `BackendResponseHeadersFilter` (before [025](025-token-relay-and-header-hygiene.md): `BackendCookieHeadersFilter`), a response `HttpHeadersFilter` of Spring Cloud Gateway, removes them before the gateway copies the backend headers into the client response.

The gateway writes its own cookies (`__Host-SESSION` and `__Host-XSRF-TOKEN`) when it sends the response, after this filter. The filter therefore does not remove them. [025](025-token-relay-and-header-hygiene.md) keeps these two rules and adds the other header rules: it removes the site-wide browser headers and `WWW-Authenticate` of the backend, sets `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, and `Strict-Transport-Security`, forces `Cache-Control: no-store` on a response with a gateway cookie or to an authenticated request, and rewrites a backend `Location`.

### Session lifetime

| Limit | Default | Behavior |
|---|---|---|
| Pre-login timeout | 5 minutes | Maximum inactive interval of a session that has no authenticated user. Example: the session that holds the saved request and the authorization request. The user has 5 minutes to complete the sign-in at Entra. |
| Idle timeout | 30 minutes | After a successful login, the gateway raises the maximum inactive interval of the session to this value. A session without a request for this time expires. |
| Maximum session lifetime | 8 hours | The gateway stores the login time in the session after a successful login. On each request with an authenticated session, if the login time is older than this limit, the gateway invalidates the session. If the login time attribute is missing, the gateway treats the session as expired and invalidates it. |

After a session expires or the gateway invalidates the session, the request behaves as a request without a session: `302` for HTML navigation, `401` otherwise. The request cache saves the HTML navigation, and a new login works.

Implementation (verified with Spring Session 4.1): Spring Session has no maximum lifetime. `SessionLifetimeWebFilter` in the security chain does the check. At login, it stores the login time in the session attribute `gateway.session.login-time` (epoch milliseconds) and calls `WebSession.setMaxIdleTime` with the idle timeout. With the store `redis`, Spring Session then sets the time to live of the Redis key to the idle timeout. With the store `memory`, the repository uses the maximum inactive interval of the session. The pre-login timeout is the timeout of a new session: `MemorySessionRepository` sets it, and for Redis a `SessionTimeout` bean gives it to Spring Session.

When `SessionLifetimeWebFilter` invalidates a session, the rest of the chain gets a new, empty session from the `WebSessionManager`. The saved request of an HTML navigation and the authorization request of a new login go into this new session. The response sets the cookie of the new session only if the session has attributes. Otherwise, it deletes the `__Host-SESSION` cookie.

The filter reads the time from a `Clock` bean if one exists, else from the system clock. Tests use a clock that they move forward.

### Redis protection

This section applies only to the store `redis`.

Redis is on the critical path: every authenticated request reads the session. Redis MUST be highly available and close to the gateway.

What a person or process with read access to Redis can do:

- Hijack sessions. The key names contain the session ids, for example `spring:session:sessions:<id>`. The `__Host-SESSION` cookie carries the same id. With a key name, an attacker can build a valid session cookie.
- Read access tokens in clear text. An access token is valid at its backend until it expires.
- Read refresh tokens in clear text. A redemption of a refresh token also needs the client secret of `gateway-bff`.

What a person or process with write access to Redis can do:

- Forge sessions, for example write a security context for another user into a session.
- With Java serialization of the session values, run code in the gateway when it reads a crafted value. The JSON serialization of "Session serialization" removes this risk. It does not remove the risk of forged sessions.

Rules:

- The connection to Redis MUST use TLS.
- The gateway MUST authenticate to Redis with a Redis access control list (ACL) user and a password.
- The ACL user MUST be limited to the key pattern `~spring:session:*`. The lock keys and the grant counters of [023](023-per-audience-tokens.md) use this namespace too.
- The ACL user MUST use an allowlist of commands: `-@all`, then only the commands that the gateway needs. It MUST have no Pub/Sub channels (`resetchannels`). See "Redis ACL".
- The gateway MUST use the non-indexed reactive session repository of Spring Session. It MUST NOT configure Redis keyspace notifications: the bean `ConfigureReactiveRedisAction.NO_OP` turns off the `CONFIG SET` call. The ACL user cannot run `CONFIG`.
- The Redis server MUST be a version with the fixes for the vulnerabilities of the script engine, for example CVE-2025-49844. Minimum versions: 7.2.11, 7.4.6, 8.0.4, or 8.2.2, or a managed service with the same fixes. The gateway needs scripts ([023](023-per-audience-tokens.md)).
- Redis MUST be on a private network. Only the gateway can reach it.
- Redis persistence (RDB snapshots and the append-only file) and Redis backups MUST be off. If the platform requires them, they MUST be encrypted, and access to them MUST be restricted like access to Redis.
- Backends MUST accept traffic only from the gateway ([025](025-token-relay-and-header-hygiene.md)). Then a stolen access token cannot reach a backend directly.
- Redis MUST have a `maxmemory` limit and the eviction policy `volatile-ttl`. Every key of the gateway has a time to live. Under memory pressure, Redis first evicts the keys that expire soonest: lock keys (10 seconds), then pre-login sessions (at most 5 minutes). Sessions of active users have the longest time to live, so Redis evicts them last. An evicted session behaves like an expired session. An evicted lock key is safe ([023](023-per-audience-tokens.md), "Compare-and-set of the refresh token").
- The team MUST have an alert on the Redis metric `evicted_keys`. Each eviction means that Redis reached `maxmemory`.
- The gateway does not encrypt the session values at application level. This is an accepted risk. See "Decisions (2026-11-13)".
- The session values MUST NOT contain the client secret of `gateway-bff`. See "Session serialization".
- The gateway opens its Redis connection at startup. It does not start if Redis is not reachable. Reason: the Redis client opens its first connection with a blocking wait; at startup this wait is on the main thread, not on the event loop.
- The actuator health check does not include Redis. The Redis health check of Spring Boot sends `INFO`, and the ACL user cannot run `INFO`. See "Open questions".

Before production, the team MUST review these two measures:

- Envelope encryption of the session values, with a key in Azure Key Vault.
- Revocation of the refresh token at logout.

### Redis ACL

This section applies only to the store `redis`.

Example of the ACL user. The implementer verified the list with Spring Session 4.1 and Lettuce 7.5: during startup, login, requests, and logout, the Redis `ACL LOG` stays empty for this user. The deployment checklist records the final list. [023](023-per-audience-tokens.md) verifies the commands of its scripts.

```text
ACL SETUSER gateway on >${REDIS_PASSWORD} resetchannels ~spring:session:* -@all
  +auth +hello +ping +select +client|setname +client|setinfo
  +hgetall +hget +hset +hmset +hdel +del +exists +expire +pexpire +rename
  +set +get +incr +evalsha +script|load
```

`+get` and `+incr` are for the scripts of [023](023-per-audience-tokens.md): a Redis script runs its commands with the permissions of the ACL user. The lock release uses `GET` and `DEL` in a script, and the grant counter uses `GET`, `INCR`, and `PEXPIRE` in a script. The implementer verified the full list with the integration tests of [023](023-per-audience-tokens.md) (2026-11-13).

The gateway sends only `SCRIPT LOAD` and `EVALSHA`, never `EVAL`, so the ACL user has no `+eval`. This is not a security control: a user with `+script|load` and `+evalsha` can run any script, as with `+eval`. The real controls are the key pattern and the command list of the ACL user (a script runs its commands with the permissions of the user), and the minimum Redis versions of "Redis protection" (correction after the review of PR #57, finding SEC-57-4).

These commands MUST be denied for the ACL user: `KEYS`, `SCAN`, `MONITOR`, `RANDOMKEY`, `SLOWLOG`, `PSUBSCRIBE`, `SUBSCRIBE`, `CONFIG`, `FLUSHALL`, `FLUSHDB`, `REPLICAOF`, `MODULE`, `DEBUG`, `SHUTDOWN`.

### Session serialization

This section applies only to the store `redis`. The store `memory` keeps the session values as Java objects and never serializes them.

The session values use JSON, not Java serialization. Reason: with Java serialization, a value that someone writes into Redis can run code in the gateway when the gateway reads it.

- The serializer is the `RedisSerializer` bean of Spring Session (`springSessionDefaultRedisSerializer`) with Jackson 3 and the Jackson modules of Spring Security 7 (`SecurityJacksonModules`).
- A type validator allows only the types that the session holds. The gateway adds no type to the types of the Spring Security modules. Examples: the security context with `OAuth2AuthenticationToken` and `DefaultOidcUser`, the `OAuth2AuthorizationRequest`, the `OAuth2AuthorizedClient` of [023](023-per-audience-tokens.md), the saved request (a string), and the login time (a number). A value with any other type, for example `java.net.URL`, fails to read.
- A value that fails to read makes the session invalid. Session metadata that fails to read (missing, or of another type) also makes the session invalid. The repository deletes the session key, and the request behaves as a request without a session.
- The serializer never writes the client secret. An `OAuth2AuthorizedClient` holds its `ClientRegistration`, and the Spring Security module writes all fields of it, also `clientSecret`. Without this rule, each session in Redis would hold the client secret of `gateway-bff`, and a refresh token from Redis would be enough to get tokens. A `ClientRegistration` that the gateway reads from a session has no client secret. Code that needs the secret ([023](023-per-audience-tokens.md)) takes the registration from the `ReactiveClientRegistrationRepository`.
- The ID token decoder keeps the claims in JSON types: `iss` stays a string, each object is a map, and each array is a list. Spring Security converts `iss` to `java.net.URL`, which is not in the allowlist: without this rule, no session would load after login. `OidcIdToken.getIssuer()` still returns a URL.

### Anonymous sessions

Anonymous traffic can create sessions: each HTML navigation without a session saves a request, and each `GET /oauth2/authorization/entra` saves an authorization request. These sessions fill the session store. The gateway limits them:

- A session without an authenticated user lives at most for the pre-login timeout (5 minutes). See "Session lifetime".
- A request to a public operation, and a fetch request that gets `401`, MUST NOT create a session.
- With the store `memory`, the store has a maximum number of sessions and removes pre-login sessions first (see "Session store"). The team MUST alert on the `WARN` log line of a full store.
- With the store `redis`, the team MUST monitor the number of sessions. The source is the key count of the Redis database in the Redis monitoring of the platform. The gateway does not scan Redis, because the ACL user cannot run `KEYS` or `SCAN`. An alert fires above a threshold (see "Open questions").
- The edge in front of the gateway (load balancer or web application firewall) MUST limit the request rate for each client address. The limit applies to `/oauth2/authorization/**` and to HTML navigations without a `__Host-SESSION` cookie, because both create a session. The team sets the limits during the deployment.
- The saved request holds only the path and the query of the request, and at most 1,024 bytes. A longer request is not saved; after login, the browser goes to `/`.

### CSRF

- Cookie `__Host-XSRF-TOKEN`: readable by JavaScript, `Secure`, `Path=/`, no `Domain`.
- Header `X-XSRF-TOKEN`. The gateway reads the token only from this header. A form field `_csrf` or a query parameter has no effect.
- The SPA reads the cookie and sends the header on each state-changing request.
- Since version 6, Spring Security writes the CSRF cookie only when a component subscribes to the token. A small `WebFilter` (`CsrfCookieWebFilter`) subscribes to the `CsrfToken` exchange attribute so that the cookie is always present. This follows the SPA guidance of the Spring Security reference; verify it for Spring Security 7.
- After a successful login and after logout, the gateway writes a new `__Host-XSRF-TOKEN` cookie. The old token is no longer valid. The gateway does not store the token: a token is valid when it equals the `__Host-XSRF-TOKEN` cookie of the request. After the new cookie, the browser no longer sends the old value, so the old token fails. Spring Security 7 deletes the cookie at logout and does nothing at login. `RotatingCsrfTokenRepository` writes a new token when Spring Security clears the token, and the login success handler clears the token. The response then has exactly one `__Host-XSRF-TOKEN` cookie.
- A missing or wrong token returns `403 Forbidden` with the generic 403 problem body of [024](024-authorization-manager.md) and `Content-Type: application/problem+json`.

### Rule for safe methods

CSRF protection does not cover `GET`, `HEAD`, and `OPTIONS`. `SameSite=Lax` sends the session cookie on a cross-site top-level `GET`. A `GET` operation that changes state is therefore open to CSRF.

Rule for OAS authors and backend teams: an operation with the method `GET`, `HEAD`, or `OPTIONS` MUST NOT change state. Example: `GET /orders-api/orders/42/cancel` is not allowed. Use `POST /orders-api/orders/42/cancel`.

The gateway cannot check this rule. The review of the OAS file is the control. See "Open questions" for a CLI rule.

### Logout

1. The SPA sends `POST /logout` with the CSRF header.
2. The gateway invalidates the `WebSession` in the session store and clears the security context.
3. The gateway writes a new `__Host-XSRF-TOKEN` cookie.
4. The gateway responds `302 Found` to the Entra logout endpoint with exactly two parameters: `client_id` (the `gateway-bff` client id) and `post_logout_redirect_uri`.
5. Entra signs out the user and redirects the browser to the post-logout redirect URI.

The gateway sends no `id_token_hint`, because the ID token MUST NOT appear in a URL. The `OidcClientInitiatedServerLogoutSuccessHandler` of Spring Security adds `id_token_hint`, so the gateway uses the custom `EntraLogoutSuccessHandler`. The implementer verifies the parameter `client_id` against the Entra documentation of the logout endpoint.

Effect: without `id_token_hint`, Entra cannot know which account to sign out. If the browser has more than one Entra account, Entra shows an account picker. The user selects the account.

Example:

```http
HTTP/1.1 302 Found
Location: https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/logout?client_id=<gateway-bff-client-id>&post_logout_redirect_uri=https%3A%2F%2Fgateway.example.com%2F
```

The post-logout redirect URI is `gateway.public-base-url` followed by the path in `gateway.security.logout.post-logout-redirect-path` (default `/`). Example: `https://gateway.example.com/`. The same URI MUST be registered as the post-logout redirect URI of the `gateway-bff` app ([021](021-entra-app-registrations.md)). The path follows "Rules for redirect paths".

### Public base URL

TLS can end at a load balancer. Then the gateway sees `http` and an internal host, and the Spring placeholder `{baseUrl}` gives a wrong URI. The gateway does not trust forwarding headers in this phase ([025](025-token-relay-and-header-hygiene.md)). `server.forward-headers-strategy` is `none`. Without an explicit value, Spring Boot trusts these headers on some cloud platforms, for example Kubernetes. The property `gateway.public-base-url` therefore gives the public URL of the gateway. The gateway builds these URIs from it:

| URI | Example with `https://gateway.example.com` |
|---|---|
| OAuth2 redirect URI | `https://gateway.example.com/login/oauth2/code/entra` |
| Post-logout redirect URI | `https://gateway.example.com/` |
| Login failure redirect | `https://gateway.example.com/?login=failed` |

[025](025-token-relay-and-header-hygiene.md) also builds the `X-Forwarded-Host`, `X-Forwarded-Proto`, and `X-Forwarded-Port` headers from this property.

Rules for `gateway.public-base-url`:

- The property is required. The gateway does not start if it is missing.
- The value MUST be an absolute URL with the scheme `https`, for example `https://gateway.example.com`.
- Exception for local runs only: when the Spring profile `local` is active, the gateway also accepts the scheme `http` for a local host, for example `http://localhost:8080`. Without the profile `local`, the gateway does not start with an `http` value.
- The gateway parses the value as a URI (`java.net.URI`), not as a string. For the `http` exception, the host MUST be exactly `localhost`, `127.0.0.1`, or `[::1]`, in lowercase, and the URI MUST have no user information. A string comparison such as "starts with `http://localhost`" is not allowed, because `http://localhost.evil.example` would pass.
- When the profile `local` is active, the gateway writes one `WARN` log line at startup: `Profile 'local' is active. Do not use it in a deployed gateway.`
- The `gateway-bff` app of a deployed environment MUST NOT have an `http://localhost` redirect URI or post-logout redirect URI ([021](021-entra-app-registrations.md)). Local runs use a separate app registration.
- The value MUST NOT contain a path, a query, a fragment, or user information. It MUST NOT end with `/`.
- The gateway does not start if the value breaks one of these rules.

### Endpoints owned by this chain

This chain owns three Spring Security endpoints: `GET /oauth2/authorization/entra`, `GET /login/oauth2/code/entra`, and `POST /logout`. `UndocumentedOperationWebFilter` of [024](024-authorization-manager.md) excludes exactly these three requests, each only with its method and the registration id `entra` (finding QA-59-1). Each other request without an access rule gets `404` from that filter, before this chain reads the session.

So another method or another registration id on these paths also gets `404`, also with an authenticated session and without a CSRF token. Examples: `GET /logout`, `PUT /logout`, `POST /login/oauth2/code/entra`, `GET /login/oauth2/code/unknown`, and `GET /oauth2/authorization/entra-6f1c2e8a-0000-0000-0000-000000000000` (a per-audience registration of [023](023-per-audience-tokens.md)).

### Actuator endpoints

Decision (2026-11-10): the actuator runs on a separate management port and exposes only `health` ([002](002-gateway-bootstrap.md)). Actuator endpoints are not behind this chain and need no session. Network policy restricts access to the management port.

In Spring Boot 4.1, the management context uses the security filters of the main context. A separate chain therefore matches each request of the management context (server namespace `management`) before the session chain. It permits the paths under the actuator base path (`management.endpoints.web.base-path`, default `/actuator`) without a session, CSRF token, or request cache. It denies every other path with the generic `404` problem body (finding SEC-59-2). The actuator answers `404` for each endpoint that it does not expose. The gateway does not start when `management.server.port` is `0` or equals `server.port` ([024](024-authorization-manager.md), "Management port"). On the main port, `/actuator/health` is a normal request: before [024](024-authorization-manager.md) it gets the temporary rule, with [024](024-authorization-manager.md) it gets `404`.

### Log guard

No log line may contain a token or a secret. The values are: the ID token, the access tokens, the refresh tokens, the authorization code, the PKCE code verifier, the session ids, the client secret, and the Redis password. The gateway itself never logs them. Some libraries log them at the level `DEBUG` or `TRACE`. Decision of the review of PR #63 (SEC-63-1 to SEC-63-4): the log guard (`LogGuard`) protects the logs in three ways. It runs at startup, right after Spring Boot sets the log levels, and before the gateway opens a connection.

1. Wiretap. Outside the Spring profile `local`, the gateway does not start when `spring.cloud.gateway.server.webflux.httpclient.wiretap` or `spring.cloud.gateway.server.webflux.httpserver.wiretap` is `true`. The error message names the property. A wiretap logs each header and each body.
2. Pinned loggers. Outside the profile `local`, the guard sets the level `INFO` for each logger of the table below with the effective level `DEBUG` or `TRACE`. The effective level is the level of the logger, or the level that it inherits from a parent logger or from the root logger. A logger that does not exist yet at startup gets the level of its nearest parent. A child logger with its own level below `INFO` also gets `INFO`. A level of `INFO` or higher stays. The guard writes one `WARN` line that names each pinned logger.
3. Masking. Always, also with the profile `local`: a masking layer in Logback replaces these values in each log line with `***`:
   - the value after `Bearer` or `Basic`, from 8 characters;
   - the value of `access_token`, `refresh_token`, `id_token`, `client_secret`, and `code_verifier`, after `=` or `:`, also with JSON quotes;
   - the value of `code=`, for example in the query of the login callback;
   - each JSON Web Token: `eyJ`, base64url characters, a dot, and base64url characters.

   The masking is the last line of defense. It works on the encoded line of each Logback appender that writes to an output stream, for example the console and a file. So it covers the message, the stack trace, and the structured logging formats of Spring Boot (`ecs`, `gelf`, `logstash`). It keeps the format of Spring Boot: the console pattern stays the default pattern, and a JSON line stays valid JSON.

| Logger (with its child loggers) | What it can write |
|---|---|
| `org.springframework.cloud.gateway.filter.headers.observation` | The request headers to the backend, with the relayed `Authorization` header (`DEBUG`). |
| `org.springframework.http.codec` | The decoded and encoded JSON and form values, for example the token response (`TRACE`). |
| `org.springframework.web.HttpLogging` | The same lines as the codecs. Spring writes them to this logger when the logger of the codec is off (`TRACE`). Found by the `TRACE` test of [023](023-per-audience-tokens.md). |
| `org.springframework.web.reactive.function.client` | The token requests of the gateway. With `spring.codec.log-request-details`, also their headers, with the client secret. |
| `org.springframework.web.server.adapter.HttpWebHandlerAdapter` | Each request URI, with the authorization code of the login callback (`DEBUG`). |
| `org.springframework.web.util` and `_org.springframework.web.util` | The parsed URIs, with the authorization code (`TRACE`). `RfcUriParser` writes to a hidden logger with the prefix `_`. Found by the `TRACE` test. |
| `org.springframework.security.oauth2` | The login, the token responses, and their errors. The `TRACE` test found no token in its lines. The guard pins it, because the test does not run each error path. |
| `io.lettuce.core.protocol` | Each Redis command and reply: the session id, the tokens in the session, and the password of `AUTH` and `HELLO` (`TRACE`). |
| `reactor.netty.http.client` and `reactor.netty.http.server` | The connections, and the wiretap of the HTTP client and the HTTP server. |

The profile `local` is for a developer machine. With it, a developer can turn on a wiretap and debug each logger. The masking stays on.

Limits:

- The guard runs at startup. It does not check a level that changes at runtime, for example through the actuator `loggers` endpoint. The gateway does not expose that endpoint ([002](002-gateway-bootstrap.md)).
- The masking needs Logback, the default logging system of Spring Boot. With another logging system, the gateway writes one `WARN` line at startup and logs without the masking.
- An appender that does not write to an output stream, for example a syslog appender, gets no masking. The gateway writes one `WARN` line with the names of these appenders.

## Behavior and errors

| Condition | Result |
|---|---|
| Request to a public operation | No authentication required. No session is created. |
| No session, HTML navigation | `302` to `/oauth2/authorization/entra`. A pre-login session with a timeout of 5 minutes holds the saved request. |
| No session, other request | `401` with the problem body. No session is created. |
| Pre-login session older than the pre-login timeout at the callback | The session has expired. The callback has no saved authorization request: `302` to `<public base URL>/?login=failed`. |
| Session idle for more than the idle timeout | The session has expired. Same result as no session. |
| Login time older than the maximum session lifetime | The gateway invalidates the session. Same result as no session. |
| Authenticated session without the login time attribute | The gateway invalidates the session. Same result as no session. |
| Valid session | Request continues to routing ([013](013-oas-route-locator.md)). |
| Undocumented path or method | With [024](024-authorization-manager.md): `404` from `UndocumentedOperationWebFilter`, for every client. The session is not read. Before [024](024-authorization-manager.md): the temporary rule applies, then routing returns `404`. |
| Another method or registration id on an endpoint of this chain, for example `GET /logout`, `PUT /logout`, `POST /login/oauth2/code/entra`, or `GET /oauth2/authorization/<id>` with an id other than `entra` | With [024](024-authorization-manager.md): `404` with the generic body from `UndocumentedOperationWebFilter`, also with an authenticated session. No login starts, and no CSRF check runs. |
| `GET /actuator/health` on the management port | `200` without a session. This chain does not apply. |
| State-changing request without a valid token in `X-XSRF-TOKEN` | `403` with the generic 403 problem body. A token in a form field `_csrf` does not count. |
| Login callback with wrong `state`, wrong `nonce`, or invalid ID token, or an error from Entra | `302` to `<public base URL>/?login=failed`. No authenticated session. |
| `gateway.public-base-url` is missing or invalid | The gateway does not start. |
| `gateway.public-base-url` is `http://localhost...` and the profile `local` is not active | The gateway does not start. |
| `GET /login` | `404` from `UndocumentedOperationWebFilter`. The gateway serves no login page. |
| Login failure path or post-logout redirect path breaks a rule of "Rules for redirect paths" | The gateway does not start. |
| Store `memory` is full and holds a pre-login session | The store removes the oldest pre-login session and stores the new session. |
| Store `memory` is full and holds only authenticated sessions, and a request needs a new session | `503` with the problem body of "Session store". No `Location` header, no `__Host-SESSION` cookie. |
| `gateway.session.store` has a value other than `memory` or `redis`, or `gateway.session.memory.max-sessions` is less than 1 | The gateway does not start. |
| Store `redis`: Redis unavailable | The request fails with a server error. The gateway does not create sessions in memory. At startup, the gateway does not start. |

## Configuration

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          entra:
            provider: entra
            client-id: ${GATEWAY_BFF_CLIENT_ID}
            authorization-grant-type: authorization_code
            redirect-uri: "${gateway.public-base-url}/login/oauth2/code/{registrationId}"
            scope: [openid, profile, offline_access]
            client-secret: ${GATEWAY_BFF_CLIENT_SECRET}   # see "Decisions"
        provider:
          entra:
            issuer-uri: https://login.microsoftonline.com/${ENTRA_TENANT_ID}/v2.0
            user-name-attribute: oid
  data:
    redis:          # only for gateway.session.store=redis
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      username: ${REDIS_USERNAME}   # Redis ACL user
      password: ${REDIS_PASSWORD}
      ssl:
        enabled: true
server:
  forward-headers-strategy: none   # see "Public base URL"
gateway:
  public-base-url: ${GATEWAY_PUBLIC_BASE_URL}   # for example https://gateway.example.com
  session:
    store: memory            # memory or redis
    memory:
      max-sessions: 100000
  security:
    session:
      pre-login-timeout: 5m
      idle-timeout: 30m
      max-lifetime: 8h
    login:
      failure-path: "/?login=failed"
    logout:
      post-logout-redirect-path: "/"
```

| Property | Type | Default | Meaning |
|---|---|---|---|
| `gateway.public-base-url` | String | None (required) | Public URL of the gateway, for example `https://gateway.example.com`. Source of the redirect URIs. See "Public base URL". |
| `gateway.session.store` | `memory` or `redis` | `memory` | The session store. See "Session store". |
| `gateway.session.memory.max-sessions` | Integer | `100000` | Maximum number of sessions in the memory of one instance, with the store `memory`. At least 1. |
| `gateway.security.session.pre-login-timeout` | Duration | `5m` | Maximum inactive interval of a session without an authenticated user. |
| `gateway.security.session.idle-timeout` | Duration | `30m` | A session with an authenticated user and without a request for this time expires. |
| `gateway.security.session.max-lifetime` | Duration | `8h` | Maximum time from login to the end of the session. |
| `gateway.security.login.failure-path` | String | `/?login=failed` | Redirect target after a login failure. See "Rules for redirect paths". |
| `gateway.security.logout.post-logout-redirect-path` | String | `/` | Path of the post-logout redirect URI. The gateway adds `gateway.public-base-url` before it. See "Rules for redirect paths". |

The `spring.data.redis.*` properties apply only to the store `redis`.

In `application.yaml`, `client-id`, `client-secret`, and `gateway.public-base-url` read their environment variables with an empty default. A missing variable then gives a clear startup error ("is required"), not a literal `${...}` value.

The Redis server settings (`maxmemory`, eviction policy, ACL, persistence) are infrastructure settings, not gateway properties. See "Redis protection".

## Acceptance criteria

Entry point and responses:

- [x] An HTML `GET` to a non-public operation without a session returns `302` with `Location: /oauth2/authorization/entra`.
- [x] A `GET` with `Accept: application/json` to a non-public operation without a session returns `401` and no `Location` header.
- [x] The 401 response has `Content-Type: application/problem+json` and the body `{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Authentication required."}`.
- [x] A request to a public operation without a session reaches the backend, and the response sets no `__Host-SESSION` cookie.
- [x] A fetch request that gets `401` sets no `__Host-SESSION` cookie. With the store `redis`, Redis has no new session key.

Login:

- [x] The authorization redirect to the mock IdP contains `code_challenge`, `code_challenge_method=S256`, `state`, `nonce`, and the scopes `openid profile offline_access`.
- [x] After login, the response sets `__Host-SESSION` with `HttpOnly`, `Secure`, `Path=/`, `SameSite=Lax`, and no `Domain`. The session id differs from the id before login.
- [x] After login, the browser is redirected to the saved HTML request.
- [x] No response body or header of the login flow contains the ID token, the access token, or the refresh token.
- [x] With the store `redis`, the session in Redis contains the authorized client for `entra` with a refresh token.
- [x] With the store `redis`, a second gateway instance that uses the same Redis accepts the session cookie of the first instance.
- [x] With `gateway.public-base-url=https://gateway.example.com`, a login callback with a wrong `state` returns `302` with `Location: https://gateway.example.com/?login=failed`. The following fetch request with the same cookie returns `401`.
- [x] `GET /login` returns `404`.
- [x] A login callback with an ID token with a wrong `nonce`, a wrong signature, a wrong `iss`, a wrong `aud`, or an `exp` in the past returns `302` to `<public base URL>/?login=failed`. The following fetch request returns `401`.
- [x] The gateway does not start when the registration `entra` does not use `authorization_code` or the scope `openid`, or when the provider has no `end_session_endpoint`, a relative one, or one with a query.

Cookies and backends:

- [x] A backend receives no `Cookie` header, before and after login, also when the browser sends other cookies.
- [x] When a backend returns `Set-Cookie` and `Set-Cookie2`, the client response contains neither header from the backend. A response of a public operation still sets the `__Host-XSRF-TOKEN` cookie of the gateway.
- [x] The default configuration sets `server.forward-headers-strategy` to `none`.

Session store:

- [x] With the default configuration, the store is `memory` with at most 100,000 sessions. The gateway starts without Redis and has no Redis connection factory. Its tests run without Docker.
- [x] The gateway does not start with `gateway.session.store=disk` or with `gateway.session.memory.max-sessions=0`.
- [x] With the store `memory` and a maximum of 2 sessions, a third pre-login session removes the oldest pre-login session. The browser of the removed session can log in again.
- [x] With the store `memory` and 2 authenticated sessions out of 2, an HTML navigation and `GET /oauth2/authorization/entra` of a new browser return `503` with the problem body of "Session store", no `Location` header, and no `__Host-SESSION` cookie. A fetch request of that browser returns `401`, a public operation returns `200`, and the authenticated sessions still work. After a logout, the new browser gets `302`.
- [x] The store `memory` removes expired sessions in a task that does not run on the event loop.

Session lifetime (the integration tests run with the store `memory` and with the store `redis`):

- [x] With the store `redis`, a new pre-login session in Redis has a time to live of at most 5 minutes with the default configuration.
- [x] With the store `redis`, after login the session in Redis has a time to live between 1,790 and 1,800 seconds (the idle timeout of 30 minutes).
- [x] The test sets a short pre-login timeout. A callback after this timeout returns `302` to the login failure redirect, and no authenticated session exists.
- [x] The test sets a short idle timeout of 3 seconds. A fetch request after 2.5 seconds returns `200`. A session without a request for longer than the idle timeout is not accepted: a fetch request returns `401`.
- [x] The test sets a short maximum session lifetime and moves the clock of `SessionLifetimeWebFilter` forward. A session older than this limit is not accepted: a fetch request returns `401`, and the store no longer has the session.
- [x] After the gateway ends a session at the maximum lifetime, an HTML navigation returns `302` with a new session. After the next login, the browser is redirected to that navigation.
- [x] After the gateway ends a session at the maximum lifetime, a new login works and does not redirect to `?login=failed`.
- [x] After the gateway ends a session at the maximum lifetime, a fetch request returns `401` and deletes the `__Host-SESSION` cookie.
- [x] With the store `redis`, the test removes the login time attribute from an authenticated session in Redis. The next fetch request returns `401`, and the session key is removed from Redis.

Redis (store `redis`):

- [x] The default configuration enables TLS for Redis and reads a Redis user name and password from environment variables.
- [x] The integration test uses a Redis ACL user with the allowlist of "Redis ACL". The full login, a token redemption ([023](023-per-audience-tokens.md)), and logout work with this user.
- [x] For that ACL user, Redis denies `KEYS`, `SCAN`, `MONITOR`, `RANDOMKEY`, `SLOWLOG`, `PSUBSCRIBE`, `CONFIG SET`, `FLUSHALL`, `REPLICAOF`, and `MODULE`.
- [x] At startup, the gateway sends no `CONFIG` command to Redis.
- [x] A session value in Redis with a JSON type that is not in the allowlist (for example `java.net.URL`) does not load. The request behaves as a request without a session.
- [x] Session metadata in Redis that is missing or has another type (for example a creation time that is a string) makes the session missing.
- [x] No session value in Redis is in the Java serialization format.
- [x] No session value in Redis contains the client secret of `gateway-bff`.
- [x] An HTML `GET /logout` without a session returns `404`, and the session store has no new session.
- [x] An HTML `GET` of a non-public operation that gets `invalid_grant` ([023](023-per-audience-tokens.md)) returns `302`, and the invalidated session does not exist in the session store afterwards.
- [x] An HTML `GET` without a session with a path and query of more than 1,024 bytes returns `302`, and the pre-login session has no saved request.

CSRF:

- [x] A `POST` without `X-XSRF-TOKEN` returns `403`. The same `POST` with a valid token in `X-XSRF-TOKEN` passes.
- [x] A form `POST` (`application/x-www-form-urlencoded`) with a valid token in the field `_csrf` and without the header returns `403`.
- [x] A `POST` without a valid CSRF token returns `403` with `Content-Type: application/problem+json` and the body `{"type":"about:blank","title":"Forbidden","status":403,"detail":"Access denied."}`.
- [x] The CSRF cookie is named `__Host-XSRF-TOKEN` and has `Secure`, `Path=/`, and no `Domain`.
- [x] The `__Host-XSRF-TOKEN` value after login differs from the value before login. A `POST` with the token from before login returns `403`.
- [x] The response of `POST /logout` sets a new `__Host-XSRF-TOKEN` value.

Logout:

- [x] `POST /logout` with a CSRF token deletes the session from the store and redirects to the Entra logout endpoint.
- [x] The logout redirect has the query parameters `client_id` and `post_logout_redirect_uri`, and no other parameter. It has no `id_token_hint`.
- [x] With `gateway.public-base-url=https://gateway.example.com`, the Entra logout redirect contains `post_logout_redirect_uri=https://gateway.example.com/`.

Redirect paths and public base URL:

- [x] The gateway starts with each of these failure paths: `/?login=failed`, `/signed-out`.
- [x] The gateway does not start when `gateway.security.login.failure-path` has one of these values: `//evil.example.com/`, `/\evil.example.com`, `/%2F%2Fevil.example.com`, `https://evil.example.com/`, `/a#b`, `/oauth2/authorization/entra`, `/oauth2;x/authorization/entra`, `/login`, `/login;x`, `/logout`, a path with a control character, or a non-public operation.
- [x] The gateway does not start when `gateway.security.logout.post-logout-redirect-path` has one of the values of the previous criterion.
- [x] With `gateway.public-base-url=https://gateway.example.com`, the authorization redirect contains `redirect_uri=https://gateway.example.com/login/oauth2/code/entra`, also when the request has other `Host`, `Forwarded`, or `X-Forwarded-*` values. The test runs the gateway on a real port (`webEnvironment = RANDOM_PORT`) and sends the request with the forged headers over a real connection, not through a mock-bound `WebTestClient`.
- [x] The gateway does not start when `gateway.public-base-url` is missing.
- [x] The gateway does not start with each of these values of `gateway.public-base-url`: `http://gateway.example.com`, `gateway.example.com`, `https://gateway.example.com/`, `https://gateway.example.com/app`, `https://gateway.example.com?a=1`.
- [x] The gateway starts with `https://gateway.example.com`. It starts with `http://localhost:8080`, `http://127.0.0.1:8080`, and `http://[::1]:8080` when the profile `local` is active, and does not start with `http://localhost:8080` without the profile `local`.
- [x] With the profile `local` active, the gateway does not start with each of these values: `http://localhost.evil.example`, `http://localhost@evil.example`, `http://user@localhost:8080`, `http://evil.example#@localhost`, `http://127.0.0.1.nip.io`, `http://LOCALHOST:8080`.
- [x] With the profile `local` active, the startup log has one `WARN` line about the profile.

Log guard:

- [x] Outside the profile `local`, the gateway does not start when `spring.cloud.gateway.server.webflux.httpclient.wiretap` or `spring.cloud.gateway.server.webflux.httpserver.wiretap` is `true`. The error message names the property. With the profile `local`, the gateway starts.
- [x] With `logging.level.root=TRACE`, each logger of "Log guard" and its child loggers log at `INFO`, and one `WARN` line names them. With the profile `local`, the levels stay.
- [x] A logger of the list that does not exist yet at startup gets `INFO`. A child logger with its own level `TRACE` gets `INFO`. A child logger with its own level `WARN` keeps `WARN`. Without a level below `INFO`, the guard writes no `WARN` line.
- [x] The masking replaces each value of "Log guard" with `***`, in the message and in the stack trace, with a pattern and with the structured logging format `ecs`. The JSON line stays valid. A line without such a value stays the same, and the other bytes of a UTF-8 line stay the same.
- [x] The masking also covers an appender inside an asynchronous appender, and the gateway installs it only once.
- [x] The `TRACE` test of [023](023-per-audience-tokens.md) ("Acceptance criteria", the log criterion) finds no token and no secret in the output, with each session store.

Excluded endpoints and actuator:

- [x] With [024](024-authorization-manager.md), `GET /logout` returns `404` with the generic body, with and without an authenticated session.
- [x] `GET /actuator/health` on the management port returns `200` without a session and without a redirect.

## Test plan

- Unit tests: `MemorySessionRepository` (limit, removal order, refusal, expiry, cleanup); `SessionStoreFullWebFilter`; entry point selection by `Accept` header and by the deny reason `NO_MATCH`; `PublicOperationsMatcher` built from access rules; 401 problem body; validation of the redirect paths (each example of "Rules for redirect paths") and of `gateway.public-base-url` with and without the profile `local`; maximum session lifetime check with a fixed clock, also without the login time attribute; `HeaderOnlyCsrfTokenRequestHandler` ignores the form field.
- Slice tests with `WebTestClient` and `SecurityMockServerConfigurers.mockOidcLogin()`: authenticated access, CSRF with `csrf()` and without, CSRF in a form field, logout redirect parameters.
- Integration test with a mock identity provider (IdP), for example WireMock or a mock OAuth2 server container.
  Cases: full login flow, PKCE parameters, cookie names and attributes, session id change, CSRF token rotation at login and logout, tokens not in responses, login failure redirect, pre-login timeout, idle timeout, maximum session lifetime, a request after the end of a session, no session for public operations and for `401` responses.
  The login and session lifetime tests run twice: with the store `memory`, without Docker, and with the store `redis` and Redis in Testcontainers. Only the tests with the store `redis` start the Redis container.
- Integration test of the store `memory` with a small maximum number of sessions: removal of pre-login sessions and `503`.
- Integration tests with Redis in Testcontainers: logout deletes the Redis key, missing login time, ACL, serialization, and two application contexts on the same Redis (shared session).
- BlockHound in the integration tests: no blocking call on the event loop.
- Test servers and clients use `127.0.0.1`, not the wildcard address and not `localhost` (see "Review findings", the flaky `TokenLoggingIT`). `GatewayBootstrapIT` checks the address that the gateway server and the management server really bind, not only the property (QA-69-5). On macOS, `GatewayStartupStopTest` reproduces the cause: the gateway does not start on a main port or a management port that another process uses on `127.0.0.1`. Linux refuses this bind also without the fix, so the test runs only on macOS. A test that starts a second application uses the shared test configuration `OwnNettyResources`, so that its close does not stop the servers of the other tests (QA-69-6).
- Log guard: unit tests for the masking of each value and for the masking encoder in a Logback context of its own, with a pattern and with the structured logging format `ecs`. Tests that start a small Spring application check the wiretap, the pinned loggers, and the profile `local`. The `TRACE` test of [023](023-per-audience-tokens.md) checks the output of the whole flow.
- Startup stop (decision of 2026-11-13, [024](024-authorization-manager.md), "Startup stop until the authorization manager is active"): the gateway does not start with a protected operation until [024](024-authorization-manager.md) is active. Tests of this spec that load protected operations register a test implementation of `AudienceAuthorizationActive` from `gateway/src/test/java`. The production code of this spec MUST NOT register it. The tests use the test bean `TestAudienceAuthorization`, and no test catalog: the permission catalog is removed ([Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13)).

## Delivery

Three stacked PRs. The first estimate was one PR of 400–480 changed lines. With the tests, the mock identity provider, and the Redis tests, the work is about three times larger, so it has three slices. Each slice builds on the previous slice and passes `./mvnw -B clean verify`.

| Slice | Branch | Content |
|---|---|---|
| 1 | `feat/022-session-login-1` | Startup settings: `gateway.public-base-url` (`PublicBaseUrl`), the profile `local`, `RedirectPathValidator`, the session properties, `PublicOperationsMatcher`, `SessionLoginSettings`. No new dependency. |
| 2 | `feat/022-session-login-2` | Security chain: `SessionSecurityConfiguration`, `oauth2Login` with PKCE and the `entra`-only resolver, entry point, problem bodies, request cache, CSRF (`CsrfCookieWebFilter`, `HeaderOnlyCsrfTokenRequestHandler`), `EntraLogoutSuccessHandler`, the session cookie, "Cookies and backends", `server.forward-headers-strategy: none`. Integration tests with a mock identity provider. |
| 3 | `feat/022-session-login-3` | Spring Session with the store `memory` (default) and the store `redis`: `MemorySessionRepository` with its limit, `SessionStoreFullWebFilter`, `RedisAutoConfigurationFilter`, `DrbgSessionIdGenerator`, JSON serialization with a type allowlist, pre-login and idle timeouts, maximum session lifetime, Redis settings. Integration tests with both stores; the tests of the store `redis` use Redis in Testcontainers. |

Slices 2 and 3 MUST merge together (SEC-50-2). Slice 2 alone keeps the sessions in the default in-memory store of Spring WebFlux: it has no pre-login timeout and no limit that removes anonymous sessions first. Slice 3 adds the session store of "Session and cookies" with these limits.

The criteria that need [023](023-per-audience-tokens.md) or [024](024-authorization-manager.md) stay open after slice 3: `GET /login` and `GET /logout` without a session return `404` only with `UndocumentedOperationWebFilter`, and the `invalid_grant` and token redemption criteria need the provider of [023](023-per-audience-tokens.md). These criteria passed after [023](023-per-audience-tokens.md) and [024](024-authorization-manager.md) merged. The status is Implemented since 2026-11-14.

The load balancer affinity for the store `memory`, the Redis server settings of "Redis protection", and the rate limit of "Anonymous sessions" are infrastructure work. The deployment checklist lists them. They are not part of the gateway PR.

## Decisions (2026-11-12)

- Client authentication: client secret ([021](021-entra-app-registrations.md)). The property `spring.security.oauth2.client.registration.entra.client-secret` reads an environment variable.

## Decisions (2026-11-13)

The product owner asked for conservative defaults so that the implementation can start. The team chose these defaults. The team can change each of them later.

- Session lifetime: the idle timeout is 30 minutes, and the maximum session lifetime is 8 hours. Both values are configurable. This answers the open question about session lifetime.
- Login failure: the gateway redirects the browser to `/?login=failed`. The path is configurable. The gateway does not use the default login page of Spring Security. The path follows "Rules for redirect paths"; the gateway checks this at startup. This answers the open question about the login failure target.
- Post-logout redirect URI: `gateway.public-base-url` plus `/`. The path is configurable. The URI is registered on the `gateway-bff` app ([021](021-entra-app-registrations.md)). This answers the open question about the post-logout redirect URI.
- Session values in Redis: no application-level encryption for now. This is an accepted risk. A person or process with read access to Redis can hijack sessions, because the key names contain the session ids. That person can also read access tokens and refresh tokens in clear text. The controls are the rules of "Redis protection". Examples: TLS, an ACL user limited to `~spring:session:*` without `KEYS`, `SCAN`, and `MONITOR`, and a private network. Other controls: no persistence or encrypted persistence, and backends that accept traffic only from the gateway. A person or process with write access to Redis can forge sessions. JSON serialization with a type allowlist prevents code execution from a written value. Before production, the team MUST review envelope encryption (Azure Key Vault) and refresh-token revocation at logout. This answers the open question about encryption.
- 401 body: `application/problem+json` (RFC 9457) with a fixed `type`, `title`, `status`, and a generic `detail`. The body contains no detail that reveals internals. This answers the open question about the 401 body.
- Public base URL: the required property `gateway.public-base-url` gives the public URL of the gateway. The gateway builds the OAuth2 redirect URI, the post-logout redirect URI, and the login failure redirect from it, never from request headers. Trusted proxies stay unsupported. This answers the open question about `{baseUrl}` behind a load balancer.

Decision of the product owner (2026-11-13): "the basic gateway implementation for cache must be in memory; Redis must be optional". The team applied it to the session store. The team chose these details:

- The property `gateway.session.store` selects `memory` (default) or `redis`. The store `memory` opens no Redis connection.
- The store `memory` has the same limits as Redis: pre-login timeout, idle timeout, maximum lifetime, missing login time, and `DRBG` session ids.
- The store `memory` holds at most 100,000 sessions (configurable). It removes the oldest pre-login session first. When only authenticated sessions remain, a new session gets `503`. Reason: anonymous traffic must never log out authenticated users.
- Limitation: with `memory`, sessions are per instance. Several instances need sticky sessions at the load balancer. A restart or a deploy logs out the users of that instance. Use `redis` for several instances without affinity.

Defaults chosen by the team after the review of PR #47 (QA-47, SEC-47). The team can change each of them later:

- Cookies: `__Host-SESSION` and `__Host-XSRF-TOKEN`. The session cookie keeps `SameSite=Lax`, because `Strict` breaks the Entra callback (SEC-47-2).
- CSRF: the token comes only from the header `X-XSRF-TOKEN`. The gateway creates a new token at login and at logout. `GET`, `HEAD`, and `OPTIONS` operations MUST NOT change state (SEC-47-2).
- Logout: no `id_token_hint`. The redirect has only `client_id` and `post_logout_redirect_uri`. Entra can show an account picker (SEC-47-8).
- Anonymous sessions: pre-login timeout of 5 minutes, raised to the idle timeout at login. Redis has `maxmemory` with an eviction policy. The team monitors the session count (SEC-47-6). The third review round changed the policy and the edge rule (see below).
- Missing login time: the gateway treats the session as expired (SEC-47-10).
- Redirect paths: the rules of "Rules for redirect paths". `http://localhost` only with the Spring profile `local` (SEC-47-11).
- Problem bodies: every problem body has a generic `detail` (QA-47-13).
- `NO_MATCH`: the entry point returns `404` for the deny reason `NO_MATCH` of [024](024-authorization-manager.md), also without a session (QA-47-8).

Defaults chosen by the team after the third review round of PR #47. The team can change each of them later:

- Request cache (QA-47-16): no saved request for `NO_MATCH`, `SESSION_INVALID`, and `REAUTHENTICATION_REQUIRED`.
- CSRF denial (QA-47-17): the generic 403 problem body.
- Redis ACL (SEC-47-13): a command allowlist, no channels, the non-indexed session repository, and `ConfigureReactiveRedisAction.NO_OP`.
- Redis version (SEC-47-14): at least 7.2.11, 7.4.6, 8.0.4, or 8.2.2, or a managed equivalent.
- Eviction (SEC-47-15): `volatile-ttl` and an alert on `evicted_keys`. The edge MUST limit the rate on `/oauth2/authorization/**` and on HTML navigations without a session cookie. The saved request is the path and the query, at most 1,024 bytes.
- Serialization (SEC-47-17): JSON with the Jackson modules of Spring Security and a type allowlist. Write access to Redis is part of the accepted risk.
- Local profile (SEC-47-21): URI parsing, an exact local host, no user information, a `WARN` log line, and no `http://localhost` URIs on a deployed `gateway-bff`.

## Review findings

| Finding | Resolution in this spec |
|---|---|
| QA-47-8 | "Entry point", "Endpoints owned by this chain", "Behavior and errors", and the criterion for `GET /logout`. |
| QA-47-11 | "Rules for redirect paths" and the criterion for the post-logout redirect path. |
| QA-47-13 | "Entry point": the 401 body has a `detail`. |
| SEC-47-1 | "Redis protection" and "Decisions (2026-11-13)". |
| SEC-47-2 | "Security invariants", "Session and cookies", "CSRF", and "Rule for safe methods". The CLI rule is in "Open questions". |
| SEC-47-6 | "Session lifetime" and "Anonymous sessions". |
| SEC-47-8 | "Logout". |
| SEC-47-10 | "Session lifetime" and the criterion for a missing login time. |
| SEC-47-11 | "Rules for redirect paths" and "Public base URL". |
| QA-47-15 | The criterion for `redirect_uri` with forged headers runs on a real server. |
| QA-47-16 | "Security chain sketch" (request cache) and the HTML criteria that check Redis keys. |
| QA-47-17 | "Security chain sketch" (CSRF access denied handler), "CSRF", and the criterion for the 403 body. |
| SEC-47-13 | "Redis protection", "Redis ACL", and the criteria for denied commands. |
| SEC-47-14 | "Redis protection": minimum Redis versions. |
| SEC-47-15 | "Redis protection" (`volatile-ttl`, `evicted_keys`) and "Anonymous sessions" (edge rule, saved request size). |
| SEC-47-17 | "Redis protection" (write access), "Session serialization", and the criteria for the type allowlist. |
| SEC-47-21 | "Public base URL" and the criteria with local host values. |
| QA-47-24 | The cookie names in the root [README](../README.md), and the consent error in [026](026-service-to-service-bearer.md). |
| QA-51-1 | "Session lifetime": a new session after the end of a session, and the criteria for a navigation, a login, and a fetch request after the end of a session. |
| QA-51-2 | The criteria for the idle timeout (fetch after 2.5 seconds) and for the Redis time to live after login. |
| QA-51-3 | "Session lifetime": the `Clock` bean, and the criterion for the maximum lifetime. |
| QA-51-4 | "Session serialization": metadata that fails to read. |
| SEC-51-1 | "Session and cookies": the `DRBG` generator with a security strength of 256 bits. |
| SEC-51-2 | [023](023-per-audience-tokens.md), "Registration for token calls". |
| QA-50-2 | The criteria for an invalid ID token and for the startup checks of the registration. |
| QA-50-3 | "Security chain sketch" (PKCE) and "Session and cookies": session ids come from the `DRBG` generator. |
| SEC-50-1 | "Security invariants" and "Cookies and backends". |
| SEC-50-2 | "Delivery": slices 2 and 3 merge together. |
| SEC-50-3 | "Public base URL" and "Configuration": `server.forward-headers-strategy: none`. |
| QA-49-1 | "Rules for redirect paths": no `;`, and the examples and criteria with `;`. |
| QA-57-2 | "Login flow": the login success handler removes the per-audience authorized clients. |
| SEC-57-4 | "Redis ACL": `SCRIPT LOAD` with `EVALSHA` can run any script, as `EVAL` can. The ACL user and the minimum Redis versions are the controls. |
| QA-59-1 | "Endpoints owned by this chain": each excluded request has one method and the registration id `entra`. The row of "Behavior and errors" for other methods and registration ids. |
| SEC-59-2 | "Actuator endpoints": the management chain permits only the actuator base path, and the startup check of the management port ([024](024-authorization-manager.md)). |
| SEC-63-1, SEC-63-2, SEC-63-3, SEC-63-4 | "Log guard": the wiretap check, the pinned loggers, the masking, and the criteria of "Log guard". The log criterion of [023](023-per-audience-tokens.md) says exactly what its test proves. |
| SEC-61-1, SEC-61-2, SEC-61-3 | "Cookies and backends" and "Entry point": the response header rules of [025](025-token-relay-and-header-hygiene.md), and the backend `401`. |
| Flaky `TokenLoggingIT` (follow-up of the review of PR #67) | "Test plan". A full build once got `426 Upgrade Required` at the login start, and another build got `404` from IntelliJ IDEA in `KubernetesForwardingHeadersIT`. Cause: another program on the machine listened on `127.0.0.1` on the same port as a test server. Reactor Netty binds with `SO_REUSEADDR`, and on macOS such a bind of the wildcard address succeeds next to a listener on `127.0.0.1`. Then the more specific listener gets each connection to `127.0.0.1` or `localhost`. This happens also with port `0`. Fix: every test server binds `127.0.0.1`, and every test client connects to `127.0.0.1`. A bind of `127.0.0.1` fails when the port is in use there, so a test never talks to another program. |
| QA-69-5 | "Test plan". `GatewayBootstrapIT` read only the `server.address` properties. It now reads the bound address of the gateway server and of the management server. `GatewayStartupStopTest.portOfAnotherProcessOnTheLoopbackAddressStopsTheStartup` is a macOS reproduction (`@EnabledOnOs(MAC)`). |
| QA-69-6 | "Test plan". One `OwnNettyResources` class in `gateway/src/test`, next to `Loopback`, replaces the copies in `GatewayStartupStopTest` and `RedisSessionIT`. `RedisAudienceTokenIT` uses it too. |

## Open questions

1. Which component serves `/`, the target of the login failure path and the post-logout redirect URI? The gateway does not serve the SPA files. If no public operation matches `/` and no other component on the same site serves `/`, `UndocumentedOperationWebFilter` ([024](024-authorization-manager.md)) returns `404` for both redirects.
2. Store `redis`: does the gateway need a Redis key namespace other than the Spring Session default? The implementation keeps the default `spring:session`. In Spring Boot 4.1 the property is `spring.session.data.redis.namespace`. If the namespace changes, the ACL key pattern of "Redis protection" changes with it.
3. Can a CLI rule help with "Rule for safe methods"? Example: a rule that reports a `GET` operation with a request body, or with an `operationId` that starts with `delete` or `cancel`. Such a rule finds only some cases.
4. What is the alert threshold for the number of sessions in Redis, and is 100,000 the right maximum for the store `memory`? Both depend on the expected number of users and on the memory size. A session with tokens needs a few kilobytes.
5. Store `redis`: must the actuator health check include Redis? The implementation turns off the Redis health check of Spring Boot, because it sends `INFO` and the ACL user cannot run `INFO`. With Redis in the health check, a Redis outage removes every gateway instance from the load balancer, also for public operations. Options: keep it off; add `+info` to the ACL; or write a health check with `PING`.

## References

- [002 — Gateway bootstrap](002-gateway-bootstrap.md)
- [013 — OAS route locator](013-oas-route-locator.md)
- [018 — Authorization extension](018-authorization-extension.md)
- [021 — Entra app registrations](021-entra-app-registrations.md)
- [023 — Per-audience tokens](023-per-audience-tokens.md)
- [024 — Authorization manager](024-authorization-manager.md)
- [025 — Token relay and header hygiene](025-token-relay-and-header-hygiene.md)
- Spring Security reference: OAuth 2.0 Login (reactive), CSRF for single-page applications, OIDC logout.
- Spring Session reference: reactive Redis.
- Microsoft identity platform: sign out (front-channel logout endpoint).
- RFC 3986: Uniform Resource Identifier (URI), `path-absolute`.
- RFC 6265bis: cookie prefixes (`__Host-`) and `SameSite`.
- RFC 7636: Proof Key for Code Exchange (PKCE).
- RFC 9457: Problem Details for HTTP APIs.
