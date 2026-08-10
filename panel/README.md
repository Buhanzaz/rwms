# RWMS Panel

`panel/` is the React/TypeScript web panel for RWMS operational users. It is a
browser client, not a domain-service facade: business commands, authorization,
state transitions, and persistence remain with the owning backend services.

Russian version: [README.ru.md](README.ru.md).

## Public boundary and authentication

- The browser talks only to the public API gateway on its own origin:
  `/auth/**` for OAuth/OIDC and `/api/**` for public APIs. It must never be
  configured with an internal service address, `localhost` service port, or
  `/api/internal/**` route.
- Interactive sign-in uses the gateway's OIDC flow. The panel accepts the
  human `USER` principal; the gateway and owning service still enforce every
  role and warehouse-access check. Hiding a UI control is not authorization.
- The Vite proxy is a local-development convenience only. Production browser
  requests remain same-origin and must be served behind the public gateway.

## Product surface and state ownership

The router exposes the operational panel for warehouse/equipment, rentals and
orders, inventory, maintenance/acceptance, logistics, task board, assistant,
write-offs, and settings. The current user profile determines which warehouse
contexts and screens are usable.

- TanStack Query holds server reads; query keys include the relevant warehouse,
  entity, and filter inputs. The server stays authoritative.
- The selected warehouse is a non-authoritative local UI preference. It is
  revalidated against the profile returned after authentication.
- A verified authenticated subject and its bearer-grant revision own a fresh
  nested TanStack Query client. A logout, subject change, or grant change
  remounts the protected subtree so effect-owned realtime streams stop, cancels
  and removes protected query and mutation state, and clears media blob URLs
  before the next protected subtree mounts. The root client retains public
  offer and bootstrap state.
- Asset and media SSE streams are invalidation signals, not a second domain
  projection. Their handlers refresh or patch only affected query entries and
  evict media blobs whose revision changed; they do not globally clear the
  cache.
- The rental-item dossier consumes the complete canonical activity vocabulary.
  Maintenance repair transfer preparation and completion are shown in the
  repair history using the immutable warehouse snapshot returned by
  `dossier-service`; the exact boundary is the [dossier OpenAPI](../contracts/openapi/dossier-service.yaml)
  and the strict [panel activity model](src/features/rental-items/dossier/model/dossier-service.ts).
- The panel may combine independent public reads for a screen, but it must not
  orchestrate cross-service business workflows in the browser.

## Rental assistant, clients, and orders

- Assistant clarification cards are durable server data. Independent branches
  such as OSB and LDSP remain separately answerable in either order, while the
  panel renders only friendly question kinds and exact server-provided options.
  Search cards require both cabin type and finish, and exact follow-up filters
  come from the search result rather than browser guesses. Within one mounted
  result surface, the active logical group remains selected through selection
  and stream updates; compact rounded group pills replace underline tabs, and
  exact filters stay collapsed until the manager opens them. See the
  [assistant OpenAPI](../contracts/openapi/assistant-service.yaml), the strict
  [assistant adapter](src/features/assistant/api/assistant-api.ts), and the
  [conversation page](src/features/assistant/pages/assistant-page.tsx).
- The assistant selection and its expiry are logistics-owned. Checkbox removal
  sends the complete remaining ID set with an idempotency key; an empty set
  releases the hold, while partial removal applies the authoritative renewed
  expiry. Streamed LLM removal is reconciled immediately and followed by the
  terminal conversation refetch. The browser never treats a local checkbox set
  as authoritative state.
- Rental navigation exposes the client grid at `/clients` and client details at
  `/clients/:clientId`. Clients are created from chat, booking, or the new-order
  flow; the client grid has no standalone creation action. The client grid is
  server-paginated; a client detail reads that client's orders and links to
  chat, warehouse selection, or manual order creation with only `clientId` as
  prefill. A truthful null phone on a historical client is displayed as not
  specified and is never backfilled in the browser. See the
  [logistics OpenAPI](../contracts/openapi/logistics-service.yaml) and the
  [client feature](src/features/clients/).
- Booking requests 50 cabins per server page and defers text before it becomes
  a query. It does not poll the full catalogue on a timer, card grids size to
  their actual compact content, and each card initially loads one preview;
  final availability and hold effects remain server-authoritative. Hold expiry
  schedules one deadline refresh instead of re-rendering the whole booking
  screen each second. See the [booking catalogue](src/features/booking/booking-catalog-page.tsx)
  and [expiry hook](src/features/booking/use-booking-hold-expiry.ts).
- Order creation and address-edit screens persist the delivery address,
  latitude, longitude, editable contact phone, comment, and concrete
  acceptable delivery dates.
  A DRAFT detail makes its save prerequisites visible: a nonblank address,
  finite coordinate pair, nonblank contact phone, one or more acceptable dates,
  selected warehouse, one or more cabins, and a persisted, unchanged rental
  term for every selected cabin. Its address dialog changes only delivery data
  and keeps the selected client. Saving moves the order to the saved state so it
  can be handled in Tasks; it does not create a shipment or trip. Lists and
  details render those server projections. Order detail shows logistics-owned
  planned/actual delivery and return movements, then performs independent public
  dossier reads for selected cabins. Estimate or repair absence is shown as
  proven only after a complete dossier projection has been paged to exhaustion;
  partial, failed, or still-paged reads remain explicitly inconclusive. See the
  [order adapter](src/features/orders/api/orders-api.ts), [draft
  detail](src/features/orders/pages/order-detail-page.tsx), [delivery
  dialog](src/features/orders/components/order-delivery-dialog.tsx), and
  [dossier evidence](src/features/orders/components/order-unit-dossier-evidence.tsx).

## Logistics driver board

- Shipment and return forms select an active driver by opaque worker ID. A
  warehouse transfer has no responsible driver because it creates shared
  warehouse movement work.
- `/logistics/board` shows only shipment and return cards in horizontal calendar
  columns. Each date contains independently collapsible active-driver queues and
  an unassigned queue only when such cards exist. Current and scheduled lanes
  remain distinct inside the same driver section, and cards collapse
  independently.
- A logistics card can be reordered only inside its existing date, lane and
  driver queue. The client sends task and entry versions with the new index; it
  never changes the driver audience through drag and drop.
- `/logistics/tasks` is the separate shared warehouse movement board. It keeps
  transfer, general movement, repair-place and capital-repair workflows without
  driver sections or driver identity in the UI.
- Optimistic movement is presentation state only. A rejection restores the
  prior board and refreshes the authoritative projection; a driver-directory
  failure leaves known tasks and board commands available.

See the [canonical logistics contract](../contracts/openapi/logistics-service.yaml),
[board model](src/features/logistics/driver-board/driver-board-model.ts), and
[logistics board](src/features/logistics/driver-board/logistics-board-page.tsx),
[movement board](src/features/logistics/driver-board/driver-board-page.tsx), and
[warehouse-transfer adapter](src/features/logistics/warehouse-transfers/adapters/http-warehouse-transfer-client.ts).

## Failures, concurrency, and commands

The shared bearer client turns gateway Problem Details into `ApiError` with the
HTTP status and machine-readable code. A missing session, unreachable gateway,
or unsupported public operation is shown as an explicit failure; there is no
mock-success fallback.

Ordinary assistant HTTP requests and assistant turn streams use the same safe
Problem Details conversion. `401` remains an authentication-renewal/login
signal, `403` requires refreshed grants or user action, and `409` requires an
authoritative refetch/conflict path. A malformed Problem Details body still
returns a status-bearing `ApiError` with a safe fallback message.

For mutable operations, use the fencing mechanism specified by the canonical
OpenAPI operation (`expectedVersion`, ETag, or equivalent) and show a consistent
refresh/retry path for `409 Conflict`. Retried creates and effects require the
contract's idempotency key or stable external identifier. Do not infer command
success from a cache update or SSE notification.

## Local development and checks

Use a current Node.js LTS installation from this directory:

```bash
npm ci
npm run dev
npm run typecheck
npm test
npm run lint
npm run build
```

`npm run dev` listens on `localhost:8080`; its proxy target is for local
development only. Configure and test a real public gateway origin before an
integration or release claim.

## Release rules

Build only from the intended reviewed source scope. A release bundle must be
tested through same-origin `/auth/**` and `/api/**`, including first sign-in,
profile/workspace read, an authorized command, a `409` path where applicable,
and an invalidation-driven refresh. Do not publish an artifact built from a
different checkout or a mixed unreviewed worktree.

## Current limitations

- Protected server data and media blobs must remain inside the revision-scoped
  client boundary. Do not add a browser store, an unscoped object-URL cache, or
  a direct root-client write for protected state; those bypass the principal and
  grant fence.
- The assistant streaming path shares the normal bearer client's Problem
  Details conversion; do not add a second status or error-body policy.

## Source of truth

Public operation semantics live under `contracts/openapi/`; event semantics
live under `contracts/events/`. The gateway owns routing and token validation,
not business aggregation. Verify UI changes against the owning service and
canonical contract together.

The shared failure boundary is implemented in
[`src/lib/api-client.ts`](src/lib/api-client.ts) and the assistant stream uses it
from [`src/features/assistant/api/assistant-api.ts`](src/features/assistant/api/assistant-api.ts).
