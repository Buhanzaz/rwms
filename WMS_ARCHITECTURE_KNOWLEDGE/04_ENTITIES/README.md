# 04 Entities

## Legacy Accessory To Estimate Furniture Material Link (2026-07-10)

- `AccessoryItem.furnitureMaterial` is an optional many-to-one link to `RepairEstimateCatalogNode` and accepts only `MATERIAL` nodes.
- Database uniqueness on `FURNITURE_MATERIAL_ID` means one estimate catalog material may be mapped to at most one accessory item.
- This link is not equivalent to the target equipment `furnitureCategory` flag: it identifies one concrete estimate catalog material whose quantity drives one accessory stock/assignment synchronization.

Evidence:

- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/AccessoryItem.java`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/liquibase/changelog/2026/07/02-120000-accessory-item-furniture-material.xml`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/liquibase/changelog/2026/07/03-143000-accessory-item-furniture-material-unique.xml`

## Base Classes

- `UuidEntity`: mapped superclass with UUID id.
- `CreateAuditEntity`: adds `createdBy`, `createdDate`.
- `CreateUpdateAuditEntity`: adds `lastModifiedBy`, `lastModifiedDate`.
- `FullAuditEntity`: adds optimistic `version`.
- `WarehouseDictionaryEntity`: mapped superclass for simple active/sorted dictionaries with `name` and `sortOrder`.
- `User`: does not extend the audit hierarchy; maps to `USER_` and implements Jmix user details behavior.

## Enum Storage Warning

Two enum storage styles exist:

- Real `@Enumerated(EnumType.STRING)` fields, for example repair process status and rental item event type.
- String-backed enum IDs with getters/setters using `fromId`, for example queue status, board task status, reservation status/type/client type.

`RentalItem.status`, `RentalItemEvent.statusBefore`, and `RentalItemEvent.statusAfter` are plain strings. Treat validation/enforcement as `UNKNOWN` unless service code proves a constraint.

## Entity Catalog

### Security And User Preferences

| Entity | Table | Purpose | Fields | Relations | Lifecycle and business meaning |
|---|---|---|---|---|---|
| `User` | `USER_` | Authenticated UI user. | `id`, `version`, `username`, `password`, `firstName`, `lastName`, `email`, `timeZoneId`, `active`, `globalRole`. | Jmix authorities; referenced by warehouse access, reservations, grid settings. | Seeded admin exists. Business permissions also use `globalRole`. |
| `UserWarehouseAccess` | `USER_WAREHOUSE_ACCESS` | Per-user warehouse access. | `accessLevel`, `active`, `comment`. | `user`, `warehouse`. | Used by `WarehouseAccessService` for warehouse-scoped UI/service queries. |
| `UserGridColumnSettings` | `USER_GRID_COLUMN_SETTINGS` | Per-user grid column preferences. | `gridCode`, `columnKey`, `visible`, `sortOrder`, `width`. | `user`. | Used by rental item grid column settings. |

### Warehouse And Stock

| Entity | Table | Purpose | Fields | Relations | Lifecycle and business meaning |
|---|---|---|---|---|---|
| `Warehouse` | `WAREHOUSE` | Physical/business warehouse. | `name`, `code`, `city`, `address`, `timeZone`, `active`, `sortOrder`, `comment`. | Referenced by almost all operational entities. | Main tenant-like scoping unit for users, inventory, queues, reservations, repair. |
| `WarehouseSegment` | `WAREHOUSE_SEGMENT` | Legacy/general stock segment dictionary. | Inherits dictionary fields. | Referenced by `StockItem`. | Segment workflow beyond simple classification is `UNKNOWN`. |
| `StockItem` | `STOCK_ITEM` | Quantity stock item not modeled as numbered rental asset. | `name`, `totalQuantity`, `unit`, `active`, `comment`. | `warehouse`, `segment`, `category`. | Used by reservation availability and warehouse AI search. No picking/shipping process found. |

### Rental Catalog And Numbered Assets

| Entity | Table | Purpose | Fields | Relations | Lifecycle and business meaning |
|---|---|---|---|---|---|
| `RentalCategory` | `RENTAL_CATEGORY` | Top-level rental classifier. | `name`, `code`, `active`, `sortOrder`. | Parent of subcategories; linked by items and classifier attributes. | Seeded with rental import data. |
| `RentalSubcategory` | `RENTAL_SUBCATEGORY` | Second classifier level. | `name`, `code`, `active`, `sortOrder`. | `category`; parent of types. | Filters UI and mobile passport options. |
| `RentalType` | `RENTAL_TYPE` | Concrete rental type. | `name`, `code`, `active`, `sortOrder`. | `subcategory`. | Display formatter currently returns base type name. |
| `RentalItem` | `RENTAL_ITEM` | Numbered rental asset, often a cabin/equipment object. | `number`, `status`, `comment`, audit fields. | `warehouse`, `category`, `subcategory`, `type`, `condition`. | Number normalized to uppercase; unique by number. Status changes are tracked by events. |
| `RentalItemCondition` | `RENTAL_ITEM_CONDITION` | Condition dictionary for rental item. | `name`, `code`, `active`, `sortOrder`, `comment`. | Referenced by `RentalItem`. | Mobile new inventory tries to use condition `NEW`/new names if configured. |
| `RentalAttributeDefinition` | `RENTAL_ATTRIBUTE_DEFINITION` | Dynamic passport attribute definition. | `name`, `code`, `dataType`, `active`, `sortOrder`. | Options and values. | Used in mobile passport and rental item details. |
| `RentalAttributeOption` | `RENTAL_ATTRIBUTE_OPTION` | Option for enum-like attribute. | `name`, `code`, `active`, `sortOrder`. | `attributeDefinition`. | Used when attribute data type is enum-like. |
| `RentalAttributeValue` | `RENTAL_ATTRIBUTE_VALUE` | Value of dynamic passport attribute for one rental item. | `valueString`, `valueText`, `valueNumber`, `valueBoolean`. | `rentalItem`, `attributeDefinition`, optional `valueOption`. | Unique by rental item and attribute definition. |
| `RentalClassifierAttribute` | `RENTAL_CLASSIFIER_ATTRIBUTE` | Attribute availability by classifier. | `active`, `sortOrder`. | `attributeDefinition`, optional category/subcategory/type. | Drives dynamic fields for item category/type. |
| `RentalClassifierAttributeCategoryLink` | `RENTAL_CLASSIFIER_ATTRIBUTE_CATEGORY_LINK` | Additional category link for classifier attribute. | `active`, `sortOrder`. | `rentalClassifierAttribute`, `category`. | Used by classifier attribute UI/service logic. |
| `RentalTag` | `RENTAL_TAG` | Item tag dictionary. | `name`, `code`, `active`, `sortOrder`. | Linked to items through `RentalItemTag`. | Used in item details/search. |
| `RentalItemTag` | `RENTAL_ITEM_TAG` | Many-to-many item/tag link. | Audit fields. | `rentalItem`, `tag`. | Unique item/tag relation. |

### Accessories

| Entity | Table | Purpose | Fields | Relations | Lifecycle and business meaning |
|---|---|---|---|---|---|
| `AccessoryCategory` | `ACCESSORY_CATEGORY` | Accessory top-level dictionary. | `name`, `code`, `active`, `sortOrder`, `comment`. | Parent of accessory subcategories/items. | Used for quantity-only accessory catalog. |
| `AccessorySubcategory` | `ACCESSORY_SUBCATEGORY` | Accessory subcategory. | `name`, `code`, `active`, `sortOrder`, `comment`. | `category`. | Filters accessory items. |
| `AccessoryItem` | `ACCESSORY_ITEM` | Quantity accessory/material item. | `name`, `code`, `active`, `sortOrder`, `comment`. | `category`, `subcategory`, optional `furnitureMaterial`. | Can map furniture material from repair catalog. |
| `AccessoryStockBalance` | `ACCESSORY_STOCK_BALANCE` | Accessory stock counters per warehouse. | `quantityTotal`, `quantityAvailable`, `quantityReserved`, `quantityInRent`, `quantityBroken`, `quantityWrittenOff`, `comment`. | `warehouse`, `accessoryItem`. | Formula is explicitly deferred in source comment; exact invariant is `UNKNOWN`. |
| `RentalItemAccessory` | `RENTAL_ITEM_ACCESSORY` | Accessories assigned to a rental item. | `quantity`, audit fields. | `rentalItem`, `accessoryItem`. | Mobile passport can keep, clear, or replace accessories. |
| `RentalItemEventAccessory` | `RENTAL_ITEM_EVENT_ACCESSORY` | Accessory snapshot line for event history. | `quantity`, audit fields. | `event`, `accessoryItem`. | Created by accessory snapshot events. |

### Reservations

| Entity | Table | Purpose | Fields | Relations | Lifecycle and business meaning |
|---|---|---|---|---|---|
| `Reservation` | `RESERVATION` | Temporary/client reservation header. | `reservationNumber`, `reservationType`, `status`, `clientType`, client/person/company fields, due/confirm/cancel timestamps, `cancelReason`, `comment`. | `warehouse`, optional `rentalItem`, `responsibleRentalManager`, lines. | Status flow includes temporary, waiting payment, confirmed, expired, cancelled, completed. |
| `ReservationLine` | `RESERVATION_LINE` | Reserved rental item or stock item line. | `status`, `quantity`, `comment`. | `reservation`, optional `rentalItem`, optional `stockItem`. | Used by availability and release/cancel flows. |
| `ReservationAccessory` | `RESERVATION_ACCESSORY` | Accessory reservation attached to reservation or line. | `quantity`, `status`, `comment`. | `reservation`, optional `reservationItem`, `accessoryItem`. | Updates accessory stock counters through services. |
| `ReservationSearchSettings` | `RESERVATION_SEARCH_SETTINGS` | UI refresh/search settings. | `code`, `refreshSeconds`, `active`, audit fields. | None. | Singleton-like settings loaded or created by service. |

### Workers, Queues, Tasks

| Entity | Table | Purpose | Fields | Relations | Lifecycle and business meaning |
|---|---|---|---|---|---|
| `WorkerClass` | `WORKER_CLASS` | Worker skill/class dictionary. | `code`, `name`, `description`, `comment`, `active`, `sortOrder`. | Worker assignments, groups, queue bindings. | Used to route tasks and group workers. |
| `Worker` | `WORKER` | Person/worker performing tasks. | `firstName`, `lastName`, `middleName`, `displayName`, `appLogin`, `appPassword`, `active`, `comment`. | `warehouse`; assignments and group memberships. | Display name is normalized from name parts when present. Mobile worker auth workflow is `UNKNOWN`. |
| `WorkerClassAssignment` | `WORKER_CLASS_ASSIGNMENT` | Worker to class relation. | `active`, `comment`. | `worker`, `workerClass`. | Validates duplicate/cross-scope rules in service. |
| `WorkerGroup` | `WORKER_GROUP` | Group/crew for work. | `name`, `description`, `active`. | `warehouse`, `workerClass`, members. | Used by queue board assignment/taking logic. |
| `WorkerGroupMember` | `WORKER_GROUP_MEMBER` | Worker membership in group. | `roleInGroup`, `active`. | `workerGroup`, `worker`. | Multiple memberships supported; cross-warehouse mismatch rejected. |
| `WorkQueue` | `WORK_QUEUE` | Kanban queue column per warehouse. | `code`, `name`, `description`, `sortOrder`, `active`, `collapsed`, `hidden`, `sinkQueue`, `queueKind`, `notificationThreshold`, `notifyWhenThresholdReached`, `holdingPeriodMinutes`. | `warehouse`, worker class bindings, queue entries. | Kinds: movement, repair, holding. Holding/sink queues have special ordering/visibility rules. |
| `WorkQueueWorkerGroup` | `WORK_QUEUE_WORKER_GROUP` | Queue binding to worker class. | `stopTaskOnTake`, `active`. | `queue`, `workerClass`. | Controls available groups and task interruption behavior. |
| `BoardTask` | `BOARD_TASK` | Work item shown on queue board. | `title`, `unitNumber`, `description`, `status`, `taskKind`, `plannedDurationMinutes`, `deadlineAt`, `doneAt`. | `warehouse`, optional `rentalItem`, optional `repairProcess`. | Generated from repair plans or manually created. |
| `QueueEntry` | `QUEUE_ENTRY` | Board task instance in a queue and route position. | `routeIndex`, `position`, `entryType`, `status`, `taskText`, `plannedDurationMinutes`, `activeStartedAt`, `pausedAt`, `doneAt`, `activeWorkSeconds`. | `task`, `queue`. | REAL/SHADOW entries drive sequential repair routes. |
| `TaskAssignment` | `TASK_ASSIGNMENT` | Worker/group assignment and timing for queue entry. | `status`, `assignedAt`, `startedAt`, `pausedAt`, `finishedAt`. | `queueEntry`, `workerGroup`, `worker`. | Used to take, pause, resume, finish tasks and interrupt current work. |
| `TaskTimeEvent` | `TASK_TIME_EVENT` | Time tracking event. | `eventType`, `reason`, `createdAt`. | `queueEntry`, optional `worker`. | Records started/paused/resumed/finished events. |
| `BoardTaskPhotoLink` | `BOARD_TASK_PHOTO_LINK` | Link between board task and event photo. | `photoType`, `sortOrder`. | `boardTask`, `photo`. | Supports task photo categories before/work/after/acceptance. |

### Repair, History, Media

| Entity | Table | Purpose | Fields | Relations | Lifecycle and business meaning |
|---|---|---|---|---|---|
| `RepairEstimate` | `REPAIR_ESTIMATE` | Repair estimate header. | `cabinNumber`, `sourceParty`, `destinationParty`, `comment`, `dispatchDate`, `status`, `totalAmount`. | `rentalItem`, `warehouse`, `latestEvent`. | Draft/completed status. Completing can create repair process/tasks or mark item ready if empty. |
| `RepairEstimateLine` | `REPAIR_ESTIMATE_LINE` | Work/material line inside estimate. | `lineType`, `description`, `lineComment`, `unit`, `quantity`, `unitPrice`, `lineTotal`, `rowOrder`, `catalogCode`, `sourceLineKey`. | `estimate`, task plans. | Stable source key prevents duplicate line identity. |
| `RepairEstimateCatalogNode` | `REPAIR_ESTIMATE_CATALOG_NODE` | Repair catalog tree/canvas node. | `code`, `name`, `nodeType`, `active`, `sortOrder`, `unit`, `unitPrice`, `durationMinutes`, `includeInEstimate`, `commonItem`, `showInMainMenu`, `mainMenuOrder`, `mainMenuTitle`, `canvasX`, `canvasY`, `furniture`, `comment`. | `parent`, optional `workQueue`, optional route queue kind. | Drives estimate catalog picker, task route resolution, furniture/accessory mapping. |
| `RepairEstimateCatalogLink` | `REPAIR_ESTIMATE_CATALOG_LINK` | Link between catalog nodes. | `linkType`, `active`, `sortOrder`, `comment`. | `sourceNode`, `targetNode`. | Link types: dependency and follow-up. |
| `RepairEstimateTaskPlan` | `REPAIR_ESTIMATE_TASK_PLAN` | Planned queue task from estimate line. | `groupComment`, `generationStatus`, `sortOrder`, `active`, `comment`. | `estimate`, `estimateLine`, optional `repairProcess`, `queue`, `followUpNode`, `generatedBoardTask`. | Plans can be reordered/duplicated; generated once into board tasks. |
| `RepairProcess` | `REPAIR_PROCESS` | Repair or rework lifecycle process. | `status`, `processKind`, movement required/done/cancelled flags, `acceptedAt`, `acceptedBy`, `acceptanceComment`, `comment`. | `estimate`, `rentalItem`, `warehouse`, optional `sourceProcess`, requested group/worker. | Kinds: estimate repair, rework. Statuses: active, after repair, rework, accepted, cancelled. |
| `RepairProcessTaskLine` | `REPAIR_PROCESS_TASK_LINE` | Join from process task plan to estimate line. | UUID id. | `taskPlan`, `estimateLine`. | Tracks lines included in generated repair task. |
| `RentalItemEvent` | `RENTAL_ITEM_EVENT` | Timeline/history event for rental item. | `eventType`, `title`, `comment`, `eventDate`, `statusBefore`, `statusAfter`, actor fields, mobile task metadata, source fields, event group/order. | `rentalItem`, `warehouse`, parent event, estimate, repair process, board task, queue entry, worker group, worker. | Central audit/history stream for inventory, estimates, repairs, photos, accessories. |
| `RentalItemEventPhoto` | `RENTAL_ITEM_EVENT_PHOTO` | Photo attached to history event. | `storagePath`, `originalFileName`, `contentType`, `sizeBytes`, `familyRootKey`, `processingStatus`, dimensions for original/preview/thumb/tiny, `processingError`, `sortOrder`. | `event`. | Created by uploads; processed by RabbitMQ/Go worker if queue enabled. |

## Enum Catalog

Enums found:

- `BoardTaskPhotoType`: `BEFORE`, `WORK`, `AFTER`, `ACCEPTANCE`
- `BoardTaskStatus`: `ACTIVE`, `DONE`, `CANCELLED`
- `PhotoProcessingStatus`: `PENDING`, `READY`, `FAILED`
- `QueueEntryStatus`: `WAITING`, `IN_PROGRESS`, `PAUSED`, `DONE`, `CANCELLED`
- `QueueEntryType`: `REAL`, `SHADOW`
- `RentalAttributeDataType`: `STRING`, `TEXT`, `NUMBER`, `BOOLEAN`, `ENUM`
- `RentalItemEventType`: `ESTIMATE`, `INVENTORY_NEW_ITEM`, `INVENTORY_EXISTING_ITEM`, `ACCESSORY_UPDATED`, `ESTIMATE_DRAFT`, `ESTIMATE_COMPLETED`, `STATUS_CHANGED`, `REPAIR_TASK_STARTED`, `REPAIR_TASK_COMPLETED`, `REPAIR_READY_FOR_CHECK`, `REPAIR_ACCEPTED`, `BEFORE_RENT`, `AFTER_RENT`, `AFTER_REPAIR`, `CAPITAL_REPAIR`, `MANUAL`
- `RentalItemStatus`: `READY`, `TEMP_RESERVED`, `RESERVED`, `IN_RENT`, `NEED_INSPECTION`, `WAITING_ESTIMATE_CONFIRMATION`, `WAITING_REPAIR`, `IN_REPAIR`, `WAITING_REPAIR_CHECK`, `IN_CAP_REPAIR`
- `RepairEstimateCatalogLinkType`: `DEPENDENCY`, `FOLLOW_UP`
- `RepairEstimateCatalogNodeType`: `CATEGORY`, `SUBCATEGORY`, `WORK`, `MATERIAL`, `LOCATION`, `OPTION`
- `RepairEstimateLineType`: `WORK`, `MATERIAL`
- `RepairEstimateStatus`: `DRAFT`, `COMPLETED`
- `RepairEstimateTaskPlanGenerationStatus`: `PENDING_GENERATION`, `GENERATED`, `FAILED`
- `RepairProcessKind`: `ESTIMATE_REPAIR`, `REWORK`
- `RepairProcessStatus`: `ACTIVE`, `AFTER_REPAIR`, `REWORK`, `ACCEPTED`, `CANCELLED`
- `RepairProcessTaskKind`: `REPAIR_WORK`, `REWORK`, `MOVE_TO_REPAIR`, `MOVE_FROM_REPAIR`
- `ReservationAccessoryStatus`: `ACTIVE`, `RELEASED`, `CANCELLED`, `EXPIRED`, `COMPLETED`
- `ReservationClientType`: `INDIVIDUAL`, `LEGAL_ENTITY`
- `ReservationItemStatus`: `ACTIVE`, `RESERVED`, `RELEASED`, `CANCELLED`, `EXPIRED`, `COMPLETED`
- `ReservationStatus`: `ACTIVE`, `TEMPORARY`, `WAITING_PAYMENT`, `CONFIRMED`, `EXPIRED`, `CANCELLED`, `COMPLETED`
- `ReservationType`: `TEMPORARY`, `CLIENT`
- `TaskAssignmentStatus`: `ACTIVE`, `PAUSED`, `DONE`, `CANCELLED`
- `TaskTimeEventType`: `STARTED`, `PAUSED`, `RESUMED`, `FINISHED`
- `UserGlobalRole`: `SYSTEM_ADMIN`, `WMS_ADMIN`, `WAREHOUSE_MANAGER`, `RENTAL_MANAGER`, `VIEWER`
- `WarehouseAccessLevel`: `VIEW`, `EDIT`, `MANAGE`
- `WorkQueueKind`: `MOVEMENT`, `REPAIR`, `HOLDING`

## Target Browser Dossier Contracts (2026-07-12)

These are target frontend port DTOs, not Spring entities or final persistence
schemas:

- `RentalItemDossierDto` contains the cabin/version plus overview, activities,
  event photo groups, inspections, estimates, repairs, rental movements,
  comments, and explicit empty reserve/return projections.
- `CabinActivityDto` records occurred time, actor snapshot, type/source,
  comment, status transition, parent activity, photo group, and document links.
- `CabinPhotoGroupDto` groups ordered `CabinPhotoDto` values by source event and
  stage (`BEFORE`, `WORK`, `AFTER`, `ACCEPTANCE`, `GENERAL`). Common photo DTOs
  expose thumb/preview and `originalAvailable`, not an original URL.
- `RentalItemDto.version` is the browser optimistic-concurrency token for
  dossier-level status, photo, and general-comment commands.

Evidence:

- `panel/src/features/rental-items/dossier/model/rental-item-dossier.ts`
- `panel/src/features/rental-items/model/rental-item.ts`

## F4T Task-Board Event Stream Ownership Candidate (2026-07-14)

The current backend candidate classifies the existing task-board entities into
seven service-local streams:

- `WORKER_CLASS` includes class lifecycle facts;
- `WORKER` includes active/warehouse state and qualification snapshots, but not
  human-readable profile or credential fields;
- `WORKER_GROUP` includes member snapshots;
- `WORK_QUEUE` includes queue settings and class bindings;
- `QUEUE_USAGE_REFERENCE` remains its own externally referenced aggregate;
- `BOARD_TASK` owns task lifecycle and stable `externalTaskId` facts;
- `QUEUE_ENTRY` owns entry lifecycle plus assignment, time-event and
  interruption child facts.

Operational worker-credential intents, request fingerprints, delivery leases,
inbox/outbox rows and replay audits are not domain streams. `QUEUE_ENTRY`
remains separate from `BOARD_TASK` because public commands expose its own
optimistic version. The canonical transport shapes are defined by
`contracts/events/task-board/task-board-events-v1.schema.json`; Java records are
service-local implementation details.

This classification is implementation-candidate evidence. Final concurrency,
idempotency, replay and compatibility verification is pending.

## Asset aggregates (implementation record, 2026-07-16)

`RentalItem` is the canonical cabin aggregate: a globally normalized,
non-reusable number, warehouse reference, target status, passport fields,
dynamic passport JSON/tags, editable general comment and append-only manual
notes. Textual comments and notes remain service-local rather than integration
facts.

`EquipmentCatalogItem`, `EQUIPMENT_BALANCE`, `EQUIPMENT_MOVEMENT`,
`EQUIPMENT_ALLOCATION_HOLD` and `OPERATION_LEASE` are separate asset aggregate
families. Cabin contents are balance projections; a cabin with contents cannot
silently change warehouse. Balance and rental-item advisory locks serialize
status reclassification and transfers, while expected versions guard commands.

Readiness audit correction: `WRITTEN_OFF` is deliberately unavailable to the
public manual transition, but the current fenced endpoint delegates to that
same transition and therefore cannot write off a cabin. Operation-lease rows
and fencing tokens exist, yet public passport/status/warehouse/comment and
cabin-balance mutations do not all reject an active lease. The aggregate shape
is implemented; fenced transition and exclusivity behavior remain Stage 5
blockers rather than approved entity invariants.

### Stage 5 invariant completion update (2026-07-16)

The fenced lease command now validates its active fencing token and uses an
internal status transition that permits `WRITTEN_OFF`; the public manual status
command remains fail-closed for that value. Public cabin status, passport,
warehouse, comment/note and contents/disposition mutations reject an unexpired
operation lease, so they cannot bypass the fencing boundary.

`EQUIPMENT_ALLOCATION_HOLD` now has `ACTIVE`, `COMMITTED`, `RELEASED` and
`EXPIRED` lifecycle states. Commit is a versioned/idempotent asset-owned fact
and keeps the stock unavailable until release; it does not create a future
logistics aggregate or movement.

## Maintenance target aggregates (verified implementation, 2026-07-17)

`CatalogVersion` owns immutable published nodes/links and DRAFT-to-ACTIVE-to-
SUPERSEDED lifecycle. `MaintenanceEstimate` owns revisions, lines and frozen
plans. `MaintenanceRepair` owns execution and acceptance axes, stages, direct
repairs and child rework; acceptance/write-off are read projections rather than
independent aggregates. Lease, task-board and media records are opaque local
snapshots or reconciliation records, never shared entities or foreign keys.

Every mutable aggregate uses an event-stream head/version CAS. Projection rows,
event facts and outbox facts commit together. The controlled catalog preserves
legacy business identity and selected safe fields while excluding audit,
layout, obsolete flags and comments; it does not make the legacy Jmix entity
shape the target JPA model.

## Stage 7 inventory aggregate proposal (unapproved, 2026-07-17)

Approval resolution: the user's `Начинай Stage 7 все разрешаю` response,
followed by `Продолжай`, approves the revision, uniqueness, permanent source,
point-in-time completion, 30-minute capture and no-session-lease model below.
Implementation and verification remain pending in the ordered Stage 7 subgates.

The proposal in `docs/plans/20260717-inventory-service-contract.md` does not yet
authorize entities or migrations. It separates three mutable revision/CAS
boundaries:

- `INVENTORY_SESSION` owns warehouse/start snapshots, lifecycle
  `ACTIVE -> COMPLETED | CANCELLED`, the one-active-per-warehouse invariant,
  copied expected-population membership, acknowledgement and frozen completion
  statistics;
- `INVENTORY_FINDING` owns immutable origin/identity, inspection observation,
  reconciliation/conflicts, media references and a maintenance-issued frozen
  plan snapshot/fingerprint; finding edits do not reuse the session revision;
- `INVENTORY_PUBLICATION_INTENT` owns post-completion state and revision, while
  append-only publication attempts record delivery/reconciliation outcomes and
  cannot mutate the completed session.

Proposed structural invariants are one expected row per
`(inventoryId, assetId)`, exactly one expected finding for each expected row, at
most one finding per `(inventoryId, assetId)` and per
`(inventoryId, identityMatchKey)`, and permanent non-reusable source identity
`inventoryId:findingId`. Source-create/attach state is durable so a committed
asset remains auditable when attach loses a cancel/complete CAS race.

Completion freezes an asset point-in-time validation snapshot/digest/time on
the session. Session/finding CAS proves only inventory-local revisions; the
validation snapshot is not an asset aggregate token, lease or fence and does
not claim remote stability through the PostgreSQL commit. Later canonical asset
facts do not rewrite a completed session and are evaluated as current
maintenance-publication preconditions.

The asset stable capture is a technical resource with a proposed non-sliding
30-minute TTL, not an inventory aggregate or business lease. Inventory releases
it immediately after the copied start transaction commits; a concurrent loser
also releases immediately, and asset expiry is only crash/orphan fallback. No
capture remains attached to an `ACTIVE` session, so cancel/complete have none to
release. Canonical assets, maintenance repairs/tasks and media remain owned by
their existing services. These aggregate, revision, uniqueness and capture
choices are approval-required proposals, not approved target facts.

### Stage 7 verified aggregate resolution (2026-07-17)

The preceding proposal is retained as audit history. The approved inventory
aggregates are implemented as JPA business projections over the service-local
event store. Business packages use no low-level JDBC. Exactly six technical
eventing adapters retain SQL: `InventoryDeadLetterRelay`,
`InventoryDeadLetterStore`, `InventoryEventStore`,
`InventoryMediaInboxProcessor`, `InventoryMediaRetryStore` and
`InventoryOutboxStore`. Flyway V1 owns the schema; Hibernate validates it.
The final Stage 7-only matrix and reviewed commit `51460a3` close this aggregate
boundary.

## Stage 8 asset logistics boundary (2026-07-17)

The `RentalItem`, asset-owned operation lease and allocation-hold aggregates
remain in `asset-service`; logistics has no JPA association, foreign key or
shared mutable entity. Its private read model is a MapStruct-safe snapshot of
only asset ID, version, warehouse ID, status and attached
`{equipmentId, quantity}` values. Lease and hold responses are opaque DTOs.

Typed return/shipment/transfer document-line input is converted to an
asset-local owner reference and never persisted as an arbitrary caller string.
Transfer arrival changes the canonical rental item and rebalances attached
cabin equipment through the existing asset movement/ledger aggregates in the
same asset transaction. The new sanitized effect fact is covered by asset
Flyway V4; no Stage 8 entity exists in the asset schema.

## Stage 8 task-board logistics boundary (2026-07-17)

`BoardTask` and `TaskSyncSource` remain task-board JPA aggregates; logistics
has no JPA association, foreign key, shared entity or task-table ownership.
The source record binds a stable logistics external task ID to one task-board
task and prevents an unrelated source from reusing that identity. A MapStruct
read mapper exposes only the safe logistics snapshot
`{taskId, taskVersion, warehouseId, externalTaskId, status, doneAt}`.

The task is created in task-board's `UNASSIGNED` queue under its own fixed
preparation route. Logistics supplies no mutable entity-shaped task payload and
cannot map a request into a task entity or domain transition.

## Stage 8 maintenance logistics boundary (2026-07-17)

`LogisticsReturnShortage` and its `returnId,lineId` embedded JPA key remain
maintenance-owned. The source is an immutable record of warehouse, rental-item
version and canonical `{equipmentId, missingQuantity}` values; there is no
JPA association, foreign key or shared mutable entity with logistics, asset or
inventory.

MapStruct maps only the entity read to a safe private stored snapshot before
the service parses its local JSON array. The logistics request never maps into
an entity and cannot create an estimate/repair, task, lease or asset
transition.

## Stage 8 media logistics boundary (2026-07-17)

No Stage 8 media entity, owner binding or schema relation is introduced.
`media_asset` remains media-owned; logistics stores only opaque media ID and
generation references later in its own bounded context. The private receiver
derives its transient owner key from document/line UUIDs and checks exact
owner/warehouse/current-ready state without a foreign key, JPA association,
shared model or call into the Stage 7 inventory owner proof.

## Stage 8 logistics return-registration consumer slice (2026-07-17)

`logistics-service` now maps only its own Flyway V1/V2 tables through JPA.
`LogisticsDocument` and `LogisticsDocumentLine` remain the public workflow
aggregate and preserve immutable expected/factual contents snapshots as JSON
evidence. `LogisticsExternalAttempt`, `LogisticsGuard` and
`LogisticsReconciliation` are logistics-local durable workflow records: they
hold stable idempotency operation IDs, opaque remote lease references and
reconciliation/audit state, never an asset, warehouse, task, maintenance or
media JPA association.

Return registration persists `DRAFT -> REGISTERING` and its warehouse attempt
before it calls another service. The relay then obtains a safe warehouse
identity, captures the safe asset snapshot, obtains a typed asset operation
lease and requests the fenced return-intake effect. The resulting line keeps
only copied safe contents evidence and opaque remote identifiers. A permanent
boundary rejection reaches `CONFLICT`; an indeterminate outcome or exhausted
bounded retry reaches `RECONCILIATION_REQUIRED`. No local entity assumes that
a timed-out asset effect was undone.

## Stage 8 logistics workflow and inbound evidence model (2026-07-17)

`LogisticsDocument`/`LogisticsDocumentLine` remain the only public workflow
aggregate and line model. The service-local `LogisticsGuard`,
`LogisticsExternalAttempt`, `LogisticsEquipmentHoldReference`,
`LogisticsTaskReference`, `LogisticsMediaReference`,
`LogisticsReturnShortageSnapshot`, `LogisticsReconciliation` and
`LogisticsReconciliationRequest` retain opaque remote IDs and immutable local
evidence only. They have no JPA association or foreign key to warehouse,
asset, task-board, maintenance, media or inventory persistence.

V3 adds line-scoped media generation and shortage-snapshot support; V4 adds
versioned shipment hold/task references; V5 supplies transfer operation
vocabulary; V6 adds validated inbound replay/observation records. The latter
are technical evidence projections, not source aggregate replicas or command
owners. JPA maps the business model; technical event/outbox/inbox/replay
adapters use their narrowly allowlisted JDBC transactions.

### Stage 8 mutable projection version resolution (2026-07-18)

Immutable Flyway V7 adds `row_version` only to the mutable
`LogisticsExternalAttempt`, `LogisticsGuard` and `LogisticsMediaReference` JPA
projections. Immutable idempotency, shortage-snapshot and reconciliation-
request evidence rows are deliberately not given meaningless versions. The
business `LogisticsDocumentService` contains no JDBC; its concurrent
subject/operation/key serialization is invoked through the Spring Data JPA
repository. Low-level SQL remains restricted to named technical event-store,
outbox, inbox, recovery, DLT and deterministic replay adapters.

## Stage 9 dossier entity model (verified, 2026-07-18)

The dossier model separates `DossierSourceFact`, `DossierInbox`, partition and
aggregate checkpoints, projection generation/pointer, subject association,
immutable activity, current media projection, unlinked fact, sanitized dead
letter, replay run/partition high-water, cabin publication head and outbox.
These are dossier-local JPA entities with no association to a producer model.

Visible activity identity is deterministic per source event and cabin within a
generation. A cabin publication head serializes monotonically versioned,
stable-ID downstream facts without turning the activity projection into a
command aggregate. Inventory finding associations are producer-owned evidence;
once proven, their cabin/warehouse identity cannot be silently rewritten.
Media state is generation-aware and `DELETED` is absent from visible groups
without deleting immutable source/activity evidence.
