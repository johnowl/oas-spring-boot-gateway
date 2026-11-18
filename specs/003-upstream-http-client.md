# 003 — Upstream HTTP client

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 1 — Foundation |
| Depends on | [002](002-gateway-bootstrap.md) |
| Modules | `gateway` |

## Summary

This spec configures the HTTP client that the gateway uses to call backends. It sets an explicit connect timeout and a global response timeout. It also sets a fixed connection pool with a maximum size, an acquire timeout, a maximum idle time, and a maximum life time. It also supports a per-route `response-timeout` in route metadata. Tests prove the behavior when a backend is too slow. Without these limits, one slow backend can use all connections and threads, and the latency of all routes increases.

## Motivation

The default HTTP client settings are not safe for production:

- Without a response timeout, a request to a backend that never answers stays open.
- Without a connection limit, a slow backend can open an unbounded number of connections.
- If the gateway keeps an idle connection longer than the backend keep-alive, the backend closes the connection first. The next request on that connection then fails with a connection reset.

Some operations need a different timeout, for example a report export. The OAS extension `x-gateway-response-timeout` declares this timeout (see [006](006-x-gateway-extension.md)). The gateway must apply it per route.

## Scope

### In scope

- Global HTTP client properties with documented default values.
- A fixed connection pool.
- Per-route `response-timeout` metadata, tested with routes in test configuration.
- Tests for response timeout, connect timeout, and pool acquire timeout.

### Out of scope

- Generation of the `response-timeout` metadata from OAS files. See [013](013-oas-route-locator.md).
- Retries and circuit breakers. They are not in the design brief.
- Body buffering. The gateway MUST NOT buffer request or response bodies.

## Design

### Global HTTP client properties

The gateway configures the client with Spring Cloud Gateway properties in `gateway/src/main/resources/application.yaml`. The application does not create its own `HttpClient` bean.

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          httpclient:
            connect-timeout: 2000          # milliseconds
            response-timeout: 10s
            pool:
              type: FIXED
              max-connections: 500
              acquire-timeout: 5000        # milliseconds
              max-idle-time: 30s
              max-life-time: 5m
```

Spring Cloud Gateway 5.0.x (Spring Cloud 2025.1.x) uses the prefix `spring.cloud.gateway.server.webflux.httpclient`. Older versions use `spring.cloud.gateway.httpclient`. The configuration metadata of version 5.0.4 gives these types: `connect-timeout` is an integer in milliseconds, `pool.acquire-timeout` is a long in milliseconds, and `response-timeout`, `pool.max-idle-time`, and `pool.max-life-time` are durations. The default `pool.type` is `elastic`, so the project MUST set `FIXED`.

### Default values

The values below are the defaults in `application.yaml`. They are still proposals: the team MUST confirm them before the first production release. Operators can override each value (see "Configuration").

| Property | Proposed value | Reason |
|---|---|---|
| `connect-timeout` | `2000` ms | Backends run in the same network. A TCP connection that takes longer indicates a problem. |
| `response-timeout` | `10s` | Upper limit for normal operations. Slow operations declare `x-gateway-response-timeout`. |
| `pool.type` | `FIXED` | Bounded pool. `ELASTIC` has no upper limit. |
| `pool.max-connections` | `500` | Limit of open connections for each backend address. |
| `pool.acquire-timeout` | `5000` ms | A request waits at most this time for a free connection. |
| `pool.max-idle-time` | `30s` | MUST be lower than the keep-alive timeout of every backend. |
| `pool.max-life-time` | `5m` | Closes old connections, so that new backend instances receive traffic after scaling or DNS changes. |

Reactor Netty keeps one connection pool for each remote address. `max-connections` is therefore a limit for each backend address, not a global limit.

### Per-route response timeout

Spring Cloud Gateway reads the route metadata key `response-timeout` (milliseconds). A route value replaces the global `response-timeout` for that route.

```yaml
routes:
  - id: slow-report
    uri: http://localhost:${mock.backend.port}
    predicates:
      - Path=/reports/**
    metadata:
      response-timeout: 30000
```

In [013](013-oas-route-locator.md), the route locator sets this metadata from `GatewayRoute.responseTimeout` (see [007](007-route-model.md)). This spec only verifies that the metadata works.

## Behavior and errors

| Condition | Expected result | Status |
|---|---|---|
| Backend answers within the timeout | The gateway streams the backend response to the client. | Backend status |
| Backend sends no response headers within the response timeout | The gateway cancels the backend request and returns `504 Gateway Timeout`. | Verified by test |
| Route has `response-timeout` metadata | The gateway uses the route value, not the global value. | Verified by test |
| Backend does not accept the TCP connection within `connect-timeout` | Netty raises `ConnectTimeoutException`. The gateway returns `500 Internal Server Error`. | Verified by test |
| No pool connection is free within `acquire-timeout` | Reactor Netty raises `PoolAcquireTimeoutException`. The gateway returns `500 Internal Server Error`. | Verified by test |

Spring Cloud Gateway maps a response timeout to `504 Gateway Timeout` in the `NettyRoutingFilter`. It does not map the connect timeout or the acquire timeout. These exceptions reach the default WebFlux error handler, which returns `500`. See "Open questions".

## Configuration

| Property (relative to the `httpclient` prefix) | Type | Default in this project | Meaning |
|---|---|---|---|
| `connect-timeout` | integer (ms) | `2000` | Maximum time to open a TCP connection to a backend. |
| `response-timeout` | duration | `10s` | Maximum time to receive the response headers. |
| `pool.type` | enum | `FIXED` | Pool type. |
| `pool.max-connections` | integer | `500` | Maximum connections for each backend address. |
| `pool.acquire-timeout` | integer (ms) | `5000` | Maximum wait for a free connection. |
| `pool.max-idle-time` | duration | `30s` | Idle time after which the gateway closes a connection. |
| `pool.max-life-time` | duration | `5m` | Maximum age of a connection. |
| Route metadata `response-timeout` | integer (ms) | not set | Response timeout of one route. |

Operators MAY override each property with environment variables or an external configuration file.

## Acceptance criteria

- [x] `application.yaml` contains all properties of the "Configuration" table with the documented values.
- [x] A backend waits longer than the global response timeout. The integration test receives `504`, or the status recorded in "Behavior and errors".
- [x] A route has metadata `response-timeout: 500`, and the backend delay is 1 second. The integration test receives the timeout status after approximately 500 ms, not after the global timeout.
- [x] An integration test with route metadata `response-timeout: 3000` and a backend delay of 1 second receives the backend response with status `200`.
- [x] An integration test with a connect timeout to an address that does not answer records the actual status in this spec.
- [x] An integration test uses `pool.max-connections: 1` and sends a second request while a slow request is in progress. This spec records the actual status for the acquire timeout.
- [x] No test or production code reads the full request or response body into memory in the proxy path.

## Test plan

- The test helper `DelayingMockBackend` is a Reactor Netty `HttpServer`. It answers `200 ok` after the delay in milliseconds that the query parameter `delay` gives. The delay does not block.
- The test profile `upstream-test` (`application-upstream-test.yaml`) sets `connect-timeout: 300`, `response-timeout: 1s`, and four routes: `/global/**` (no metadata), `/short/**` (`response-timeout: 500`), `/long/**` (`response-timeout: 3000`), and `/unreachable/**`.
- Integration test `UpstreamTimeoutIT`: global response timeout, per-route timeout lower than the global value, per-route timeout higher than the backend delay, and connect timeout.
- Integration test `UpstreamPoolIT`: pool acquire timeout. It uses a separate application context with `pool.max-connections=1` and `pool.acquire-timeout=200`.
- For the connect timeout, the route `/unreachable/**` uses `http://192.0.2.1:81`. The address is in TEST-NET-1 (RFC 5737) and is never routed, so the connection does not answer on CI or on developer machines. The test also asserts that the error comes before the response timeout.
- BlockHound is active, as in [002](002-gateway-bootstrap.md).

## Delivery

One PR. Main files: `application.yaml`, `UpstreamTimeoutIT.java`, `UpstreamPoolIT.java`, `DelayingMockBackend.java`, test configuration. Estimate: fewer than 250 changed lines.

## Open questions

- What are the final values for the timeouts and the pool? The values in this spec are proposals.
- What is the keep-alive timeout of each backend? `pool.max-idle-time` MUST be lower than the lowest value.
- Must the gateway map the connect timeout and the acquire timeout to other statuses? Today both return `500`, so clients and dashboards cannot separate them from gateway bugs. A proposal: `502 Bad Gateway` for the connect timeout and `503 Service Unavailable` for the acquire timeout. This needs a small `ErrorWebExceptionHandler` or a global filter, in a new spec.
- Which response body format does the gateway use for timeout errors? `application/problem+json` is used for `403` in [024](024-authorization-manager.md), but the brief does not define it for timeouts.
- Does the project need `pool.max-pending-acquires` or `pool.eviction-interval`? The brief does not mention them.

## References

- [002 — Gateway bootstrap](002-gateway-bootstrap.md)
- [006 — x-gateway extension](006-x-gateway-extension.md)
- [007 — Route model](007-route-model.md)
- [013 — OAS route locator](013-oas-route-locator.md)
- Spring Cloud Gateway, HTTP timeouts configuration: https://docs.spring.io/spring-cloud-gateway/reference/
- Reactor Netty connection pool: https://projectreactor.io/docs/netty/release/reference/
