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
