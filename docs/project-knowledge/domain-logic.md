# Current Domain Logic Map

Status: Confirmed high-level ownership as of 2026-08-07. Detailed request,
status and field semantics remain in canonical contracts and owning service
tests.

## Domain Responsibilities

### Identity And Access

`auth-service` owns interactive and service identities, OAuth/OIDC clients,
roles and warehouse-access grants. Other services validate issued Bearer JWTs
and enforce their own domain authorization; they do not store passwords or mint
replacement user identities.

Machine clients are provisioned from versioned, exact-scope registrations.
Adding a required downstream capability advances that client's revision so the
stored registration and previously issued tokens cannot silently retain an
older permission set.

The panel keeps its one-time OIDC state and PKCE transaction in browser
`sessionStorage`. A duplicate delivery or remount of the same callback must
share the first callback exchange rather than attempting to consume the state a
second time. If that state was already consumed, the panel may resume only an
already stored, renewable `USER` session created by the first exchange; a
genuinely absent state without that session fails closed. This preserves the
same-origin `/auth/callback` contract and does not move tokens or PKCE data to
long-lived browser storage.

Evidence: [`services/auth-service/`](../../services/auth-service/),
[`auth-service.yaml`](../../contracts/openapi/auth-service.yaml),
[`auth-provider.tsx`](../../panel/src/features/auth/auth-provider.tsx).

### Warehouses

`warehouse-service` owns canonical warehouse identity, metadata and timezone.
Other services store warehouse IDs as opaque references and authorize access;
they do not reproduce the warehouse registry in shared tables.

Warehouse UUID is the stable external reference. Display names are unique
after trim, whitespace folding and case normalization. Inactive warehouses
remain readable for historical references. A warehouse that has never recorded
an operation may correct its timezone immediately; after first use, timezone
changes are effective-dated and do not rewrite earlier facts or reports.
Deactivation proceeds through `DRAINING` and exact-version confirmations from
operation owners before `INACTIVE`. The public warehouse directory remains a
product-approved global authenticated read rather than a grant-filtered list.

Evidence: [`services/warehouse-service/`](../../services/warehouse-service/),
[`warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml),
[`V3__add_normalized_warehouse_name.sql`](../../services/warehouse-service/src/main/resources/db/migration/V3__add_normalized_warehouse_name.sql),
[`V4__warehouse_effective_time_zones.sql`](../../services/warehouse-service/src/main/resources/db/migration/V4__warehouse_effective_time_zones.sql),
[`V5__warehouse_lifecycle.sql`](../../services/warehouse-service/src/main/resources/db/migration/V5__warehouse_lifecycle.sql).

### Cabins And Equipment

`asset-service` owns cabins/rental items, their status, equipment/content,
balances, holds and leases. A logistics or maintenance workflow requests or
records effects through contracts; it does not mutate asset tables directly.

Furniture selected from a cabin by either a maintenance estimate or a direct
repair enters asset-owned, append-only pending-return custody. The selection
is not a loss or write-off: it can return to warehouse STOCK, or become a
terminal balance only when the separately approved maintenance property
decision is applied. A custody return is an incoming warehouse operation;
retries reuse permanent custody evidence rather than creating another balance
effect.

Asset uses warehouse-owned directional admission for physical custody: incoming
operations require an `ACTIVE` warehouse, while outgoing operations may finish
while it is `DRAINING`. It reconciles a durable operation mark from immutable
asset events and confirms asset readiness for a draining warehouse only after
its non-terminal cabins, balances and local workflows have drained. Historical
timezone context is read from warehouse-service as of the immutable operation
timestamp, never inferred from the current warehouse timezone.

`WRITTEN_OFF` and `LOST` are terminal for ordinary commands and cannot be
sources for a normal furniture transfer. The only direct manual cabin statuses
are `SALE`, `USED_SALE`, `FREE`, `WAREHOUSE` and `OWN_NEEDS`; workflow statuses
come only from their owning fenced effects. Physical inter-warehouse changes
must come from logistics. A no-movement data correction is a distinct
administrator-only, reason/evidence-bearing command that atomically corrects a
cabin and its contents and preserves immutable audit/events.

Equipment display names are normalized-unique while UUID remains the stable
reference. The retained HTML import is a constrained legacy
creation path: it does not merge an existing cabin, does not synthesize a
workflow/terminal status and records initial furniture as immutable receipts
instead of replacing live quantities.

The private inventory source-asset command accepts exactly one cabin-composition
representation: canonical catalogue UUIDs or the supported manager client's
display-name format. Asset-service alone resolves names to active catalogue
IDs before registering the permanent inventory source or claiming the cabin
number. Mixed, incomplete, unknown or ambiguous input persists nothing. The
permanent fingerprint uses resolved UUIDs, so equivalent name and UUID retries
return the same asset instead of creating a duplicate.

Evidence: [`asset-service.yaml`](../../contracts/openapi/asset-service.yaml),
[`warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml),
[`MaintenanceFurnitureCustodyService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/MaintenanceFurnitureCustodyService.java),
[`AssetWarehouseLifecycleReconciler.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/AssetWarehouseLifecycleReconciler.java),
[`AdministrativeAssetCorrectionService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/administrative/AdministrativeAssetCorrectionService.java),
[`InventoryAssetService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/InventoryAssetService.java),
[`CabinCompositionService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/CabinCompositionService.java),
[`RentalItemHtmlImportService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/RentalItemHtmlImportService.java),
[`V28__equipment_catalog_identity_and_live_usage.sql`](../../services/asset-service/src/main/resources/db/migration/V28__equipment_catalog_identity_and_live_usage.sql),
[`V34__maintenance_furniture_custody.sql`](../../services/asset-service/src/main/resources/db/migration/V34__maintenance_furniture_custody.sql).

### Workforce And Tasks

`task-board-service` owns worker registry, qualifications, queues, assignments
and operational task state. Source domains may request work through defined
contracts while task execution remains task-board-owned.

A logistics driver task has one task-board-owned visibility audience. An
unassigned task is dispatcher-only; an assigned task is visible and executable
only for its active, same-warehouse, primary-qualified driver; an unclaimed
warehouse-shared task is visible to every such driver and becomes assignee-only
after take. A shared task may name a responsible driver without narrowing
visibility. Task-board resolves the authoritative worker name and applies the
same policy to feed, detail, take, execution ordering and invalidation.

Evidence: [`services/task-board-service/`](../../services/task-board-service/),
[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml),
[`DriverTaskAudienceService.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/DriverTaskAudienceService.java).

### Maintenance

`maintenance-service` owns repair catalogs, estimates, repairs, acceptance,
rework and write-off decisions. Materials and works are maintenance catalog
concepts; their effects on assets, inventory or tasks cross explicit service
boundaries.

A return inspection starts exactly one maintenance-owned `DRAFT` estimate for
each submitted return line; a multi-cabin return never has a shared estimate.
Logistics freezes only the exact per-line inspection photos and the
`returnId:lineId` source, while furniture/loss selection happens later inside
that individual estimate. For a cabin with recorded contents, selected
furniture follows normal asset-owned pending-return custody. If the canonical
contents are empty, estimate completion requires explicit confirmation. The
confirmed legacy selection creates maintenance-owned `UNACCOUNTED` loss
decisions only after the repair completes; they remain separately approvable
and become effective without an asset-service custody or warehouse-balance
effect. If contents appear before queuing the repair, the unaccounted path
fails closed rather than bypassing normal accounting.

Cabin and additional-equipment write-off/loss use one property-decision model.
One list row is one decision/root asset; a repair chain remains visible in the
detail. A warehouse manager or administrator may create a mandatory-reason
proposal, but only a global administrator makes the final decision. A non-empty
cabin must choose between moving exact positive quantities to warehouse and
disposing the remainder, or disposing all contents with the cabin. An empty
cabin has no contents choice. Asset mutation and logistics movement are
asynchronous, durable effects whose pending/quarantined state remains visible
and can be recovered only by a version-fenced administrator review.

Automatic furniture-catalog linking first persists a stable node-UUID intent,
then calls asset-service outside the catalog transaction, and finally confirms
the exact result. Lost responses and local commit races are replay-safe; rename
or remap conflicts require audited administrator retry or abandonment and never
silently delete the remote item.

All maintenance-owned cross-service commands use immutable local plans and
short prepare/remote/finalize boundaries. Remote calls reject an ambient local
transaction. Transfer arrival receives the exact post-arrival asset version
from logistics, replays task/lease/status effects with stable derived keys and
original expected versions after a lost local commit, and revalidates the full
transfer-line repair chain before finalization.

The repair catalog is a directed navigation graph, not a one-shot picker.
After an estimator adds a work, material or location, both clients continue
from that selected terminal node only when it has an active, usable outgoing
catalog step. Without such a step they retain the current branch. A reverse
edge is never followed automatically, so a cyclic catalog cannot reset the
user to the root or grow navigation indefinitely.

Evidence: [`services/maintenance-service/`](../../services/maintenance-service/),
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
[`PropertyDispositionApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionApplicationService.java),
[`FurnitureEquipmentLinkStore.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/FurnitureEquipmentLinkStore.java),
[`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
[`V38__durable_furniture_equipment_links.sql`](../../services/maintenance-service/src/main/resources/db/migration/V38__durable_furniture_equipment_links.sql),
[`repair-estimate-catalog-picker.tsx`](../../panel/src/features/repair-estimates/repair-estimate-catalog-picker.tsx),
[`MaintenanceScreen.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/screens/MaintenanceScreen.kt).

### Inventory

`inventory-service` owns inventory sessions, findings, completion and
publication intent/state. Publication into another domain uses explicit,
idempotent integration rather than shared database mutation.

An equipment shortage found at inventory completion is an idempotent
maintenance `LOSS` proposal with the inventory session/finding as immutable
source evidence. Final approval and the terminal asset effect remain
maintenance/asset responsibilities; inventory does not write their tables.

The panel owns whole-session operations: opening an inventory, reviewing the
ordered final plan, resolving reconciliation choices and completing the exact
reviewed version. The manager Android app is field-only: it reopens or creates
one finding, records passport facts, photos, furniture, works/materials and the
proposed repair priority/movement choice. It does not start or complete the
session and does not publish operational tasks.

Membership is live while a session is active. An arrival at the inventoried
warehouse becomes an expected uninspected item; a departure is excluded even
if it had already been inspected. A terminal asset fact for either
`WRITTEN_OFF` or `LOST` is a departure, so the cabin leaves the active
population while prior evidence remains historical. A later return requires a
new inspection.

Inspection saves evidence and a frozen proposal only. Repair, movement and
task-board effects are not created before inventory completion. Completion is
fenced by the reviewed plan version and publishes durable, idempotent intents.
Existing future work is explicitly replaced or manually merged; work already
started is preserved and overlapping works and materials are subtracted before
the remaining successor is queued. A cabin that is `AFTER_RENT` and awaiting
inspection produces a draft estimate for its remarks, not a repair task.

Inspection evidence keeps general photographs separate from photographs of a
specific work line: work-line references belong to the frozen plan and are not
copied into the finding's cover-photo collection. Passport facts and furniture
are inspection observations, not direct panel mutations of asset-service. The
first save explicitly records either that furniture is absent or the observed
catalog quantities; only the post-cabin furniture reconciliation applies those
facts to the inventory result.

When an Android manager reopens an already saved finding, they explicitly
choose whether to supplement it or replace it. Supplement starts from the
previous inspection's passport, furniture observation, photos, comment and
frozen plan. Replacement starts a new active revision: it does not carry prior
comment, photos, work/material lines or furniture observation, requires a new
photo and furniture answer, and uses the current registry passport and contents
only as neutral input values. Before a repeat mode becomes editable, Android
requires a fresh inventory read; a cached/offline snapshot is not allowed to
produce a version-fenced repeat command. A first, not-yet-saved inspection
retains its normal offline-capable flow. Older revisions stay historical rather
than being destructively deleted.

For a supplement, Android resolves every retained logical media ID through the
media owner's current `READY` projection and replaces only its generation before
the final save. The same rebase is persisted immediately before a durable
background retry. Missing or non-ready retained media fails the operation
explicitly; it is never silently omitted from the inspection evidence.

Each `WORK_STAGED` finding revision has its own immutable maintenance source
key: `inventoryId:findingId:sourceRevision`. A supplement therefore freezes a
new version of the proposal while retaining the earlier snapshot as historical
evidence; it never overwrites that snapshot or creates a repair/task before
session completion. Final publication supplies the exact selected revision, so
maintenance binds only that historical source to the repair.

Freezing that source is an idempotent maintenance command. Inventory may repeat
it once, with the identical request and idempotency key, only after a transport
failure or `502/503/504`; validation, conflict, authentication, other server
errors and malformed responses are not retried.

Inventory command replay stores only successful responses. If the owning
command rolls back, its exact idempotency lease is retired in a separate short
transaction so the same request can be retried immediately. A concurrent live
owner still blocks duplicates, and a failed attempt is never replayed as a
success.

The operational planning date is never earlier than both the session business
date and the current date in the warehouse timezone. Automatic movement and
repair dates start from that effective date. A manual past date is rejected,
and a final-plan draft that became stale overnight must be prepared and
reviewed again before preview or completion.

A quarantined inbound movement is recovered without deleting or recreating its
repair. A warehouse `MANAGE` command may resume only the exact stable
`LOGISTICS / CREATE_DRIVER_TASK` intent when the repair is still ordinary,
queued and not started, and logistics proves that the corresponding driver task
is absent. The command preserves priority, works and materials, changes only
the inbound planning mode/date and reuses the original stable integration key.
If logistics reports any existing or uncertain task, recovery fails closed.

Evidence: [`services/inventory-service/`](../../services/inventory-service/),
[`inventory-service.yaml`](../../contracts/openapi/inventory-service.yaml),
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
[`InventoryApplicationService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryApplicationService.java),
[`InventoryIdempotencyService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryIdempotencyService.java),
[`InventoryIdempotencyRecoveryIntegrationTest.java`](../../services/inventory-service/src/test/java/dev/buhanzaz/rwms/inventory/service/InventoryIdempotencyRecoveryIntegrationTest.java),
[`InventoryReadProjectionIntegrationTest.java`](../../services/inventory-service/src/test/java/dev/buhanzaz/rwms/inventory/InventoryReadProjectionIntegrationTest.java),
[`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
[`InventoryMaintenanceService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryMaintenanceService.java),
[`V40__version_inventory_repair_sources.sql`](../../services/maintenance-service/src/main/resources/db/migration/V40__version_inventory_repair_sources.sql),
[`InventoryAssetInboxProcessor.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/eventing/InventoryAssetInboxProcessor.java),
[`InventoryScreens.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/screens/InventoryScreens.kt),
[`ManagerViewModel.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/ManagerViewModel.kt),
[`InventoryMediaRebasePolicy.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/uploads/InventoryMediaRebasePolicy.kt),
[`BackgroundUploadWorker.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadWorker.kt),
[`InventoryEquipmentPolicy.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/InventoryEquipmentPolicy.kt),
[`InventoryReinspectionPolicy.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/InventoryReinspectionPolicy.kt),
[`inventory-pages.tsx`](../../panel/src/features/inventory/inventory-pages.tsx),
[`inventory-view-mapper.ts`](../../panel/src/features/inventory/domain/inventory-view-mapper.ts),
[`inventory-inspection-details.tsx`](../../panel/src/features/inventory/inventory-inspection-details.tsx).

### Logistics And Rental

`logistics-service` owns rental inquiries plus returns, shipments, transfers,
driver work and their orchestration. It persists its workflow/reconciliation
state and calls other owners through versioned, idempotent boundaries.

Rental counterparties are logistics domain records, not OAuth clients. Every
new client has a normalized required phone and an authenticated responsible
manager; an IP or legal entity also requires a contact person. Reads are
manager/warehouse scoped, and an inaccessible duplicate is reported as a
generic conflict without disclosing its identity. Rental orders carry the
delivery address, optional coordinate pair, contact phone, comment and a
unique bounded set of acceptable delivery dates. Saving requires all delivery
facts except the comment, and a planned shipment date must belong to the
accepted set.

The assistant-facing cabin boundary remains logistics-owned. Facets expose
current characteristics and exact type-to-dimension relations; catalog lookup
is read-only. A search group cannot acquire holds until it has an exact type
and finish. Selection replacement stores a durable exact-byte command receipt
before calling asset-service. Removing items sends the complete retained set,
which releases removed holds immediately and gives retained holds a new
settings-derived expiry; an empty set releases the whole selection.

A first warehouse-bound create always requires warehouse-service admission and
fails before owner/outbox persistence when dependencies are disabled,
unavailable or the warehouse lifecycle rejects the direction. New accepted
operations persist the exact admitted direction and lifecycle version with
their permanent operated-boundary marks. Only a live owner identity, live
idempotency receipt and complete exact mark vector can form a dependency-free
replay candidate; the document/equipment/driver owner remains the checksum
authority. Legacy, incomplete, expired or mismatched evidence is never guessed
and instead requires remote re-admission or returns an explicit dependency
failure.

Background external effects are claimed per owner in bounded stable pages.
The persisted claim token, monotonic fence, expiry, row version and request
hash form one capability; both preflight and final mutation revalidate it.
Remote HTTP runs only after the claim transaction closes. Database time owns
due/expiry/defer decisions, and owner permits plus a bounded worker executor
prevent one slow workflow family from starving the others. A stale worker or
duplicate completion cannot mutate the attempt after lease expiry/reclaim.

Evidence: [`services/logistics-service/`](../../services/logistics-service/),
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml),
[`LogisticsWarehouseLifecycle.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsWarehouseLifecycle.java),
[`LogisticsWarehouseLifecycleStore.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsWarehouseLifecycleStore.java),
[`V39__warehouse_admission_evidence.sql`](../../services/logistics-service/src/main/resources/db/migration/V39__warehouse_admission_evidence.sql),
[`LogisticsExternalAttemptClaimService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsExternalAttemptClaimService.java),
[`V40__bounded_logistics_external_attempt_claims.sql`](../../services/logistics-service/src/main/resources/db/migration/V40__bounded_logistics_external_attempt_claims.sql),
[`OrderClientService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/OrderClientService.java),
[`RentalInquiryCabinSelectionStore.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/RentalInquiryCabinSelectionStore.java),
and
[`V42__clients_order_delivery_and_acceptable_dates.sql`](../../services/logistics-service/src/main/resources/db/migration/V42__clients_order_delivery_and_acceptable_dates.sql).

### Repair Places And Driver Queue

`maintenance-service` owns repair-place allocation and repair-stage progress.
`RESERVED` records a selected delivery and is not physical occupation;
`OCCUPIED` and `READY_TO_RELEASE` are cabins physically at a repair place.
Task-board completion remains the fact that finishes a repair stage. Once all
stages finish, maintenance makes a movement-backed allocation ready to release;
the logistics scheduler creates/reconciles the removal movement on its next
pass.

The public driver board is a logistics read model. It separately exposes
physical occupation (`occupiedRepairPlaceCount`) and capacity-aware scheduled
use (`usedRepairPlaceCount`). Its `repairPlaces` cards contain only cabins in
`OCCUPIED` or `READY_TO_RELEASE`; a `RESERVED` delivery remains in the driver
queue until the cabin physically reaches the repair zone. The browser cannot
manually move an ordinary scheduled task into Current; only
`CAPITAL_TO_PRODUCTION` may be inserted there manually. A completed repair's
removal movement is inserted before ordinary Current work but after all leading
pinned cards.

Each newly created shipment persists one logistics-owned driver intent for the
whole document, with immutable document-line/cabin members and the client
snapshot. A warehouse-local logistics setting caps the selected cabin count at
1–100 (the compatibility default is one); a request above the current cap is
rejected before the document or intent is created. The internal task-board
priority is fixed and is not a logistics-board decision. Completion evidence is
applied idempotently to every grouped cabin. Existing line-derived shipment
intents are retained as legacy records; return and transfer continue to persist
one intent per document line. Shipment and return use an assigned or unassigned
audience according to the opaque worker ID stored on the document. Transfer is
always warehouse-shared and stores no responsible-driver identity or display
snapshot. The panel presents shipment/return in dated columns with collapsible
driver sections, and keeps shared warehouse movements on a separate board.
Shipment/return drag-and-drop is version-fenced and can only reorder inside the
same driver/date/lane queue; it cannot reassign the audience.

Evidence:
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml),
[`RepairPlaceService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/RepairPlaceService.java),
[`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
[`DriverQueueScheduler.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverQueueScheduler.java),
[`DriverTaskRelay.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverTaskRelay.java),
[`DriverBoardService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverBoardService.java),
[`DocumentDriverTaskPlanner.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DocumentDriverTaskPlanner.java),
[`ShipmentTaskSettingsService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/settings/service/ShipmentTaskSettingsService.java),
and
[`V46__shipment_task_grouping.sql`](../../services/logistics-service/src/main/resources/db/migration/V46__shipment_task_grouping.sql).

### Media

`media-service` owns media metadata, upload/finalize, private originals,
variants and transformations. MinIO remains private; other domains reference
media IDs and ownership contexts rather than object keys or credentials.

Phone-side capture and gallery intake normalize still-image pixels to their
upright orientation before upload. Media-service neither auto-rotates EXIF
images nor accepts a mutable rotation command for new media. Its historical
`rotationDegrees` read field is retained for compatible display of old assets,
not as current media state to mutate.

An exact create-upload replay normally returns the existing open session. If
the session expired before any content was finalized, media-service issues a
new session for the same logical media ID and immutable object identity. It
does not create a duplicate asset, event or idempotency record and never
reopens or overwrites completed content.

Processing has one durable four-attempt cycle with fenced leases and bounded
1s/2s/4s dependency recovery. A validation failure is terminal immediately;
the fourth dependency failure is terminal, and a crashed fourth attempt is
reclaimed only to record `PROCESSING_ATTEMPT_EXHAUSTED` without a fifth
processor invocation. Database success remains authoritative across a failed
Kafka commit, so redelivery produces neither a second variant set nor a second
`READY` fact. Terminal/review records are immutable audit evidence and do not
themselves authorize or execute another cycle.

Evidence: [`services/media-service/`](../../services/media-service/),
[`media-service.yaml`](../../contracts/openapi/media-service.yaml),
[`ManagerCameraScreen.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/components/ManagerCameraScreen.kt),
[`ManagerPhotos.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/components/ManagerPhotos.kt),
[`InventoryMediaRebasePolicy.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/uploads/InventoryMediaRebasePolicy.kt),
[`BackgroundUploadWorker.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/uploads/BackgroundUploadWorker.kt),
[`consumer.go`](../../services/media-service/internal/worker/consumer.go),
[`worker.go`](../../services/media-service/internal/persistence/worker.go),
[`V11__bounded_media_processing_recovery.sql`](../../services/media-service/db/migration/V11__bounded_media_processing_recovery.sql),
[`upload_session_recovery_integration_test.go`](../../services/media-service/internal/persistence/upload_session_recovery_integration_test.go).

### Read Projections And Assistant

`dossier-service` projects cross-domain cabin activity and
`analytics-service` projects KPI/dashboard facts. Neither becomes the command
owner for source aggregates. `assistant-service` owns conversation state and
tool-call history, while rental availability and inquiry decisions remain in
`logistics-service`.

Assistant clarification branches are durable and independent. A question is
superseded only by a newer question for the same branch, so ОСБ and ЛДСП
questions may be answered in either order and survive reload. The server, not
the LLM or browser, validates exact facet relationships before searching: a
single compatible dimension may be selected automatically, while multiple
dimensions produce buttons. Approximate six-metre input resolves only through
the current 6x2.4 relation; module and security-post choices are never guessed.
Read-only reference lookup by number or text reports current types, finishes,
dimensions, characteristics and linoleum without creating or renewing holds.

Dossier `PARTIAL` means that the requested cabin's active generation has
hidden or unresolved proven coverage. Globally unlinked facts, raw validation
failures and legacy DLT rows without exact cabin/generation proof stay in
operational recovery state but do not make every cabin partial. Resolving or
rebuilding coverage never deletes its audit evidence, and relay delivery state
does not substitute for projection completeness.

Analytics recovery metrics are observations, not projection transitions.
Read-only aggregate queries report active and terminal gaps, oldest gap age,
maximum retained gap attempt, pending/retry sanitized-DLT backlog and its
oldest age, and terminal DLT count. Scraping cannot resolve a gap, advance a
checkpoint, replay an event or mark DLT work complete. Empty state is zero,
future age is clamped to zero, and a failed database observation is `NaN`.

Dossier uses the same observational rule for ten fixed gauges. Operational
counts span retained generations for unresolved unlinked facts and exact DLT
coverage, while activity-outbox and sanitized-DLT gauges split pending/retry
backlog from terminal rows and expose oldest backlog ages. These broader
operational counts never participate in cabin-scoped `PARTIAL` calculation,
generation activation, replay or publication transitions.

Assistant exposes two observational gauges for durable tool calls still in
`STARTED`: current count and oldest age. `COMPLETED` and `FAILED` remain
terminal history and are not classified as recovery backlog. These reads do
not retry a tool, create a logistics command or resolve the separately recorded
durable coordination decision.

Evidence: [`services/dossier-service/`](../../services/dossier-service/),
[`services/analytics-service/`](../../services/analytics-service/),
[`services/assistant-service/`](../../services/assistant-service/),
[`DossierQueryService.java`](../../services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/service/DossierQueryService.java),
[`DossierSanitizedDeadLetter.java`](../../services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/domain/DossierSanitizedDeadLetter.java),
[`AnalyticsRecoveryMetrics.java`](../../services/analytics-service/src/main/java/dev/buhanzaz/rwms/analytics/eventing/AnalyticsRecoveryMetrics.java),
[`DossierRecoveryMetrics.java`](../../services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/eventing/DossierRecoveryMetrics.java),
and
[`AssistantRecoveryMetrics.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/eventing/AssistantRecoveryMetrics.java).

## Cross-Domain Invariants

- One aggregate and each of its lifecycle statuses have one command owner.
- Other services hold opaque IDs or contract-defined immutable snapshots, not
  shared mutable entities.
- A mutable command is concurrency-fenced and a retried effect is idempotent.
- A committed fact is published through outbox and consumed with inbox
  deduplication when the flow uses Kafka.
- A consumer detects unsupported versions and ordering gaps; it does not
  silently fabricate missing state.
- A multi-service workflow persists its saga/reconciliation state in its
  initiating owner.
- UI state, browser storage and gateway routing never become business sources
  of truth.
- Historical mocks, legacy databases and migration notes do not define current
  behavior unless a user explicitly requests a reviewed import.

## Adding Or Changing A Rule

Record only durable behavior that is implemented and tested. Link the exact
contract, service package, Flyway migration or focused test that proves it.
If a requested rule changes owner, status meaning, identity, money, time or
destructive behavior and current sources do not resolve it, add the question to
[`open-questions.md`](open-questions.md) and ask the user before implementation.
