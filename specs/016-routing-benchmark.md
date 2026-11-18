# 016 — Routing benchmark

| Field | Value |
|---|---|
| Status | Draft |
| Phase | 5 — Path trie and route lookup performance |
| Depends on | [013](013-oas-route-locator.md), [015](015-path-trie.md) |
| Modules | `gateway`, `benchmarks/` (new folder for the k6 script and results) |

## Summary

This spec measures the cost of the Spring Cloud Gateway route lookup for large route sets. A Java Microbenchmark Harness (JMH) benchmark measures `RoutePredicateHandlerMapping.lookupRoute` with 100, 1,000, and 5,000 routes. A k6 load test measures the end-to-end latency. The results go into a versioned results file. The results document the gain of the indexed route lookup in [017](017-indexed-route-lookup.md). 017 is required (see "Decisions"), so the results do not decide if the project implements it.

## Motivation

`RoutePredicateHandlerMapping.lookupRoute` evaluates the routes in order until one predicate matches. The cost grows with the number of routes. A request to an undocumented path evaluates every route and then returns 404, so 404 is the worst case. A client can send many 404 requests on purpose. The project does not know today if this cost is important compared to the network and the backend. A replacement of the lookup adds code and risk, so the project needs data before it decides.

## Scope

### In scope

- JMH benchmark of `lookupRoute` for N = 100, 1,000, and 5,000 routes, with three scenarios: first match, last match, and 404.
- JMH benchmark of `PathTrie.match` ([015](015-path-trie.md)) with the same route sets, as a comparison point.
- A synthetic route generator that produces OAS files with realistic paths.
- A k6 script for an end-to-end load test against a gateway with a mock backend.
- A results file with the environment, the numbers, and the result of the decision gate for [017](017-indexed-route-lookup.md).

### Out of scope

- The indexed route lookup itself. See [017](017-indexed-route-lookup.md).
- Benchmarks of authentication and authorization ([022](022-session-login.md), [024](024-authorization-manager.md)).
- Benchmarks in the PR pipeline. The benchmark runs manually.

## Design

### Synthetic route sets

Class `SyntheticSpecs` (test code in `gateway`) generates N routes from OAS files with a fixed seed. The mix is:

| Share | Path form | Example |
|---|---|---|
| 50% | literal | `/svc-17-api/orders/summary` |
| 40% | one variable | `/svc-17-api/orders/{id}` |
| 10% | two variables | `/svc-17-api/orders/{id}/items/{itemId}` |

Each spec has a different `x-gateway.prefix`, with 10 to 20 paths per spec. The last spec can have fewer paths, so that the total is exactly N. Each path has 1 to 3 methods. The generated specs MUST pass the validator ([005](005-validation-engine.md)), so the routes are the same routes that the locator ([013](013-oas-route-locator.md)) produces in production.

### Scenarios

| Scenario | Request | What it measures |
|---|---|---|
| `firstMatch` | Path and method of the first route in route order | Best case of the linear lookup |
| `lastMatch` | Path and method of the last route in route order | Worst case for a documented path |
| `notFound` | `/unknown-api/does/not/exist` | Worst case: all routes evaluated, then 404 |

### JMH benchmark

Class `RouteLookupBenchmark` in `gateway/src/jmh/java` (see "Open questions" for the location).

- `@State(Scope.Benchmark)` creates the routes through the real `OpenApiRouteDefinitionLocator` and `RouteDefinitionRouteLocator`, then caches them.
- The benchmark calls `lookupRoute` through a small subclass of `RoutePredicateHandlerMapping` in test code, because the method is `protected`.
- Each invocation uses a new `MockServerWebExchange`, because predicates write exchange attributes.
- `@Param({"100", "1000", "5000"})` for N, and `@Param({"firstMatch", "lastMatch", "notFound"})` for the scenario.
- Modes: `Mode.AverageTime` and `Mode.SampleTime` (for p99), unit microseconds.
- Settings: `@Fork(2)`, `@Warmup(iterations = 5)`, `@Measurement(iterations = 10)`.
- The benchmark calls `.block()` on the result. Blocking is acceptable here because the benchmark does not run on a Netty event loop.

Class `PathTrieBenchmark` measures `PathTrie.match` with the same route sets and scenarios.

The benchmark runs with a Maven profile:

```bash
benchmarks/run-jmh.sh -rf json -rff benchmarks/results/jmh-routing.json
```

Decision (2026-11-12): the JMH code lives in `gateway/src/jmh/java`. The Maven profile `jmh` of the `gateway` module:

- adds `src/jmh/java` as a test source folder (`build-helper-maven-plugin`);
- adds `jmh-core` in test scope, and `jmh-generator-annprocess` in `annotationProcessorPaths` (Java 23 and later do not run annotation processors from the classpath);
- writes the test classpath to `gateway/target/jmh-classpath.txt` (`maven-dependency-plugin:build-classpath`).

The script `benchmarks/run-jmh.sh` compiles with the profile and runs `org.openjdk.jmh.Main` with that classpath. It passes its arguments to JMH. The project does not build a `benchmarks.jar`, because a shaded jar with the Spring Boot application is hard to build correctly.

Implementation details:

- `RouteLookupBenchmark` starts the full application (`SpringApplicationBuilder`, random ports) with `gateway.openapi.specs` on the generated files, and gets the `RoutePredicateHandlerMapping` bean. `lookupRoute` is `protected`, so the test class `org.springframework.cloud.gateway.handler.RouteLookupAccess` (same package, `src/jmh/java`) calls it.
- `BenchmarkRequests` selects the request of each scenario from the routes sorted by order. Variables in the path become `x42`.
- `SyntheticSpecs` is in `gateway/src/test/java`, so that the normal build tests it.

### k6 load test

Script `benchmarks/k6/routing.js`. `RATE` has the default value 1000. `k6 inspect` (Docker image `grafana/k6`) loads the script without errors. The test environment runs:

1. A mock backend that returns `200` with a small fixed body.
2. The gateway with N generated routes that point to the mock backend.
3. k6 on a separate machine or container, with a `constant-arrival-rate` executor.

The script stops at once when `BASE_URL` or `LAST_PATH` is missing. Each request has a `check` on the status: 200 for `lastMatch` and 404 for `notFound`. The thresholds `checks{scenario:<name>}: ['rate==1']` make k6 exit with a non-zero code when one status is wrong, so a wrong `LAST_PATH` cannot produce `lastMatch` numbers. The empty thresholds on `http_req_duration{scenario:<name>}` make k6 print p50, p95, and p99 for each scenario. The example below shows only the structure:

```javascript
import http from 'k6/http';

export const options = {
  scenarios: {
    lastMatch: { executor: 'constant-arrival-rate', rate: __ENV.RATE, timeUnit: '1s',
                 duration: '2m', preAllocatedVUs: 200, exec: 'lastMatch' },
    notFound:  { executor: 'constant-arrival-rate', rate: __ENV.RATE, timeUnit: '1s',
                 duration: '2m', preAllocatedVUs: 200, exec: 'notFound', startTime: '2m30s' },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function lastMatch() { http.get(`${__ENV.BASE_URL}${__ENV.LAST_PATH}`); }
export function notFound()  { http.get(`${__ENV.BASE_URL}/unknown-api/does/not/exist`); }
```

The k6 run repeats for N = 100, 1,000, and 5,000 at the same arrival rate. The test compares p99 latency between the values of N.

### Results file

File `benchmarks/results/routing.md` records:

- Date (ISO 8601), commit SHA, machine type, CPU count, operating system.
- Java version, Spring Boot version, Spring Cloud Gateway version, JVM options.
- JMH table: N × scenario × (average, p99) for `lookupRoute` and for `PathTrie.match`.
- k6 table: N × scenario × (p50, p95, p99, requests per second, error rate).
- The result of the decision gate for [017](017-indexed-route-lookup.md) and the criterion that applied.
- Raw JMH JSON and k6 summary JSON next to the file.

### Decision gate for 017

The gate is informational. [017](017-indexed-route-lookup.md) is required, independent of the gate (see "Decisions"). The results file records which criterion is true at the target of about 10,000 routes:

| Id | Criterion | Proposed threshold |
|---|---|---|
| D1 | JMH `lookupRoute` p99 for `notFound` | greater than 50 µs |
| D2 | k6 p99 for `notFound` at the expected N compared to N = 100 | more than 10% higher |
| D3 | k6 p99 for `lastMatch` at the expected N compared to N = 100 | more than 10% higher |

If no criterion is true, [017](017-indexed-route-lookup.md) stays required. The team repeats this benchmark when the route count doubles or when the Spring Cloud Gateway version changes.

The thresholds are proposals. The team MUST confirm them before the first run.

## Acceptance criteria

- [x] `SyntheticSpecs` generates N = 100, 1,000, and 5,000 routes, and the generated specs have no violation at ERROR.
- [x] `RouteLookupBenchmark` runs all nine combinations of N and scenario and writes JMH JSON output.
- [x] In the `notFound` scenario, the benchmark verifies once in setup that `lookupRoute` returns no route.
- [x] In the `firstMatch` and `lastMatch` scenarios, the benchmark verifies once in setup that `lookupRoute` returns the expected route id, and that this route is the first or the last route of the running gateway (`RouteLocator.getRoutes()`).
- [x] `PathTrieBenchmark` runs the same nine combinations.
- [ ] `benchmarks/k6/routing.js` runs with `k6 run` and reports p50, p95, and p99 for each scenario.
- [ ] `benchmarks/results/routing.md` contains all fields listed in "Results file".
- [ ] `benchmarks/results/routing.md` states the result of the decision gate for [017](017-indexed-route-lookup.md) with the criterion id (D1, D2, D3, or "none").
- [x] `./mvnw -B verify` without the `jmh` profile does not run the benchmarks.

## Test plan

- Unit test of `SyntheticSpecs`: same seed gives the same specs; the specs pass validation; route counts are correct.
- Setup checks inside the benchmarks (see acceptance criteria), so that a broken setup cannot produce wrong numbers.
- Manual run of JMH and k6 on a documented machine. The PR that adds the results file contains the raw output.

## Delivery

Two PRs:

1. Benchmark code: Maven profile `jmh`, `SyntheticSpecs`, `RouteLookupBenchmark`, `PathTrieBenchmark`, k6 script (about 350 lines).
2. Results: `benchmarks/results/routing.md` and raw output. The status of [017](017-indexed-route-lookup.md) does not change in this PR.

## Decisions (2026-11-12)

- Spec [017](017-indexed-route-lookup.md) is required, independent of the decision gate. The smoke run showed linear cost in `lookupRoute` (about 0.1 µs per route; 106 µs for a 404 with 1,000 routes, 508 µs with 5,000) and a constant cost in the trie (about 0.1 µs). The target is about 10,000 routes.

## Open questions

- Are the proposed thresholds (50 µs, 10%) correct for the latency budget of the gateway?
- Which machine runs the official benchmark? Results on a developer laptop are not comparable with production.
- Does the benchmark include the indexed lookup of [017](017-indexed-route-lookup.md) later, to confirm the gain? This spec proposes "yes", in the PR of [017](017-indexed-route-lookup.md).
- With `UndocumentedOperationWebFilter` ([024](024-authorization-manager.md)), a request to an undocumented path gets 404 before route lookup. Is the `notFound` scenario (criteria D1 and D2) still relevant for the decision about [017](017-indexed-route-lookup.md)?

## References

- [013 — OAS route locator](013-oas-route-locator.md)
- [015 — Path trie](015-path-trie.md)
- [017 — Indexed route lookup](017-indexed-route-lookup.md)
- [024 — Authorization manager](024-authorization-manager.md)
- Spring Cloud Gateway `RoutePredicateHandlerMapping` source code (version from [001](001-project-skeleton.md)).
- JMH: <https://github.com/openjdk/jmh>
- k6: <https://grafana.com/docs/k6/latest/>
