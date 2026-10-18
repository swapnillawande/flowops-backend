# FlowOps AI

A logistics platform being built step by step with Java 21 and Spring Boot.
The current milestone includes PostgreSQL-backed warehouse and product catalog APIs.
Stock management, order fulfillment, React, Kafka, Ollama, and Kubernetes are
planned; they are not yet implemented.

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
tenant and normalized to uppercase. Stock quantities belong to the next milestone.

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

## Structure

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

1. Add warehouse stock for existing products, then atomic inventory reservations with concurrency tests.
2. Add orders and shipments to complete one fulfillment workflow.
3. Add OIDC authentication and authenticated tenant isolation.
4. Build a React and TypeScript interface for that workflow.
5. Add Kafka with transactional outbox publishing and idempotent consumers.
6. Integrate local Ollama for shipment explanations; keep inventory decisions deterministic.
7. Add container packaging, Kubernetes manifests, CI, and observability.

Keep each milestone runnable and tested before moving to the next. Performance
and reliability claims belong in the portfolio only after measurement.
