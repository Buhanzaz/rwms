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
- The same OIDC transaction may be started by the standalone logistics
  workspace. Its callback performs a full-page return to
  `/logistics-panel/**`; ordinary panel return paths continue through the
  panel router. Both clients use the same renewable `rwms-panel` user session.
- `CUSTOMER` is a recognized human role only so user administration, order
  actors and dossier history can render truthful labels. It remains ineligible
  for ManagerApp and receives no panel order or warehouse command affordance;
  the supported customer workflow is the separate CustomerApp. See the
  [auth model](src/features/auth/auth-model.ts), [user model](src/features/settings/users/model/users.ts)
  and [order permissions](src/features/orders/permissions/orders-permissions.ts).
- `/manager/` is the separate rental-manager web surface. It accepts only the
  dedicated `rwms-rental-manager-web` session of an authenticated `RENTAL_MANAGER`
  user with `rentalAccess=true` and exactly `rental.manage`. Its shared warehouse
  provider keeps only explicitly granted warehouses; an empty grant set is
  explained without blocking chat or client lookup.
- Rental-manager notification dialogs also consume the logistics-owned
  `/api/logistics/v1/rental-booking-change-alerts` feed. Completed cancellations
  and reschedules use distinct mutation IDs, server calendar dates and exact
  ruble strings. Acknowledgement is version-fenced and idempotent for the current
  user; it never changes the order or payment. The order link appears only when
  the server returns `canOpenOrder=true`. Feed errors remain visible and never
  become fabricated empty or paid results.
- The manager shell has a collapsed fee-only entry backed by
  `/api/logistics/v1/rental-booking-change-quotes`. It shows only pending proposals
  in editable warehouses, without requesting full orders or customer contacts.
  Existing order details reuse the same waiver form where order visibility is
  already granted; neither path grants additional order access. Fee commands
  require a staff role, rental access, effective write scope and warehouse EDIT.
  Only current, unexpired OFFERED quotes are listed. An outstanding
  or unconfigured fee can be waived with a trimmed 1–2000 character reason and
  its own quote version/idempotency key. A conflict reloads the quote but retains
  the reason and never resubmits automatically. Waiver neither changes the
  booking nor extends the quote deadline; the waiver reason and exact settlement
  are displayed only for charge entries in existing order audit.
- The Vite proxy is a local-development convenience only. Production browser
  requests remain same-origin and must be served behind the public gateway.

## Standalone administration

- `/admin/` is a standalone administrator workspace. It uses the dedicated
  `rwms-admin-web` `USER` session with exactly `admin.manage` after the OIDC
  protocol scopes; its UI gate is `SYSTEM_ADMIN` only. Browser traffic remains
  same-origin through `/auth/**` and `/api/**`.
- The grouped sidebar separates claims, user settings, object settings and
  general settings. The object-settings selector admits only active,
  non-representative production and/or main objects. Vehicle and trailer catalog
  calls use public `/api/logistics-planner/v1/admin/**`; relocating a resource
  changes its permanent catalog home, not a logistics trip. Its editor saves
  nullable documented dimensions and weights, vehicle route margins and speeds,
  measured axle-load profiles, and a same-object default trailer as one
  version-fenced configuration; an empty physical field means unknown, not zero.
- Repair-place settings are count-only and refill becomes eligible immediately.
  The object work schedule combines task-board work time with inventory holiday
  days; drivers set their own shifts, and inventory has no per-day cabin quota
  or weekday-specific setting.

## Product surface and state ownership

`/admin/rental` saves all rental hold durations and late-change settings in one
version-fenced logistics command. Advance notice uses warehouse-local calendar
days (default 2). The fee is unconfigured (null mode/value), fixed whole RUB, or
0–100 percent with up to two decimal places; decimal strings preserve exact
amounts. The optional international rental-support phone is never taken from
customer data. Conflicts retain the draft until an explicit reload. Configuring
a rule does not itself assess or collect a fee.

The router exposes the operational panel for warehouse/equipment, rentals and
orders, inventory, maintenance/acceptance, logistics, task board, assistant,
write-offs, and settings. The current user profile determines which warehouse
contexts and screens are usable.

- TanStack Query holds server reads; query keys include the relevant warehouse,
  entity, and filter inputs. The server stays authoritative.
- The selected warehouse is a non-authoritative local UI preference. It is
  revalidated against the profile returned after authentication.
- `/settings/warehouses` is presented as **Objects** and edits the warehouse-service aggregate
  directly. The form persists independent production/main checkboxes or one representative parent,
  uses the shared IANA timezone selector with a visible UTC offset, and exposes lifecycle/timezone
  actions inside edit. The directory shows code, city, classifications, timezone, order and a
  tooltip warning for missing coordinates. An existing representative object exposes its complete
  directed support-link editor with priority, capability switches, weekdays, date exceptions and an
  optional service interval. The editor replaces that collection under the warehouse version fence;
  it never creates a second logistics object or map marker.
- `/settings/users` groups RWMS and rental entitlements in a dedicated access section. RWMS access
  enables object grants only for an eligible ManagerApp role; a shared timezone selector is used for
  create and edit, password change is an edit action, and the directory consolidates application,
  rental and object access. `WMS_ADMIN` remains readable for existing identities but is not offered
  as a new role assignment.
- Task-board brigade settings submit the selected membership and the workers'
  version-fenced current-brigade changes in one group command. The panel does
  not offer a second worker-table assignment action; task-board commits or
  rejects the complete change atomically.
- `/task-board` renders queues as side-by-side desktop columns with a vertical
  card stack in each column; mobile stacks the columns. It has no date selector
  or browser-owned queue decisions. The default view shows every current REAL
  card and hides future SHADOW cards. “Show future subtasks” reveals all shadows.
  Every eligible future card exposes an `Available to workers` checkbox to an
  `EDIT` user; it sends the entry version to the server and switches that stage
  between `SHADOW` and `REAL`. A promoted ordinary stage can be taken in parallel
  with an earlier ordinary stage, subject to the queue's WorkerApp switch, plan
  limit and worker qualification. The current stage cannot be demoted, and no
  later stage can be enabled while SES remains unfinished.
  “Full route” on a REAL card
  reveals and highlights that task's entries across
  every queue even while the global checkbox is off, expands those queues and
  vertically scrolls each column to its matching card without changing order; pressing
  it again clears that temporary route view and restores the prior queue state and scroll
  positions. The warehouse-scoped, versioned UI preference also restores the
  future-card switch, collapsed queues, board scroll and each queue's vertical
  scroll after task details/back navigation; it never stores task or queue facts.
  The first
  `availableTaskLimit` waiting REAL cards (six by default) are marked as the
  visual daily plan on the complete manager board. A `MANAGE` user edits that
  warehouse-local count from the column header and uses the adjacent checkbox
  to publish or hide the queue in WorkerApp. WorkerApp retains active REAL work,
  receives only that many waiting REAL cards and never receives shadows while the
  queue is enabled; disabling it removes the whole queue, including active cards.
  On an unfiltered desktop board an
  `EDIT` user can drag unpinned `WAITING REAL` cards to reorder them inside the
  same queue under entry/queue version fences and the observed target-card
  identity. Active, pinned and shadow cards cannot be dragged. Equal-height
  headers keep long names such as “Перемещение
  мебели” aligned with every other column.
  A directly executable electricity task therefore appears immediately, while
  manager promotion restores a future shadow ahead of a later unpinned REAL; a
  pinned REAL stays ahead. SES remains the exclusive holding gate. Driver
  movement and external capital work remain on their existing surfaces.
  Complexity is resolved only for currently rendered repair IDs in batches of
  at most 200.
- The `Ремонты` sidebar item and bare `/repairs` registry remain supported. The
  registry pages 50 maintenance-owned repairs at a time, resolves asset-owned
  cabin numbers in batches of eight concurrent reads, and shows type, current
  stage/work, priority, status and origin without a date or duplicate queue
  view. `?view=queue` is removed back to the registry. Create, detail and edit
  use `?create=1`, `?repairId=...` and `?repairId=...&edit=1`; direct workspace
  close/back falls back to `/repairs`, while a recorded in-panel origin is
  preserved.
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
  `dossier-service`. A completed task-evidence cover fact becomes a history
  activity with an opaque media ID, generation and task-entry ID, never an
  object-storage URL; the exact boundary is the [dossier OpenAPI](../contracts/openapi/dossier-service.yaml)
  and the strict [panel activity model](src/features/rental-items/dossier/model/dossier-service.ts).
- The cabin `История` tab renders those canonical activities as a compact,
  chronological, expandable timeline. Related estimate, repair, inventory, and
  media facts are grouped only by their canonical source or folder IDs; past
  operations use green markers and the latest loaded operation uses blue.
  Expanding a group shows the exact server-supplied time, actor, media states,
  event breakdown, and available route to its owning workflow without inferring
  missing domain data; permission-controlled technical mode retains raw source
  metadata. `Открыть фото задания` resolves only that opaque task-evidence
  reference through the public `TASK_BOARD_ENTRY` media owner proof and the
  caller's bearer session. See the [activity register](src/features/rental-items/dossier/dossier-activity-register.tsx).
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
  capital-repair and acceptance surfaces rather than the ordinary task board.
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
  changes. During cabin creation the first added image is the default title;
  every preview exposes an explicit title button, and the selected image is
  uploaded first with `sortOrder=0`. Zero-byte or nameless selections are
  rejected locally before the asset command; media-service remains the
  authoritative upload validator. Cabin and booking previews request
  `SMALL`, an opened gallery or work workspace requests `MEDIUM`, and the
  fullscreen viewer requests `LARGE` only on demand. See the
  [creation uploader](src/features/rental-items/rental-item-creation-photo-uploader.tsx).
  Warehouse cabin creation now computes the ordered 1-20 image manifest before
  the command (the title image is index zero) and atomically creates the cabin
  with an asset-owned durable creation intent and availability hold. The panel
  uploads into the server-provided media folder with the stable per-image
  command IDs and reports completion only after every image is READY and
  asset-service has proved the exact gallery and cover through media-service.
  An upload failure or dialog close leaves explicit pending work; reopening the
  dialog lists it and requires the user to reselect the exact source bytes and
  order before resume. Explicit abandon retains the cabin and media, while
  asset-service quarantines the cabin in `WAREHOUSE`. Inventory creation keeps
  the existing no-photo endpoint because its inspection finding owns the later
  photo workflow. See the [durable intent adapter](src/features/rental-items/rental-item-creation-intent-support.ts)
  and the [asset API](src/features/rental-items/api/asset-rental-items-api.ts).
  A cabin detail carousel reads only the media-service active gallery folder;
  its photo archive still lists every historical folder. If that authoritative
  active-folder projection fails while archive media loaded successfully, the
  detail reports the photo service unavailable instead of mixing folders as a
  fallback. Failed image-byte requests remain visible as errors even when metadata
  loaded successfully. Passport, archive and fullscreen views offer an explicit
  retry; a failed fullscreen request never becomes a permanent loading spinner.
  Automatic owner-proof retries remain bounded and successful photos are retained.
  Warehouse cards, booking cards and the passport carousel count and
  show only the active latest batch, with its explicit cover first and stable
  association order. Their fullscreen shortcut is constrained to that same
  batch; it never reintroduces older archive folders. The client also applies
  this ordering defensively when a cover appears later in the bounded response.
  The passport Photo tab lists retained media-owned folders before their photos and
  shows source, occurrence time, actor and photo count on each folder. It reads
  every `INVENTORY`/`MEDIA` dossier page independently from History-tab filters;
  unavailable provenance stays explicitly unknown instead of being guessed.
  The archive also follows every media cursor instead of stopping after the
  first 100 associations. The folder ID comes from the media-owned CABIN
  association, so consolidated legacy photos open together while inventory and
  later upload batches remain separate. Older folders remain available from the
  photo archive but never enter the passport or warehouse-card carousel.
  Repair task details keep the task's aggregate media, each work line's source
  media, and task-board result evidence in separate exact-reference galleries.
  Each authoritative acceptance refresh follows every database-backed
  maintenance page, reads one task-board snapshot, resolves the represented
  repair IDs through bounded batches, hydrates at most eight exact fallbacks
  concurrently, and reuses the synchronized task-board cabin number. It falls
  back to the asset-owned item read only when that task-board number is absent,
  avoiding an unbounded per-row request fan-out without changing domain
  ownership. The list and an opened exact repair poll independently every 15
  seconds. The opened repair remains actionable only when its exact acceptance
  projection supplies `readyAt`; another list-page failure therefore cannot
  hide valid work, while an unresolved child rework still closes the action.
  Only each work-line gallery uses a compact carousel with an in-image photo count and
  always-visible previous/next controls; aggregate and result-evidence
  galleries keep the standard shared presentation. For a synchronized repair,
  the aggregate gallery uses the first executable task-board entry proof and
  each work-line gallery uses its exact stage entry proof. These references
  still point to the existing canonical media objects; the panel does not
  change their owner. A draft without a task-board entry falls back to its
  original inventory, estimate, or repair proof, with an authoritative
  inventory source taking precedence. Worker-result evidence also uses its
  exact task-board entry proof.
  The acceptance/rework dossier uses the same synchronized-entry precedence. On desktop, its acceptance-photo
  panel and selected-queue result-photo panel occupy bounded workspace rows, with a larger lower row
  for result photos and queue details; the queue details
  scroll within their own card rather than stretching or clipping the comparison carousel. Every
  acceptance and result image preserves its full bounds with contain fitting. The right card does not
  duplicate per-photo worker confirmation metadata already represented by the result carousel. The top panel can switch
  between acceptance photos and aggregate task photos; while comparison is open, the alternate set
  occupies the selected queue's result-photo card and closing comparison restores that queue's result
  carousel. Work-line photos are not mixed into either set. The queue selector sits directly in the lower-right card
  title and shows only one physical repair queue at a time without an extra selector row or an
  `Очередь` prefix. Historical stages with the same non-null physical queue ID are coalesced only
  for this read: the card keeps all of their work, material, task-board result, timing, brigade and
  member facts while persisted history remains unchanged. Work and material rows use a matching
  compact list style and description–quantity text, work comments open on demand, and each work source set stays in its
  smaller line carousel. On the interactive acceptance page, the write-off and acceptance/rework
  actions are rendered in the header beside the Back button rather than below the queue details.
  Starting rework stores only the source warehouse, repair identity, version and selected lineage
  identities in the URL. A reload fetches the authoritative source again and rejects a malformed,
  moved, stale or no-longer-pending intent instead of trusting browser navigation state. Direct and
  rework drafts also retain one opaque creation idempotency key for their entire lifetime and every
  transport retry.
  These controls change presentation only and do not create browser-owned repair or media state.
  They do not substitute the cabin gallery. Inventory folders use the inventory
  dossier activity for their source, occurrence time, and actor labels. See the
  [rental-item media hook](src/features/rental-items/use-rental-item-media.ts),
  [cover parser](src/features/media/api/http-media-client.ts), and [media
  feature](src/features/media/).
- A cabin detail with edit access and at least one READY image places `Создать
  представление` directly beside the photo count. The version-fenced,
  idempotent logistics command freezes only the READY photo set from the
  media-owned active (latest current) gallery folder; older folders remain in
  the passport archive and are not mixed into a newly created presentation.
  After a successful response the panel starts copying the absolute public link and
  immediately opens its `/photos/{token}` route. That route is outside the
  authenticated React subtree and renders the cabin number, dimensions,
  finishing, category, characteristics, nullable linoleum, creation time, photo
  count and immutable image grid. It has no RWMS brand. A photo opens at full
  viewport size with 1x-5x button/wheel/pinch zoom, drag panning and
  previous/next controls. Left/Right always changes the photo, including while
  zoomed; Up/Down can pan a zoomed photo. The public page owns viewport-height
  vertical scrolling, so every row remains reachable even though the global
  application shell keeps the document body fixed. It never exposes warehouse, status, rental type,
  client, passport, actor, version or storage-locator fields. See
  the [photo-presentation client](src/features/rental-items/cabin-photo-presentations-api.ts)
  [public page](src/features/rental-items/public-cabin-photo-presentation-page.tsx),
  and [fullscreen viewer](src/components/media/fullscreen-photo-viewer.tsx).
- An expiring logistics-owned contractor capability opens
  `/contractor-routes/{token}` outside the authenticated React subtree. The
  page reads the live, ordered task-board route through logistics, shows only
  the scoped address, contact, cargo, instructions, and capability-proxied
  images, and applies exact version-fenced `START`/`COMPLETE` entry commands.
  Result evidence is limited to a bounded JPEG or WebP upload with its capture
  time, checksum, and stable evidence id; completion remains disabled until
  the owner services report READY evidence. The browser neither invents task
  ids nor stores a contractor route as domain state, and an expired or revoked
  token renders one safe unavailable-link state. See the
  [public route client](src/features/logistics/contractor-route-share/contractor-route-share-api.ts)
  and [public route page](src/features/logistics/contractor-route-share/public-contractor-route-page.tsx).
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
- A writable cabin dossier offers `Отгрузка задним числом` for `FREE`, `RENTED` and the supported
  repair statuses, plus `Возврат задним числом` for `RENTED`. For an already rented cabin with no
  shipment document, that shipment command restores the missing client/date provenance without
  repeating the physical asset transition. Once the imported shipment exists, the same action
  edits its client, optional shipment driver and date under the document version instead of
  creating another document. Its modal reuses the rental-client chooser:
  a new client is created first with a separate stable idempotency key, then the fenced historical
  logistics command is retried with its own key. A historical shipment may select one warehouse
  driver or explicitly keep `Неизвестен`; an unavailable or empty driver directory does not
  invalidate that unknown choice. The UI submits a complete driver snapshot/ID pair or a null pair,
  never creates a route or driver task, and always keeps historical returns driverless. It refreshes
  only the affected cabin, logistics and dossier queries after acceptance. The shipment list places
  `Отменить` immediately after `Показать состав` for failed imported shipments in `CONFLICT` or
  `RECONCILIATION_REQUIRED`; successful imports remain non-cancellable and the service verifies
  that no completed or unknown asset transition exists. See the
  [dossier page](src/features/rental-items/rental-item-detail-page.tsx) and
  [historical-movement dialog](src/features/rental-items/historical-rental-movement-dialog.tsx),
  plus the [shipment list](src/features/logistics/logistics-shipments-page.tsx).
  When no live logistics shipment exists, that dossier labels an imported
  passport as `Отгружена` only when both its shipment date and non-blank tenant
  are present. Any live logistics document remains authoritative over those
  legacy passport facts.
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
  while the atomic server confirmation remains authoritative. A NORMAL public
  presentation exposes the server-owned `requestableDeliveryDates`: four
  warehouse-local dates from day +2 through day +5. The calendar states that
  these dates are available to request and coordinate, disables every other
  date including today and tomorrow, and accepts one to four independent
  date-only wishes from that list. This does not promise or reserve logistics
  capacity; the manager order detail shows the returned values read-only and
  never overwrites them.
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
  order-owned additional contacts, one to four independently selected desired
  dates from the server-provided requestable list, and the initial rental
  duration. The returned `desiredDeliveryWindows`
  are read-only on order detail as a client selection and remain distinct from
  the logistics-owned scheduled date. A DRAFT detail requires a nonblank contact
  phone, selected warehouse, and one or more cabins; it never offers a manager command for the
  initial per-cabin term. After a cabin is
  actually shipped/rented, its card shows duration, shipment date, and
  calculated return date. `Продлить аренду` selects one or more such cabins,
  shows desired and factual furniture only as information, and sends the shared
  month count through the existing versioned, idempotent
  `POST /orders/{id}/rental-terms/extend` command. The manager contact dialog
  keeps the selected client. Saving moves the order to the saved state for
  subsequent logistics planning; it does not create a shipment or trip.
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
  old cabin, a required reason, and a free candidate from the selected physical
  source. The source selector retains the order's service warehouse and adds
  only active support links that allow both inventory and direct fulfilment.
  Changing it clears the stale candidate and page; the candidate read and
  versioned idempotent replacement carry that source while the order warehouse
  remains unchanged. A conflict refreshes order and candidates without clearing
  the replacement draft. See the [replacement
  dialog](src/features/orders/components/order-unit-replacement-dialog.tsx) and
  [order adapter](src/features/orders/api/orders-api.ts).

## Logistics driver board

- Shipment and return forms select an active driver by opaque worker ID. A planned warehouse
  transfer may separately name its trip driver and resource-reposition intent; an unassigned
  transfer remains visible to the qualified warehouse-driver pool instead of inventing an
  employee identity.
- A regional order retains its service warehouse independently from each outbound cabin's physical
  `inventorySourceWarehouseId`. Virtual order lines derive that source from the selected cabin. A
  shipment batch must contain exactly one source: selection and submit both reject a mixed batch,
  the command and idempotency signature carry the source, and the driver picker reads the physical
  source warehouse. Scheduling and expanded task rows show both warehouse names.
- The operational return, shipment and warehouse-transfer pages select one exact
  day instead of a range or a scheduled/unscheduled mode. The default is today
  in the selected warehouse IANA timezone; an explicit choice is retained as
  the `date` URL parameter across reloads without clearing other page state.
  Active list reads always send that day to logistics-service, and a transfer
  day includes both arrivals at and departures from the selected warehouse.
- Shipment confirmation, overdue return labels, furniture-task defaults and
  scheduling dialogs evaluate “today” in the IANA timezone of the exact
  inventory-source/owning warehouse. They fail closed when warehouse metadata
  is unavailable instead of falling back to UTC, browser time or a global
  Moscow date.
- Expanded rows on `/logistics/shipments` and `/logistics/returns` show the
  linked order and customer overview before the
  cabin list: address, named contacts, phones, coordinates, rental term, and
  outbound/return drivers remain visible when their authoritative reads are
  available. Each cabin compares order-desired furniture with its current
  asset-service contents and shows a text-labelled required, action-needed,
  loaded, empty, or unavailable state. Scheduling dialogs repeat the furniture
  summary for exactly the cabins in the pending command.
- A direct historical shipment created by completed inventory has no order,
  driver, address or stock-allocation workflow. Its expanded row instead shows
  the frozen inventory client/date and the exact
  `inventoryShipmentFurniture` quantities, including a valid empty list. It
  never compares those facts with an order or shows a false action-required
  furniture warning.
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

## Daily brigade status

`/settings/kpi` accepts a schedule starting today or later. Save creates the
normal `DRAFT`; explicit activation applies a today's revision immediately to
the whole warehouse-local calendar day, while a future revision remains
scheduled. Past dates are rejected.

The KPI palette is edited once for the installation and is independent of the
selected warehouse; the work schedule applies globally to all objects.

`/` is the selected warehouse's live daily-brigade view. It reads the current
[task-board snapshot and daily activity projection](../contracts/openapi/task-board-service.yaml),
active worker groups, the active KPI schedule/palette, and only the maintenance
repairs referenced by today's intervals. It stores none of those facts in the
browser. A row spans the warehouse-local `shiftStart` through `shiftEnd`; the
current-time marker advances every second and is clamped outside the shift. No
active schedule or a configured day off is shown as an explicit state rather
than inventing a work line.

Each segment starts at task-board's persisted TAKE timestamp and ends at its
persisted completion timestamp; a live segment ends at current server-aligned
time. Shift bounds only position and clip segments and never replace those
actual times. Completed tasks remain as neutral history, while a currently
`IN_PROGRESS` or `PAUSED` segment uses the server-configured, installation-wide KPI palette range
for its live remaining percentage. Hover/focus shows its exact start and end,
cabin, physical queue, repair complexity, priority and remaining percentage.
An unconfigured palette leaves live work neutral and explains why. The dashboard
refreshes both task-board reads every 30 seconds for external worker changes;
[`HomePage`](src/features/home/home-page.tsx), the
[activity client](src/features/home/daily-brigade-activity-api.ts), and the
[presentation mapping](src/features/home/daily-brigade-timeline.ts) are the
implementation references.

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

Transport failures and malformed successful JSON, SSE, and media protocol
responses use that same boundary. The rendered `ApiError.message` is fixed,
Russian, and selected from status/code; raw browser, parser, response-body, or
service text is retained only as `diagnosticMessage` for structured telemetry.
Both native and cross-realm `AbortError` values are classified as
`REQUEST_ABORTED`, and cancellation diagnostics never become UI copy.

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

An inventory inspection save rereads the current session and uses that broad
session revision, while retaining the finding revision captured when the editor
opened. An unrelated cabin update therefore does not block a supplement, but a
concurrent edit of the same cabin still fails closed with `409`.

Before furniture review, the MANAGE completion flow shows the server-owned
`RETURNS` and `SHIPMENTS` phases. Every found former-rental cabin requires its
actual return date and client. The shipment phase submits only missing cabins
known to have departed, with actual date, existing client and arbitrary
catalog-versioned furniture quantities; every unselected missing cabin is
explicitly shown as an automatic write-off proposal. Confirming an empty
shipment list is valid and sends all remaining missing cabins to the ordinary
administrator-approved write-off flow. Only local cabins proceed to furniture
reconciliation.

The MANAGE-only inventory finish surface exposes **Recalculate session changes** in both cabin and
furniture review. It is disabled while a review/plan draft is dirty or a competing finish command is
running. The command refreshes authoritative membership on the server; on success the panel keeps
saved cabin inspections, replaces the session detail and removes only derived registry, furniture,
statistics, final-plan and completion-preview caches so the review can be rebuilt from current data.

Completed inventory history exposes the separate MANAGE-only **Recalculate and
apply outcome** action. It submits the exact completed session revision and final-plan
version/hash with an idempotency key to the public inventory boundary. Its mandatory
confirmation explains that the inventory is current truth: no-work cabins become
`FREE`, ordinary work becomes `REPAIR`, forced capital work becomes
`CAPITAL_REPAIR`, and active rental, reservation, internal-transfer or previous-repair
bindings may be superseded. Ordinary work without inbound movement is routed to task-board;
movement waits for logistics delivery, and capital work stays on the active capital route.
Applying the outcome alone never opens acceptance or rework. Photos, evidence and history remain preserved. The action
is disabled without the completed final-plan identity or while a competing history
command runs. An accepted response updates the publication cache and refreshes only
the affected session detail, final plan, statistics and warehouse history queries; the
response may identify a strictly newer corrected final-plan version/hash when the server restored
an omitted explicit observation. The panel accepts that authoritative successor and refetches its
head; a lower version or a changed hash at the same version remains a conflict. In completed
history, the outcome-status column renders the publication intent's required
`desiredAssetStatus` (`FREE`, `REPAIR` or `CAPITAL_REPAIR`) only after that intent succeeds and
adds a movement marker when `movementToRepair` is set. A current pending or failed intent is
shown as not applied; the panel never presents the frozen inspection snapshot as the final
status. Active sessions, cancelled history and rows without an outcome intent continue to show
the recorded snapshot as inspection evidence. The
authoritative contract is the [inventory OpenAPI](../contracts/openapi/inventory-service.yaml).

## Local development and checks

Use a current Node.js LTS installation from this directory:

```bash
npm ci
npm run dev
npm run typecheck
npm test
npm run lint
npm run build
npm run build:admin
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
