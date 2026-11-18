# Configuration reference

This page lists every `gateway.*` property, the Spring Boot properties that the gateway sets, the environment variables, and the Spring profiles. Operators read it to configure a deployment. The gateway checks most values at startup and does not start with an invalid value.

The defaults come from the `@ConfigurationProperties` classes and from `gateway/src/main/resources/application.yaml`.

## Environment variables

`application.yaml` reads these environment variables.

| Variable | Property | Required | Description |
|---|---|---|---|
| `GATEWAY_PUBLIC_BASE_URL` | `gateway.public-base-url` | Yes | The public URL of the gateway, for example `https://gateway.example.com`. |
| `ENTRA_TENANT_ID` | `spring.security.oauth2.client.provider.entra.issuer-uri` | Yes | The tenant id of Entra ID. The issuer is `https://login.microsoftonline.com/<tenant-id>/v2.0`. |
| `GATEWAY_BFF_CLIENT_ID` | `spring.security.oauth2.client.registration.entra.client-id` | Yes | The client id of the `gateway-bff` app registration. |
| `GATEWAY_BFF_CLIENT_SECRET` | `spring.security.oauth2.client.registration.entra.client-secret` | Yes | The client secret of `gateway-bff`. Read it from a secret store. Never put it in a file of the repository. |
| `REDIS_HOST` | `spring.data.redis.host` | Only with `gateway.session.store=redis` | The host of Redis. |
| `REDIS_PORT` | `spring.data.redis.port` | Only with `gateway.session.store=redis` | The port of Redis. |
| `REDIS_USERNAME` | `spring.data.redis.username` | Only with `gateway.session.store=redis` | The Redis access control list (ACL) user. |
| `REDIS_PASSWORD` | `spring.data.redis.password` | Only with `gateway.session.store=redis` | The password of the ACL user. |

You can set any other property with an environment variable, by the relaxed binding of Spring Boot. Example: `GATEWAY_OPENAPI_SPECS` sets `gateway.openapi.specs`.

## Gateway properties

Durations use the Spring Boot format, for example `500ms`, `60s`, `30m`, or `8h`.

| Property | Type | Default | Description |
|---|---|---|---|
| `gateway.public-base-url` | String | None. Required. | The public URL of the gateway. The gateway builds its redirect URIs from it, and the headers `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-Port` to backends. `X-Forwarded-For` comes from the connection peer, not from this property. See [Rules for the public base URL](#rules-for-the-public-base-url). |
| `gateway.openapi.specs` | List of String | `classpath:openapi/*.yaml`, `classpath:openapi/*.yml`, `classpath:openapi/*.json` | The Spring resource patterns of the OAS files. A `file:` pattern reads the file system, for example `file:/etc/gateway/openapi/*.yaml`. Any other pattern reads the classpath. Files from a `file:` location get no CI validation and no change-control review. |
| `gateway.session.store` | `memory` or `redis` | `memory` | The session store. See [Operations](operations.md#choose-the-session-store). |
| `gateway.session.memory.max-sessions` | Integer, at least `1` | `100000` | The maximum number of sessions in the memory of one instance. Only for the store `memory`. |
| `gateway.security.session.pre-login-timeout` | Duration, positive | `5m` | The maximum inactive time of a session without a signed-in user. |
| `gateway.security.session.idle-timeout` | Duration, positive | `30m` | The maximum inactive time of a session with a signed-in user. |
| `gateway.security.session.max-lifetime` | Duration, positive | `8h` | The maximum time from login to the end of the session. |
| `gateway.security.login.failure-path` | String | `/?login=failed` | The redirect target after a login failure. See [Rules for redirect paths](#rules-for-redirect-paths). |
| `gateway.security.logout.post-logout-redirect-path` | String | `/` | The path where Entra ID sends the browser after logout. See [Rules for redirect paths](#rules-for-redirect-paths). |
| `gateway.security.token-clock-skew` | Duration, not negative | `60s` | The gateway renews a per-audience token this time before its `exp`. |
| `gateway.security.token-endpoint.timeout` | Duration, positive | `5s` | The maximum time of one call to the token endpoint of Entra ID. It is also the connect timeout and the response timeout for the JSON Web Key Set. |
| `gateway.security.token-endpoint.grants-per-second` | Integer, at least `1` | `50` | The maximum number of refresh token grants of one instance in one second, for all sessions. A grant over the limit gets `503`. |
| `gateway.security.permission-cache.maximum-size` | Integer, from `1` to `1000000` | `10000` | The maximum number of entries in the cache of decoded token permissions. An entry lives until the token `exp`, and at most 1 hour. |
| `gateway.security.remove-request-headers.names` | List of String | Empty | Request header names that the gateway removes in addition to its built-in list. The comparison ignores letter case. |
| `gateway.security.remove-request-headers.prefixes` | List of String | Empty | Request header name prefixes that the gateway removes in addition to its built-in list. The comparison ignores letter case. |

Example:

```yaml
gateway:
  public-base-url: https://gateway.example.com
  session:
    store: redis
  security:
    session:
      idle-timeout: 20m
    remove-request-headers:
      names: [X-Tenant-Id]
      prefixes: [X-Internal-]
```

The values of `remove-request-headers` add to the built-in list. They cannot remove a built-in entry. A blank value stops the startup. [Security model](security-model.md#request-headers) lists the built-in entries.

### Rules for the public base URL

The gateway does not start if `gateway.public-base-url` breaks one of these rules:

- The value is an absolute URL with the scheme `https` and a host.
- The value has no path, no trailing `/`, no query, no fragment, and no user information.
- With the Spring profile `local`, the scheme `http` is also allowed for the hosts `localhost`, `127.0.0.1`, and `[::1]`.

| Value | Result |
|---|---|
| `https://gateway.example.com` | Valid. |
| `https://gateway.example.com:8443` | Valid. |
| `https://gateway.example.com/` | Invalid: ends with `/`. |
| `http://gateway.example.com` | Invalid: `http` is not allowed. |
| `http://localhost:8080` | Valid only with the profile `local`. |

### Rules for redirect paths

`gateway.security.login.failure-path` and `gateway.security.logout.post-logout-redirect-path` follow these rules. The gateway does not start if a value breaks one of them.

- The value starts with exactly one `/`. It can have a query, for example `/?login=failed`.
- The value has only printable ASCII characters and no space.
- The value has no `\`, `%`, `#`, or `;`, and no `.` or `..` segment.
- The value is not `/login` or `/logout`, and it does not start with `/oauth2/`, `/login/`, or `/logout/`.
- The path does not match a protected `GET` operation. The redirect target needs no session.

The gateway adds `gateway.public-base-url` before the path. So a redirect never goes to another host.

The post-logout redirect URI, for example `https://gateway.example.com/`, MUST be a registered redirect URI of `gateway-bff`. See [Microsoft Entra ID setup](entra-id-setup.md#gateway-bff-app-registration).

## Spring Boot properties that the gateway sets

`application.yaml` sets these values. Change them only when you understand the effect.

| Property | Value | Reason |
|---|---|---|
| `spring.security.oauth2.client.registration.entra.redirect-uri` | `${gateway.public-base-url}/login/oauth2/code/{registrationId}` | The gateway does not start with another value. |
| `spring.security.oauth2.client.registration.entra.scope` | `openid`, `profile`, `offline_access` | The login needs `openid`. `offline_access` gives the refresh token for the per-audience tokens. |
| `spring.security.oauth2.client.provider.entra.user-name-attribute` | `oid` | The principal name in logs is the `oid` claim. |
| `spring.data.redis.ssl.enabled` | `true` | The connection to Redis uses TLS. |
| `spring.cloud.gateway.server.webflux.httpclient.connect-timeout` | `2000` (milliseconds) | Connect timeout to backends. |
| `spring.cloud.gateway.server.webflux.httpclient.response-timeout` | `10s` | Global response timeout. An operation can override it with `x-gateway-response-timeout`. |
| `spring.cloud.gateway.server.webflux.httpclient.pool.type` | `FIXED` | A fixed connection pool. |
| `spring.cloud.gateway.server.webflux.httpclient.pool.max-connections` | `500` | For each backend address. |
| `spring.cloud.gateway.server.webflux.httpclient.pool.acquire-timeout` | `5000` (milliseconds) | Maximum wait for a pool connection. |
| `spring.cloud.gateway.server.webflux.httpclient.pool.max-idle-time` | `30s` | Must be lower than the keep-alive timeout of every backend. |
| `spring.cloud.gateway.server.webflux.httpclient.pool.max-life-time` | `5m` | Maximum life of a connection. |
| `server.forward-headers-strategy` | `none` | The gateway does not trust `Forwarded` and `X-Forwarded-*` headers. Keep `none`. |
| `management.server.port` | `8081` | The actuator runs on its own port. |
| `management.endpoints.web.exposure.include` | `health` | The actuator exposes only `health`. |
| `management.health.redis.enabled` | `false` | The Redis ACL user cannot run `INFO`, which the Redis health check needs. |

The main port is the Spring Boot default `8080` (`server.port`).

## Settings that stop the startup

The gateway checks these settings at startup. It does not start if one of them is wrong.

| Setting | Condition |
|---|---|
| `gateway.public-base-url` | Missing or invalid. See [Rules for the public base URL](#rules-for-the-public-base-url). |
| `gateway.openapi.specs` | No OAS file matches. A classpath pattern matches a file outside the gateway module. |
| OAS files | A contract rule reports an `ERROR`. |
| `x-gateway.audience` | It equals the client id of `gateway-bff`. |
| `spring.security.oauth2.client.registration.entra` | Missing, no client secret, not `authorization_code`, no `openid` scope, or a redirect URI that does not match `gateway.public-base-url`. |
| Issuer of `entra` | Not `https://<entra-host>/<tenant-id>/v2.0` with a lowercase GUID tenant id. The hosts are `login.microsoftonline.com`, `login.microsoftonline.us`, and `login.partner.microsoftonline.cn`. |
| Client registrations | A registration id other than `entra` starts with `entra-`. This prefix is reserved for the per-audience registrations. |
| `management.server.port` | `0`, or the same value as `server.port`. |
| `gateway.session.store` | A value other than `memory` or `redis`. |
| `gateway.session.store=redis` | Redis is not reachable. |
| Numeric and duration properties | A value outside the range of the table above, for example a negative `gateway.security.token-clock-skew` or a zero `gateway.security.session.idle-timeout`. |
| `gateway.security.remove-request-headers` | A blank name or prefix. |
| A wiretap of the HTTP client or server | `spring.cloud.gateway.server.webflux.httpclient.wiretap` or `spring.cloud.gateway.server.webflux.httpserver.wiretap` is `true`, outside the profile `local`. |
| Routes | A route that does not come from an OAS file, for example a route in `application.yaml`. |

## Spring profiles

The gateway defines one profile: `local`. Do not use it in a deployed gateway.

| Effect | Without `local` | With `local` |
|---|---|---|
| `gateway.public-base-url` with `http` | Not allowed. | Allowed for `localhost`, `127.0.0.1`, and `[::1]`. |
| Wiretap of the HTTP client or server | The gateway does not start. | Allowed. |
| Loggers that can write tokens at `DEBUG` or `TRACE` | The log guard sets them to `INFO`. | Not changed. |
| Masking of tokens in log lines | On. | On. |
| Startup log | No line. | `WARN`: `Profile 'local' is active. Do not use it in a deployed gateway.` |

The test resources also use the profile `upstream-test`. It exists only in `gateway/src/test/resources` and is not part of the application.

Activate a profile with `--spring.profiles.active=local` or with the environment variable `SPRING_PROFILES_ACTIVE=local`.

## Related pages

- [Operations](operations.md): ports, health, session store, and logs.
- [Security model](security-model.md): what each security setting protects.
- [Spec 022 — Session login](../specs/022-session-login.md), section "Configuration".
