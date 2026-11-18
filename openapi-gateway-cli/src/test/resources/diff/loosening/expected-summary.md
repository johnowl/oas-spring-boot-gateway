### Access loosening changes

Each change makes an operation easier to call. Check that each change is intended.

| Change | Method | Public path | Details | File | Pointer |
|---|---|---|---|---|---|
| permission-removed-from-all-of | ` DELETE ` | ` /orders-api/orders/{id} ` | ` Orders.Delete removed from all-of ` | ` src/test/resources/diff/loosening/head/orders.yaml ` | ` /paths/~1orders~1{id}/delete ` |
| permission-removed-from-all-of | ` DELETE ` | ` /orders-api/orders/{id} ` | ` Orders.Write removed from all-of ` | ` src/test/resources/diff/loosening/head/orders.yaml ` | ` /paths/~1orders~1{id}/delete ` |
| became-public | ` GET ` | ` /orders-api/orders/{id} ` | ` base: audience=6f1c2e8a-0000-0000-0000-000000000000, all-of=[Orders.Read]; head: public ` | ` src/test/resources/diff/loosening/head/orders.yaml ` | ` /paths/~1orders~1{id}/get ` |
| any-of-removed | ` POST ` | ` /orders-api/orders/{id}/cancel ` | ` base: audience=6f1c2e8a-0000-0000-0000-000000000000, all-of=[Orders.Read, Orders.Write], any-of=[Orders.Cancel]; head: audience=6f1c2e8a-0000-0000-0000-000000000000, all-of=[Orders.Read] ` | ` src/test/resources/diff/loosening/head/orders.yaml ` | ` /paths/~1orders~1{id}~1cancel/post ` |
| permission-removed-from-all-of | ` POST ` | ` /orders-api/orders/{id}/cancel ` | ` Orders.Write removed from all-of ` | ` src/test/resources/diff/loosening/head/orders.yaml ` | ` /paths/~1orders~1{id}~1cancel/post ` |
| authenticated-only-replaced-permissions | ` GET ` | ` /orders-api/orders/{id}/notes ` | ` base: audience=6f1c2e8a-0000-0000-0000-000000000000, any-of=[Orders.Refund]; head: audience=6f1c2e8a-0000-0000-0000-000000000000, authenticated-only ` | ` src/test/resources/diff/loosening/head/orders.yaml ` | ` /paths/~1orders~1{id}~1notes/get ` |
| permission-added-to-any-of | ` POST ` | ` /orders-api/orders/{id}/refund ` | ` Finance.Admin added to any-of ` | ` src/test/resources/diff/loosening/head/orders.yaml ` | ` /paths/~1orders~1{id}~1refund/post ` |
| audience-changed | ` GET ` | ` /payments-api/payments/{id} ` | ` base: audience=7a2d3f9b-0000-0000-0000-000000000000, all-of=[Payments.Read]; head: audience=8b3e4a0c-0000-0000-0000-000000000000, all-of=[Payments.Read] ` | ` src/test/resources/diff/loosening/head/payments.yaml ` | ` /paths/~1payments~1{id}/get ` |

### Audience bindings

The gateway sends the token of each audience to its upstream. Check that each upstream is the backend of the audience.

| Change | Audience | Upstream (base) | Upstream (head) | Prefix (base) | Prefix (head) | Upstream base path (base) | Upstream base path (head) | File |
|---|---|---|---|---|---|---|---|---|
| binding-new | ` 8b3e4a0c-0000-0000-0000-000000000000 ` | none | ` http://payments-service:8080 ` | none | ` /payments-api ` | none | none | ` src/test/resources/diff/loosening/head/payments.yaml ` |

