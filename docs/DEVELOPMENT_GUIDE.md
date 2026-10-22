# Understanding the backend

## Follow a request through the code

Start with `ReservationController`, then read `ReservationService`, `Stock`, and
the reservation repositories. Their responsibilities are deliberately separate:

1. The controller maps an HTTP URL to a Java method. `@Valid` checks request fields.
   DTO records define what callers may send and receive. Spring Security first verifies the bearer token; the controller resolves its
   trusted tenant claim rather than accepting a tenant from request JSON.
2. The service applies the business rules. `@Transactional` makes the reservation
   row, its items, and every affected stock balance commit or roll back together.
3. Repositories load tenant-scoped rows. A pessimistic write lock prevents another
   quantity-changing transaction from modifying the same balance until completion.
4. Entity methods protect quantities. `Stock.reserve` checks free stock;
   `Stock.releaseReserved` returns only held units. JPA tracks changes to loaded
   entities and writes them at commit, even without another `save` call.
5. After commit, the activity listener logs the operation's identifiers. No success
   log is emitted when a constraint failure causes rollback.

Flyway migrations create and evolve the database. JPA annotations map Java fields
to columns, while database constraints provide another layer of protection.
Never edit a migration that has already been applied; add the next version.

## Reservation example

For a stock balance of 10 units, holding 3 gives `onHand=10`, `reserved=3`, and
`available=7`. Releasing that reservation restores `reserved=0` and `available=10`.
Physical stock does not change. A receipt adds physical units; an adjustment
changes physical units but cannot reduce the balance below the reserved amount.

A reservation can contain up to 50 different products in one warehouse. Every
requested stock row is locked in the same product-ID order. This reduces deadlock
risk when requests list the same products in opposite orders. If any item cannot
be held, the whole transaction rolls back, including earlier item changes.

For standalone reservations, the client reuses a request ID for retries. An identical retry returns the existing
reservation. Changing the items with that ID returns a conflict. Release locks the
reservation before returning its units, so two release requests cannot return the
same hold twice. Standalone holds follow `ACTIVE -> RELEASED`; order-owned holds may instead become
`CONSUMED` during dispatch. Neither terminal state can be reopened; there is no delete API.

## Order and inventory integration

Follow `OrderController -> OrderService -> ReservationService` for the first
cross-module workflow. OrderService claims the tenant/request key with PostgreSQL
`INSERT ... ON CONFLICT DO NOTHING`. Identical concurrent requests wait for the
first transaction, then read the existing order instead of creating another hold.
An order ID is generated internally and used as its private inventory retry key.

Creation stores product SKU/name snapshots and changes the temporary `PENDING`
row to `CONFIRMED` after the stock hold is created. Every write uses the same
transaction; any failure rolls back both modules. Cancellation locks the order,
then its reservation, then stock in consistent product order. It releases the hold
and marks the order `CANCELLED` together. Repeated cancellation does not return
units twice.

Reservations owned by orders carry an `ownerOrderId`. Inventory checks that owner
in both reservation replay and release operations. This prevents the standalone
reservation HTTP API from releasing a hold while its order remains confirmed.
Inventory still owns its tables; the order module calls inventory services rather
than querying those tables. Cross-module identifiers are not cross-module foreign
keys, so future service extraction remains possible.

`OrderActivityLogger` uses the same after-commit pattern as inventory logging.
Customer references and product names are not included in application log messages.
These synchronous module calls are not a distributed saga. Extracting services
will require durable commands/events and compensation for failure handling.

## Shipment preparation and dispatch

Follow `ShipmentController -> ShipmentService -> OrderService -> ReservationService`.
ShipmentService never queries order or inventory tables. Preparation locks the
order and creates one shipment, leaving the stock hold unchanged. The order ID
acts as the shipment creation retry key, and a repeated carrier label replays the
existing shipment.

Dispatch obtains locks in the order: order, shipment, reservation, then stock rows
in consistent product order. Inventory removes each held quantity from both
`onHand` and `reserved`, writes `DISPATCH` audit entries, and marks the reservation
`CONSUMED`. OrderService marks the order `DISPATCHED`; ShipmentService marks its
shipment `DISPATCHED`. All modules share one transaction, so a failure at any step
rolls back every state and quantity change.

The scalar shipment-to-order lookup before locking avoids loading an entity that
could become stale while waiting for the order lock. Repeated dispatch reads the
already-dispatched shipment and does not consume again. Dispatch audit keys are
separate from client-supplied movement request IDs to avoid namespace collisions.

Cancellation has a different event contract from logging. OrderService publishes
`OrderCancellation` synchronously inside the transaction. ShipmentService's
`@EventListener` cancels a prepared shipment, and `MANDATORY` propagation requires
an existing transaction. Listener errors roll back order cancellation and stock
release. By contrast, `@TransactionalEventListener` activity loggers run only after
commit. Do not make the cancellation listener asynchronous without replacing this
consistency model with a durable workflow.

Both cancellation and dispatch lock the same order first. Whichever commits first
makes the other command invalid; a dispatched order needs a future returns flow,
not cancellation. Internal tracking numbers and carrier labels do not represent
real carrier bookings. Manual delivery events are supported; live carrier integration and partial shipping are not implemented.

## Comments and readability

For future changes:

- Explain each class's responsibility and the business meaning of request fields.
- Comment public operations, transaction boundaries, lock ordering, idempotency,
  state transitions, and rules whose purpose is not obvious from their names.
- Explain why a safeguard exists, with a small example when useful. Avoid comments
  that merely repeat an assignment or getter.
- Update comments and this guide when behavior changes. Keep controllers focused
  on HTTP and put business rules in services/entities.
- Test important failure cases against PostgreSQL, especially concurrency,
  rollback, tenant isolation, and retries.

## Logging conventions

Use SLF4J `Logger` and `LoggerFactory`, with parameterized placeholders:

```java
log.debug("Reservation replay: tenantId={} reservationId={}", tenantId, reservationId);
```

- `INFO`: committed inventory state changes, through `InventoryActivityLogger`.
- `DEBUG`: replay, lookup, and preparation details useful during development.
  A prepared change is not described as successfully committed.
- `WARN`: database constraint rejections; log the exception type instead of SQL
  exception text, which may include submitted values.

Application log messages use operational identifiers rather than request bodies,
reasons, names, credentials, or tokens. Framework/database diagnostic logs are
separate and can still contain constraint values; account for that when configuring
production log storage. Logs supplement the database audit records and do not
replace them. Reservation lifecycle history lives in reservation records; it does
not create physical stock movements because on-hand quantity is unchanged.

Enable application debug messages locally with:

```sh
LOGGING_LEVEL_COM_FLOWOPS=DEBUG ./mvnw spring-boot:run
```

These application events are in-process callbacks for logging. They are not durable
messages, a Kafka outbox, or delivery guarantees to another service.

## Current boundaries

OIDC authentication and tenant isolation are implemented. All tenant members
currently share operational permissions; role restrictions and actor attribution
are still future work. Standalone reservations
require explicit release; order-owned holds must be released through order cancellation.
Expiration, partial release, delivery tracking, and returns remain future work.
Full-order shipments and dispatch are implemented; cancelling prepared shipments
is coordinated through order cancellation. The bundled Keycloak start-dev setup is local infrastructure; production identity
configuration and TLS must be provisioned before deployment. See the README for runnable API examples and the next milestones.

## Delivery progression and history

Shipment status changes lock the order and shipment, validate the forward-only
lifecycle, and append a sequenced status event in the same transaction. An event
request ID is unique within tenant/shipment scope; identical retries return the
original event before lifecycle validation. Delivery marks the order delivered
without changing its already-consumed reservation or physical stock.

Preparation, dispatch, and cancellation use the same history recorder. Sequence
numbers are allocated while the parent mutation lock is held, so equal timestamps
cannot reorder the timeline. The V9 upgrade test checks that stored dispatch
timestamps become auditable history without altering shipment state.

## React client boundary

The `frontend/` directory owns browser state and presentation; Java services still
own all business decisions. Vite forwards same-origin `/api` requests locally to
Spring Boot. No tenant selector or tenant ID input exists in the browser.

The API wrapper surfaces backend validation/conflict messages instead of silently
substituting fake data. Forms disable pending submissions and keep their retry ID
stable for unchanged payloads. After success, the console reloads server state.
Use the frontend README for setup and file responsibilities. The UI uses native
dialog focus handling, labeled controls, status text alongside color, and responsive
CSS breakpoints. Desktop browser QA covers a full workflow on a separate database.

## Stock movement UI and ambiguous retries

`OrderDetails` fetches the canonical order DTO so list pagination or an earlier
status cannot become its source of truth. `MovementHistory` reads only the selected
warehouse/product ledger. `useResource` aborts and discards superseded requests,
clears old data during reload, and gives the user a recovery action on failure.

StockMovementForm captures a specific warehouse/product when opened, fetches the
current balance, and validates whole units, audit reasons, and initial capacity
checks. It preserves an unchanged command's retry key. After a lost response,
a refreshed balance could already include that command; the form therefore lets
the original key reach the backend on replay instead of incorrectly applying new-
command availability checks. The server still locks and validates new movements.

## Authentication and tenant ownership

The React entry point initializes OIDC before mounting business screens. Login uses
an authorization code protected by PKCE, with a public client and exact redirect
origins. Access/refresh tokens live in memory; temporary redirect state uses session
storage. A page reload returns to sign-in (the provider session can make that quick).
The API wrapper adds the current access token, and invalid sessions clear the
mounted console so one account cannot inherit another account's loaded records.

Spring Security validates the signed JWT before a controller runs. Required checks
include issuer, expiry, audience, nonblank subject, and a canonical UUID `tenant_id`.
Current tenant resolution fails closed and has no runtime demo fallback. Services
still take an explicit tenant UUID, making transaction boundaries and repository
scope visible. Client-supplied tenant selectors never override identity claims.

The bearer API is stateless. CSRF protection is disabled because no ambient cookie
or HTTP Basic authentication grants API access; JavaScript explicitly attaches the
bearer header. Revisit that decision if cookie authentication is introduced.
The local Vite proxy provides same-origin API calls; the API does not open wildcard
CORS. Only `/actuator/health` is public. Security errors use the same `{error}` shape
as business errors and never include token contents.

Keycloak's `tenant_id` user attribute is admin-editable only, and public registration
is disabled. Trusting a correctly signed claim is insufficient if users can change
its source attribute. The import includes two tenants for a reproducible isolation
check; Berlin keeps the previous demo UUID, so existing records need no migration.
Do not add a client tenant picker without first implementing verified membership.

Business tests use an explicit authenticated request fixture. Dedicated security
integration tests send signed JWTs through the real decoding/filter chain, including
invalid signatures/claims and cross-tenant access. No test-only security bypass is
packaged in the application. Existing activity loggers remain commit-aware; user
names, credentials, and tokens must not appear in operational logs.
