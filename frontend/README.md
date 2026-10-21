# FlowOps operations console

React + TypeScript + Vite, with ordinary CSS and Lucide icons. This is a real API
client for the Spring Boot application in the repository root; it has no mock-data
mode or external carrier integration.

## Start

Use Node.js 22.12 or newer. From the repository root, start PostgreSQL and **restart
Spring Boot on the current source** so the latest migrations and delivery APIs load:

```sh
docker compose -f docker/compose.yaml up -d --wait
./mvnw spring-boot:run
```

In a second terminal:

```sh
cd frontend
npm ci
npm run dev
```

Open the Local URL printed by Vite (normally http://127.0.0.1:5173). Port 5173 is
strict: if occupied, stop your old frontend process rather than silently running
a different instance. Existing backend data is displayed; records are not seeded.
Do not stop someone else's process to free a port.

The Vite server proxies `/api` to `http://127.0.0.1:8080`. To use a different local
backend, set the target when starting Vite:

```sh
API_TARGET=http://127.0.0.1:8081 npm run dev
```

The browser stays same-origin, so broad CORS permissions are unnecessary. This
proxy is a development/preview convenience, not production hosting. A production
server must serve the static build and reverse-proxy `/api` to Spring Boot.
Authentication is not implemented; keep both services local.

## First workflow

1. Open **Catalog & stock**, add a warehouse and a product.
2. Select the warehouse and initialize opening stock for the product.
3. Choose **New order**, enter a customer reference and positive quantities.
4. In **Orders**, prepare a shipment with a carrier label.
5. In **Shipments**, dispatch it, then mark it in transit and delivered with notes.
6. Select the tracking number or **View history** to see its timeline.

Order cancellation asks for confirmation and releases stock through the backend.
Dispatch asks for confirmation because it deducts physical stock and prevents
cancellation. UI actions reflect the known lifecycle, while the server remains the
authority and rejects stale or conflicting commands.

## Checks

```sh
npm test
npm run build
```

Tests cover real API error handling, displayed API totals, offline state, retry-key
preservation after failed order submission, delivery transition selection, and
escaping history notes as plain text. The production build performs TypeScript
checking before bundling. `npm run preview` serves the build for local inspection.

## Code map

- `types.ts`: typed public API contracts.
- `api.ts`: fetch, JSON parsing, and actionable server/network errors.
- `App.tsx`: navigation, paginated resources, tables, and action orchestration.
- `forms.tsx`: catalog, stock, order, shipment, and delivery forms.
- `components.tsx`: status badges, accessible dialogs, paging, history, mutation state.
- `styles.css`: shared visual tokens and responsive layout breakpoints.

Read requests are aborted when a page is replaced. Mutation forms disable submit
while pending and keep errors beside the form. Order/status forms retain an
idempotency key for an unchanged payload while the form remains open. After an
ambiguous connection failure, retry there rather than closing and starting another
order. Browser-persistent draft recovery is future work. Refresh reloads real data;
there is no live push channel yet.

The overview shows server-reported order/shipment totals. Tables and activity show
the current page; no delivery-rate or other aggregate metrics are invented. Catalog
lists are currently unpaginated, matching the backend's local-demo API. Full catalog
editing, stock receipts/adjustments UI, authentication, and automatic/live tracking
are later milestones.
