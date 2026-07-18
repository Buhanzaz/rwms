# 08 Integrations

## REST Controllers

Controllers:

- `MobileApiController`
- `RepairMediaController`

## Mobile API

Base path: `/api/mobile`.

Endpoints:

- `GET /catalog`
- `GET /warehouses`
- `GET /rental-items/validate?warehouseCode=&number=`
- `GET /rental-items/passport/options`
- `GET /rental-items/passport?warehouseCode=&number=`
- `POST /tasks`
- `GET /tasks/by-qr/{qrCode}`
- `DELETE /tasks/by-qr/{qrCode}`
- `POST /photos/upload-url`
- `POST /photos/upload`
- `POST /estimates/send-email`

Important DTO records in `MobileApiController`:

- `CatalogResponse`
- `CatalogNodeDto`
- `WarehouseOptionResponse`
- `RentalItemValidationResponse`
- `InventoryPassportOptionsResponse`
- `InventoryClassifierOption`
- `InventoryAttributeDefinitionDto`
- `InventoryAttributeOptionDto`
- `InventoryAccessoryOptionDto`
- `InventoryPassportResponse`
- `InventoryAttributeValueDto`
- `InventoryPassportRequest`
- `InventoryAttributeValueRequest`
- `InventoryAccessoryQuantityRequest`
- `TaskSaveRequest`
- `TaskItemRequest`
- `TaskSaveResponse`
- `TaskResponse`
- `TaskItemResponse`
- `PhotoResponse`
- `PhotoUploadUrlRequest`
- `PhotoUploadUrlResponse`
- `FileUploadResponse`
- `SendEstimateEmailRequest`
- `SendEstimateEmailResponse`

Notes:

- `POST /photos/upload-url` returns an upload URL for `/api/mobile/photos/upload`; it is not a MinIO presigned URL.
- `POST /estimates/send-email` is a stub and returns not accepted; real email integration is not implemented.
- Security is public/admin in legacy; see `07_SECURITY/README.md`.

## Media API

Endpoints:

- `GET /api/repair-media/{photoId}?variant=preview|thumb|tiny|original`
- `GET /api/rental-items/{rentalItemId}/latest-photos.zip`

Media content is loaded through `RepairEstimateService`.
Variant defaults to `preview`.

## Media Storage

`LocalMediaStorageService` supports:

- Local filesystem storage under `repair.media.local-root`.
- Optional MinIO object storage.
- Incoming upload object path.
- Variant object path building.
- Delete incoming object only.
- Delete whole media family.
- Structured path context based on warehouse code/city, item number, event type/date, event id, photo id.

Important behavior from tests:

- READY processing result deletes only incoming object.
- FAILED processing result keeps incoming object for retry/debugging.

## RabbitMQ Photo Processing

Java side:

- `PhotoProcessingQueueService.publish` sends task messages to `repair.media.process`.
- `@RabbitListener` consumes result messages from `repair.media.processed`.
- Result updates `RentalItemEventPhoto.processingStatus`, dimensions, object paths, and errors.

Task message fields:

- `photoId`
- `bucket`
- `incomingObjectKey`
- `familyRootKey`
- `contentType`
- `rentalItemId`
- `eventId`
- `warehouseCode`
- `itemNumber`
- `eventType`
- `previewLongEdge`
- `thumbLongEdge`
- `tinyLongEdge`

Result message fields:

- `photoId`
- `status`
- `originalObjectKey`
- original/preview/thumb/tiny dimensions
- `error`

## Go Photo Worker

Path: `wms-panel-old/photo-worker-go`.

Behavior:

- Consumes RabbitMQ queue `repair.media.process`.
- Reads incoming object from MinIO.
- Builds:
  - `original.jpg`
  - `preview_*.webp`
  - `thumb_*.webp`
  - `tiny_*.webp`
- Publishes result to `repair.media.processed`.
- Uses `bimg/libvips`.
- Exposes health endpoint `/healthz` on port 8081.

Docker Compose supplies MinIO/RabbitMQ and worker env vars.

## AI Integrations

Two AI surfaces:

1. `WarehouseAiQueryInterpreter`
   - OpenAI-compatible HTTP call to Mistral.
   - Falls back to local parsing when unconfigured or failing.
   - Used by `WarehouseAiSearchView`.

2. `SmartReservationSearchService`
   - Uses Spring AI `OpenAiChatModel`.
   - Builds context from warehouses, classes/types/conditions/tags/attributes.
   - Returns structured criteria, recognized tokens, warnings.
   - Fails fast when AI is not configured in tests.

Default model is `mistral-small-latest`.
API key is external and must not be committed.

## File/Workbook Integration

`RepairCatalogWorkbookImportService` uses Apache POI to import repair catalog workbook data.
`RepairCatalogWorkbookImportRunner` is present as a component/runner.
Exact operational trigger is not fully documented here; treat as `UNKNOWN` until source is read before implementing import UI/API.

## External Integration Unknowns

- Real email sending is not implemented.
- External ERP/accounting integration was not found.
- Payment integration was not found.
- Real production object-storage policy is `UNKNOWN`.
- The common target RabbitMQ topology/retry baseline is resolved by F0; see the
  target foundation section below. Long-term retention, delivery SLA, and
  historical replay remain `UNKNOWN`.

## Panel Browser-Local Estimate Media Mock

Target implementation note; this does not replace the legacy media pipeline facts above:

- Current `panel` estimate media uses `IndexedDbRepairEstimateMediaAdapter` with IndexedDB database `rwms-repair-estimate-media`, object store `media`, and Blob-plus-metadata records.
- The earlier `rwms:repair-estimate-media:v1` localStorage/base64 store is **SUPERSEDED**. It remains only as best-effort migration input and is removed after successful migration or invalid legacy data; the current adapter never writes new base64 data to localStorage.
- Media writes are serialized through a runtime-shared promise queue and origin-wide Web Lock, then committed through IndexedDB `readwrite` transactions.
- Browser-mock limits are 25 MB per file and 250 MB per upload batch, with capacity/quota failures surfaced as explicit errors.
- Object URL hydration uses a 24-entry LRU, in-flight deduplication keyed by `contentVersion`, cross-context `BroadcastChannel` invalidation, and non-persisted `pagehide` cleanup.
- These storage/cache details remain behind the unchanged `RepairEstimateMediaClient` port and are not final media-service API contracts.

## Dossier Media Variants And Original Access (2026-07-12)

- Current browser media records model `UPLOADING`, `PROCESSING`, `READY`, and
  `FAILED`, provenance through IO/Go, and `THUMB`/`PREVIEW`/original semantics.
  The mock persists the source Blob in IndexedDB; it does not perform real
  compression or network processing.
- Generic hydration returns preview variants plus `originalAvailable`; it never
  exposes an original URL. Original URLs are resolved lazily through the media
  port only for `ESTIMATE`, `INSPECTION`, or `WORK` viewer contexts.
- The per-user `showOriginalPhotos` preference defaults to false and is stored
  through a preference port. It applies to estimate, inventory inspection,
  repair work, and repair acceptance viewers. Warehouse dossier photo folders
  and history remain preview-only regardless of the preference.
- Durable browser refs preserve the original storage reference across
  hydrate/dehydrate even though hydrated common DTOs omit its URL.

Target evidence:

- `panel/src/features/media/`
- `panel/src/features/repair-estimates/ports/repair-estimate-media-client.ts`
- `panel/src/features/repair-estimates/adapters/indexed-db-repair-estimate-media-adapter.ts`
- `panel/src/features/acceptance/repair-acceptance-dossier.tsx`

## Target RabbitMQ Foundation (2026-07-12)

RabbitMQ is the approved common at-least-once integration bus:

- durable topic exchange `rwms.domain.v1`;
- routing keys `<domain>.<aggregate>.<event>.v1`;
- publisher confirms and mandatory returns;
- consumer-owned durable queue and DLQ;
- exactly three retries after the initial delivery with bounded backoff, then
  reject without requeue/dead-letter;
- producer-owned transactional outbox and consumer-owned inbox deduplication by
  `eventId`; F0 documents this ownership but does not share persistence models.

The common starter is opt-in and backs off when AMQP is absent or disabled. A
RabbitMQ 4.1 Testcontainers integration suite proved routing to DLQ, four total
delivery attempts (initial plus three retries), publisher acknowledgement, and
mandatory return of an unroutable message. Local Compose provides the broker;
credentials remain external/default-development configuration rather than
contract data.

Resolved: broker selection and the common retry/DLQ/confirm/return baseline are
no longer `UNKNOWN`. Still `UNKNOWN`: long-term retention, operational delivery
SLA, historical replay policy, and each future service's domain routing/event
contract until its owning stage.

Evidence:

- `compose.yaml`
- `platform/spring-boot-starter/src/main/java/dev/buhanzaz/rwms/platform/autoconfigure/RwmsRabbitAutoConfiguration.java`
- `platform/spring-boot-starter/src/main/java/dev/buhanzaz/rwms/platform/rabbit/RwmsRabbitTopologyFactory.java`
- `platform/spring-boot-starter/src/test/java/dev/buhanzaz/rwms/platform/autoconfigure/RabbitTopologyIntegrationTest.java`

## F2 Task-Board RabbitMQ Integration (2026-07-13)

`task-board-service` is the first domain producer/consumer to implement the F0
service-owned messaging convention. It publishes committed facts only through
`task_board_outbox`:

- `task-board.board-task.created.v1`;
- `task-board.board-task.cancelled.v1`.

The outbox stores the exact UTF-8 `EventEnvelope` body plus SHA-256, correlation
and causation metadata, actor snapshot, aggregate version, delivery attempts,
and a database-time lease. Relay claims preserve per-aggregate head-of-line
ordering; owner/token fencing prevents a late publisher confirmation from
finishing a reclaimed lease. Publisher confirms, mandatory returns, bounded
backoff, broker recovery, database recovery after broker acknowledgement, and
at-least-once replay are covered by RabbitMQ 4.1/PostgreSQL tests.

The service-owned durable queue `task-board-service.delivery-audit.q` is a
technical self-consumer only: it validates the canonical created/cancelled
contract and records an inbox deduplication row, but does not mutate domain
state or republish events. Identical duplicate `eventId` deliveries are
harmless; a reused ID with a different body is poison, receives four total
attempts, and is dead-lettered without an inbox effect. Infinite requeue is not
used.

Canonical AsyncAPI describes `rwms.domain.v1`, routing keys, AMQP headers,
durable queue, publish operations, and the self-audit receive operation. Event
delivery is at-least-once. Long-term outbox/inbox/event retention, replay
windows, delivery SLA, and production operational ownership remain `UNKNOWN`.

Evidence:

- `contracts/events/task-board-events.yaml`
- `services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/integration/`
- `services/task-board-service/src/test/java/dev/buhanzaz/rwms/taskboard/TaskBoardRabbitIntegrationTest.java`
- `services/task-board-service/src/test/java/dev/buhanzaz/rwms/taskboard/TaskBoardContractIntegrationTest.java`

## F4K Kafka/Cloud Stream Foundation (2026-07-13)

Commit `52c0702` implements the shared Kafka 4.3.1/Spring Cloud Stream 5.0.2
foundation without publishing application business events. The common starter
is conditional and fail-closed for approved destinations and event schemas. It
enforces aggregate-ID record keys, synchronous broker acknowledgement,
`acks=all`, producer idempotence, Zstd, initial attempt plus retries at
1s/2s/4s, consumer-owned DLT naming, inbox/checkpoint deduplication and
aggregate-version-gap quarantine primitives.

Kafka 4.3.1 KRaft is available in the Compose `core` profile. RabbitMQ remains
the factual F0/F2 application integration and stays enabled until F4R; F4K does
not dual-publish, retarget existing outbox rows or remove AMQP. The stateless
gateway has no Kafka client. Auth and task-board acquire business topics/outbox
cutover only in F4A/F4T.

Evidence:

- `contracts/events/technical/`
- `platform/spring-boot-starter/src/main/java/dev/buhanzaz/rwms/platform/kafka/`
- `platform/spring-boot-starter/src/test/java/dev/buhanzaz/rwms/platform/kafka/`
- `compose.yaml`
- F4K commit `52c0702`

## F4A Auth Kafka Recovery Evidence (2026-07-14)

Auth binds the approved `USER_AUTHORIZATION` and `WORKER_ACCESS` producer
channels during startup without emitting a fake event. The transactional outbox
still publishes through the common Cloud Stream `StreamBridge` publisher.

Kafka aggregate keys are UTF-8 byte arrays, matching the configured
`ByteArraySerializer`. The previous string key caused the first due recovery
attempt to fail after the broker returned. A real Kafka outage/recovery test now
passes using the unchanged initial-attempt-plus-three-retries policy.

The test treats a timeout-race redelivery as a valid at-least-once duplicate and
proves safety with stable event IDs, inbox deduplication, ordered unique
aggregate versions and payload/secret scans. This is focused F4A recovery
evidence; the complete F4A exit matrix remains open.

Final F4A verification also covers startup prebinding of both sanitized DLT
topics, their real-broker recovery on remaining bounded attempts, database
outage/restart, inbox deduplication, aggregate-version quarantine and strict
secret-free DLT bodies. The auth suite passed 129 tests with no failures.

## F4T Task-Board Kafka Candidate (2026-07-14)

The current task-board candidate publishes sanitized `DomainEventEnvelopeV2`
facts to seven aggregate-family topics through a PostgreSQL outbox and
Cloud Stream `StreamBridge`. Producer bindings and their consumer-owned DLT
destinations are initialized without synthetic domain events. Aggregate UUIDs
remain partition keys and every lifecycle fact of one aggregate stays on one
topic.

Functional consumers receive `byte[]`, validate the canonical aggregate/fact
contract, deduplicate by event ID and atomically advance per-aggregate
checkpoints. A version gap blocks the aggregate and creates quarantine evidence.
Validation failures are sent directly to a sanitized DLT; transient processing
failures use the bounded initial attempt plus 1s/2s/4s retry policy before DLT.
DLT bodies expose only failure code, message checksum and recorded time.

Rabbit delivery is intentionally still present and enabled independently.
F4T supplies dual-run/parity capability but does not retire Rabbit; F4R owns
drain, retargeting and runtime removal after proof. The canonical schema is
`contracts/events/task-board/task-board-events-v1.schema.json`. Final real
bounded retry/recovery and Rabbit-to-Kafka cutover parity are covered by the
focused runtime Testcontainers batch. Removal of the Rabbit runtime remains
F4R work.

## F4R Rabbit Retirement And Media Compatibility (2026-07-14)

RabbitMQ is retired from target Spring and task-board integration. The common
starter and task-board runtime contain no AMQP dependency, binding, relay,
listener or dual-write path. Kafka outbox/inbox/DLT/quarantine remains the
target transport implementation.

The unchanged legacy Go worker is the only exception. Start its isolated broker
with `docker compose --profile media-compat-rabbit up -d media-compat-rabbitmq`.
The compatibility contract uses `PHOTO_RABBITMQ_URL`, request queue
`repair.media.process`, result queue `repair.media.processed`, and network alias
`rabbitmq`. The worker itself is not a root-Compose deployable and remains Stage
4 work. V1 envelopes and F0/F2 Rabbit evidence stay available read-only.

## F4G Trace And Correlation Boundary (2026-07-14)

The gateway propagates standard W3C `traceparent`/`tracestate` on downstream
HTTP requests. `X-Correlation-Id` remains the separate RWMS technical request
identifier and is never rewritten into a trace identifier. Gateway integration
tests prove both values survive the HTTP hop independently.

The gateway deliberately has no Kafka or Cloud Stream dependency. Trace
continuation from downstream HTTP processing to Kafka publication remains in
the F4A/F4T service instrumentation, preserving service ownership and the
stateless edge boundary.

## F4I Readiness Integration Candidate (superseded 2026-07-14)

The service-only product direction removed F4I deployment readiness from the
active roadmap. Kubernetes/Helm/Kind and readiness-only integrations have no
current RWMS scope; their prior candidate results remain historical audit
evidence and do not block W1.

## W1 Warehouse Kafka Outbox (2026-07-14)

Warehouse lifecycle changes write a sanitized immutable envelope in the same
PostgreSQL transaction as registry state. The relay owns lease, checksum,
aggregate ordering, 1s/2s/4s retry, durable DLT and operator requeue; it marks
an event `PUBLISHED` only after broker acknowledgement. The topic is
`rwms.warehouse.warehouse.v1`, keyed by warehouse UUID, with event types
`warehouse.warehouse.created.v1`, `.changed.v1` and `.deactivated.v1`.

The event contract permits only `warehouseId`, `code`, `timeZone`, `active`
and nullable `sortOrder`. It carries no name, city, address or protected auth
state. Kafka is transport only; W1 intentionally has no warehouse event store
or consumer inbox.

## Asset integrations (implementation record, 2026-07-16)

`contracts/events/asset/asset-events-v1.schema.json` defines sanitized facts on
aggregate-family topics keyed by aggregate ID. The asset transactional outbox
uses the event-store envelope; consumer inbox deduplication, version-gap
quarantine, bounded retry and a hash-only DLT avoid treating Kafka as the
canonical archive. Comment text, manual-note text, tenant references, media
URLs and PII are rejected before an envelope is persisted.

The only new cross-service call is the direct private warehouse existence/active
check, using the narrow asset client credential rather than the gateway. The
required media upload/read integration for `ownerType=RENTAL_ITEM` is not
connected yet because no matching approved media HTTP edge exists; the panel
therefore fails closed instead of inventing a route or retaining a mock.

### Asset Kafka recovery evidence update (2026-07-16)

The `AssetKafkaBrokerRecoveryIntegrationTest` uses real PostgreSQL and Kafka
containers. It pauses the broker and proves that the outbox row remains
`PENDING` with no `published_at`, then proves publication after recovery. It
also covers at-least-once duplicate inbox safety, aggregate-version-gap
quarantine/checkpoint blocking, validation DLT sanitization and a byte-array
aggregate key compatible with the enforced `ByteArraySerializer`. Focused
consumer tests cover initial delivery plus the bounded 1s/2s/4s retry budget.

## Maintenance integrations (verified implementation, 2026-07-17)

Auth supplies a disabled-by-default maintenance client with separate exact
downstream scopes. Asset exposes only maintenance-owned lease/fenced status
operations; task-board exposes source-bound task sync; the gateway adds only
the stateless public maintenance route. Maintenance consumes safe media and
task-board facts into service-local projection/inbox state and never receives a
broad asset, queue-management or media credential.

Outbound facts use catalog-version, estimate and repair topics through a
PostgreSQL outbox and broker acknowledgement. Inbound effects combine event-ID
deduplication, effect and aggregate checkpoint in one transaction; gaps
quarantine the aggregate. Validation goes directly to the sanitized DLT,
transient failures use bounded 1s/2s/4s retries, and replay requires an
operator-reviewed action. Tests cover duplicates, ordering, outages,
DLT/quarantine recovery and late task correlation without moving authority
into Kafka.

## Stage 7 inventory integration proposal (2026-07-17)

Approval resolution: the user's `Начинай Stage 7 все разрешаю` response,
followed by `Продолжай`, approves the exact direct-service sequence, stable
capture/source create, point-in-time validation, maintenance plan/source
boundaries, messaging and query/statistics contract below. The missing media
runtime is authorized as a separate first subgate; authority is not runtime
evidence.

Current target gaps are explicit: there is no inventory OAuth client,
inventory-authorized warehouse endpoint/allowlist returning the required
version/active/timezone shape, stable asset population capture/global resolve/
permanent source create, maintenance inventory upsert or public gateway route.
Service-specific private warehouse endpoints already exist for auth and asset,
but do not authorize inventory. Asset public pages cannot freeze one population
above 200 records. Inventory-origin repair lookup/upsert is hard-wired to the
local browser adapter and is invisible to maintenance HTTP.

The proposed narrow sequence uses direct exact-scope HTTP only:

- warehouse returns active identity/version/timezone;
- asset accepts an idempotent capture request keyed by the inventory start
  operation and technical attempt IDs and returns fixed membership/count/digest/
  order plus stable pages. A changed request fingerprint conflicts. Each capture
  has an exact non-sliding 30-minute TTL. Inventory releases immediately after
  its copied start transaction commits; concurrent losers release immediately,
  no capture remains on `ACTIVE`, and asset-side expiry is crash/orphan fallback.
  Inventory commits no local session when copy/digest/dependency/expiry checks
  fail before its transaction. A still-uncommitted public-key retry may append a
  new technical attempt, while a committed retry replays the local session;
- asset also provides global number resolution and permanent
  `inventoryId:findingId` create; it exposes no inventory business lease, hold
  or fenced-status mutation;
- completion preview receives an asset point-in-time validation snapshot with
  versions/status/warehouse, `validatedAt` and digest. Complete repeats that
  validation immediately before local commit and returns stale `409` when it
  differs. Matching validation is frozen with local CAS; it is not a token or
  proof that remote assets remain unchanged until PostgreSQL commits. A later
  canonical change preserves completed history and is rechecked as a blockable/
  reconcilable current precondition at maintenance publication;
- during `WORK_STAGED` save, `maintenance.inventory` resolves `AUTO` against the
  then-active warehouse catalog or validates a `MANUAL` selection and returns
  exact catalog-version/node/queue IDs, codes/kinds, normalized lines/prices,
  durations, ordered route, movement/photo requirements and one immutable
  fingerprint;
- after completion, maintenance accepts exact source upsert with
  `origin=INVENTORY` and that historical snapshot/fingerprint. It verifies
  source/current-asset preconditions but never regenerates, silently upgrades
  or reroutes the plan after later catalog activation. Maintenance owns the
  repair asset lease and existing task-board synchronization;
- gateway exposes only the later public `/api/inventory/**` route.

No task-board change or inventory task-board/media credential is proposed.
Inventory consumes owner-bound finalized media facts and stores only opaque
`{mediaId,generation}` references. The absent media HTTP/JWT/PostgreSQL/outbox
runtime still blocks production upload and full Stage 7 cutover; resolving it
is a separately authorized previous-stage closure, not inventory integration
work.

The proposed inventory session/publication aggregate-family topics use the
existing transactional outbox, broker acknowledgement, inbox/checkpoint
atomicity, version-gap quarantine, sanitized DLT and bounded initial plus
1s/2s/4s retry rules. Exact schemas remain forbidden until contract approval.

The public inventory outline additionally proposes VIEW-authorized,
warehouse-scoped server paging for session history, detail/findings and frozen
statistics, with business-date and UTC start/terminal filters. Only `COMPLETED`
sessions have statistics rows or enter summaries; `ACTIVE`/`CANCELLED` remain in
history/detail. Browser LocalStorage totals are not query authority.

Integration verification must cover public-key/technical-attempt replay and
mismatch, deterministic pages/digest, immediate copied/loser release, no active
retention, non-sliding 30-minute crash/TTL cleanup and no local start on pre-
commit failure; `AUTO`/`MANUAL` freeze, later catalog activation without
rewrite, historical preview validation, exact upsert fingerprint replay/
conflict, and maintenance-owned lease/task recovery. Query contract tests must
cover VIEW, warehouse isolation, paging/sort bounds, time-boundary and
completed-only statistics semantics, concrete quantity/count/overflow limits,
and independent category/grand/aggregate-row `HALF_UP` parity with maintenance.
Race tests must distinguish a pre-fresh-validation change (`409`/new preview)
from a post-`validatedAt` change (retained completed snapshot plus publication
block/reconciliation), without adding an inventory asset lease. Publication
tests must exhaust the required-intent fold precedence, blocked peers with a
retryable intent, MANAGE reconcile-and-retry and terminal `CLOSED_BLOCKED`.

### Stage 7 verified integration resolution (2026-07-17)

The direct prerequisites, inventory HTTP/events, stateless gateway and panel
cutover are implemented. The complete media real-dependency gate/build passed.
Inventory's real Kafka/PostgreSQL class passed 3/3, covering owner-proof
outbox ack/recovery, ordering, duplicates, gap quarantine, bounded retries,
sanitized `PROCESSING_FAILED` DLT and recovered consumption. Final totals are
inventory 36/36, architecture 26/26, gateway 34/34, Vitest 48 and Playwright
9/9, with panel typecheck/lint/build green. Final review/commit remains open.

## Stage 8 logistics auth integration prerequisite (2026-07-17)

The first Stage 8 prerequisite is complete in `auth-service` only. The
disabled `logistics-service` client can request one exact direct-call scope at
a time: `warehouse.logistics`, `asset.logistics`, `task-board.logistics`,
`maintenance.logistics` or `media.logistics`. It has no generic asset/media
scope, no USER token and no multi-receiver token.

No upstream endpoint or client invocation is implemented yet. Each receiving
service must add its own direct private endpoint and local JWT validation
before logistics can consume that scope; the gateway remains outside this path.

### Completed warehouse receiver (2026-07-17)

`GET /api/internal/warehouse/v1/warehouses/logistics/{id}/identity` is the
only implemented Stage 8 upstream path. It returns exact identity/version/
active/timezone data and no topology/location fields. A known inactive
warehouse is returned with `active=false`; logistics must treat it as a
workflow conflict and must not infer a fallback location or state.

### Completed asset receiver (2026-07-17)

`asset-service` now owns the only implemented `asset.logistics` direct-call
boundary: `/api/internal/asset/v1/logistics/**`. It returns a safe snapshot
only, keeps lease/hold identifiers opaque, derives document-line owners
locally and accepts only closed fenced return, shipment and transfer actions.
Transfer arrival moves attached non-zero cabin balances through asset-owned
`CABIN_TO_CABIN` ledger movements; logistics cannot set a raw status, location
or accounting correction. The receiver is independently verified (focused 13,
full asset suite 63). At that standalone receiver checkpoint logistics had not
yet invoked it; the completed workflow record below describes its constrained
consumption.

### Completed task-board receiver (2026-07-17)

`task-board-service` now owns the only implemented
`task-board.logistics` direct-call boundary:
`/api/internal/task-board/v1/logistics/preparation-tasks`. It registers,
reads and source-cancels a stable logistics preparation task while retaining
task-board ownership of queue/worker/route selection in `UNASSIGNED`.
Registration/status/cancellation return or accept only safe identifiers,
versions, lifecycle data and constrained scheduling values; free task content
and arbitrary cancel reasons remain unavailable.

The receiver is independently verified (focused 4; full task-board suite 104).
At that standalone checkpoint logistics had not yet invoked it; maintenance and
media were still separate prerequisites. The completed workflow record below
describes their later constrained consumption.

### Completed maintenance receiver (2026-07-17)

`maintenance-service` now owns the only implemented
`maintenance.logistics` direct-call boundary:
`/api/internal/maintenance/v1/logistics/returns/{returnId}/lines/{lineId}/shortage`.
It stores/reads a permanent, immutable source-keyed equipment-shortage
snapshot under maintenance ownership. It deliberately does not create a
repair/estimate or invoke asset, task-board or inventory behavior.

The receiver is independently verified (focused 25; full maintenance suite
131). At that standalone checkpoint logistics had not yet invoked it and media
was the remaining separate prerequisite; later Stage 8 workflow evidence
records their constrained consumption.

### Completed media receiver (2026-07-17)

`media-service` now owns the only implemented `media.logistics` direct-call
boundary: `POST /api/internal/media/v1/logistics/references/validate`. It
derives a document-line owner from supplied UUIDs and validates one to twenty
unique opaque media ID/current-generation references for one declared
logistics owner type and warehouse. It returns only opaque IDs/generations;
storage URLs, object keys, filenames, content type, status and policy do not
cross this boundary.

The receiver is independently verified (focused auth/API/contract; full Go
suite: 10 passing packages and 2 no-test packages). At that standalone
checkpoint logistics had not yet invoked it. It adds no event, owner-projection,
upload or Stage 7 dependency; later Stage 8 work uses only its opaque
validation boundary.

## Stage 8 logistics return-registration consumption (2026-07-17)

The first logistics-owned consumer invokes only the following independently
authenticated private contracts, using one exact service scope per request:

- `GET /api/internal/warehouse/v1/warehouses/logistics/{warehouseId}/identity`
  with `warehouse.logistics`;
- `GET /api/internal/asset/v1/logistics/rental-items/{rentalItemId}/snapshot`,
  `POST /api/internal/asset/v1/logistics/operation-leases`, and
  `PUT /api/internal/asset/v1/logistics/rental-items/{rentalItemId}/effects`
  with `asset.logistics`.

The consumer sends a typed `LOGISTICS_RETURN` owner reference, exact expected
rental-item version, opaque lease/fence values returned by asset, and the same
stable idempotency operation ID on every lease/effect retry. It commits its
local attempt/event/outbox work before network I/O and uses a durable relay
with bounded 1s/2s/4s retries. No HTTP result is transformed into a shared
Java model. The V2 registration slice did not consume task-board, maintenance
or media; later line-level workflow work uses only their approved contracts.

## Stage 8 logistics workflow and inbound integrations (2026-07-17)

The completed return, shipment and transfer sagas now call only their approved
private receiver contracts. Return acceptance validates opaque line media and
performs fenced asset settlement; a shortage request writes the maintenance
source; shipment uses asset holds plus stable task-board preparation records;
transfer uses origin/destination asset effects. Every remote mutation carries
a stable logistics attempt ID, and an unknown HTTP outcome becomes local
reconciliation rather than a speculative reverse call.

Kafka input is restricted to six declared source topics: rental-item,
operation-lease and equipment-hold facts from asset; board-task facts;
maintenance estimates; and media facts. The consumer validates exact producer,
aggregate, key and payload shape, stages evidence locally, deduplicates by
event ID, checkpoints each source aggregate and quarantines version gaps.
It retries transient local effects at 1s/2s/4s, writes a hash-only DLT, stops
fail-closed when PostgreSQL is unavailable and restarts only after local health
returns. Inbound facts are read-only evidence and never mutate the source
owner's state.

## Stage 9 dossier consumer decision (2026-07-18)

Stage 9 may consume only canonical V2 producer facts through a service-owned
inbox, source-aggregate checkpoint and replay projection; it cannot query a
producer database or make a source-service HTTP call to derive a cabin link.
Asset and maintenance carry direct `rentalItemId`; inventory finding facts may
associate a non-null `assetId`, and `INVENTORY_FINDING` media converges through
that association regardless of arrival order. Current logistics and task-board
facts lack a canonical cabin subject: Stage 9 validates and journals them as
sanitized `SUBJECT_NOT_PROVIDED` technical evidence and never exposes them as
cabin activity. Dossier performs no producer HTTP or database lookup and never
infers a cabin ID.
