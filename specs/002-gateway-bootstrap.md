# 002 — Gateway bootstrap

| Field | Value |
|---|---|
| Status | Implemented |
| Phase | 1 — Foundation |
| Depends on | [001](001-project-skeleton.md) |
| Modules | `gateway` |

## Summary

This spec completes the gateway application that [001](001-project-skeleton.md) creates in the `gateway` module. The application uses Spring Cloud Gateway Server WebFlux on Reactor Netty. This spec adds the actuator health endpoint on a separate management port and the native Netty transport. A test proves that one test route forwards a request to a mock backend. A second test proves that Spring MVC and Tomcat are not on the classpath. This spec gives later specs a working, non-blocking base to add routes and security.

## Motivation

Performance is a primary requirement. The gateway must run on the non-blocking Reactor Netty stack. If `spring-boot-starter-web` comes onto the classpath, Spring Boot can start a servlet stack, and the performance model breaks. A test must detect this early. The native transport (epoll on Linux, kqueue on macOS) reduces overhead compared with the Java NIO transport.

## Scope

### In scope

- Dependency: `spring-boot-starter-actuator`.
- Native transport dependencies for Linux (epoll) and macOS (kqueue).
- Actuator health endpoint at `/actuator/health` on the management port `8081`.
- An integration test with one test route to a mock backend. The route exists only in test configuration.
- A test that Spring MVC and Tomcat are not on the classpath.

### Out of scope

- The `GatewayApplication` class, the `spring-cloud-starter-gateway-server-webflux` dependency, the executable jar, and the `contextLoads` test. [001](001-project-skeleton.md) delivers them.
- Upstream HTTP client timeouts and pool configuration. See [003](003-upstream-http-client.md).
- Routes from OAS files and 404 for undocumented paths. See [013](013-oas-route-locator.md).
- Security. See [022](022-session-login.md).

## Design

### Starting point

[001](001-project-skeleton.md) delivers these parts of the `gateway` module:

- The `GatewayApplication` class in the base package `com.johnowl.gateway`.
- `application.yaml` with `spring.application.name: gateway`.
- The test `GatewayApplicationTests.contextLoads`.
- The dependencies `openapi-gateway-core`, `spring-cloud-starter-gateway-server-webflux`, `spring-boot-starter-test`, and `reactor-test`.
- The `spring-boot-maven-plugin` with the `repackage` execution, which builds the executable jar.

This spec does not change these parts, except for the new configuration in the application configuration file.

### Dependencies

This spec adds these dependencies:

| Dependency | Scope | Reason |
|---|---|---|
| `org.springframework.boot:spring-boot-starter-actuator` | compile | Health endpoint. |
| `io.netty:netty-transport-native-epoll` (Linux classifiers) | runtime | Native transport on Linux. |
| `io.netty:netty-transport-native-kqueue` (macOS classifiers) | runtime | Native transport on macOS. |
| `io.projectreactor.tools:blockhound-junit-platform` | test | Installs BlockHound for every test. Detects blocking calls on Netty threads. |

The BOMs from [001](001-project-skeleton.md) manage all versions. The module MUST NOT declare `spring-boot-starter-web`.

### Native transport

Reactor Netty uses the native transport when the native library is on the classpath and loads correctly. The module adds the epoll and kqueue artifacts with the classifiers for each target platform. The application does not need code for this.

Decision (2026-11-12): the module ships these classifiers. They are the same platforms for which Reactor Netty ships its native QUIC codec.

| Artifact | Classifiers |
|---|---|
| `io.netty:netty-transport-native-epoll` | `linux-x86_64`, `linux-aarch_64` |
| `io.netty:netty-transport-native-kqueue` | `osx-x86_64`, `osx-aarch_64` |

Reactor Netty already brings epoll for `linux-x86_64`. The module declares it again so that the list of platforms is in one place.

### Actuator

Decision (2026-11-10): the actuator runs on a separate management port.

- The application exposes only the `health` endpoint. Orchestrators use it for the liveness and readiness probes.
- Actuator endpoints are not gateway routes. They are not behind the session security chain ([022](022-session-login.md)) or the `UndocumentedOperationWebFilter` ([024](024-authorization-manager.md)).
- A client can call the health endpoint without a session. Network policy restricts access to the management port.

```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health
```

The project has no security configuration yet. [022](022-session-login.md) MUST verify that the session security chain does not apply to the management port.

### Test route and mock backend

The test starts a mock backend with the Reactor Netty `HttpServer` on a random port. A `@DynamicPropertySource` method starts it and sets the property `mock.backend.port`. This choice adds no new test library. The test defines one route in a test profile. The route is not in `src/main/resources`.

```yaml
# gateway/src/test/resources/application-bootstrap-test.yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: bootstrap-test
              uri: http://localhost:${mock.backend.port}
              predicates:
                - Path=/test/**
```

The property prefix `spring.cloud.gateway.server.webflux` depends on the Spring Cloud Gateway version. Older versions use `spring.cloud.gateway`. The configuration metadata of `spring-cloud-gateway-server-webflux` 5.0.4 (Spring Cloud 2025.1.4, see [001](001-project-skeleton.md)) contains `spring.cloud.gateway.server.webflux.routes`.

### No servlet stack

The `gateway` classpath contains `tomcat-embed-el` (see [001](001-project-skeleton.md)). This artifact is not a servlet container. The class `org.apache.catalina.startup.Tomcat` is in `tomcat-embed-core`, so `tomcat-embed-el` does not make the classpath test fail.

### BlockHound

The `blockhound-junit-platform` artifact installs BlockHound when the JUnit Platform starts, before any application context starts. This applies to all tests in the `gateway` module, unit tests and integration tests. A blocking call on a Netty thread then fails the test.

The Spring Boot and Reactor BOMs do not manage BlockHound. The parent `pom.xml` manages it with the property `blockhound.version` (`1.0.18.RELEASE`). BlockHound 1.0.11 and earlier do not work on Java 25: their shaded Byte Buddy supports class files up to Java 24 only.

The `argLine` property of the `gateway` module adds these JVM options for Surefire and Failsafe:

- `-XX:+AllowRedefinitionToAddDeleteMethods`: BlockHound needs it on JDK 13 and later.
- `-XX:+EnableDynamicAgentLoading`: BlockHound loads a Java agent after the JVM starts.

The integration test `blockHoundIsActive` proves that BlockHound is installed: a `Thread.sleep` on a Reactor parallel thread fails with `BlockingOperationError`.

The test class `GatewayBlockHoundIntegration` lists the calls that BlockHound allows. Each call is a short lock around work in memory, never I/O. Spring Core allows the locks of its `ConcurrentReferenceHashMap` in the same way. The list has one entry:

- `tools.jackson.databind.deser.DeserializerCache._createAndCacheValueDeserializer`: Jackson builds a deserializer under this lock. It does not cache some deserializers, for example those of the JDK collections of Spring Security, so it builds them again for each read. Redis reads the session values on its event loop ([022](022-session-login.md), "Session serialization"). Two parallel requests of one session can therefore wait for this lock. A contended `synchronized` block is the same wait, but BlockHound sees only the park of the `ReentrantLock`. Without the entry, BlockHound ended such a request with `500` (CI run 43712577572).

The entry names a method of Jackson, not a mapper. It therefore applies to all Jackson mappers in the tests, not only to the mapper of `SessionJsonSerializer`. It covers only the build and the resolution of a deserializer. It never covers `deserialize()`: Jackson calls `deserialize()` after the method returns. A blocking call in `deserialize()` on a Netty thread or a Reactor thread still fails with `BlockingOperationError`.

A new entry needs the same reason: a lock around work in memory, not I/O.

## Behavior and errors

| Condition | Result |
|---|---|
| `GET /actuator/health` on the management port while the application runs | `200`. The body contains `"status":"UP"` and the `liveness` and `readiness` groups. |
| `GET /actuator/health` on the main server port | The actuator does not answer on the main server port. |
| Request to `/test/echo` in the test profile | The gateway forwards the request to the mock backend and returns the backend response. |
| Request to another actuator endpoint on the management port, for example `/actuator/env` | The endpoint is not exposed. |

## Configuration

| Property | Value | Meaning |
|---|---|---|
| `management.server.port` | `8081` | Separate port for the actuator. |
| `management.endpoints.web.exposure.include` | `health` | Exposes only the health endpoint. |

## Acceptance criteria

- [x] `./mvnw -B -pl gateway -am verify` succeeds.
- [x] The application starts with `java -jar gateway/target/<artifact>.jar` and `GET /actuator/health` on the management port returns `200` with status `UP`.
- [x] `GET /actuator/health` on the main server port does not return the actuator health response.
- [x] An integration test sends `GET /test/echo` through the gateway, and the mock backend receives the request with path `/test/echo`.
- [x] The response of that test contains the status, headers, and body that the mock backend sent.
- [x] A test asserts that the classes `org.springframework.web.servlet.DispatcherServlet` and `org.apache.catalina.startup.Tomcat` are not on the classpath.
- [x] A test asserts that the application context is a reactive web application context.
- [x] On a Linux build agent, a test asserts that `io.netty.channel.epoll.Epoll.isAvailable()` is `true`.
- [x] On macOS, a test asserts that `io.netty.channel.kqueue.KQueue.isAvailable()` is `true`.
- [x] `GET /actuator/env` on the management port returns `404`.
- [x] `src/main/resources` contains no route definition.

## Test plan

- Integration test `GatewayBootstrapIT` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `WebTestClient`): health endpoint on the management port (a free port that the test chooses; the gateway does not start with `management.server.port=0`, [024](024-authorization-manager.md) "Management port"), test route to the mock backend, `/actuator/env` not exposed, no actuator on the main server port.
- Unit test `NoServletStackTest`: classpath checks for `DispatcherServlet` and `Tomcat`.
- Unit test `NativeTransportTest`: `@EnabledOnOs(LINUX)` for epoll and `@EnabledOnOs(MAC)` for kqueue.
- BlockHound is active in all tests of the module. The test `blockHoundIsActive` in `GatewayBootstrapIT` proves it.
- `SessionJsonSerializerTest` holds the lock of the Jackson deserializer cache and reads an authorized client on a Reactor parallel thread. The read waits for the lock and then reads the client. It does not fail with `BlockingOperationError`.
- `SessionJsonSerializerTest` reads a value with a mapper whose test deserializer calls `Thread.sleep` in `deserialize()`, on a Reactor parallel thread. The read fails with `BlockingOperationError`.

## Delivery

One PR. Main files: `gateway/pom.xml`, the application configuration file, test classes, and test configuration. Estimate: fewer than 250 changed lines.

## Open questions

None. The classifiers and the BlockHound setup are decided in "Design".

## References

- [001 — Project skeleton](001-project-skeleton.md)
- [003 — Upstream HTTP client](003-upstream-http-client.md)
- [013 — OAS route locator](013-oas-route-locator.md)
- [024 — Authorization manager](024-authorization-manager.md)
- Spring Cloud Gateway Server WebFlux reference: https://docs.spring.io/spring-cloud-gateway/reference/
- Reactor Netty reference: https://projectreactor.io/docs/netty/release/reference/
- BlockHound: https://github.com/reactor/BlockHound
