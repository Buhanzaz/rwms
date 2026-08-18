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
- The cabin `История` tab renders those canonical activities as a compact,
  chronological, expandable timeline. Related estimate, repair, inventory, and
  media facts are grouped only by their canonical source or folder IDs; past
  operations use green markers and the latest loaded operation uses blue.
  Expanding a group shows the exact server-supplied time, actor, media states,
  event breakdown, and available route to its owning workflow without inferring
  missing domain data; permission-controlled technical mode retains raw source
  metadata. See the [activity register](src/features/rental-items/dossier/dossier-activity-register.tsx).
- When queuing a repair returns the exact Problem Details code
  `BOOKED_UNIT_REPLACEMENT_REQUIRED`, the repair editor keeps the persisted draft
  task ID and version, closes the completion dialog, and shows
  `Бытовка забронирована` with a `К заказам` action to the existing `/orders`
  flow. Generic queue failures keep their normal error and do not show this
  replacement hint. See the [repair task editor](src/features/repair-tasks/repair-task-editor-workspace.tsx).
- Inventory inspection/final-plan, estimate, and primary-repair editors carry
  the explicit `forceCapitalRepair` choice through their canonical commands and
  show the server value read-only after the relevant plan is no longer
  editable. In an inventory plan this control is rendered directly after the
  repair-movement choice, and the two destinations are mutually exclusive.
  Custom work or material selects its explicit repair/holding board queue; the
  frozen line routing snapshot then reconstructs every line in exactly one
  stage, including repeated catalog work IDs. A new rework inherits the capital
  choice because its create contract does not authorize replacing it.
  Calculated CAPITAL repairs continue to use the existing separate
  capital-repair and acceptance surfaces rather than the ordinary repair table.
  Their driver-board cards load the authoritative repair plan only when
  expanded and render its ordered queues as separate work and material columns
  with exact quantities and units.
- Shared owner-media surfaces show READY images first and READY videos through
  their compressed MP4 `PLAYBACK` variant with native controls. JPEG, PNG,
  WebP, MP4, and WebM can be added. A selected local original is shown
  immediately while its same-origin upload continues in the background, with
  byte progress directly below that preview; cover/delete actions appear only
  after the server item is ready. Only an image can be selected as the cover,
  and protected blob URLs are released when their generation or owner scope
  changes. See the [media feature](src/features/media/).
- The panel may combine independent public reads for a screen, but it must not
  orchestrate cross-service business workflows in the browser.

## Rental assistant, clients, and orders

- Assistant clarifications are one durable server-ordered sequence. The panel
  renders only the lowest-sequence `PENDING` question with its exact
  server-provided options, hides queued/history entries, and disables free text
  until that answer succeeds. A stale or out-of-order `409` refetches the
  authoritative question. There are no independently actionable branches and
  no lower `Продолжить точный поиск` panel; ordinary messages, cabin results,
  and client-presentation actions remain in the conversation. Search cards
  require both cabin type and finish, and the active logical result group stays
  selected through selection and stream updates. See the
  [assistant OpenAPI](../contracts/openapi/assistant-service.yaml), the strict
  [assistant adapter](src/features/assistant/api/assistant-api.ts), and the
  [conversation page](src/features/assistant/pages/assistant-page.tsx).
- The assistant selection and its expiry are logistics-owned. Checkbox removal
  sends the complete remaining ID set with an idempotency key; an empty set
  releases the hold, while partial removal applies the authoritative renewed
  expiry. Streamed LLM removal is reconciled immediately and followed by the
  terminal conversation refetch. The browser never treats a local checkbox set
  as authoritative state.
- An order-linked assistant route validates the exact `clientId` and `orderId`,
  lists or creates the conversation through the contract's `rentalOrderId`, and
  sends the already-known client and order rather than opening a second client
  chooser. Logistics fixes the order warehouse; the browser neither selects a
  different warehouse nor auto-archives and recreates conversations as a saga.
- Rental navigation exposes the server-paginated client grid at `/clients` and
  client details at `/clients/:clientId`. The prominent top-right
  `Создать клиента` action opens the existing client form in a modal and creates
  through the logistics API. It supports zero or more named additional contacts
  without a separate page. Primary and additional client contacts remain separate and
  are shown by name and phone in list and detail views; order-owned contacts are
  stored and rendered separately. Client detail reads that client's orders and
  links to chat, warehouse selection, or manual order creation with only
  `clientId` as prefill. A truthful null phone on a historical client is
  displayed as not specified and is never backfilled in the browser. See the
  [logistics OpenAPI](../contracts/openapi/logistics-service.yaml) and the
  [client feature](src/features/clients/).
- Booking requests 50 cabins per server page and defers text before it becomes
  a query. It does not poll the full catalogue on a timer, card grids size to
  their actual compact content, and each card initially loads one preview;
  final availability and hold effects remain server-authoritative. Hold expiry
  schedules one deadline refresh instead of re-rendering the whole booking
  screen each second. See the [booking catalogue](src/features/booking/booking-catalog-page.tsx)
  and [expiry hook](src/features/booking/use-booking-hold-expiry.ts).
- Ordinary booking is supported only as `/booking?clientId&orderId`. It validates
  that exact order and client, fixes the order warehouse (or the first selected
  warehouse while the draft order has none), and lists or creates a direct
  logistics inquiry with `conversationId: null`. It never creates a hidden
  assistant conversation and never reuses an active AI-linked inquiry. A route
  without an order shows an explicit unsupported state.
- Every NORMAL public cabin card uses a labelled `Выбрать бытовку …` checkbox.
  A selected cabin exposes `Добавить наполнение`, which opens a compact,
  scrollable dialog rather than an inline/full-screen editor. The type selector
  shows `Название: доступно — число`; the client adds each type once and changes
  its quantity with bounded `−`/`+` controls. Filling is drafted independently
  for each selected cabin. Its effective capacity uses the refreshed shared
  availability, that cabin's existing draft, and physical contents in selected
  held cabins without double counting, then applies nullable `maximumPerCabin`.
  The presentation polls and refetches on focus/reconnect; a smaller refreshed
  pool keeps the explainable draft, marks it invalid, and disables confirmation
  while the atomic server confirmation remains authoritative. A public
  presentation collects one to five independent, date-only client wishes; the
  manager order detail shows the returned values read-only and never overwrites
  them.
- A REPLACEMENT presentation requires the server's exact selection count and
  preserves client click order. It has no furniture editor: desired quantities
  remain reserved and transfer to the corresponding replacement in the current
  order. If the old cabin already contains furniture after a completed movement,
  the server creates its physical movement to the replacement. A permanent
  public booking rejection is terminal for that revision and asks the client to
  request an updated link rather than retrying locally.
- Order creation and edit screens persist the selected client, editable order
  contact phone, and comment only. The public presentation's second step
  collects the delivery address, optional coordinate pair, zero or more
  order-owned additional contacts, one to five independently selected desired
  dates, and the initial rental duration. The returned `desiredDeliveryWindows`
  are read-only on order detail as a client selection and remain distinct from
  the logistics-owned scheduled date. A DRAFT detail requires a nonblank contact
  phone, selected warehouse, and one or more cabins; it never offers a manager command for the
  initial per-cabin term. After a cabin is
  actually shipped/rented, its card shows duration, shipment date, and
  calculated return date. `Продлить аренду` selects one or more such cabins,
  shows desired and factual furniture only as information, and sends the shared
  month count through the existing versioned, idempotent
  `POST /orders/{id}/rental-terms/extend` command. The manager contact dialog
  keeps the selected client. Saving moves the order to the saved state so it can be
  handled in Tasks; it does not create a shipment or trip.
- An order that the server marks editable exposes `Добавить бытовки` in both DRAFT
  and SAVED states, including after the first cabin was added. Its chooser has
  exactly two linked paths: AI chat and ordinary booking, each carrying the
  current `clientId` and `orderId`; neither path is gated by manager-entered
  desired dates because the client supplies the date and rental duration in
  the presentation. The obsolete inline cabin layout is absent. Server
  projections show the reservation state of each cabin
  and each desired furniture row. The furniture editor caps a row by both the
  shared available quantity and `maximumPerCabin`, without silently truncating
  that order's already saved quantity; a concurrent conflict refreshes the
  authoritative order and catalogue. Order detail also shows logistics-owned
  planned/actual delivery and return movements, then performs independent
  public dossier reads for selected cabins. Estimate or repair absence is shown
  as proven only after a complete dossier projection has been paged to
  exhaustion; partial, failed, or still-paged reads remain explicitly
  inconclusive. See the
  [order adapter](src/features/orders/api/orders-api.ts), [draft
  detail](src/features/orders/pages/order-detail-page.tsx), [delivery
  dialog](src/features/orders/components/order-delivery-dialog.tsx), and
  [dossier evidence](src/features/orders/components/order-unit-dossier-evidence.tsx).
- The server's independent `permissions.canReplaceUnits` projection exposes
  `Заменить бытовки` even when ordinary order editing is closed. A manager
  first selects existing order cabins and then uses exactly one of two modes:
  the existing booking route receives ordered repeated `replacementUnitId`
  parameters for client selection, while direct replacement accepts exactly one
  old cabin, a required reason, and a free same-warehouse candidate from the
  order boundary. The versioned idempotent replace response updates the current
  order with its transferred furniture projection; a conflict refreshes order
  and candidates without clearing the replacement draft. See the [replacement
  dialog](src/features/orders/components/order-unit-replacement-dialog.tsx) and
  [order adapter](src/features/orders/api/orders-api.ts).

## Logistics driver board

- Shipment and return forms select an active driver by opaque worker ID. A
  warehouse transfer has no responsible driver because it creates shared
  warehouse movement work.
- Expanded rows on `/logistics/order-tasks`, `/logistics/shipments`, and
  `/logistics/returns` show the linked order and customer overview before the
  cabin list: address, named contacts, phones, coordinates, rental term, and
  outbound/return drivers remain visible when their authoritative reads are
  available. Each cabin compares order-desired furniture with its current
  asset-service contents and shows a text-labelled required, action-needed,
  loaded, empty, or unavailable state. Scheduling dialogs repeat the furniture
  summary for exactly the cabins in the pending command.
- The warehouse-transfer adapter validates the complete current logistics
  document shape. Transfer-only responses require null driver and rental
  shipment fields and are rejected explicitly when required fields are missing
  or contain shipment data; the panel never fabricates a successful transfer.
- `/logistics/board` shows only shipment and return cards in horizontal calendar
  columns. Each date contains independently collapsible active-driver queues and
  an unassigned queue only when such cards exist. Current and scheduled lanes
  remain distinct inside the same driver section, and cards collapse
  independently.
- The dated board renders only grouped shipment/return projections; legacy
  cards without a trip projection and transfers are hidden. A card headline is
  `Отгрузить бытовку` or `Вернуть бытовку`, never a task/trip number or raw
  legacy text. It shows `Дата выполнения задания`, not the client wish. Each
  cabin shows actual filling as `Название: Nшт` and exactly one final status:
  gray `Нет наполнения`, orange `Ожидает наполнения`, yellow `Ожидает выноса
  наполнения`, or green `Наполнение готово`.
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

Media additions use a bounded four-worker upload queue with stable idempotency
keys and ordered results. A first failure prevents new jobs from starting,
waits for already-started transfers to settle, and reports that original
failure instead of fabricating a partial success. Browser byte progress uses
the same authenticated, same-origin content route and does not alter the media
contract or make the local preview authoritative.

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
