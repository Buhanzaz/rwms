# Current Domain Logic Map

Status: Confirmed high-level ownership as of 2026-08-05. Detailed request,
status and field semantics remain in canonical contracts and owning service
tests.

## Domain Responsibilities

### Identity And Access

`auth-service` owns interactive and service identities, OAuth/OIDC clients,
roles and warehouse-access grants. Other services validate issued Bearer JWTs
and enforce their own domain authorization; they do not store passwords or mint
replacement user identities.

Evidence: [`services/auth-service/`](../../services/auth-service/),
[`auth-service.yaml`](../../contracts/openapi/auth-service.yaml).

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

Evidence: [`asset-service.yaml`](../../contracts/openapi/asset-service.yaml),
[`warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml),
[`MaintenanceFurnitureCustodyService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/MaintenanceFurnitureCustodyService.java),
[`AssetWarehouseLifecycleReconciler.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/AssetWarehouseLifecycleReconciler.java),
[`AdministrativeAssetCorrectionService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/administrative/AdministrativeAssetCorrectionService.java),
[`RentalItemHtmlImportService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/RentalItemHtmlImportService.java),
[`V28__equipment_catalog_identity_and_live_usage.sql`](../../services/asset-service/src/main/resources/db/migration/V28__equipment_catalog_identity_and_live_usage.sql),
[`V34__maintenance_furniture_custody.sql`](../../services/asset-service/src/main/resources/db/migration/V34__maintenance_furniture_custody.sql).

### Workforce And Tasks

`task-board-service` owns worker registry, qualifications, queues, assignments
and operational task state. Source domains may request work through defined
contracts while task execution remains task-board-owned.

Evidence: [`services/task-board-service/`](../../services/task-board-service/),
[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml).

### Maintenance

`maintenance-service` owns repair catalogs, estimates, repairs, acceptance,
rework and write-off decisions. Materials and works are maintenance catalog
concepts; their effects on assets, inventory or tasks cross explicit service
boundaries.

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

Evidence: [`services/maintenance-service/`](../../services/maintenance-service/),
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
[`PropertyDispositionApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionApplicationService.java),
[`FurnitureEquipmentLinkStore.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/FurnitureEquipmentLinkStore.java),
[`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
[`V38__durable_furniture_equipment_links.sql`](../../services/maintenance-service/src/main/resources/db/migration/V38__durable_furniture_equipment_links.sql).

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
if it had already been inspected. A later return requires a new inspection,
while the prior evidence remains historical.

Inspection saves evidence and a frozen proposal only. Repair, movement and
task-board effects are not created before inventory completion. Completion is
fenced by the reviewed plan version and publishes durable, idempotent intents.
Existing future work is explicitly replaced or manually merged; work already
started is preserved and overlapping works and materials are subtracted before
the remaining successor is queued. A cabin that is `AFTER_RENT` and awaiting
inspection produces a draft estimate for its remarks, not a repair task.

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
[`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
[`InventoryAssetInboxProcessor.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/eventing/InventoryAssetInboxProcessor.java),
[`InventoryScreens.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/screens/InventoryScreens.kt),
[`inventory-pages.tsx`](../../panel/src/features/inventory/inventory-pages.tsx).

### Logistics And Rental

`logistics-service` owns rental inquiries plus returns, shipments, transfers,
driver work and their orchestration. It persists its workflow/reconciliation
state and calls other owners through versioned, idempotent boundaries.

Evidence: [`services/logistics-service/`](../../services/logistics-service/),
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml).

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

Evidence:
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml),
[`RepairPlaceService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/RepairPlaceService.java),
[`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
[`DriverQueueScheduler.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverQueueScheduler.java),
[`DriverTaskRelay.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverTaskRelay.java),
[`DriverBoardService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverBoardService.java).

### Media

`media-service` owns media metadata, upload/finalize, private originals,
variants and transformations. MinIO remains private; other domains reference
media IDs and ownership contexts rather than object keys or credentials.

Evidence: [`services/media-service/`](../../services/media-service/),
[`media-service.yaml`](../../contracts/openapi/media-service.yaml).

### Read Projections And Assistant

`dossier-service` projects cross-domain cabin activity and
`analytics-service` projects KPI/dashboard facts. Neither becomes the command
owner for source aggregates. `assistant-service` owns conversation state and
tool-call history, while rental availability and inquiry decisions remain in
`logistics-service`.

Evidence: [`services/dossier-service/`](../../services/dossier-service/),
[`services/analytics-service/`](../../services/analytics-service/),
[`services/assistant-service/`](../../services/assistant-service/).

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
