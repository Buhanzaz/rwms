# Current Domain Logic Map

Status: Confirmed high-level ownership as of 2026-08-04. Detailed request,
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

Evidence: [`services/warehouse-service/`](../../services/warehouse-service/),
[`warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml).

### Cabins And Equipment

`asset-service` owns cabins/rental items, their status, equipment/content,
balances, holds and leases. A logistics or maintenance workflow requests or
records effects through contracts; it does not mutate asset tables directly.

Evidence: [`services/asset-service/`](../../services/asset-service/),
[`asset-service.yaml`](../../contracts/openapi/asset-service.yaml).

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

Evidence: [`services/maintenance-service/`](../../services/maintenance-service/),
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml).

### Inventory

`inventory-service` owns inventory sessions, findings, completion and
publication intent/state. Publication into another domain uses explicit,
idempotent integration rather than shared database mutation.

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
