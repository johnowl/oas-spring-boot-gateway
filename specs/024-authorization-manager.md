# 024 — Authorization manager

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 7 — Session authentication and authorization in the gateway |
| Depends on | [015](015-path-trie.md), [018](018-authorization-extension.md), [023](023-per-audience-tokens.md) |
| Modules | `gateway` |

## Summary

This spec adds the fine-grained authorization of the gateway. A `ReactiveAuthorizationManager<AuthorizationContext>` finds the access rule of each request in a security index (`PathTrie<OperationAccess>`, which holds the access rule and the route id). For non-public operations, it checks that the access token of the rule audience has the correct `aud` and the required permissions. The spec also adds `UndocumentedOperationWebFilter`, which returns 404 for each request without an access rule before Spring Security reads the session. It adds the handlers for 401, 403, 404, 429, 500, 502, and 504 responses, and deny reasons in exchange attributes. It replaces the temporary rule of [022](022-session-login.md). Default deny protects every path that has no access rule.

## Motivation

The OpenAPI Specification (OAS) files declare an access rule for each operation ([018](018-authorization-extension.md)). The gateway must enforce these rules on every request, with a low cost. Spring Security filters run before gateway routing, so the authorization manager cannot use the matched route. It needs its own index of the access rules.

The `aud` check for each route is mandatory. Without it, a token for `payments-api` with the permission `Admin` passes an `orders-api` route that requires `Admin`.

## Scope

### In scope

- `SecurityIndex`: a `PathTrie<OperationAccess>` built once at startup from the access rules of the loaded OAS files.
- `UndocumentedOperationWebFilter`: a `WebFilter` before the Spring Security filter chain that returns 404 for undocumented operations.
- `GatewayAuthorizationManager`: implementation of `ReactiveAuthorizationManager<AuthorizationContext>`.
- `DenyReason` and the exchange attributes for the operation, the rule, and the deny reason.
- `GatewayAccessDeniedHandler`: 403, 404, 429, 500, 502, and 504 responses, and delegation to the entry point for 302 and 401.
- Logs and counter metrics for denials.
- Change of the security chain of [022](022-session-login.md): `anyExchange().access(gatewayAuthorizationManager)`.
- The production bean of `AudienceAuthorizationActive`, which ends the temporary startup stop for protected operations (see "Startup stop until the authorization manager is active").
- A test matrix for all decisions.

### Out of scope

- Token redemption and permission decoding. See [023](023-per-audience-tokens.md).
- Token relay to backends. See [025](025-token-relay-and-header-hygiene.md).
- Bearer token requests from services. See [026](026-service-to-service-bearer.md).
- Rebuild of the index on route reload. [014](014-route-reload.md) MUST rebuild the security index together with the routes.
- Actuator endpoints. They run on the separate management port ([002](002-gateway-bootstrap.md)) and are not in the security index.

## Design

### Security invariants

- The gateway MUST return `404` when no access rule matches the method and path of a request (default deny). This applies to every client, authenticated or not. The response MUST NOT be `401` or `403`, so that it does not reveal which paths exist.
- The authorization manager MUST also deny a request without an access rule (`NO_MATCH`). The response is the same `404`, also for an authenticated user and also without a session.
- The gateway MUST check, for each non-public route, that the token `aud` equals the audience of the access rule.
- In session mode, a wrong token `aud` MUST NOT produce `401`, because a `401` can start a login loop in the single-page application (SPA).
- The gateway MUST read permissions only from the access token of the rule audience ([023](023-per-audience-tokens.md)). The ID token MUST NOT be used for authorization.
- A 403 response MUST NOT reveal which permissions the operation requires, or which permissions the user has.
- The manager MUST read the lookup result only from the exchange attribute that `UndocumentedOperationWebFilter` sets, or from its own lookup. A client cannot set exchange attributes.
- The gateway MUST NOT use `BearerTokenServerAccessDeniedHandler`, because it reports `insufficient_scope`. The gateway does not use scopes.
- Until PR 2 of this spec is merged, the gateway MUST NOT start, and a reload MUST NOT apply, when an OAS file has a protected operation. See "Startup stop until the authorization manager is active".
- An audience token that fails validation ([023](023-per-audience-tokens.md), "Security invariants") MUST give `500` (`INVALID_AUDIENCE_TOKEN`), not `401`. The gateway obtained the token itself, so the user cannot fix the error with a new login.
- The security index MUST NOT check the rule of one operation while the backend serves another operation. `RequestPathFirewall` rejects the paths that a backend can read differently, and the index does not apply the rule of a variable segment to a case variant of a literal sibling. See "Paths that a backend can read differently".
- The gateway MUST forward a request only on the route of the access rule that the security chain checked. The gateway does not start with a route that does not come from an OAS file. See "Only OAS routes".
- The actuator MUST run on its own fixed port. The management context lets only the paths under the actuator base path pass. See "Management port".

### Startup stop until the authorization manager is active

Decision (2026-11-13, team default after the review of PR #48, findings QA-48-1, QA-48-2, QA-48-3, and SEC-48-4).

Before PR 2 of this spec, the gateway has no `aud` check and no permission check. The temporary rule of [022](022-session-login.md) requires only an authenticated user. A gateway that forwards a protected operation in this state lets every authenticated user call it. The gateway therefore does not start with a protected operation until the authorization manager is active.

"Active" means: PR 2 of this spec ("Delivery"), with rows 4 to 14 of the decision table, is merged.

The stop is tied to code, not to a property:

| Item | Value |
|---|---|
| Marker interface | `com.johnowl.gateway.routing.AudienceAuthorizationActive`, in `gateway/src/main`, in the same package as `OpenApiRouteDefinitionLocator`. It has no methods. |
| Rule | The stop applies unless the application context has a bean of type `AudienceAuthorizationActive`. |
| Valid bean | The class of each bean of this type MUST be in the package `com.johnowl.gateway` or one of its subpackages, and MUST NOT be a proxy (no JDK proxy and no CGLIB subclass). Otherwise the application does not start, also when all operations are public. This check stops a bean that a configuration file defines without gateway code, for example an XML file in `spring.main.sources` with a `ProxyFactoryBean` (finding SEC-52-1). |
| Production bean | PR 2 of this spec registers it, in the same configuration class as `GatewayAuthorizationManager`. No production code registers it before PR 2. The route locator needs the bean before the security chains exist, so the bean checks the chains at the end of the startup (finding SEC-60-1). The method that builds the session chain registers the chain with the manager, and the method that builds the management chain registers the chain as exempt. The gateway does not start when a `SecurityWebFilterChain` bean is not registered, when no chain is registered with the manager, or when the session chain has no `AuthorizationWebFilter`. |
| Test bean | Tests of [022](022-session-login.md) and [023](023-per-audience-tokens.md) that load protected operations register a test implementation. The test implementation is in `gateway/src/test/java` only. |
| Property | None. No configuration property registers the bean or turns off the stop. |
| Startup check | `OpenApiRouteDefinitionLocator` ([013](013-oas-route-locator.md)), after the CONTRACT rules. |
| Reload check | `OpenApiRouteReloader` ([014](014-route-reload.md)) applies the same check to the new set of OAS files. |
| Added by | Code PR 2 of [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13). It replaces the check `gateway.security.catalog is required, ...` on `main`. |

A protected operation is an operation whose access rule is not public (`AccessRule.isPublic()` is false, [018](018-authorization-extension.md)). The check reads `ValidationReport.accessRules()` and uses the first protected operation in the order of that list.

Exact message at startup and on reload:

```text
<METHOD> <public path pattern> is a protected operation, but the authorization manager (spec 024) is not active.
```

Example: `GET /orders-api/orders/{id} is a protected operation, but the authorization manager (spec 024) is not active.`

- At startup, the locator throws an `IllegalStateException` with this message, and the application does not start.
- On reload, `reload()` keeps the old routes, logs the message at `ERROR` level, and returns a failed `ReloadResult` ([014](014-route-reload.md)).

Exact message for a bean that is not valid (see "Valid bean"):

```text
<class name> is not a valid bean of type AudienceAuthorizationActive. The bean must be a class in the package com.johnowl.gateway, and not a proxy.
```

`OpenApiRoutingConfiguration` throws an `IllegalStateException` with this message before it creates the locator.

Note: Spring Boot 4.1 has no switch that turns off XML bean definitions. It ignores the key `spring.xml.ignore`. The "Valid bean" check is therefore the guard against XML bean definitions.

After PR 2 of this spec, the production bean exists, so the stop never applies in production. The check stays in the code as a guard: a gateway without the authorization manager still does not start with a protected operation.

### Security index

`SecurityIndex` wraps a `PathTrie<OperationAccess>` ([015](015-path-trie.md)). For each operation, it inserts:

- pattern: public path (`prefix` + OAS path), for example `/orders-api/orders/{id}`;
- methods: the method of the operation, for example `DELETE`;
- value: the `OperationAccess` of the operation, with the `AccessRule` and the route id.

```java
public class SecurityIndex {
    public static SecurityIndex build(Collection<OperationAccess> operations, String basePath); // startup only
    public Optional<OperationAccess> find(ServerWebExchange exchange);                          // per request
    public boolean isNonPublicGet(String path);                                                 // startup checks of 022
}
```

`OperationAccess` is the operation data that [018](018-authorization-extension.md) provides: route id, public path, method, and access rule.

`find` splits the request path into segments with the same rules as the route predicates. Verified in Spring Cloud Gateway 2025.1 (PR 1 of this spec): the `Path` predicate and the route index ([017](017-indexed-route-lookup.md)) parse the raw path (`request.getURI().getRawPath()`) once into a `PathContainer`, keep it in the exchange attribute `GATEWAY_PREDICATE_PATH_CONTAINER_ATTR`, and match the decoded segment values. `find` uses the same attribute and the same segments (`IndexedRoutePredicateHandlerMapping.segments`). It therefore takes the exchange, not only the request. Like the routes, the index adds `spring.webflux.base-path` before each pattern. A path with a trailing slash or with an empty segment matches no access rule. The index and the routes come from the same validated set of OAS files.

The class is not final, so that a test can count the calls of `find`. It has no method that changes it after `build`.

### Paths that a backend can read differently

Decision (2026-11-14, team default after the review of PR #59, finding SEC-59-1). The index compares decoded segments without matrix parameters, with case-sensitive literals. A backend can compare paths in another way. Then the index checks the rule of one operation, and the backend runs another operation. Examples with the operations `GET /users/me` (public) and `GET /users/{id}` (protected), or the reverse:

| Request | Index without the fix | Backend that reads it differently |
|---|---|---|
| `GET /users/me;x=1` | `/users/me` (the index removes the matrix parameter) | `/users/{id}` with the id `me;x=1` |
| `GET /users/m%65` | `/users/me` (the index decodes `%65`) | `/users/{id}` with the id `m%65` |
| `GET /users/ME` | `/users/{id}` | `/users/me`, if the backend ignores case |

The gateway closes these cases in two places:

- `RequestPathFirewall` ([013](013-oas-route-locator.md)) returns `400` with an empty body for a raw path with `;` or `%3B` (in any case; a backend that decodes the path first reads it as `;`), or with a percent-encoded unreserved character of RFC 3986 (`ALPHA`, `DIGIT`, `-`, `.`, `_`, `~`), for example `%65`. A client never needs to encode these characters. A literal segment of an OAS path has only unreserved characters ([007](007-route-model.md)), so no documented path needs `;`.
- `PathTrie.match` ([015](015-path-trie.md)) does not go to the variable child for a segment that is not a literal child, but equals a literal child when the case is ignored. So `GET /users/ME` matches no operation, and the gateway returns `404`. The router ([017](017-indexed-route-lookup.md)) uses the same trie, so it also selects no route.

### Undocumented operation filter

Decision (2026-11-10): an undocumented path or method returns `404` to every client, before the security chain runs.

`UndocumentedOperationWebFilter` implements `WebFilter`. Its order MUST put it before the Spring Security `WebFilterChainProxy`. Verified in Spring Security 7.1: `WebFilterChainProxy` has the order `-100`, so the filter has the order `-101`. `RequestPathFirewall` ([013](013-oas-route-locator.md)) still runs first. A test checks the order of the three filters in the application context: `RequestPathFirewall`, then `UndocumentedOperationWebFilter`, then `WebFilterChainProxy`.

For each request, the filter does these steps:

1. If the request comes from the management context, call the next filter for a path under the actuator base path, and write `404` with the generic problem body for every other path. See "Management port".
2. If the method and the path are an excluded request (table below), call the next filter.
3. Call `SecurityIndex.find(request)`.
4. If `find` returns a rule, store the `OperationAccess` in the exchange attribute `GatewaySecurityAttributes.OPERATION_ACCESS` and call the next filter.
5. If `find` returns empty, write `404` with the generic problem body and do not call the next filter.

In step 5, the filter MUST NOT read the session or the security context. The request therefore causes no Redis access. This also protects the performance of the gateway under scanning traffic.

The filter excludes only the requests that the session security chain of [022](022-session-login.md) handles. Each excluded request has one method and one path, with the registration id `entra` (finding QA-59-1):

| Excluded request | Owner |
|---|---|
| `GET /oauth2/authorization/entra` | Start of the login flow (`oauth2Login`) |
| `GET /login/oauth2/code/entra` | Login callback from Entra ID (`oauth2Login`) |
| `POST /logout` | Logout |

Every other method and every other registration id on these paths goes through the security index, so it gets `404`. Examples: `GET /logout`, `PUT /logout`, `POST /login/oauth2/code/entra`, `GET /login/oauth2/code/unknown`, `POST /oauth2/authorization/unknown`, and `GET /oauth2/authorization/entra-<client-id>`. Without this rule, a `POST` got `403` from the CSRF check and `GET /login/oauth2/code/unknown` got `302`.

A documented path with an undocumented method also returns `404`, not `405`. Example: the OAS file documents only `GET /orders-api/orders/{id}`. Then `PUT /orders-api/orders/42` returns `404`.

The filter MUST NOT apply the security index to the actuator on the management port ([002](002-gateway-bootstrap.md)). Verified in Spring Boot 4.1: the management context uses the web filters of the main context ([022](022-session-login.md)). The filter therefore lets each request of the management context (server namespace `management`) under the actuator base path pass, like the management security chain of [022](022-session-login.md). The excluded requests and the actuator base path match `request.getPath().pathWithinApplication()`, the same path as the matchers of Spring Security.

### Management port

Decision (2026-11-14, team default after the review of PR #59, finding SEC-59-2). The management context passes the security index, so the network policy of the management port protects it. With `management.server.port=0`, the port is random, and no network policy can restrict it. With the same port as `server.port`, the actuator runs in the main context.

- At startup, the gateway does not start when `management.server.port` is `0` or equals `server.port` (default `8080`). `ManagementEndpoints` checks the two values. Exact messages:

  ```text
  management.server.port must not be 0. The actuator needs a fixed port that the network policy restricts (spec 002).
  management.server.port must differ from server.port (<server port>). The actuator needs its own port (spec 002).
  ```

- The management security chain of [022](022-session-login.md) permits only the actuator base path (`management.endpoints.web.base-path`, default `/actuator`) and the paths below it. It denies every other path with the generic `404` body. `UndocumentedOperationWebFilter` applies the same rule first.
- Tests do not use the port `0`. `ManagementPortContextCustomizerFactory` in `gateway/src/test` gives each Spring Boot test context a free port when the context starts. A test that starts the application itself chooses a free port (`FreePort.next()`).
- In the tests, every server binds `127.0.0.1` (`Loopback` in `gateway/src/test`), and every client connects to `127.0.0.1`: the gateway, the management server, the mock backends, and the mock identity provider. `ManagementPortContextCustomizerFactory` sets `server.address` and `management.server.address`, and a test that starts the application itself passes them as arguments. `FreePort.next()` checks the port on `127.0.0.1`. See [022](022-session-login.md), "Review findings", for the reason.

### Only OAS routes

Decision (2026-11-14, team default after the review of PR #59, finding SEC-59-3). The security index and the route index ([017](017-indexed-route-lookup.md)) are separate. A defect, or a route from another source than an OAS file, could make them select different operations. Then a request reaches a backend under the access rule of another operation.

- `RouteOperationFilter` is a `GlobalFilter` with the highest precedence. It compares the id of the matched route (`GATEWAY_ROUTE_ATTR`) with `OPERATION_ACCESS.routeId()`. If they differ, or if one of them is missing, it writes `404` with the generic problem body, does not call the next filter, and logs one `ERROR` line with both route ids.
- At startup, `OasRoutesOnly` reads all routes of the route locator once. The gateway does not start when a route id is not the id of an OAS route, or when two routes have the same id. Exact messages:

  ```text
  The route <id> does not come from an OAS file. The gateway accepts only the routes of the OAS files, because only they have an access rule (spec 024).
  The route id <id> is used by more than one route.
  ```

- The only exception is a test configuration in `gateway/src/test` with a bean of type `com.johnowl.gateway.routing.TestRouteIds`. The bean lists the ids of the test routes. The class of the bean MUST be in the package `com.johnowl.gateway` and MUST NOT be a proxy, the same "Valid bean" rule as for `AudienceAuthorizationActive`. No production code and no property registers this bean. The bootstrap and upstream tests define their routes in `OpenTestRoutesConfiguration`, not in YAML.
- Route reload ([014](014-route-reload.md)) MUST rebuild the security index together with the route index, from the same validated set of OAS files. `RouteOperationFilter` answers `404` while the two indexes disagree.

Generic 404 body, the same for all undocumented operations:

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type":"about:blank","title":"Not Found","status":404,"detail":"Not found."}
```

### Authorization manager

Spring Security 6.4 and later define the method `authorize`, which returns `Mono<AuthorizationResult>`. Earlier versions use `check`. The project uses Spring Security 7 ([001](001-project-skeleton.md)). The implementer verifies the method against this version.

```java
public Mono<AuthorizationResult> authorize(Mono<Authentication> authentication,
                                           AuthorizationContext context) {
    ServerWebExchange exchange = context.getExchange();
    Optional<OperationAccess> found = Optional
        .ofNullable(exchange.<OperationAccess>getAttribute(OPERATION_ACCESS))   // set by the filter
        .or(() -> index.find(exchange.getRequest()));                          // no attribute: own lookup
    if (found.isEmpty()) return deny(exchange, DenyReason.NO_MATCH);   // defense in depth
    AccessRule rule = found.get().rule();   // found.get().routeId() is used in the WRONG_AUDIENCE log
    exchange.getAttributes().put(ACCESS_RULE, rule);
    if (rule.isPublic()) return grant();
    return authentication
        .filter(this::isAuthenticatedUser)
        .flatMap(user -> permissions.resolve(user, rule.audience(), exchange)   // 023
            .map(token -> {
                if (!rule.audience().equals(token.audience())) return deny(exchange, DenyReason.WRONG_AUDIENCE);
                if (!rule.allows(token.permissions()))        return deny(exchange, DenyReason.MISSING_PERMISSION);
                return granted();
            }))
        .switchIfEmpty(deny(exchange, DenyReason.NOT_AUTHENTICATED));
        // token errors from 023 map to CONSENT_REQUIRED, REAUTHENTICATION_REQUIRED,
        // SESSION_INVALID, TOKEN_REQUEST_REJECTED, TOKEN_GRANT_LIMIT,
        // TOKEN_ENDPOINT_TIMEOUT, TOKEN_ENDPOINT_ERROR, or INVALID_AUDIENCE_TOKEN
}
```

The sketch shows the order of the checks. It is not final code. The `NO_MATCH` branch is defense in depth. `UndocumentedOperationWebFilter` already returns `404` for each request without an access rule, and Spring Security answers the three excluded requests before the manager runs. Examples that reach the branch: a unit test of the manager without the filter, or a defect in the filter. Before the review of PR #59 (QA-59-1), `GET /logout` and `GET /oauth2/authorization/entra-<client-id>` reached it.

The manager reuses the lookup result of `UndocumentedOperationWebFilter` through the attribute `OPERATION_ACCESS`. A request does not search the security index twice. If the attribute is missing, the manager does its own lookup (defense in depth). Example: a unit test of the manager without the filter.

`AccessRule.allows` evaluates `all-of` and `any-of` from precompiled sets. An `authenticated-only` rule has empty sets, so `allows` returns true. The gateway still resolves the audience token for it, because [025](025-token-relay-and-header-hygiene.md) relays that token.

### Exchange attributes

| Attribute | Type | Set when |
|---|---|---|
| `GatewaySecurityAttributes.OPERATION_ACCESS` | `OperationAccess` | `UndocumentedOperationWebFilter` finds an access rule. |
| `GatewaySecurityAttributes.ACCESS_RULE` | `AccessRule` | A rule matches. |
| `GatewaySecurityAttributes.DENY_REASON` | `DenyReason` | The manager denies the request. |
| `GatewaySecurityAttributes.TOKEN_ERROR` | Token error fields: registration id, HTTP status, `error` value, AADSTS codes | The provider of [023](023-per-audience-tokens.md) gets an error from the token endpoint. |

`DenyReason` values: `NO_MATCH`, `NOT_AUTHENTICATED`, `CONSENT_REQUIRED`, `REAUTHENTICATION_REQUIRED`, `SESSION_INVALID`, `TOKEN_REQUEST_REJECTED`, `TOKEN_GRANT_LIMIT`, `TOKEN_ENDPOINT_TIMEOUT`, `TOKEN_ENDPOINT_ERROR`, `TOKEN_ENDPOINT_THROTTLED`, `INVALID_AUDIENCE_TOKEN`, `WRONG_AUDIENCE`, `MISSING_PERMISSION`, `INTERNAL_ERROR`. `TOKEN_ENDPOINT_THROTTLED` comes from [023](023-per-audience-tokens.md), "Throttling".

### Denial logs and metrics

The gateway logs each denial with the deny reason, the method, the path, and the principal name (`oid`, or `-` without a user). The manager reads the principal name from the authentication also for `NO_MATCH` (finding QA-60-5). `UndocumentedOperationWebFilter` does not read the session, so its `NO_MATCH` lines have `-`. The log MAY contain the required permissions, because logs are not visible to clients. The log MUST NOT contain tokens.

Rules for the path in the log:

- The log contains the raw path as the client sent it, still percent-encoded (`request.getURI().getRawPath()`), not the decoded path. Then two requests that decode to the same path stay different in the log.
- The gateway escapes control characters, the same as in [008](008-cli-basic.md). A line feed in the path cannot start a new log line.
- The gateway cuts the path after 512 characters and adds `...`.
- One component formats the path for the log (`LogPathFormatter`). All denial log lines use it.

The denial log writes exactly one line for each denial. `UndocumentedOperationWebFilter` also writes the `DEBUG` line and increments the counter for its `404` (`NO_MATCH`), without the principal: it does not read the session. The line is built only when the `DEBUG` level is on.

Line format (one line, the fields in this order): `Access denied: reason=<reason> method=<method> path=<path> principal=<oid or -> route=<route id>`, then for a token error `registration=<id> status=<status> error=<error or -> errorCodes=[...]` and `check=<failed check>` for an invalid token, and for `WRONG_AUDIENCE` `expectedAudience=<audience> tokenAudience=<aud>`. A `NO_MATCH` line has no route. The component is `AuthorizationDenials`. For the token errors of [023](023-per-audience-tokens.md), the line also contains the fields of the attribute `TOKEN_ERROR`. The provider of [023](023-per-audience-tokens.md) does not write its own line.

| Deny reason | Log level | Counter metric |
|---|---|---|
| `NO_MATCH` | `DEBUG` | `gateway.authorization.denied`, tag `reason` |
| `NOT_AUTHENTICATED` | `DEBUG` | `gateway.authorization.denied`, tag `reason` |
| `MISSING_PERMISSION` | `INFO` | `gateway.authorization.denied`, tag `reason` |
| `SESSION_INVALID` | `INFO` | `gateway.authorization.denied`, tag `reason` |
| `CONSENT_REQUIRED` | `INFO` | `gateway.authorization.denied`, tag `reason` |
| `REAUTHENTICATION_REQUIRED` | `INFO` | `gateway.authorization.denied`, tag `reason` |
| `TOKEN_GRANT_LIMIT` | `WARN` | `gateway.authorization.denied`, tag `reason` |
| `TOKEN_ENDPOINT_TIMEOUT` | `WARN` | `gateway.authorization.denied`, tag `reason` |
| `TOKEN_ENDPOINT_ERROR` | `WARN` | `gateway.authorization.denied`, tag `reason` |
| `TOKEN_ENDPOINT_THROTTLED` | `WARN` | `gateway.authorization.denied`, tag `reason` |
| `TOKEN_REQUEST_REJECTED` | `ERROR` | `gateway.authorization.denied`, tag `reason` |
| `INVALID_AUDIENCE_TOKEN` | `ERROR` | `gateway.authorization.denied`, tag `reason` |
| `WRONG_AUDIENCE` | `ERROR` | `gateway.authorization.audience.mismatch`, tag `route` |
| `INTERNAL_ERROR` | `ERROR` | `gateway.authorization.denied`, tag `reason` |

The value of the tag `reason` is the name of the `DenyReason`, for example `MISSING_PERMISSION`. A `WRONG_AUDIENCE` denial increments only `gateway.authorization.audience.mismatch`.

The `INFO` level gives an audit trail of denied access with the default log configuration. `NOT_AUTHENTICATED` stays at `DEBUG`, because every expired session produces it. `TOKEN_REQUEST_REJECTED` is a configuration error, for example a wrong scope or a wrong client secret. Its `ERROR` line has the fields of [023](023-per-audience-tokens.md) ("Token endpoint errors").

`INVALID_AUDIENCE_TOKEN` is a configuration error. The token endpoint returned a token that fails the validation of [023](023-per-audience-tokens.md): signature, issuer, `ver`, `tid`, `exp`, `nbf`, `aud`, `azp`, `oid`, or `roles`. The checks `aud` and `azp` apply to a new token (step 7 of the provider) and to a stored token; `oid` and `roles` apply to a stored token in the permission resolver. Example: the backend app issues version 1 tokens, so the issuer is not the version 2 issuer and `ver` is `1.0` ([021](021-entra-app-registrations.md), "Tenant-wide rules"). The gateway does not store the token. Its `ERROR` line contains the registration id and the name of the failed check, and no token.

`INTERNAL_ERROR` is a defect (findings SEC-60-2 and QA-60-1). The resolver of [023](023-per-audience-tokens.md) or the decision failed with an error that is not a token error of [023](023-per-audience-tokens.md). The manager never lets such an error leave the decision, so the response is never the default error page of Spring. Its `ERROR` line has the class name of the error (`exception=<class>`), and not the message of the error, because a message can contain a token.

`WRONG_AUDIENCE` is a configuration error or a defect, not a client error, because the gateway obtained the token itself. The expected audience is `x-gateway.audience`, the client id of the backend app. The token passed the validation of [023](023-per-audience-tokens.md), so it is a version 2 token of the tenant. The check is a defense against a defect in the gateway, for example a token stored under the registration of another audience. For `WRONG_AUDIENCE`, the gateway:

- MUST log at `ERROR` level the route id (`OperationAccess.routeId()`), the expected audience, and the token audience. The log MUST NOT contain the token.
- MUST increment the counter metric `gateway.authorization.audience.mismatch`, with the tag `route` (the route id).

### Decision table

`UndocumentedOperationWebFilter` runs before the manager. A request without an access rule gets `404` from the filter and does not reach the manager. Row 1 is defense in depth.

Decision (2026-11-10): the gateway does not support Cross-Origin Resource Sharing (CORS), because the SPA is served from the same site ([022](022-session-login.md)). An undocumented `OPTIONS` request gets `404` from `UndocumentedOperationWebFilter`, like any other undocumented method.

The manager evaluates the rows from top to bottom. The first row that applies gives the result.

| # | Condition | Decision | HTTP response | Deny reason |
|---|---|---|---|---|
| 1 | No access rule for method and path. With the filter, only a defect reaches this row. | Deny | `404`, generic problem detail. Also for an authenticated user and without a session. | `NO_MATCH` |
| 2 | Rule is public (`security: []`) | Grant | Continues | None |
| 3 | No authenticated user | Deny | Entry point: `302` to `/oauth2/authorization/entra` for HTML, `401` otherwise | `NOT_AUTHENTICATED` |
| 4 | Token redemption returns a consent error ([023](023-per-audience-tokens.md), "Token endpoint errors", row 1) | Deny; session stays valid | `403`, consent problem detail ([023](023-per-audience-tokens.md)) | `CONSENT_REQUIRED` |
| 5 | Token redemption returns another `interaction_required` ([023](023-per-audience-tokens.md), row 2) | Deny; all authorized clients removed; session invalidated | Entry point, as in row 3 | `REAUTHENTICATION_REQUIRED` |
| 6 | Token redemption returns another `invalid_grant` ([023](023-per-audience-tokens.md)), or the session or its `entra` client no longer exists, for example because a parallel request of the same session ended it | Deny; session invalidated | Entry point, as in row 3 | `SESSION_INVALID` |
| 7 | Token endpoint returns another `4xx` error, for example `invalid_scope` or `invalid_client` | Deny; session stays valid; `ERROR` log | `500`, generic problem detail | `TOKEN_REQUEST_REJECTED` |
| 8 | The session reached the grant limit of [023](023-per-audience-tokens.md) (10 grants in 60 seconds) | Deny; session stays valid | `429`, generic problem detail | `TOKEN_GRANT_LIMIT` |
| 8a | Entra throttles the token requests, or the gateway pauses its grants ([023](023-per-audience-tokens.md), "Throttling") | Deny; session stays valid | `503`, generic problem detail | `TOKEN_ENDPOINT_THROTTLED` |
| 9 | Token endpoint does not respond within the timeout, or the session lock of [023](023-per-audience-tokens.md) is not free within 10 seconds | Deny; session stays valid | `504`, generic problem detail | `TOKEN_ENDPOINT_TIMEOUT` |
| 10 | Connection error or `5xx` status from the token endpoint | Deny; session stays valid | `502`, generic problem detail | `TOKEN_ENDPOINT_ERROR` |
| 11 | The audience token fails validation ([023](023-per-audience-tokens.md)): signature, issuer, `ver`, `tid`, `exp`, `nbf`, `aud`, `azp`, `oid`, or `roles` | Deny; token not stored; session stays valid; `ERROR` log | `500`, generic problem detail. Never `401`. | `INVALID_AUDIENCE_TOKEN` |
| 12 | Token `aud` differs from rule audience | Deny; `ERROR` log | `500`, generic problem detail | `WRONG_AUDIENCE` |
| 13 | `rule.allows(permissions)` is false | Deny | `403`, generic problem detail | `MISSING_PERMISSION` |
| 14 | All checks pass (permission rule or `authenticated-only`) | Grant | Continues to routing | None |
| E | Another error during rows 4 to 13, for example a defect in the resolver | Deny; session stays valid; `ERROR` log | `500`, generic problem detail, for fetch requests and HTML navigations | `INTERNAL_ERROR` |

Row E is not a step of the order. It applies when an error that is not a token error of [023](023-per-audience-tokens.md) ends one of the rows 4 to 13.

A session that a parallel request ended is not a defect. The provider of [023](023-per-audience-tokens.md) gives `SESSION_INVALID` for it, so row 6 applies, not row E.

Row 4 comes before rows 5 and 6, because Entra reports missing consent with `error=invalid_grant` or `error=interaction_required` ([023](023-per-audience-tokens.md)).

### Response handling

When the manager denies, `AuthorizationWebFilter` raises `AccessDeniedException`. `ExceptionTranslationWebFilter` calls the entry point if there is no authenticated principal. Otherwise it calls `GatewayAccessDeniedHandler`. Verify this behavior in Spring Security 7.

Both paths MUST give `404` for `NO_MATCH`. The entry point of [022](022-session-login.md) checks the deny reason `NO_MATCH` first and writes the generic 404 body. `GatewayAccessDeniedHandler` does the same for an authenticated user.

`GatewayAccessDeniedHandler` reads `DENY_REASON`:

| Deny reason | Response |
|---|---|
| `NO_MATCH` | `404`, generic 404 body of "Undocumented operation filter". |
| `SESSION_INVALID`, `REAUTHENTICATION_REQUIRED` | Delegate to the entry point of [022](022-session-login.md). |
| `TOKEN_GRANT_LIMIT` | `429`, generic problem body of [023](023-per-audience-tokens.md). |
| `CONSENT_REQUIRED` | `403`, consent problem body of [023](023-per-audience-tokens.md). |
| `TOKEN_REQUEST_REJECTED` | `500`, generic problem body. No `WWW-Authenticate` header. |
| `TOKEN_ENDPOINT_TIMEOUT` | `504`, generic problem body of [023](023-per-audience-tokens.md). |
| `TOKEN_ENDPOINT_ERROR` | `502`, generic problem body of [023](023-per-audience-tokens.md). |
| `TOKEN_ENDPOINT_THROTTLED` | `503`, generic problem body of [023](023-per-audience-tokens.md). |
| `INVALID_AUDIENCE_TOKEN` | `500`, generic problem body. No `WWW-Authenticate` header. |
| `WRONG_AUDIENCE` | `500`, generic problem body. No `WWW-Authenticate` header. |
| `INTERNAL_ERROR` | `500`, generic problem body. No `WWW-Authenticate` header. |
| Any other reason, for example `MISSING_PERMISSION` | `403`, generic problem body. |

Generic 403 body, the same for all 403 cases except `CONSENT_REQUIRED`:

```http
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{"type":"about:blank","title":"Forbidden","status":403,"detail":"Access denied."}
```

Generic 500 body for `WRONG_AUDIENCE`, `INVALID_AUDIENCE_TOKEN`, `TOKEN_REQUEST_REJECTED`, and `INTERNAL_ERROR` (the same body as in [023](023-per-audience-tokens.md)):

```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/problem+json

{"type":"about:blank","title":"Internal Server Error","status":500,"detail":"Internal error."}
```

The `401` with `WWW-Authenticate: Bearer error="invalid_token"` applies only to the bearer chain of [026](026-service-to-service-bearer.md), if [026](026-service-to-service-bearer.md) is accepted.

The problem bodies of this section come from the gateway. A backend response never has `WWW-Authenticate`: the gateway removes it ([025](025-token-relay-and-header-hygiene.md), "Response headers"). A backend `401` keeps its status and its body.

## Acceptance criteria

- [x] The security index is built once at startup. No request builds or changes it.
- [x] A request to a path without an access rule returns `404` with the generic problem body, without a session and with an authenticated session.
- [x] A request with an undocumented method on a documented path returns `404`, not `405`.
- [x] An undocumented `OPTIONS` request with `Origin` and `Access-Control-Request-Method` headers returns `404`.
- [x] A request to an undocumented path never returns `401`, `403`, or `302`.
- [x] A request to an undocumented path causes no Redis command (verified with a Redis spy or the Redis command count).
- [x] `GET /oauth2/authorization/entra`, `GET /login/oauth2/code/entra`, and `POST /logout` are not answered by `UndocumentedOperationWebFilter`.
- [x] `PUT /logout`, `DELETE /logout`, `POST /oauth2/authorization/unknown`, `POST /oauth2/authorization/entra`, `POST /login/oauth2/code/entra`, and `GET /login/oauth2/code/unknown` return `404` with the generic 404 body, without a session and with an authenticated session, also without a CSRF token.
- [x] `UndocumentedOperationWebFilter` runs after `RequestPathFirewall` and before `WebFilterChainProxy`.
- [x] The actuator health endpoint on the management port returns `200` without a session.
- [x] On the management port, a path outside the actuator base path (for example `/`, `/admin/secrets`, or `/actuatorx`) returns `404` with the generic 404 body.
- [x] The gateway does not start with `management.server.port=0`, or with the same value for `management.server.port` and `server.port`. The root cause has the exact message of "Management port".
- [x] `RequestPathFirewall` returns `400` for `/users/me;x=1`, `/users/me%3Bx=1`, `/users/me%3bx=1`, `/users/m%65`, and `/%6Frders/1`. The backend receives nothing.
- [x] `GET /users/ME` returns `404` when the OAS file has `/users/me` and `/users/{id}`. The security index and the route lookup find nothing.
- [x] `RouteOperationFilter` returns `404` with the generic 404 body when the matched route is not the route of `OPERATION_ACCESS`, or when `OPERATION_ACCESS` is missing.
- [x] The gateway does not start with a route from `application.yaml`. The root cause has the exact message of "Only OAS routes".
- [x] The full application context has no bean of type `TestRouteIds`.
- [x] A unit test of `GatewayAuthorizationManager` without the filter shows that a request without an access rule is denied with `NO_MATCH`.
- [x] `GET /logout` returns `404` with the generic 404 body, without a session and with an authenticated session.
- [x] `GET /oauth2/authorization/entra-6f1c2e8a-0000-0000-0000-000000000000` returns `404` with the generic 404 body, without a session and with an authenticated session. The mock identity provider receives no request.
- [x] `GET /oauth2/authorization/unknown` returns `404` with the generic 404 body.
- [x] A public operation without a session returns the backend response.
- [x] A non-public operation without a session returns `302` for HTML and `401` for fetch requests.
- [x] A user whose audience token has all `all-of` permissions gets the backend response.
- [x] A user without one `all-of` permission gets `403`.
- [x] A user with one of the `any-of` permissions gets the backend response; a user with none gets `403`.
- [x] A rule with `all-of` and `any-of` grants only when both conditions hold.
- [x] An `authenticated-only` operation grants any authenticated user.
- [x] In session mode, a token with `aud` of another audience returns `500` with the generic 500 body and no `WWW-Authenticate` header.
- [x] In session mode, a wrong audience never returns `401`.
- [x] A wrong audience writes one `ERROR` log line with the route id, the expected audience, and the token audience, and without the token.
- [x] A wrong audience increments `gateway.authorization.audience.mismatch` with the tag `route` set to the route id, and does not increment `gateway.authorization.denied`.
- [x] Each deny reason writes one log line at the level of the table in "Denial logs and metrics". Each deny reason except `WRONG_AUDIENCE` increments `gateway.authorization.denied` with the tag `reason` set to its name.
- [x] A denial of `GET /orders-api/%0Aorders` writes a log line that contains `%0Aorders` (the raw path).
- [x] A unit test of `LogPathFormatter` with a path that contains a real line feed (`\n`), a carriage return (`\r`), and a bell character (`\u0007`) returns one line with each character escaped as in [008](008-cli-basic.md). The test fails if the formatter does not escape.
- [x] A unit test of `LogPathFormatter` with a path of 2,000 characters returns the first 512 characters followed by `...`.
- [x] A `TOKEN_REQUEST_REJECTED` denial writes exactly one log line, and it contains the fields of the attribute `TOKEN_ERROR`.
- [x] For a documented operation, the security index is searched once for each request (verified with a spy on `SecurityIndex.find`).
- [x] A token with the required permission for another audience does not pass the route (cross-audience test).
- [x] A `roles` claim in the ID token has no effect on the decision.
- [x] Every 403 body except the `CONSENT_REQUIRED` body is byte-equal to the generic body. No 403 body contains a permission name.
- [x] No response contains `insufficient_scope`.
- [x] `invalid_grant` without a consent marker during token redemption gives `401` for a fetch request and invalidates the session.
- [x] `invalid_grant` with `suberror=consent_required` gives `403` with the consent problem body of [023](023-per-audience-tokens.md), and the session stays valid.
- [x] `invalid_scope` from the token endpoint gives `500` with the generic 500 body, and the session stays valid.
- [x] `interaction_required` without `65001` gives `401` for a fetch request and invalidates the session (`REAUTHENTICATION_REQUIRED`).
- [x] The grant limit of [023](023-per-audience-tokens.md) gives `429` with the generic problem body (`TOKEN_GRANT_LIMIT`).
- [x] A token endpoint timeout gives `504`, and a token endpoint `5xx` status gives `502`. Both have `Content-Type: application/problem+json`, and the session stays valid.
- [x] Every 401, 403, 404, 429, 500, 502, and 504 body has a `detail` with a fixed generic text.
- [x] `/users/me` uses the rule of `/users/me` and not the rule of `/users/{id}`.
- [x] Each denial sets `DENY_REASON` and writes one log line without tokens.
- [x] A version 1 token from the mock token endpoint (`ver` `1.0`, issuer `https://sts.windows.net/<tenant-id>/`) gives `500` with the generic 500 body, no `WWW-Authenticate` header, and the deny reason `INVALID_AUDIENCE_TOKEN`. The session stays valid and has no authorized client for that registration.
- [x] A token signed with a key of the mock JWKS but with the `iss` of another tenant, or with another `tid`, gives `500` (`INVALID_AUDIENCE_TOKEN`) with the generic 500 body and no `WWW-Authenticate` header, never `401`. The denial increments `gateway.authorization.denied` (`INVALID_AUDIENCE_TOKEN`) by one and writes one `ERROR` line with `check=iss` or `check=tid`.
- [x] For a version 1 token, the `ERROR` line has `check=iss`.
- [x] Another error of the resolver gives `500` with the generic 500 body for a fetch request and for an HTML navigation (`INTERNAL_ERROR`). It writes one `ERROR` line with the class name of the error and without its message, and increments `gateway.authorization.denied` (`INTERNAL_ERROR`).
- [x] With the session store `redis`, `invalid_grant` for an HTML navigation gives `302` to `/oauth2/authorization/entra`, and the session key no longer exists in Redis.
- [x] The `429`, `502`, `503`, and `504` responses of the full chain have exactly the generic problem body and `Content-Type: application/problem+json`.
- [x] A `NO_MATCH` line of the manager has the principal name of an authenticated user.
- [x] An `INVALID_AUDIENCE_TOKEN` denial writes exactly one `ERROR` line with the registration id and the name of the failed check, and without the token.

Startup stop (code PR 2 of the removal of the permission catalog; PR 2 of this spec for the production bean):

- [x] Without a bean of type `AudienceAuthorizationActive`, an OAS file with the protected operation `GET /orders` stops the startup. The root cause message is exactly `GET /orders is a protected operation, but the authorization manager (spec 024) is not active.`
- [x] Without the bean, an OAS file with only public operations starts the gateway.
- [x] With a test bean of type `AudienceAuthorizationActive`, an OAS file with a protected operation starts the gateway.
- The reload is not a criterion of this spec. [014](014-route-reload.md) (Optional, not implemented) owns it: when spec 014 is implemented, a reload without the bean keeps the old routes, returns a failed `ReloadResult`, and logs the message above at `ERROR` level. See the acceptance criteria of [014](014-route-reload.md).
- [x] No property of the gateway turns off the stop. A test starts the routing configuration without the production bean, with `gateway.security.catalog` and other old properties, and the stop still applies. (Before PR 2, this test started the full application. After PR 2, the full application has the production bean.)
- [x] A bean of type `AudienceAuthorizationActive` that is a JDK proxy, a CGLIB proxy, or a class outside `com.johnowl.gateway` stops the startup with the exact message for a bean that is not valid.
- [x] The full application with `spring.main.sources` set to an XML file with a `ProxyFactoryBean` for `AudienceAuthorizationActive` does not start, and the root cause has the exact message for a bean that is not valid.
- [x] Before PR 2 of this spec, no class in `gateway/src/main` implements `AudienceAuthorizationActive`. After PR 2, the full application context has exactly one bean of that type, and it comes from `gateway/src/main`.
- [x] The production bean stops the startup when a `SecurityWebFilterChain` bean is not registered with it, when no chain uses the manager, or when the session chain has no `AuthorizationWebFilter` (SEC-60-1).
- [x] `OpenApiRoutingStartupTest.protectedOperationsNeedTheCatalog` is renamed `protectedOperationsNeedTheAuthorizationManager` and checks the new message.

## Test plan

- Unit tests for `SecurityIndex`: literal before variable, backtracking by method, unknown method, trailing slash, encoded segments.
- Unit tests for `SecurityIndex` and `PathTrie`: case variants of a literal sibling find nothing. Unit tests for `RequestPathFirewall`: `;` and encoded unreserved characters give `400`.
- Unit tests for `UndocumentedOperationWebFilter`: unknown path, unknown method on a known path, each excluded request, other methods and registration ids on the excluded paths, known operation passes to the next filter and sets `OPERATION_ACCESS`, no call to `exchange.getSession()` on a 404, actuator and other paths of the management context.
- Unit tests for `RouteOperationFilter`, `OasRoutesOnly`, and `ManagementEndpoints`. Startup tests of the full application for a route from `application.yaml` and for the management port.
- Integration test with Redis in Testcontainers: undocumented requests with and without a session return `404` and send no command to Redis.
- Integration test for the management port: the health endpoint returns `200` without a session; a path outside the actuator base path returns `404` with the generic body.
- Unit tests for `GatewayAuthorizationManager` with a stub permission resolver: one test for each row of the decision table; with and without the attribute `OPERATION_ACCESS`.
- Unit tests for the entry point and `GatewayAccessDeniedHandler`: `NO_MATCH` gives `404` with and without an authenticated principal.
- `WebTestClient` tests with `mockOidcLogin()`, `mockOAuth2Client("entra-6f1c2e8a-0000-0000-0000-000000000000")`, and a test `TokenPermissionsResolver` that returns chosen `aud` and permissions. Cover the full matrix: public, authenticated-only, `all-of`, `any-of`, both, wrong audience (`500`), no match (`404`, also for `GET /logout` and `GET /oauth2/authorization/entra-<client-id>`), HTML and fetch, invalid session, consent required, rejected token request (`500`), token endpoint timeout (`504`) and error (`502`).
- Cross-audience test with two OAS files (`orders-api`, `payments-api`) that both use a permission named `Api.Admin` in different audiences. The rule `permission-format` ([018](018-authorization-extension.md)) requires a dot, so the name `Admin` alone is not valid.
- Response tests: exact 401, 403, 404, 500, 502, and 504 bodies, 401 headers, no permission names in any response.
- Log test: the level of each deny reason; the `WRONG_AUDIENCE` and `TOKEN_REQUEST_REJECTED` lines contain no token; the raw path, the escaping of control characters, and the length limit.
- Metric test with a `SimpleMeterRegistry`: the counters and tags for each deny reason.
- Startup stop tests with `ApplicationContextRunner` in `OpenApiRoutingStartupTest`: with and without the test bean, protected and public operations. Reload test in [014](014-route-reload.md), when 014 is implemented.
- Token validation tests with the mock identity provider: a version 1 token and a token with the issuer of another tenant (`INVALID_AUDIENCE_TOKEN`).
- JMH (optional, with [016](016-routing-benchmark.md)): cost of `SecurityIndex.find` with 1,000 and 5,000 operations.

## Delivery

Two PRs:

1. Index and default deny. Files: `SecurityIndex`, `UndocumentedOperationWebFilter` with the attribute `OPERATION_ACCESS`, `DenyReason`, `GatewaySecurityAttributes`, `GatewayAuthorizationManager` with rows 1 to 3, the `404` for `NO_MATCH` in the entry point, and tests. The PR changes the security chain and removes the temporary `PublicOperationsMatcher` of [022](022-session-login.md). Estimate: 300 lines.
2. Audience and permission checks: rows 4 to 14 with the resolver of [023](023-per-audience-tokens.md), `GatewayAccessDeniedHandler`, problem bodies, denial logs and metrics, full test matrix. The PR registers the production bean of `AudienceAuthorizationActive`, so the startup stop no longer applies in production. Estimate: 350 lines.

The startup stop itself (the interface, the check in the locator, and the message) is in code PR 2 of [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13). Code PR 3 of the removal (`audience-unique` and `audience-not-first-party`, [018](018-authorization-extension.md)) MUST merge before PR 2 of this spec.

## Notes of the first implementation PR (2026-11-14)

- PR 1 has the rows 1 to 3 of the decision table. Until PR 2, the manager grants each authenticated user for a protected operation, as the temporary rule of [022](022-session-login.md) did. The startup stop still applies, so no production gateway starts with a protected operation.
- PR 1 adds `GatewayAccessDeniedHandler` with the rows `NO_MATCH` (`404`) and "Any other reason" (`403`) of "Response handling", because an authenticated request without an access rule (`GET /logout`) needs the `404` already. PR 2 adds the other rows.
- PR 1 adds the deny reason `NOT_AUTHENTICATED` and the attributes `OPERATION_ACCESS` and `ACCESS_RULE`. PR 2 adds `WRONG_AUDIENCE` and `MISSING_PERMISSION`.
- The routes in `application.yaml` have no access rule, so the gateway does not start with them ("Only OAS routes"). The bootstrap and upstream tests define their test routes in `OpenTestRoutesConfiguration` (`gateway/src/test`), with a `TestRouteIds` bean and a test `SecurityIndex` with a public operation for each test route.

## Notes of the second implementation PR (2026-11-14)

- The production bean is `GatewayAudienceAuthorization` in `com.johnowl.gateway.security`, a plain class. `AuthorizationConfiguration` registers it with a static `@Bean` method, next to `GatewayAuthorizationManager`.
- The manager maps each deny reason that [022](022-session-login.md) and [023](023-per-audience-tokens.md) define, also `TOKEN_ENDPOINT_THROTTLED` (`503`, row 8a), which this spec did not name before.
- The full application tests use the real manager. The test stand-in `TestAudienceTokens` of [023](023-per-audience-tokens.md) is removed. Tests with `mockOidcLogin()` use a stub `TokenPermissionsResolver` with chosen `aud` values and permissions. The mock identity provider gives the audience tokens the roles `Orders.Read`, `Orders.Write`, and `Payments.Read` by default; a test can choose other roles.
- Only tests with a part of the configuration (`ApplicationContextRunner` without `AuthorizationConfiguration`) still use the test bean `TestAudienceAuthorization`.

## Decisions (2026-11-13)

The product owner asked for conservative defaults so that the implementation can start. The team chose these defaults. The team can change each of them later.

- Other denials: every deny reason except `WRONG_AUDIENCE` increments the counter `gateway.authorization.denied` with the tag `reason`. `WRONG_AUDIENCE` stays at `ERROR` level with its own counter. This answers the open question about other denial logs. The log levels changed after the review of PR #47 (see below).
- Metric name: `gateway.authorization.audience.mismatch` with the tag `route` is confirmed. This answers the open question about the metric name.
- Index lookup: the manager reuses the lookup result of `UndocumentedOperationWebFilter` through the exchange attribute `OPERATION_ACCESS`. This answers the open question about a second lookup.
- Token endpoint errors from [023](023-per-audience-tokens.md) (decided on 2026-11-13) add the deny reasons `TOKEN_ENDPOINT_TIMEOUT` (`504`) and `TOKEN_ENDPOINT_ERROR` (`502`). `CONSENT_REQUIRED` uses the distinct problem `type` of [023](023-per-audience-tokens.md).

Defaults chosen by the team after the review of PR #47 (QA-47, SEC-47). The team can change each of them later:

- `NO_MATCH` (QA-47-8): `404` with the generic body, also for an authenticated user and without a session. Both the entry point and `GatewayAccessDeniedHandler` apply this rule.
- Log levels (SEC-47-9): `MISSING_PERMISSION`, `SESSION_INVALID`, and `CONSENT_REQUIRED` at `INFO`; `TOKEN_ENDPOINT_TIMEOUT` and `TOKEN_ENDPOINT_ERROR` at `WARN`; `NOT_AUTHENTICATED` and `NO_MATCH` at `DEBUG`; `WRONG_AUDIENCE` and `TOKEN_REQUEST_REJECTED` at `ERROR`. This answers the open question about the level of token endpoint errors.
- Path in logs (SEC-47-9): the raw, percent-encoded path, with escaped control characters, cut after 512 characters.
- Other token endpoint `4xx` errors (QA-47-1, SEC-47-12): the new deny reason `TOKEN_REQUEST_REJECTED` gives `500`.
- Consent before session end (QA-47-4): the consent row comes before the `invalid_grant` row.
- Third review round: the deny reasons `REAUTHENTICATION_REQUIRED` (entry point, SEC-47-19) and `TOKEN_GRANT_LIMIT` (`429`, `WARN`, SEC-47-16). One denial log line includes the token error fields (QA-47-23). `LogPathFormatter` formats the path (QA-47-21).
- Problem bodies (QA-47-13): every problem body has a generic `detail`.

Removal of the permission catalog (product owner, 2026-11-13). See [Removal of the permission catalog (2026-11-13)](README.md#removal-of-the-permission-catalog-2026-11-13):

- The `aud` check compares the token `aud` with `x-gateway.audience`, the client id of the backend app. The permission check uses the `roles` claim of the audience token. No catalog is involved.
- A permission that no user has, for example a typo such as `Orders.Raed`, is not found in the PR. Every request to that operation gets `403` (`MISSING_PERMISSION`). The gateway fails closed.

Defaults chosen by the team after the review of PR #48 (QA-48, SEC-48). The team can change each of them later:

- Startup stop (QA-48-1, QA-48-2, QA-48-3, SEC-48-4): the stop applies unless a bean of the marker interface `AudienceAuthorizationActive` exists. PR 2 of this spec registers the production bean. Tests of [022](022-session-login.md) and [023](023-per-audience-tokens.md) register a test implementation from `src/test`. No production property exists. A reload ([014](014-route-reload.md)) applies the same check. The message and the criteria are in "Startup stop until the authorization manager is active". No deployment of `main` happens before code PR 2 of the removal of the permission catalog merges.
- Token validation (SEC-48-3): the new deny reason `INVALID_AUDIENCE_TOKEN` gives `500` with an `ERROR` log, never `401`. The `WRONG_AUDIENCE` example no longer uses a version 1 token, because such a token now fails the validation first.

## Review findings

| Finding | Resolution in this spec |
|---|---|
| QA-47-1, SEC-47-12 | `TOKEN_REQUEST_REJECTED` in "Decision table" (row 7) and "Response handling". |
| QA-47-4 | "Decision table": row 4 (consent) before row 5 (`invalid_grant`). |
| QA-47-8 | "Security invariants", "Decision table" (row 1), "Response handling", and the criteria for `GET /logout` and `GET /oauth2/authorization/entra-<client-id>`. |
| QA-47-13 | "Response handling": the 500 body has a `detail`. The criterion for every problem body. |
| SEC-47-9 | "Denial logs and metrics". |
| QA-47-21 | "Denial logs and metrics": `LogPathFormatter`, and the unit test criteria with real control characters. |
| QA-47-23 | "Exchange attributes" (`TOKEN_ERROR`) and "Denial logs and metrics": one line for each denial. |
| SEC-47-16 | `TOKEN_GRANT_LIMIT` in "Decision table" (row 8) and "Response handling". |
| SEC-47-19 | `REAUTHENTICATION_REQUIRED` in "Decision table" (row 5) and "Response handling". |
| QA-48-1, QA-48-2, QA-48-3, SEC-48-4 | "Startup stop until the authorization manager is active", "Security invariants", the criteria for the startup stop, and "Delivery". |
| SEC-48-3 | `INVALID_AUDIENCE_TOKEN` in "Decision table" (row 11), "Denial logs and metrics", and "Response handling". The new `WRONG_AUDIENCE` example. |
| SEC-59-1 | "Paths that a backend can read differently", "Security invariants", the criteria for `;`, encoded unreserved characters, and case variants. [013](013-oas-route-locator.md), [015](015-path-trie.md), and [017](017-indexed-route-lookup.md). The CONTRACT warning is an open question. |
| SEC-59-2 | "Management port", step 1 of "Undocumented operation filter", "Security invariants", and the criteria for the management port. [022](022-session-login.md) and [002](002-gateway-bootstrap.md). |
| SEC-59-3 | "Only OAS routes", "Security invariants", "Notes of the first implementation PR", and the criteria for `RouteOperationFilter` and routes from `application.yaml`. [014](014-route-reload.md). |
| QA-59-1 | "Undocumented operation filter": the excluded requests with method and registration id. "Authorization manager" and "Decision table": row 1 is defense in depth. The criterion for the other requests to these paths. [022](022-session-login.md). |
| QA-59-2 | "Undocumented operation filter": the order of the three filters, and its criterion. |
| SEC-60-1 | "Startup stop until the authorization manager is active": the production bean checks the security chains. Its criterion. |
| SEC-60-2, QA-60-1 | `INTERNAL_ERROR` in "Exchange attributes", "Denial logs and metrics", "Decision table", and "Response handling". Its criterion. |
| QA-60-2 | The criteria for a token of another tenant and for a version 1 token: body, header, counter, and the exact check. |
| QA-60-3 | The criterion for `invalid_grant` of an HTML navigation with the store `redis`. |
| QA-60-4 | The criterion for the whole `429`, `502`, `503`, and `504` bodies of the full chain. |
| QA-60-5 | "Denial logs and metrics": the manager reads the principal name for `NO_MATCH`. Its criterion. |
| SEC-61-3 | "Response handling": a backend response has no `WWW-Authenticate` ([025](025-token-relay-and-header-hygiene.md)). |
| CI-43712577572 | "Decision table": row 6 for a session that a parallel request ended. The `500` of the CI run did not come from row E: BlockHound ended the read of the session in the tests ([002](002-gateway-bootstrap.md), "BlockHound"). |

## Open questions

- Should a CONTRACT rule ([006](006-x-gateway-extension.md)) warn about literal segments that a backend can read as another literal or as a variable value, for example two sibling literals that differ only in case (`/orders/export` and `/orders/Export`)? The gateway already denies case variants at runtime ("Paths that a backend can read differently"). A warning would find the problem in the PR. No code exists for it yet (finding SEC-59-1).

## References

- [001 — Project skeleton](001-project-skeleton.md)
- [002 — Gateway bootstrap](002-gateway-bootstrap.md)
- [015 — Path trie](015-path-trie.md)
- [018 — Authorization extension](018-authorization-extension.md)
- [022 — Session login](022-session-login.md)
- [023 — Per-audience tokens](023-per-audience-tokens.md)
- [025 — Token relay and header hygiene](025-token-relay-and-header-hygiene.md)
- [026 — Service-to-service bearer](026-service-to-service-bearer.md)
- Spring Security reference: authorization for WebFlux, `ReactiveAuthorizationManager`.
- RFC 9457: Problem Details for HTTP APIs.
- RFC 6750: Bearer token usage (`WWW-Authenticate` error codes).
