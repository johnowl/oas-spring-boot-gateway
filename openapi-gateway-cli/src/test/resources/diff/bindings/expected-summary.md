### Audience bindings

The gateway sends the token of each audience to its upstream. Check that each upstream is the backend of the audience.

| Change | Audience | Upstream (base) | Upstream (head) | Prefix (base) | Prefix (head) | Upstream base path (base) | Upstream base path (head) | File |
|---|---|---|---|---|---|---|---|---|
| binding-changed | ` 6f1c2e8a-0000-0000-0000-000000000000 ` | ` http://orders-service:8080 ` | ` http://reporting-service:8080 ` | ` /orders-api ` | ` /orders-api ` | none | none | ` src/test/resources/diff/bindings/head/orders.yaml ` |
| binding-new | ` 7a2d3f9b-0000-0000-0000-000000000000 ` | none | ` http://payments-service:8080 ` | none | ` /payments-api ` | none | none | ` src/test/resources/diff/bindings/head/payments.yaml ` |
| binding-changed | none | ` http://health-service:8080 ` | ` http://status-service:8080 ` | ` /health-api ` | ` /health-api ` | none | none | ` src/test/resources/diff/bindings/head/health.yaml ` |

