# FlowOps AI

A logistics platform being built step by step with Java 21 and Spring Boot.
The current milestone includes PostgreSQL-backed warehouse, product catalog, and
stock balance, movement, and reservation APIs. Order fulfillment, React, Kafka,
Ollama, and Kubernetes are planned; they are not yet implemented.

## Run locally

Prerequisites: Java 21 and a running Docker engine. Use the included Maven wrapper.

```sh
docker compose -f docker/compose.yaml up -d --wait
./mvnw spring-boot:run
```

The API runs on port 8080. PostgreSQL is exposed on localhost port 5433.
Flyway applies migrations automatically; Hibernate validates the resulting schema.
Existing migration files must remain unchanged once applied; add a new migration for schema changes.

Optional database settings: `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`.
Defaults match the local Compose database. The Compose password is for local development.

Stop the database without deleting its data:

```sh
docker compose -f docker/compose.yaml stop
```

## Try the warehouse API

```sh
curl -i http://localhost:8080/api/v1/health
curl -i http://localhost:8080/actuator/health
curl -i -X POST http://localhost:8080/api/v1/warehouses \
  -H 'Content-Type: application/json' \
  -d '{"name":"Berlin Central","city":"Berlin","countryCode":"de"}'
curl -i http://localhost:8080/api/v1/warehouses
```

Creation returns `201 Created` and a `Location` header. Follow that URL to fetch
the warehouse. Repeating a name (case insensitive, within the same tenant) returns
`409`. Invalid input returns `400`; an unknown warehouse returns `404`.
Errors have the shape `{"error":"description"}`.

| Method | Endpoint | Behavior |
| --- | --- | --- |
| GET | `/api/v1/health` | Application identification |
| GET | `/actuator/health` | Application and database health |
| POST | `/api/v1/warehouses` | Create a warehouse |
| GET | `/api/v1/warehouses` | List demo tenant warehouses |
| GET | `/api/v1/warehouses/{id}` | Fetch a warehouse for the demo tenant |

**Local demo only:** authentication is not implemented. Warehouse endpoints use
a fixed server-side tenant ID (`11111111-1111-4111-8111-111111111111`). Clients
cannot choose the tenant. Tenant-scoped queries are tested, but this is not an
authenticated multi-tenant deployment. Add OIDC and derive the tenant from verified
identity claims before exposing business APIs publicly.

## Try the product catalog

A product identifies what can be stored or shipped. Its SKU is unique within a
tenant and normalized to uppercase. Warehouse stock quantities are tracked separately from the product catalog.

```sh
curl -i -X POST http://localhost:8080/api/v1/products \
  -H 'Content-Type: application/json' \
  -d '{"sku":"laptop-001","name":"Business laptop","weightKg":1.250}'
curl -i http://localhost:8080/api/v1/products
```

| Method | Endpoint | Behavior |
| --- | --- | --- |
| POST | `/api/v1/products` | Create a product; returns `201` and its `Location` |
| GET | `/api/v1/products` | List demo tenant products ordered by SKU |
| GET | `/api/v1/products/{id}` | Retrieve a demo tenant product |

Follow the creation response's `Location` to retrieve the product. SKU supports
1–64 ASCII letters, digits, dots, underscores, or hyphens, starting with a letter
or digit. Leading and trailing spaces are trimmed from SKU and name. Name is
required and limited to 150 characters after trimming. `weightKg` is required,
positive, and supports at most six integer and three decimal digits (0.001 to
999999.999). Decimal storage avoids binary floating point rounding in weights.

Duplicate SKU returns `409`, invalid input `400`, and unknown or another tenant's
product `404`. Products share the warehouse API's fixed demo tenant and currently
have no authentication. The list is unpaginated for this initial demo; pagination
is needed before supporting large catalogs.

## Initialize warehouse stock

Create a warehouse and product first using the examples above, then copy their
IDs into these variables:

```sh
WAREHOUSE_ID='<warehouse UUID from the response>'
PRODUCT_ID='<product UUID from the response>'
curl -i -X POST "http://localhost:8080/api/v1/warehouses/$WAREHOUSE_ID/stock" \
  -H 'Content-Type: application/json' \
  -d "{\"productId\":\"$PRODUCT_ID\",\"initialQuantity\":25}"
curl -i "http://localhost:8080/api/v1/warehouses/$WAREHOUSE_ID/stock"
curl -i "http://localhost:8080/api/v1/warehouses/$WAREHOUSE_ID/stock/$PRODUCT_ID"
```

| Method | Endpoint | Behavior |
| --- | --- | --- |
| POST | `/api/v1/warehouses/{warehouseId}/stock` | Initialize a product's stock; returns `201` and `Location` |
| GET | `/api/v1/warehouses/{warehouseId}/stock` | List that warehouse's stock ordered by product ID |
| GET | `/api/v1/warehouses/{warehouseId}/stock/{productId}` | Retrieve that product's stock |

`initialQuantity` is a required nonnegative integer, up to 2147483647. Fractional
JSON quantities are rejected instead of silently truncated. Opening stock can be
zero. Responses include `onHand`, `reserved`, and `available = onHand - reserved`.
`reserved` starts at zero. A version field tracks balance updates. Movement transactions lock the stock row
to serialize concurrent changes.

Opening stock can be created only once per tenant/warehouse/product. A repeated or
simultaneous duplicate request returns `409` and never resets the quantity. The
warehouse and product must both belong to the current demo tenant; missing or
inaccessible references return `404`. An existing warehouse without stock returns
an empty list; looking up an uninitialized product's stock returns `404`.

The database enforces nonnegative stock, `reserved <= onHand`, and a unique stock
record per tenant/warehouse/product. Warehouse and product references are checked
through their service boundaries; there are no cross-module foreign keys. Deletion
of warehouses/products is not exposed. Referential cleanup will need a defined
workflow if deletion is introduced. Lists remain unpaginated for the local demo.

Opening stock also creates an `OPENING` audit entry. Migration V5 backfills an
opening entry from each existing stock balance, preserving its original creation
time. Historical changes made outside FlowOps before that migration cannot be
reconstructed. Business APIs continue to use the fixed demo tenant without authentication.

## Record receipts and adjustments

Once stock is initialized, post changes to its movements endpoint. A receipt adds
units; an adjustment uses a signed quantity to record a correction. Both require a
reason. For example, a receipt of 10 and a damage adjustment of -3 change a balance
of 25 to 32.

```sh
REQUEST_ID=$(uuidgen)
curl -i -X POST "http://localhost:8080/api/v1/warehouses/$WAREHOUSE_ID/stock/$PRODUCT_ID/movements" \
  -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"$REQUEST_ID\",\"type\":\"RECEIPT\",\"quantity\":10,\"reason\":\"Delivery received\"}"
curl -i "http://localhost:8080/api/v1/warehouses/$WAREHOUSE_ID/stock/$PRODUCT_ID/movements"
```

| Method | Endpoint | Behavior |
| --- | --- | --- |
| POST | `/api/v1/warehouses/{warehouseId}/stock/{productId}/movements` | Apply a receipt or adjustment; new movement returns `201` |
| GET | `/api/v1/warehouses/{warehouseId}/stock/{productId}/movements` | List movement history ordered by creation time and ID |

- `requestId`: client-generated UUID, unique per stock record. Reuse it when retrying the same operation.
- `type`: `RECEIPT` (positive quantity) or `ADJUSTMENT` (positive or negative, never zero). `OPENING` is created internally.
- `quantity`: signed integer within the 32-bit range. Fractional values are rejected.
- `reason`: required, trimmed, and limited to 250 characters.

Identical retries return `200` with the original movement and its original balance
snapshot, even after subsequent changes. Reusing the request ID with a different
type, quantity, or normalized reason returns `409`. Generate a new ID for a genuinely
new change; generating a new ID on every retry would apply the quantity again.

Each movement returns its ID, stock ID, request ID, type, signed quantity, before
and after balances, reason, and timestamp. Read the stock endpoint for the current
balance. Adjustments that would reduce `onHand` below `reserved` or overflow the
integer capacity return `409` without writing a movement. Invalid payloads return
`400`; inaccessible warehouses or missing stock return `404`.

Balance changes and movement insertion commit in one database transaction. Row
locking prevents lost updates and serializes duplicate checks. The database also
enforces request uniqueness and valid movement arithmetic. Audit history has no
update/delete API; database administrators can still modify it. Actor identity will
be added with authentication. History is unpaginated for this local demo.

## Reserve and release inventory

A reservation holds units without changing physical stock. All items must have
initialized stock in the same tenant-owned warehouse. A hold either succeeds for
every line or changes nothing.

```sh
RESERVATION_REQUEST_ID=$(uuidgen)
curl -i -X POST "http://localhost:8080/api/v1/warehouses/$WAREHOUSE_ID/reservations" \
  -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"$RESERVATION_REQUEST_ID\",\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":2}]}"
```

Copy the reservation ID from the response, then retrieve or release it:

```sh
RESERVATION_ID='<reservation UUID from the response>'
curl -i "http://localhost:8080/api/v1/warehouses/$WAREHOUSE_ID/reservations/$RESERVATION_ID"
curl -i -X POST "http://localhost:8080/api/v1/warehouses/$WAREHOUSE_ID/reservations/$RESERVATION_ID/release"
```

| Method | Endpoint | Behavior |
| --- | --- | --- |
| POST | `/api/v1/warehouses/{warehouseId}/reservations` | Hold all items; `201` with `Location`, or `200` for an identical retry |
| GET | `/api/v1/warehouses/{warehouseId}/reservations/{reservationId}` | Read status, original items, and timestamps |
| POST | `/api/v1/warehouses/{warehouseId}/reservations/{reservationId}/release` | Return all held units; `200`, including repeated release |

`requestId` is a required UUID scoped to the tenant and warehouse. `items` must
contain 1–50 distinct product UUIDs with positive integer quantities. Retrying the
same ID and items (in any order) returns the current reservation without reserving
again. Different items with the same ID return `409`. Replaying a released
reservation returns `RELEASED` and does not reopen it.

`onHand` stays unchanged; `reserved` increases on creation and decreases on release.
Insufficient free units return `409`; invalid/duplicate items return `400`; missing
or out-of-scope warehouses, stock, and reservation lookups return `404`. Every
stock row is locked in consistent product-ID order. Different requests with the
same retry key are also protected by a database unique constraint.

Reservations do not expire automatically and are not yet linked to orders. They
require explicit full release. Partial release, dispatch, and actor attribution
will be separate milestones. Reservation creation/release emit operational logs
after commit; DEBUG logging adds replay/lookup details. See the
[development guide](docs/DEVELOPMENT_GUIDE.md) for a code walkthrough, comments,
transaction handling, logging conventions, and how to enable DEBUG locally.

## Verify

```sh
./mvnw verify
```

Docker must be running. Testcontainers starts a disposable PostgreSQL 17 instance,
so tests do not require Compose or use your development database. The first run
may download the database image and Maven dependencies.

Tests cover the application context, warehouse service, HTTP creation/retrieval,
normalization, validation, malformed requests, duplicate names, the database
uniqueness constraint, and isolation of another tenant's records. Product tests also
cover SKU normalization, sorted listing, weight limits, and database constraints.
Stock tests cover opening balances, duplicate requests including simultaneous
submissions, tenant and warehouse isolation, invalid references, fractional and
negative quantities, and database stock invariants. Movement tests verify audit
history, replay behavior, rollback if audit insertion fails, reserved-stock and
overflow protection, concurrent changes, tenant isolation, and upgrades from V4.
Reservation tests cover multi-item rollback, competing holds, reordered retries,
repeated/concurrent release, reserved-stock protection, scope validation, and
success logging only after commit.

## Structure

- `inventory`: balances, movements, reservations, lifecycle handling, and commit-aware activity logging
- `product`: product catalog controller, DTOs, service, entity, repository, and exceptions
- `tenant`: shared server-side demo tenant until authentication is implemented
- `warehouse/controller`: HTTP requests and responses
- `warehouse/dto`: validated input and public response fields
- `warehouse/service`: business operations and transaction boundaries
- `warehouse/entity` and `warehouse/repository`: PostgreSQL persistence
- `exception`: shared HTTP error handling
- `src/main/resources/db/migration`: versioned schema changes
- `docker`: local database infrastructure

## Next milestones

1. Add orders that use reservations, followed by dispatch deductions and reservation expiry.
2. Add orders and shipments to complete one fulfillment workflow.
3. Add OIDC authentication and authenticated tenant isolation.
4. Build a React and TypeScript interface for that workflow.
5. Add Kafka with transactional outbox publishing and idempotent consumers.
6. Integrate local Ollama for shipment explanations; keep inventory decisions deterministic.
7. Add container packaging, Kubernetes manifests, CI, and observability.

Keep each milestone runnable and tested before moving to the next. Performance
and reliability claims belong in the portfolio only after measurement.
