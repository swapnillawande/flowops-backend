# Understanding the backend

## Follow a request through the code

Start with `ReservationController`, then read `ReservationService`, `Stock`, and
the reservation repositories. Their responsibilities are deliberately separate:

1. The controller maps an HTTP URL to a Java method. `@Valid` checks request fields.
   DTO records define what callers may send and receive. The trusted demo tenant
   is supplied by the server, not accepted from request JSON.
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

The client reuses a request ID for retries. An identical retry returns the existing
reservation. Changing the items with that ID returns a conflict. Release locks the
reservation before returning its units, so two release requests cannot return the
same hold twice. The lifecycle is `ACTIVE -> RELEASED`; there is no delete API.

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

Authentication and actor attribution are not implemented yet. Reservations require
explicit release; expiration, order ownership, partial release, and dispatch are
future work. Keep this application local until authenticated tenant isolation is
implemented. See the README for runnable API examples and the next milestones.
