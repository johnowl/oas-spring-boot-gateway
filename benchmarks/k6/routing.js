// End-to-end routing load test of spec 016.
// Environment: BASE_URL (gateway), LAST_PATH (path of the last route in route order), RATE (requests per second, default 1000).
// Example: k6 run -e BASE_URL=http://gateway:8080 -e LAST_PATH=/svc-9-api/orders-3/x42 -e RATE=2000 benchmarks/k6/routing.js
// k6 exits with a non-zero code when a response has an unexpected status, so a wrong BASE_URL or
// LAST_PATH cannot produce numbers.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL;
const LAST_PATH = __ENV.LAST_PATH;
const RATE = Number(__ENV.RATE || 1000);

if (!BASE_URL || !/^https?:\/\/[^/]+$/.test(BASE_URL)) {
  throw new Error('Set BASE_URL to the gateway address without a path, for example -e BASE_URL=http://gateway:8080');
}
if (!LAST_PATH || !LAST_PATH.startsWith('/')) {
  throw new Error('Set LAST_PATH to the path of the last route, for example -e LAST_PATH=/svc-9-api/orders-3/x42');
}
if (!Number.isInteger(RATE) || RATE <= 0) {
  throw new Error('RATE must be a positive integer');
}

export const options = {
  scenarios: {
    lastMatch: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s',
      duration: '2m', preAllocatedVUs: 200, exec: 'lastMatch',
    },
    notFound: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s',
      duration: '2m', preAllocatedVUs: 200, exec: 'notFound', startTime: '2m30s',
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // Each response must have the expected status. k6 tags each request with its scenario.
    'checks{scenario:lastMatch}': ['rate==1'],
    'checks{scenario:notFound}': ['rate==1'],
    // No limit: these entries make k6 print p50, p95 and p99 for each scenario.
    'http_req_duration{scenario:lastMatch}': [],
    'http_req_duration{scenario:notFound}': [],
  },
};

export function lastMatch() {
  const res = http.get(`${BASE_URL}${LAST_PATH}`);
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function notFound() {
  const res = http.get(`${BASE_URL}/unknown-api/does/not/exist`);
  check(res, { 'status 404': (r) => r.status === 404 });
}
