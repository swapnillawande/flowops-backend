# FlowOps AI

A logistics platform being built step by step with Java 21 and Spring Boot.
The current milestone is a PostgreSQL-backed warehouse API. Inventory, order
fulfillment, React, Kafka, Ollama, and Kubernetes are planned; they are not yet implemented.

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

## Verify

```sh
./mvnw verify
```

Docker must be running. Testcontainers starts a disposable PostgreSQL 17 instance,
so tests do not require Compose or use your development database. The first run
may download the database image and Maven dependencies.

Tests cover the application context, warehouse service, HTTP creation/retrieval,
normalization, validation, malformed requests, duplicate names, the database
uniqueness constraint, and isolation of another tenant's records.

## Structure

- `warehouse/controller`: HTTP requests and responses
- `warehouse/dto`: validated input and public response fields
- `warehouse/service`: business operations and transaction boundaries
- `warehouse/entity` and `warehouse/repository`: PostgreSQL persistence
- `exception`: shared HTTP error handling
- `src/main/resources/db/migration`: versioned schema changes
- `docker`: local database infrastructure

## Next milestones

1. Add products, warehouse stock, and atomic inventory reservations with concurrency tests.
2. Add orders and shipments to complete one fulfillment workflow.
3. Add OIDC authentication and authenticated tenant isolation.
4. Build a React and TypeScript interface for that workflow.
5. Add Kafka with transactional outbox publishing and idempotent consumers.
6. Integrate local Ollama for shipment explanations; keep inventory decisions deterministic.
7. Add container packaging, Kubernetes manifests, CI, and observability.

Keep each milestone runnable and tested before moving to the next. Performance
and reliability claims belong in the portfolio only after measurement.
