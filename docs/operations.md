# Operations

This page explains how to run the gateway in an environment: the ports, the health check, the choice between the memory and the Redis session store, the logs, and the metrics. Operators and on-call engineers read it. [Configuration reference](configuration.md) lists all properties.

## Ports

| Port | Property | Default | Content |
|---|---|---|---|
| Main port | `server.port` | `8080` | The routes, the login, and the logout. |
| Management port | `management.server.port` | `8081` | The actuator. Only `health` is exposed. |

The gateway does not start when `management.server.port` is `0` or equals `server.port`.

Restrict the management port with a network policy. The actuator needs no session and has no authentication. Each actuator endpoint that you expose is open to every client that can reach port `8081`.

Rules for `management.endpoints.web.exposure.include`:

- Keep `health`. Add `metrics` only if you need it, for example `include: health,metrics`.
- Never expose `heapdump`, `threaddump`, `env`, `configprops`, `loggers`, `sessions`, or `*`. A heap dump contains the tokens of all sessions of the store `memory`. The `loggers` endpoint can set `DEBUG` on the token loggers at runtime, after the log guard has run.
- The gateway does not check this property. The operator is responsible for it.

## Check the health

```bash
curl http://<gateway-host>:8081/actuator/health
```

On the management port, every path outside the actuator base path (`/actuator`) gets `404`. On the main port, `/actuator/health` is a normal request. It gets `404`, unless an OAS file declares the path.

The health check does not include Redis. See [Use the Redis session store](#use-the-redis-session-store).

## Start the gateway

The gateway is a Spring Boot jar:

```bash
java -jar gateway/target/gateway-0.0.1-SNAPSHOT.jar
```

At startup, the gateway:

1. reads the OAS files of `gateway.openapi.specs` and checks them with the contract rules;
2. reads the OpenID Connect metadata of Entra ID for the registration `entra`;
3. loads the JSON Web Key Set of Entra ID (if this fails, it loads the keys at the first token validation);
4. with the store `redis`, opens the connection to Redis.

Startup log lines to check:

```text
gateway.openapi.specs: [classpath:openapi/*.yaml, classpath:openapi/*.yml, classpath:openapi/*.json]
Created 1 route(s) from 1 OAS file(s)
```

The gateway does not start without at least one OAS file. On 2026-11-17, the repository has no OAS file in the default location `gateway/src/main/resources/openapi/`. Add a file there. Keep real OAS files in a private fork or a private deployment repository: they contain backend host names and client ids. For a local test, you can also set `gateway.openapi.specs` to a `file:` location. In production, files from a `file:` location get no CI validation and no change-control review. See [Duties of operators and backend teams](security-model.md#duties-of-operators-and-backend-teams). [Configuration reference](configuration.md#settings-that-stop-the-startup) lists all conditions that stop the startup.

To change the routes, deploy new OAS files and restart the gateway. The gateway does not reload routes at runtime.

## Choose the session store

| Property value | Store | Use it when |
|---|---|---|
| `gateway.session.store=memory` (default) | The memory of each instance. | You run one instance, or the load balancer sends all requests of a browser to the same instance. |
| `gateway.session.store=redis` | Redis, shared by all instances. | You run several instances without affinity, or the sessions need to survive a deploy. |

### Use the memory session store

Limits:

- A session exists only on the instance that created it.
- With several instances, the load balancer MUST send all requests of a browser to the same instance (sticky sessions). Without this, users get `401` or a new login, and a login fails when the callback reaches another instance.
- A restart or a deploy ends all sessions of the instance. The users need to sign in again.
- The tokens of all sessions are in the heap. Turn off heap dumps, or encrypt and restrict them.
- The gateway opens no Redis connection.

Capacity:

- The store holds at most `gateway.session.memory.max-sessions` sessions (default `100000`).
- When the store is full, it removes the oldest session without a signed-in user first.
- When the store holds only sessions with signed-in users, it refuses a new session. The request gets `503`. The gateway writes this `WARN` line at most once a minute:

  ```text
  The in-memory session store holds 100000 authenticated sessions (gateway.session.memory.max-sessions). It refuses new sessions.
  ```

- A public operation, a request that gets `401`, and a request with a valid session still work when the store is full.

Create an alert on this `WARN` line.

### Use the Redis session store

1. Set `gateway.session.store=redis`.
2. Set `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, and `REDIS_PASSWORD`. See [Configuration reference](configuration.md#environment-variables).
3. Prepare Redis with the rules below.

The connection uses TLS by default (`spring.data.redis.ssl.enabled=true`). The gateway does not start if Redis is not reachable.

Rules for Redis, from [spec 022](../specs/022-session-login.md) ("Redis protection" and "Redis ACL"):

| Rule | Value |
|---|---|
| Version | 7.2.11, 7.4.6, 8.0.4, 8.2.2, or later in the same line, or a managed service with the same fixes. |
| Network | A private network. Only the gateway can reach Redis. |
| Connection | TLS. |
| Authentication | An access control list (ACL) user with a password. |
| Keys of the ACL user | Only `~spring:session:*`. |
| Commands of the ACL user | An allowlist. See the example below. |
| Memory | A `maxmemory` limit and the eviction policy `volatile-ttl`. |
| Alert | On the Redis metric `evicted_keys`. |
| Persistence and backups | Off. If the platform requires them, encrypt and restrict them like Redis. |
| Keyspace notifications | Not needed. The gateway does not run `CONFIG`. |

Example of the ACL user from spec 022:

```text
ACL SETUSER gateway on >${REDIS_PASSWORD} resetchannels ~spring:session:* -@all
  +auth +hello +ping +select +client|setname +client|setinfo
  +hgetall +hget +hset +hmset +hdel +del +exists +expire +pexpire +rename
  +set +get +incr +evalsha +script|load
```

The Redis health check of Spring Boot is off (`management.health.redis.enabled=false`), because the ACL user cannot run `INFO`. Monitor Redis with the tools of your platform.

The gateway keeps session values in Redis as JSON. It does not encrypt them. A person with read access to Redis can take over sessions and read tokens. Restrict access to Redis like access to the tokens.

## Logs

The gateway uses Logback. The log masking replaces tokens and secrets with `***`. Without the Spring profile `local`, the log guard sets the level `INFO` on the loggers that can write tokens. See [Security model](security-model.md#log-guard).

Important log lines:

| Level | Logger | Message starts with | Meaning |
|---|---|---|---|
| `INFO` | `OpenApiRouteDefinitionLocator` | `gateway.openapi.specs:` | The OAS locations of this instance. |
| `INFO` | `OpenApiRouteDefinitionLocator` | `Created ... route(s) from ... OAS file(s)` | The routes are ready. |
| `ERROR` | `OpenApiRouteDefinitionLocator` | `<rule-id> <file>#<pointer> <message>` | A contract violation. The gateway does not start. |
| `WARN` | `OpenApiRouteDefinitionLocator` | `The route ... relays the access token ... without TLS` | An `http` upstream with an audience. |
| `WARN` | `SessionLoginConfiguration` | `Profile 'local' is active.` | Never in a deployed gateway. |
| `WARN` | `LogGuard` | `The log guard set the level INFO for loggers that can write tokens or secrets` | A log configuration asked for `DEBUG` or `TRACE` on a token logger. |
| `WARN` | `MemorySessionRepository` | `The in-memory session store holds ...` | The memory store is full. |
| `WARN` | `AudienceTokenConfiguration` | `The JSON Web Key Set of the registration entra did not load at startup` | Entra ID was not reachable at startup. |
| `ERROR` | `RouteOperationFilter` | `The route ... is not the route ... of the access rule.` | A defect. The request got `404`. |
| `ERROR` | `AudienceTokenRelayGatewayFilterFactory` | `The gateway has no access token of the audience ...` | A defect. The request got `500`. |

### Denial logs

Each denied request writes one line:

```text
Access denied: reason=MISSING_PERMISSION method=DELETE path=/orders-api/orders/42 principal=<oid> route=orders:/orders/{id}
```

The line has the deny reason, the method, the path, the `oid` of the user (or `-`), and the route id. A token error adds the registration, the status, and the error codes of Entra ID. A line never contains a token.

| Level | Deny reasons |
|---|---|
| `DEBUG` | `NO_MATCH`, `NOT_AUTHENTICATED` |
| `INFO` | `MISSING_PERMISSION`, `SESSION_INVALID`, `CONSENT_REQUIRED`, `REAUTHENTICATION_REQUIRED` |
| `WARN` | `TOKEN_GRANT_LIMIT`, `TOKEN_ENDPOINT_TIMEOUT`, `TOKEN_ENDPOINT_ERROR`, `TOKEN_ENDPOINT_THROTTLED` |
| `ERROR` | `TOKEN_REQUEST_REJECTED`, `INVALID_AUDIENCE_TOKEN`, `WRONG_AUDIENCE`, `INTERNAL_ERROR` |

[Security model](security-model.md#authorization-decision) explains each reason.

## Metrics

The gateway records these Micrometer counters:

| Counter | Tag | Meaning |
|---|---|---|
| `gateway.authorization.denied` | `reason` | One denied request. All deny reasons except `WRONG_AUDIENCE`. |
| `gateway.authorization.audience.mismatch` | `route` | A token with the wrong `aud`. |
| `gateway.token.grants` | `result` | One refresh token grant to Entra ID. |

The actuator exposes only `health`. To read these counters, add `metrics` to the exposed endpoints (`include: health,metrics`), or add a Micrometer registry for your monitoring system. The project does neither. Do not expose other endpoints. See [Ports](#ports).

## Backend connections

The gateway calls the backends with a fixed connection pool. The values are in [Configuration reference](configuration.md#spring-boot-properties-that-the-gateway-sets).

- `max-idle-time` (`30s`) MUST be lower than the keep-alive timeout of every backend. Otherwise a backend can close a connection that the gateway still uses.
- The global response timeout is `10s`. An operation can set its own value with `x-gateway-response-timeout`.
- The gateway does not buffer request or response bodies.

## Open questions

- How the single-page application is served is not documented in the code or the specs. The redirect targets `/` and `/?login=failed` need to be reachable without a session. If no public operation or other server answers `/`, the browser gets `404` after logout.
- Spec 022 asks for an alert on the number of sessions in Redis. The threshold is an open question in spec 022.
