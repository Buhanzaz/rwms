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

Customer self-registration is an auth-owned anonymous but CSRF-protected
command. It serializes the normalized login before BCrypt and atomically creates
one active `USER/CUSTOMER` identity plus its credential, projection, event and
outbox fact, with no warehouse grant or manager entitlement. The public PKCE
client `rwms-customer-android` can mint only `customer.rental`; a CUSTOMER token
cannot be minted through panel/manager clients, and a non-customer cannot use
the customer client. This identity is separate from the logistics customer
profile created after login.

CSRF protects that anonymous command from cross-site submission; it is not an
abuse quota. Because the public gateway is stateless and owns no request-ledger
database, source-address throttling remains a production-ingress obligation,
not auth or domain state.

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
[`CustomerRegistrationService`](../../services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/service/CustomerRegistrationService.java),
[`AuthorizationServerConfiguration`](../../services/auth-service/src/main/java/dev/buhanzaz/rwms/auth/config/AuthorizationServerConfiguration.java),
[`auth-provider.tsx`](../../panel/src/features/auth/auth-provider.tsx).

### Warehouses

`warehouse-service` owns canonical warehouse identity, metadata and timezone.
Other services store warehouse IDs as opaque references and authorize access;
they do not reproduce the warehouse registry in shared tables.

A warehouse has independent `production` and `mainWarehouse` classifications;
a regular object has at least one, while a representative has neither and
exactly one production or main parent. The derived `representative` field is
`true` exactly when `representativeParentWarehouseId` is non-null. Only
`WarehouseType`/`warehouseType` and `productionWarehouseId` are removed; they
no longer define lifecycle state. The owner also keeps an optional WGS84
coordinate pair and a directed many-to-many support graph. The coordinate pair is all-or-none; exact `0,0` is
the reserved unset placeholder and is not an operational route origin, while a
single zero axis remains valid when the other component is non-zero. New owner
commands reject the placeholder. Consumers retain old identity facts but mark
them not routable until an operator supplies a real point. One link grants
selected driver, vehicle,
inventory, direct-fulfilment, transfer and contractor-fallback capabilities
from a support warehouse to a representative served warehouse. Priority,
weekdays, allowed dates, excluded dates and an optional daily interval belong
to that link. Self-links and duplicate directions are invalid. The
organizational representative parent is independent of these operational support
links; neither relationship creates a second logistics warehouse. Logistics
and the standalone planner consume the same warehouse UUID and owner-held
coordinates.

Warehouse UUID is the stable external reference. Display names are unique
after trim, whitespace folding and case normalization. Inactive warehouses
remain readable for historical references. A warehouse that has never recorded
an operation may correct its timezone immediately; after first use, timezone
changes are effective-dated and do not rewrite earlier facts or reports.
Deactivation proceeds through `DRAINING` and exact-version confirmations from
operation owners before `INACTIVE`. The public warehouse directory is scoped
to the installation; warehouse grants do not filter it.

Evidence: [`services/warehouse-service/`](../../services/warehouse-service/),
[`warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml),
[`V3__add_normalized_warehouse_name.sql`](../../services/warehouse-service/src/main/resources/db/migration/V3__add_normalized_warehouse_name.sql),
[`V4__warehouse_effective_time_zones.sql`](../../services/warehouse-service/src/main/resources/db/migration/V4__warehouse_effective_time_zones.sql),
[`V5__warehouse_lifecycle.sql`](../../services/warehouse-service/src/main/resources/db/migration/V5__warehouse_lifecycle.sql),
[`V7__warehouse_representative_characteristic.sql`](../../services/warehouse-service/src/main/resources/db/migration/V7__warehouse_representative_characteristic.sql),
[`V8__warehouse_coordinates_and_support_links.sql`](../../services/warehouse-service/src/main/resources/db/migration/V8__warehouse_coordinates_and_support_links.sql),
historical [`V9`](../../services/warehouse-service/src/main/resources/db/migration/V9__warehouse_company_and_type.sql) and [`V10`](../../services/warehouse-service/src/main/resources/db/migration/V10__warehouse_object_classifications.sql), and current [`V11`](../../services/warehouse-service/src/main/resources/db/migration/V11__remove_platform_company_boundary.sql),
[`Warehouse`](../../services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/domain/Warehouse.java),
[`WarehouseSupportLinkService`](../../services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/service/WarehouseSupportLinkService.java),
[`CustomerWarehouseService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerWarehouseService.java),
[`PlanningResourceDirectoryService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/planning/service/PlanningResourceDirectoryService.java), and
[`standalone directory reconciliation`](../../logistics/backend/app/services/catalog.py).

### Cabins And Equipment

`asset-service` owns cabins/rental items, their status, equipment/content,
balances, holds and leases. A logistics or maintenance workflow requests or
records effects through contracts; it does not mutate asset tables directly.

Interactive creation with mandatory photos is one asset-owned durable intent,
not a browser saga. Create atomically persists the cabin, an exact creation
lease and the ordered immutable photo manifest with server media identities.
The cabin stays unavailable while the intent is `PENDING`; completion rechecks
the intent fence and exact current READY media snapshot outside/inside bounded
transactions before releasing the hold. Explicit abandon releases only that
hold and leaves the incomplete cabin in `WAREHOUSE`; it does not delete the
cabin or retained media. Exact command replay is scoped by actor, idempotency
key and request hash.

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
[`RentalItemCreationIntentService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/RentalItemCreationIntentService.java),
[`V28__equipment_catalog_identity_and_live_usage.sql`](../../services/asset-service/src/main/resources/db/migration/V28__equipment_catalog_identity_and_live_usage.sql),
[`V34__maintenance_furniture_custody.sql`](../../services/asset-service/src/main/resources/db/migration/V34__maintenance_furniture_custody.sql),
[`V45__rental_item_creation_intents.sql`](../../services/asset-service/src/main/resources/db/migration/V45__rental_item_creation_intents.sql).

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

Native roles are separate capabilities over that one aggregate. DriverApp has
only the primary driver surface and may take, pause, resume or complete; it
cannot join as a secondary. WorkerApp has no driver take or trip surface and
sees a logistics task only while it is active/paused and the worker is eligible
for a configured secondary class. A driver take transaction also persists the
slinger's `TASK_JOIN_AVAILABLE` notification. Delivery is at least once and
only an invalidation: the server feed remains the authorization source. Before
that take, a waiting logistics task is announced only to authorized DriverApp
streams; WorkerApp receives neither an early task alert nor a cross-surface
entry identity.

A slinger joins with the current worker-group identity. If that group is
executing another entry, task-board pauses that entire entry and later resumes
it when the joint logistics task closes. Configured logistics secondary
participation is optional, interrupting and notified: the driver may close with
a ready photo before a slinger accepts. The WorkerApp control says `Взять
задание`, but sends the existing `JOIN` command with the current group. A
primary assignment without a group never becomes a secondary assignment even
when the driver is also slinger-qualified. The driver and any joined slinger
share one evidence set and terminal transition. Completion by the driver or
joined slinger requires at least one result photo whose media generation is
`READY`, then removes the task for both.

Maintenance [canonicalizes each newly written ordinary plan](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/RepairPhaseSequence.java)
to one repair stage per physical task-board queue ID before resolving and persisting its full work/material content. Missing phases
create no stage, repeated source groups for one queue contribute all of their ordered lines and
comments to the surviving stage, and equal display names with different queue IDs remain separate.
That stage maps to one task-board route entry and therefore one WorkerApp subtask/result-photo
owner: one cabin therefore appears at most once in each physical queue, with every assigned work
and material line inside that one subtask. Already registered `QUEUED/GENERATED` repairs with
duplicate same-queue stages converge through the source-owned pre-start workflow. Maintenance
first builds the combined route without changing local rows; task-board atomically replaces only an
entirely unstarted route, after which maintenance merges the queued rows and binds the returned
entry IDs in one local transaction. A concurrent start rejects replacement and leaves the local
plan unchanged. Started or completed historical routes remain immutable, and task-board retains its
[compatibility execution package](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/MaintenanceTaskExecutionPackageService.java)
only for those rows. Worker detail aggregates
all work, material, comment and source-media snapshots from that complete historical segment,
while its duration, countdown and KPI budget cover only unfinished members. TAKE assigns the
representative once. The raw route row remains `routeIndex`; the displayed package uses
`routeStepIndex` and `routeStepCount`. Those package coordinates count maximal consecutive
same-queue segments, so they collapse A-A but do not collapse A-B-A across the intervening queue. Its
version-fenced COMPLETE atomically closes the representative plus later
unfinished shadow members, records audit facts for each and publishes their existing completion
events. A different queue starts a different package, and non-maintenance sources remain
entry-scoped. Task-board changes no maintenance repair state directly.

The ordinary repair board is one warehouse-wide queue projection, not a dated
queue or a second maintenance-owned repair table. Persisted scheduling metadata
does not partition or order ordinary queue positions. Registration priority is
materialized into the persisted position once. The manager snapshot returns
every unfinished `REAL` and `SHADOW` entry in canonical active, pin and persisted
position order. `availableTaskLimit` (default `6`, range `1..50`) marks only the
first waiting `REAL` cards in each physical queue as the warehouse plan. The
global definition supplies only the initial value for a new warehouse; later
reconciliation preserves the local count and `workerFeedEnabled`. These controls
do not truncate the manager projection. WorkerApp omits a disabled queue and
all of its cards, including active work. In an enabled queue it omits every
`SHADOW`, keeps active `REAL` work visible, and publishes only the first configured
waiting `REAL` cards. Native detail, media reader authorization and `TAKE`/`JOIN`
recheck the same window. Every `REAL` route gate remains actionable, while every
`SHADOW` remains read-only. Under the entry version fence, an `EDIT` user may
promote or demote only a `WAITING` ordinary entry strictly after the earliest
unfinished route position. Promotion permits parallel execution and preserves
the persisted position, so the earlier future card moves ahead of later
unpinned work; pinning is the explicit exception. The same transaction refreshes
the entry media-owner proof, so the newly visible worker audience can read
source evidence and demotion revokes it. The current stage cannot be demoted,
and an unfinished SES stage rejects later promotion. The panel hides
shadows by default, can show all future subtasks with one view checkbox, exposes
one server-backed availability checkbox on each eligible future card, and can
reveal and highlight one task's complete route across queues from its `REAL`
card. That action expands every route queue and vertically scrolls each column
to the matching card without changing order. The versioned warehouse-local UI
preference preserves future visibility, collapsed queues, board scroll and each
queue's scroll across details/back navigation, but contains no task or queue
fact. Managers may reorder only unpinned waiting real cards within one queue;
entry and queue versions plus the observed target entry identity fence the
command, while active, pinned and shadow entries keep their positions.

Global working queues can form one symmetric continuation pair under both
definition versions (`PUT /api/queue-definitions/{id}/link`). They share a
primary worker class; holding and driver queues cannot be paired. Board columns
expose the partner physical queue ID and name. One current member takes ordinary
work for all active current members, and any assigned member completes it for
the group. A completed primary assignment reserves the immediate waiting real
continuation of that same task in its linked queue for the group. WorkerApp
publishes that continuation ahead of unrelated waiting choices for its group
while it is inside the active queue plan. The existing publication switch,
plan limit, group availability and canonical route gates remain authoritative.
TAKE starts the next timer; unlinking releases the reservation. Assignment
`primaryParticipation` is immutable event evidence, independent of later group
class edits. Historical unknown roles remain unknown and create no reservation.

A route uses one mandatory phase sequence: SES, welding, exterior, interior,
electrical, then plumbing. The first existing unfinished phase is `REAL` by
default; absent or completed phases are skipped and all later work starts as
`SHADOW`. Managers may explicitly open several later ordinary stages for
parallel work. SES remains the exclusive executable holding phase even for
retained queue definitions whose historical type is `REPAIR`: while it is
unfinished, later promotion is rejected and WorkerApp receives only SES. Once
SES is complete, each promoted real stage is still admitted through its own
queue's switch, plan and qualification policy. Public date selection,
cross-queue move/date-swap commands,
maintenance daily-capacity placement and overdue rollover are absent. The
daily plan creates no dated schedule. Driver
movement and external capital-repair routes remain on their owning surfaces.
Maintenance applies this sequence before plan persistence, repair reads and
task publication in
[`RepairPhaseSequence`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/RepairPhaseSequence.java).
Task-board independently normalizes maintenance-owned registrations and
pre-start updates in
[`RepairRoutePhaseOrder`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/RepairRoutePhaseOrder.java),
so a producer cannot make a later phase executable by changing array order.

The KPI display palette is an installation-wide, version-fenced task-board aggregate:
the public gateway `GET`/`PUT /api/task-board/kpi-palette` requests map to the owner's
service-local `/api/kpi-palette` boundary and have no scope selector,
and one replacement applies to every warehouse and native worker. Legacy warehouse palette records are not selected as an implicit
baseline. KPI schedule revisions remain effective-dated in the warehouse time zone. Saving
creates or updates the pending `DRAFT`; activation of a revision effective
today promotes it to `ACTIVE` in the same command and applies it to the whole
current local calendar date. A future revision stays `SCHEDULED` until that
date, a past date is rejected, and same-date replacement retires the previous
scheduled or active revision under the existing optimistic-concurrency and
activation-receipt fences. The task-board service remains the command owner;
the panel and analytics projection do not manufacture an active schedule.

A workforce group command may replace membership and version-fenced current
group assignments atomically. Current assignment still requires active
membership, an available active group, and no conflicting active task; a failed
worker check rolls back the membership change. Closed task owner proof retains
historical assignees for read-only task-photo access, while upload/finalize
continues to require an active entry proof. For an open entry,
`readerWorkerIds` is the exact union of WorkerApp/DriverApp feed/detail-visible
workers, while `allowedWorkerIds` remains the assigned/evidence upload
audience. Task creation publishes that initial proof transactionally. A bounded
idempotent reconciliation pass runs at startup and only after a warehouse audience-revision
change; it repairs legacy proofs and converges later workforce or queue-policy changes without
idle full-table polling or a browser-owned authorization fallback. The reconciler materializes the
complete WorkerApp plan once per warehouse batch; normal proof updates evaluate only their current
entry and do not load the whole board. A failed pass does not advance its revision watermark.

#### Driver daily shift

Task-board also owns the Driver Up daily-shift aggregate and every transition in its explicit state
machine. Logistics is only the source of the concrete planner snapshot: its private idempotent
command carries the driver, warehouse, vehicle/trailer, start odometer, trip count and exact route
meters. It may also carry the complete one-based executable route operation list with aware planned
times, exact endpoints and a continuous load chain. An additive effective cabin capacity and paired
transfer load/unload operations carry canonical transfer identity separately from customer task
identity. Task-board rejects wrong endpoints, duplicate or unbalanced transfer pairs and per-leg
cabin overload; a furniture-only transfer is a real operation pair with an unchanged cabin counter.
Task-board owns those immutable children under
the replaceable-until-frozen source plan, serializes first-open creation and enforces one shift per
`(driver_id, work_date)`. The work date comes from the warehouse-service timezone and 06:00 local
boundary; Android time, background jobs and local flags are never authoritative.

The mobile token keeps the worker's immutable home warehouse and is not rewritten by a resource
reposition. Task-board verifies that home claim against the worker profile, then derives the
server-authoritative operational warehouse from assignment history. Only `ACTIVE` temporary or
completed permanent placement can create a destination shift; planned travel and in-transit state
remain unavailable. The operational warehouse's IANA timezone selects the new work date. An
already-created unfinished shift remains resumable by the exact driver/shift identity after the
assignment ends, and its frozen warehouse—not the JWT home claim—owns shift photos and other
warehouse-scoped effects.

Briefing acknowledgement, test medical self-confirmation, inspection item outcomes, inspection
completion, shift start, closing start, warehouse return, closing report, evidence reservation and
close are separate idempotent, optimistic-concurrency commands with server audit time and actor.
Inspection history is a template snapshot with per-item `NOT_CHECKED`/`OK`/`DEFECT` results, not
boolean columns. Every required item must be resolved and a blocking defect prevents start. Closing
is enabled only after all required task-board tasks assigned/planned to that exact driver/date are
terminal; Android cannot infer the last task by list position. Return confirmation, non-decreasing
odometer, bounded fuel level and any defect-required `READY` media are mandatory close gates. Route
distance and odometer distance remain separate measurements.

Weather and traffic are informational projections, never state-machine prerequisites. Task-board's
bounded MET Norway adapter normalizes/cache forecasts and configurable hazards; failures yield an
unavailable briefing. DriverApp's replaceable MapKit traffic provider follows the same fail-open
rule. Shift photos retain media-service byte ownership under
`DRIVER_SHIFT/SHIFT_EVIDENCE`; task-board publishes the exact worker proof and consumes the READY
fact for its stable reservation. A queued/offline Android command never fabricates a completed
server transition, especially `SHIFT_CLOSED`.

DriverApp renders the operation snapshot as planned ETA, including the inbound and return
positioning legs for an applied cross-warehouse shift. A valid IANA timezone from the frozen shift
warehouse controls presentation; if it is unavailable, the timestamp offset is preserved. This is
not a dynamic/actual ETA source and does not change the task state machine.

Evidence: [`services/task-board-service/`](../../services/task-board-service/),
[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml),
[`DriverTaskAudienceService.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/DriverTaskAudienceService.java),
[`MobileTaskSurfacePolicy.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/MobileTaskSurfacePolicy.java),
[`WorkerTaskAccessService.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerTaskAccessService.java),
[`WorkerQueuePlanPolicy.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerQueuePlanPolicy.java),
[`TaskBoardFutureAvailabilityService.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardFutureAvailabilityService.java),
[`TaskBoardEntryOrderingService.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardEntryOrderingService.java),
[`TaskBoardExternalRegistrationService.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalRegistrationService.java),
[`TaskBoardEntryOwnerProofReconciler.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardEntryOwnerProofReconciler.java),
[`TaskBoardWorkerExecutionService.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardWorkerExecutionService.java),
[`KpiSettingsService.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/KpiSettingsService.java),
[`task-board view preferences`](../../panel/src/features/task-board/task-board-view-preferences.ts),
[`MaintenanceTaskExecutionPackageService.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/MaintenanceTaskExecutionPackageService.java),
[`OrdinaryQueueAvailabilityPolicy.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/OrdinaryQueueAvailabilityPolicy.java),
[`V31__ordinary_queue_availability_and_holding_gate.sql`](../../services/task-board-service/src/main/resources/db/migration/V31__ordinary_queue_availability_and_holding_gate.sql),
[`DriverShiftService.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/DriverShiftService.java),
[`V39__driver_daily_shift.sql`](../../services/task-board-service/src/main/resources/db/migration/V39__driver_daily_shift.sql),
[`MetNoWeatherProvider.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/MetNoWeatherProvider.java),
[`media driver-shift projection`](../../services/media-service/internal/persistence/driver_shift_owner_projection.go),
and
[`WorkerPushDispatcher.java`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/push/WorkerPushDispatcher.java).

### Maintenance

`maintenance-service` owns repair catalogs, estimates, repairs, acceptance,
rework and write-off decisions. Materials and works are maintenance catalog
concepts; their effects on assets, inventory or tasks cross explicit service
boundaries.

The versioned repair catalog and repair-complexity display colors are global
installation settings. Their browser/API boundary has no warehouse selector;
catalog reads are available to authenticated RWMS users and mutations are
restricted to global administrators. The catalog row's retained `warehouseId`
is internal audit/routing context only and never partitions catalog content.

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

Each warehouse has a version-fenced estimate-creation window setting, default
`7` days and bounded to `1..3650`. Maintenance evaluates the inclusive deadline
from logistics-owned physical return time in the warehouse timezone; with an
arrival on 1 August and a seven-day setting, 8 August is the last creation day
and 9 August returns `ESTIMATE_CREATION_WINDOW_EXPIRED`. The rule guards both
manual estimate creation and logistics-origin automatic registration. It does
not block the separate direct-repair workflow. Logistics freezes the normal
intake transition as immutable `returnArrivedAt`; inventory-created historical
returns have no intake timestamp and are not estimate sources.

A user-entered historical rental return is different from an inventory-created
historical return: it retains the selected past document date but executes the
same current fenced intake and therefore becomes a normal estimate/repair
source when that intake reaches `INSPECTION_REQUIRED`.

Cabin and additional-equipment write-off/loss use one property-decision model.
One list row is one decision/root asset; a repair chain remains visible in the
detail. A warehouse manager or administrator may create a mandatory-reason
proposal, but only a global administrator makes the final decision. A non-empty
cabin must choose between moving exact positive quantities to warehouse and
disposing the remainder, or disposing all contents with the cabin. An empty
cabin has no contents choice. Asset mutation and logistics movement are
asynchronous, durable effects whose pending/quarantined state remains visible
and can be recovered only by a version-fenced administrator review.

A missing cabin left out of the completed-inventory shipment selection enters
that same decision model. Inventory first waits for the exact plan-wide
logistics generation to release predecessor rental state, then calls the
service-only idempotent boundary. Maintenance rereads and freezes current cabin
contents as `DISPOSE_WITH_CABIN` and creates `PENDING_APPROVAL`; it never trusts
browser-supplied balance versions and does not make the cabin terminal before
global-administrator approval.

Automatic furniture-catalog linking first persists a stable node-UUID intent,
then calls asset-service outside the catalog transaction, and finally confirms
the exact result. Lost responses and local commit races are replay-safe; rename
or remap conflicts require audited administrator retry or abandonment and never
silently delete the remote item.

`V43__backfill_furniture_equipment_link_intents.sql` rehydrates one `PENDING`
intent for each pre-durable furniture node UUID, preferring its active catalog
snapshot over draft or superseded copies. The migration changes only
maintenance-owned state; the existing reconciler subsequently establishes the
asset external reference with its idempotent ensure command, while a missing or
remapped live binding remains a visible failure rather than stale local truth.

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

An estimate, its immutable revisions and the resulting repair retain the
explicit `forceCapitalRepair` choice. Omission on a compatible command means
`false`; explicit `null` is invalid. Effective capital complexity is the
logical OR of that choice and any catalog work that already forces capital
repair. A single plan cannot also request a movement to ordinary repair. Once
queued, the repair uses the existing capital-repair placement,
driver-movement, execution and acceptance lifecycle rather than creating a
second queue owner or a client-side task.

Completed-inventory publication is routing evidence, not execution evidence.
No-work produces `FREE`; ordinary work without inbound movement registers on
task-board; selected movement completes before ordinary task registration; and
capital work stays `QUEUED/NOT_READY` on the active capital route. A frozen
`movementToRepair=true` remains authoritative when catalog rules make that work
capital and creates or reuses `CAPITAL_TO_PRODUCTION`; recalculation cannot clear
the choice. Explicit no-movement capital work creates no driver task.
Inventory-origin acceptance and rework require task-board execution proof, so a
publication-created repair cannot enter either surface directly. The proof is
stage-complete: at least one stage exists and every stage is `DONE` with its
queue entry, task-board version, completion event and completion time. The
database-paged acceptance read also excludes a source while any child rework is
still active or awaiting acceptance. Reapplication also restores legacy
publication-completed capital rows to the active capital route.

A direct-repair create performs remote routing admission outside its final
transaction, then locks the maintenance-owned rental-item fact, rereads the
exact idempotency receipt and checks for an active PRIMARY root before writing.
This serializes direct/direct lost-response retries without placing a global
unique constraint on repair rows. Such a constraint is currently invalid:
pre-start inventory replacement intentionally holds a non-terminal predecessor
and successor until its durable saga advances. A common fence across every
PRIMARY creator remains an explicit unresolved design decision.

Evidence: [`services/maintenance-service/`](../../services/maintenance-service/),
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
[`PropertyDispositionApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionApplicationService.java),
[`PropertyDispositionCreationUseCases.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionCreationUseCases.java),
[`EstimateCreationWindowPolicy.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/EstimateCreationWindowPolicy.java),
[`FurnitureEquipmentLinkStore.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/FurnitureEquipmentLinkStore.java),
[`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
[`MaintenanceEstimateModelSupport.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceEstimateModelSupport.java),
[`MaintenanceRepairModelSupport.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceRepairModelSupport.java),
[`V38__durable_furniture_equipment_links.sql`](../../services/maintenance-service/src/main/resources/db/migration/V38__durable_furniture_equipment_links.sql),
[`V43__backfill_furniture_equipment_link_intents.sql`](../../services/maintenance-service/src/main/resources/db/migration/V43__backfill_furniture_equipment_link_intents.sql),
[`V44__manual_capital_repair_selection.sql`](../../services/maintenance-service/src/main/resources/db/migration/V44__manual_capital_repair_selection.sql),
[`V46__estimate_creation_window.sql`](../../services/maintenance-service/src/main/resources/db/migration/V46__estimate_creation_window.sql),
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

Before furniture review, inventory owns an exact two-phase cabin-disposition
review. `RETURNS` requires an actual return date and existing client for every
physically found cabin whose captured status was `RENTED`. `SHIPMENTS` accepts
only the missing cabins confirmed as departed, with actual departure date,
client and zero or more catalog-versioned furniture quantities; every omitted
missing candidate becomes `WRITE_OFF`. The final plan freezes `LOCAL`,
`SHIPMENT` or `WRITE_OFF` and their evidence. Only local cabins participate in
the warehouse furniture reconciliation.

The field proposal may explicitly force capital repair. Inventory preserves
that boolean in the finding plan snapshot, every reviewed final-plan candidate
and the exact publication request. It does not infer or schedule the driver
task: maintenance combines the flag with catalog-derived complexity and owns
the resulting capital-repair lifecycle after publication.

Membership is live while a session is active. An arrival at the inventoried
warehouse becomes an expected uninspected item. Only an automatically captured
`EXPECTED` finding follows a later registry departure. An explicit operator
observation (`ADDED_NEW`, `ADDED_USED` or `UNEXPECTED_EXISTING`) remains active
in the inventory table and final-plan population when a later capture omits it;
capture eligibility cannot erase the fact that the cabin was physically found.
Terminal `WRITTEN_OFF` and `LOST` status still rejects outcome publication at
the asset owner rather than being rewritten by inventory.

A warehouse MANAGE user can explicitly refresh a stale active session from a fresh, read-only
asset capture. Inventory checks the expected session revision before the remote capture and again
under the local apply lock, then atomically feeds captured arrivals/current snapshots and
target-session departures through the existing membership journal. A target departure deactivates
only automatic `EXPECTED` population; explicit observations and their inspection snapshots remain
part of the active result. A target departure never
changes another warehouse's active session merely because that asset was absent from this capture.
The command retains findings, inspection/furniture evidence, media references and movement history;
it restarts only the derived furniture review and marks any final plan stale. Furniture review
seeding treats the client-written `quantity` field as authoritative and accepts stored
`observedQuantity` review facts for compatibility. A final plan with no maintenance work sends a
required empty preflight list, which maintenance accepts as a valid empty candidate set.

Media references are exact finding-revision evidence. Membership/current-snapshot refresh,
source-asset attachment, conflict resolution, furniture confirmation and owner-proof closure may
advance a finding without changing that evidence; each such transition copies only the immediately
preceding exact media set inside the same inventory transaction. An inspection save still replaces
the set with exactly what the client submitted, including an intentional empty set. Inventory migration
[`V18__carry_forward_inventory_finding_media.sql`](../../services/inventory-service/src/main/resources/db/migration/V18__carry_forward_inventory_finding_media.sql)
repairs pre-fix drift only when the retained cover ID proves the current revision is missing its
media, and copies the newest whole prior set containing that cover without rewriting historical
references or media-service objects.

A durable ManagerApp inspection upload rereads the active finding before its
final command. If a live asset-status or snapshot update advanced only an
uninspected finding revision while media was uploading, it persists and uses
that current fence. A saved supplement or replacement proceeds only while the
finding still has the exact revision captured by the freshly opened editor.
Both paths require `IDLE` or `SOURCE_CREATED`; an inactive finding, in-flight
source creation, or newer saved inspection remains a visible fail-closed
conflict and cannot be overwritten by background work.

Inspection saves evidence and a frozen proposal only. Repair, movement and
task-board effects are not created before inventory completion. Completion is
fenced by the reviewed plan version and publishes durable, idempotent intents
for every found cabin, including no-work findings. After the reviewed furniture
snapshot applies, each intent first asks asset-service to make the completed
finding current truth: `FREE` for no work, `REPAIR` for ordinary work and
`CAPITAL_REPAIR` for an explicit capital choice. That asset-owned transaction
supersedes non-terminal rental, transfer, reservation, presentation and lease
bindings without deleting their rows or events; `LOST`, `WRITTEN_OFF` and a
wrong warehouse remain conflicts. Asset furniture reconciliation still fences
the immutable completed-plan identity, warehouse, catalog and terminal state,
but a non-terminal status/version drift caused by that preceding authoritative
asset outcome is not a reason to reject the same reviewed furniture counts.

The same asset command also consumes the passport observation frozen with the
exact publication intent. `PRESENT` is a complete authoritative passport: type,
dimensions, finishing and category must resolve by exact active catalog names;
the complete characteristic set is replaced after splitting every raw string or
array element on commas; and a missing characteristic value clears that set,
while a missing/non-boolean linoleum value stores `null`. `ABSENT` preserves the
asset passport. Catalog resolution happens before any binding release and the
passport, characteristics, status, asset events, receipt and per-asset watermark
commit in one asset transaction. An old status-only watermark may adopt the
same immutable source once; afterwards an equal-time passport-payload change is
a conflict. Inventory migration
[`V23__freeze_inventory_outcome_passport_observation.sql`](../../services/inventory-service/src/main/resources/db/migration/V23__freeze_inventory_outcome_passport_observation.sql)
backfills only revision-fenced finding/final-plan evidence, and asset migration
[`V39__inventory_outcome_passport_watermark.sql`](../../services/asset-service/src/main/resources/db/migration/V39__inventory_outcome_passport_watermark.sql)
does not rewrite existing cabin passports.

Publication then projects the finding's exact image set as the current
media-service cabin folder, applies one persisted plan-wide logistics outcome per final-plan
reapplication generation and finally
calls maintenance for either the reviewed work or an explicit no-work cleanup.
Media keeps every older association and MinIO object as an archive. Its public
cabin-cover projection counts and previews only the active latest folder and
puts that folder's canonical cover first; folder boundaries and older batches
stay on the full archive list. The passport, warehouse cards and private
logistics presentation therefore share the same current-folder boundary and
never mix batches. Logistics
supersedes active rental/document lines and their cancellable tasks;
maintenance supersedes non-terminal estimates, repairs, task/driver effects and
leases before materializing the selected repair/capital-repair successor, or
before confirming `FREE` has no current maintenance work. All three owners keep
permanent receipts and latest-completed-inventory watermarks, so a stale session
cannot overwrite a newer one and an exact retry is recoverable without a
distributed transaction. A same-source no-work retry may carry a higher
`authoritativeAssetVersion` after passport application; maintenance preserves
the immutable outcome coordinator but records that exact invocation fingerprint
and response in its own receipt.

If completed-history recovery creates a strictly newer plan version for the same inventory,
finding and completion instant, asset, logistics, maintenance and media accept it only as a
corrected successor. Lower versions and same-version drift remain conflicts. Maintenance requires
the prior outcome to be `APPLIED`. Equivalent finding/work/routing evidence adopts the current
repair resolved from the newest completed predecessor receipt without duplicating its immutable
source row. Changed work, priority, movement/capital routing or `WORK`/`FREE` evidence supersedes the
old active route and leaves exactly one current outcome; terminal accepted or written-off work is
never rewritten. A retry can finish an interrupted corrected coordinator after remote effects have
settled and reassert the retained repair's task, driver and lease effects. If those effects already
cancelled the retained ordinary pre-start task, maintenance rotates only that inventory-owned task
to a deterministic replacement identity, clears its confirmed stage mappings, reacquires the
released lease under a new fence and registers the replacement once. Started, completed, movement
and capital work is not reopened. A missing local task-board version never erases a predecessor
repair's stable external task identity: maintenance must read current owner truth first, persist the
live fence before cancellation and accept only an explicit owner `404` as terminal absence. This
prevents a corrected inventory outcome from superseding the local repair while its old WorkerApp
task remains active. Media likewise requires
the exact prior photo set and keeps its stable gallery folder while advancing association metadata
and the watermark. Its SERVICE-only completed-outcome path may use a retained checkpointed finding
proof through a later `VERSION_GAP` only when the gap starts strictly after that proof; this does not
resolve the quarantine or reopen public upload/read authority. A cabin quarantine, another finding
quarantine reason, or a gap at or before the retained proof remains a conflict.

Every intent in one completed plan carries the same durable reapplication
generation. A scheduler retry or recovery from an unknown remote result keeps
that generation and therefore the same owner-local idempotency keys. Only the
explicit completed-history command advances it, once for the whole plan, so
all owner services deliberately reassert the same immutable inventory source.
Asset preserves an active operation lease on that same-source reassertion
because it can already belong to the exact inventory repair; maintenance and
logistics supersede unrelated predecessor work and release its lease using the
stored owner and fencing token.

The plan-wide logistics request is immutable local state, not a value rebuilt by each finding. Its
canonical body, SHA-256, generation-stable idempotency key, attempt count and lease/retry/result
state are stored before remote I/O. The first eligible finding claims and applies it in a short
transaction; every other finding observes the shared success. A semantic owner `4xx` blocks the
effect with its bounded Problem Details code, while transport failure releases it for stable-key
retry. This prevents an `N`-finding plan from generating or dispatching the same `N`-row command
`N` times.

A MANAGE command on completed inventory history fences the exact session revision and final-plan
version/SHA. Before rebuilding durable work it restores every inspected explicit observation that
the obsolete automatic-membership rule deactivated and omitted from the plan. The correction
copies every existing entry, manager choice, date and order unchanged, appends the restored rows to
a strictly newer completed version, replaces frozen statistics and emits a finding-owned restoration
fact without reopening completed media authorization. Completed inventory is authoritative, so the
correction needs no maintenance preflight and performs neither remote I/O nor downstream mutation.
Media V15 changes only its finding-inbox constraint so the restoration ordering fact can advance
the existing checkpoint; retained public owner proof remains inactive.
It then creates
missing outcomes and requeues every existing publication in the corrected plan, including a prior
`SUCCEEDED` row, because an older success cannot prove that later media, logistics and
maintenance-cleanup steps were applied. Publication attempts and responses, finding revisions,
photos and media-service/MinIO objects remain evidence; ordinary schedulers replay the immutable
plan through owner-local idempotency receipts.

Maintenance owns frozen-plan allocation. Every stage first consumes at most one
matching catalog work line, then the remaining work for that exact routing
queue, while materials select their direct or closest matching routed stage;
each line can be consumed only once. Inventory exposes the recorded routing
snapshot and panel/ManagerApp mirror that deterministic allocation for editing
and readback. A custom-only stage therefore requires an explicit repair or
holding queue and does not borrow a catalog work from another stage.

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

Immediately before every durable queued inspection, ManagerApp reads the active
finding and then the session revision. If inventory-service returns `409
INVENTORY_VERSION_CONFLICT` with detail `Inventory revision is stale` after
that preflight, the client performs one new read/fence/save cycle. An initial
`NOT_INSPECTED` finding may adopt its current revision; a supplement or
replacement must still match its original finding revision exactly. An
in-flight source, departed finding, or newer server-side inspection fails
closed rather than being overwritten. The panel uses the same separation: it
refreshes the broad session revision immediately before save but retains the
editor's finding revision. These are client recovery rules only;
inventory-service keeps ownership of the command, revision checks and
inspection transition.

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

The effective-dated installation work schedule remains task-board-owned; Driver Up
shifts are a separate aggregate and do not define this calendar. The same
effective local date, shift, breaks and `daysOff` apply to every warehouse,
while each object's calendar projection retains its authoritative timezone.
Inventory's public planning settings retain only explicit inventory holidays.
It reads the bounded private task-board object calendar for every new plan or corrective appended entry, schedules any number of eligible
movement/repair items on the same earliest common working date, and validates
manual dates against that common calendar and ordering only—there is no
inventory daily-capacity or weekday throttle. Each new plan stores the exact
calendar range, effective schedule/timezone snapshot and fingerprint, and
completion rejects a changed current fingerprint. V28 marks active draft heads
`STALE`; completed historical plans remain untouched, while a correction that
adds an omitted observation uses the same common-calendar rule for the new
entry and retains existing historical dates.

A quarantined inbound movement is recovered without deleting or recreating its
repair. A warehouse `MANAGE` command may resume only the exact stable
`LOGISTICS / CREATE_DRIVER_TASK` intent when the repair is still ordinary,
queued and not started, and logistics proves that the corresponding driver task
is absent. The command preserves priority, works and materials, changes only
the inbound planning mode/date and reuses the original stable integration key.
If logistics reports any existing or uncertain task, recovery fails closed.

Evidence: [`services/inventory-service/`](../../services/inventory-service/),
[`inventory-service.yaml`](../../contracts/openapi/inventory-service.yaml),
[`InventoryCabinDispositionService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryCabinDispositionService.java),
[`InventoryCabinWriteOffService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryCabinWriteOffService.java),
[`asset-service.yaml`](../../contracts/openapi/asset-service.yaml),
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml),
[`media-service.yaml`](../../contracts/openapi/media-service.yaml),
[`InventoryApplicationService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryApplicationService.java),
[`InventorySessionService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventorySessionService.java),
[`InventoryFindingService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryFindingService.java),
[`InventoryReviewService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryReviewService.java),
[`FindingPlanSnapshot.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/domain/FindingPlanSnapshot.java),
[`V17__manual_capital_repair_selection.sql`](../../services/inventory-service/src/main/resources/db/migration/V17__manual_capital_repair_selection.sql),
[`InventoryUploadRevisionPolicy.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/uploads/InventoryUploadRevisionPolicy.kt),
[`InventoryIdempotencyService.java`](../../services/inventory-service/src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryIdempotencyService.java),
[`InventoryIdempotencyRecoveryIntegrationTest.java`](../../services/inventory-service/src/test/java/dev/buhanzaz/rwms/inventory/service/InventoryIdempotencyRecoveryIntegrationTest.java),
[`InventoryReadProjectionIntegrationTest.java`](../../services/inventory-service/src/test/java/dev/buhanzaz/rwms/inventory/InventoryReadProjectionIntegrationTest.java),
[`InventoryAssetOutcomeService.java`](../../services/asset-service/src/main/java/dev/buhanzaz/rwms/asset/service/InventoryAssetOutcomeService.java),
[`InventoryOutcomeService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inventory/service/InventoryOutcomeService.java),
[`InventoryAuthoritativeOutcomeService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryAuthoritativeOutcomeService.java),
[`inventory_cabin_photos.go`](../../services/media-service/internal/persistence/inventory_cabin_photos.go),
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

Global cabin monthly prices belong to logistics, not asset passport metadata. V98 seeds a
separate versioned singleton with positive whole-ruble overrides for asset-owned type/category
UUID pairs; every omitted pair means zero. No catalog names or cross-database foreign keys are
stored. The local store reads one consistent revision without writing and locks the singleton
before checking an edit's expected version; different tariff rows share that fence. Explicit
zero removes one override, unchanged values preserve audit/version, and missing storage fails
closed. Existing hold, fee and booking state is unaffected by this persistence addition. Evidence:
[`RentalPricingStore`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/pricing/service/RentalPricingStore.java),
[`V98`](../../services/logistics-service/src/main/resources/db/migration/V98__global_rental_pricing.sql).

New client/photo presentations freeze the exact monthly whole-RUB price and tariff revision
when the snapshot is created, after checking the pricing facts match its cabin version. Later
tariff edits never reprice an already-sent revision. Historical links have explicit null pairs,
not a zero/current-price backfill; delivery and other charges remain independent. A failed
price read prevents publication. Evidence:
[`ClientPresentationService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/ClientPresentationService.java),
[`CabinPhotoPresentationService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/photo/CabinPhotoPresentationService.java),
[`V99`](../../services/logistics-service/src/main/resources/db/migration/V99__presentation_rental_price_snapshots.sql).

Public return, shipment and transfer list reads preserve their array payload but
are bounded to `page/size` with a maximum of 100 documents. Pagination metadata
is carried in fixed response headers, and all lines for one document page are
materialized by one batch query rather than one query per document.

The CustomerApp boundary accepts only the exact `USER/CUSTOMER`,
`customer.rental`, `rwms-customer-android` token combination. One auth subject
creates one individual, sole-proprietor or legal-entity logistics profile and selects an available
warehouse. Availability requires an active warehouse-service identity with
complete in-range coordinates other than exact `0,0` and either the
representative flag or an explicit ordinary
warehouse entry in logistics' fail-closed delivery-depot registry. A
representative warehouse therefore needs no duplicate registry entry.
Warehouse-service owns every route-origin coordinate, and the selected UUID is
immutable for the logistics-owned rental session. Asset-service remains
authoritative for `FREE` status, own-session holds, photos, equipment
catalogue/balances and hold conversion. The card projection is least privilege,
deliberately has no cabin-dossier transition and omits nullable legacy facts
instead of failing the complete page.

The client type is normalized domain data, never inferred from a display name.
New `SOLE_PROPRIETOR` and `LEGAL_ENTITY` clients share the required
contact-person invariant, while `INDIVIDUAL` does not. Logistics V86 expands
the live constraint for new sole proprietors without undoing V43's historical
reclassification or rewriting existing client rows.

The profile's entity kind, auth subject and rental-client identity are immutable. Mutable contact
and display fields use the profile version fence and update the existing logistics client projection
in the same local transaction. An avatar is not stored in logistics: the first validated warehouse
fixes an immutable media authorization scope, logistics establishes a deterministic
subject-bound `LOGISTICS_CUSTOMER_PROFILE/PROFILE_AVATAR` proof, media-service owns the image and
variants, and the profile may bind only an exact current `READY` media generation validated for that
profile UUID, warehouse and customer subject. Manager/worker or another customer subject cannot use
that owner.

The cart owns one initial rental duration for every currently selected cabin.
Its complete term set is optimistic-version fenced, initializes a newly selected
cabin to one month, is pruned when cabins are removed and invalidates an earlier
delivery slot when changed. Checkout completes a pre-existing unfinished cart's
missing term entries with that same one-month default and then carries one exact
duration per held cabin through the presentation into the ordinary rental order.
CustomerApp renders one month for a temporarily absent local term entry and
uses that identical valid default for confirmation-button eligibility; the UI
cannot display a valid duration while silently disabling checkout.
A `BOOKED` customer session is terminal: its inquiry identity and booking remain
durable, while facets, selection and cart reads return `409 INQUIRY_ARCHIVED`.
CustomerApp may create another warehouse-bound inquiry, but neither the old
session nor its booking is reused as mutable cart state.

The exact customer may still perform two explicit pre-start booking lifecycle
commands. Cancellation first persists a recoverable mutation and fences
competing order changes; the remote order cancellation runs outside its short
claim transaction, and confirmed slot capacity is released only after the
durable cancelled result. Bounded PostgreSQL-time leases, backoff and quarantine
recover an uncertain effect without a second order cancellation. Reschedule
search is booking-scoped; confirmation locks old/new warehouse dates in stable
order, revalidates the replacement offer and workload fingerprint, then changes
the order date, swaps confirmed slots and stores the replay receipt atomically.
Both commands require the current session version and stable idempotency key;
another subject receives no booking disclosure.

Customer delivery capacity exposes explicit `FIXED_WINDOW` choices
`09:00-12:00`, `12:00-15:00`, `15:00-18:00` and one `DURING_DAY` choice over the
configured warehouse-local delivery day. All four choices pass the same route
and capacity planner; `DURING_DAY` lets that planner choose the feasible arrival
inside its broad non-null hold bounds rather than creating a hard sub-window.
Logistics asks private Valhalla
for a directed truck matrix from the selected depot. It stores
`ceil(oneWayTravelSeconds / 3600)` as an informational travel band and prices
ordinary delivery by the first configured contiguous hourly warehouse tariff
that covers exact one-way travel. The last configured tariff is also the hard
delivery-acceptance boundary. Separate warehouse-owned exceptional polygons
then tighten that in-boundary result: `FORBIDDEN` rejects it, `NO_TRAILER`
forces the solo profile, and `SPECIAL_PRICE` replaces only the price. Within
one kind the smallest covering polygon wins, with source UUID as the stable
tie-break. No polygon or straight-line approximation replaces exact road
routing or extends the ordinary boundary. The planner evaluates the
complete local day rather than each window in isolation.
Available drivers and one- or two-cabin transport capacity come only from the
planner's anonymous active period shifts covering the exact warehouse-local date,
including start, end and break; no published shift means no slot. One driver
may serve several points across different hard windows or flexible day choices,
wait when early, return
to the depot to unload/reload and start another trip; warehouse operations and
the final depot finish must fit by 20:00. Directed matrix legs, per-shift cabin
capacity, site capacity, solo/trailer profile, service and travel buffers,
held/confirmed customer demand, generated delivery workload and active dated
shipment/transfer work all participate. Existing date-only shipment/transfer
conservatively reserves one driver for its whole day. Pickups are considered
only after deliveries on a return leg and are deferred when their service,
warehouse unload or a later trip would risk a delivery.
Remaining capacity is found by inserting additional cabins at the candidate
point/window into that same multi-driver schedule. Offered, held,
checkout-pending and confirmed capacity is durable and version-fenced. A cart
composition change detaches its previous slot. After a downstream booking
receipt is durable, `CHECKOUT_PENDING` continues consuming capacity until
terminal confirmation or release. Checkout reuses the ordinary saved-order
transition and creates stable per-cabin furniture tasks; a transport retry of
the same intent resumes with the original domain idempotency key instead of
creating a second order. The final hold, warehouse-capacity snapshot replacement and
every whole-day shipment/transfer create, replan, manual calendar move or
lost-response recovery share one warehouse/day transaction fence. A driver
reservation cannot commit inside the route fingerprint/hold window; concurrent
capacity writers are observed in one deterministic order.

Presentation booking and receipt-bearing customer checkout recovery are durable
queues rather than repeated first-page reads. A short committed lease fences one
worker, remote effects run outside the claim transaction, failed attempts use a
persisted two-second exponential backoff capped at five minutes, and the eighth
failure moves the row to operator-visible quarantine. Bounded `SKIP LOCKED`
claims let later due work progress around active or failing rows. Completion and
rejection require the exact unexpired lease and clear the active lease, due
schedule and quarantine metadata; presentation attempt count remains terminal
history. Fixed-name metrics expose backlog, oldest age and quarantine without
payload or free-form error labels. PostgreSQL time defines due, expiry and
terminal fencing decisions.

Rental-order cancellation and single-unit removal use another logistics-owned
durable command before their first asset effect. Unit release and furniture
reservation replacement have distinct deterministic idempotency keys and
receipts; the final order transition, public command receipt and recovery
completion commit atomically only after both required effects are proven.
Claims use PostgreSQL time, bounded `SKIP LOCKED` pages, five-minute token
leases and finite exponential backoff; the eighth transient failure and every
explicit permanent/configuration rejection enter quarantine. A pending or
quarantined command fences competing order and replacement mutations. Current
operations expose this state through logs and fixed-name metrics, but there is
no public requeue/resolve command; reviewed operator recovery remains a known
limitation. Exact idempotency replay returns the completed projection even when
another caller commits completion between the initial lookup and intent
preparation. Evidence:
[`RentalOrderMutationRecoveryService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderMutationRecoveryService.java),
[`RentalOrderMutationLocalStore`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderMutationLocalStore.java), and
[`V77`](../../services/logistics-service/src/main/resources/db/migration/V77__durable_rental_order_mutation_recovery.sql).

Every searched offer also freezes successful Valhalla public-road truck
routing, `siteCabinCapacity=1|2`, the applicable solo or trailer dimensions,
weight and axle profile, and the resolved isochrone tariff.
The customer route adapter preserves exact directed travel beyond one
32-point provider request by assembling bounded `32 x 32` matrix blocks. It
supports at most 128 online points, including the depot and candidate address.
Slot search delegates that ceiling to the route adapter and propagates its typed
workload-limit failure; a technical matrix bound cannot be represented as “no
available slots”.
Site capacity one splits a multi-cabin order into sequential solo-truck visits;
site capacity two merely permits a trailer and cannot override an absent truck
route.
The transport can retain provisional-date searches with `false` attestations for compatible
clients, but the current CustomerApp collects truck-and-trailer access on the private site and the
possible failed-trip acknowledgement in a modal before search. The final hold merges compatible
search-time attestations with its own command and rejects the reservation unless both are true; a
held slot without those attestations cannot enter checkout. The
acknowledgement is an audit fact only: no charge amount or billing transition
is inferred.

My Orders derives cabin arrival only from an exact non-cancelled shipment
document line that is an exact member of its grouped
`LOGISTICS_DOCUMENT/SHIPMENT` driver task in `COMPLETED`. One arrived cabin
may receive an idempotent bounded drawn-signature acceptance and immutable
missing-equipment, unsuitable-cabin or other problem reports before or after
acceptance. Media references must be READY generations owned by the same
shipment document/line/warehouse. Logistics owns those acceptance/problem
facts; media-service owns bytes and admits the exact CustomerApp subject only
after a subject-bound logistics owner proof.

A warehouse-card historical rental command is a logistics-owned factual import,
not an asset-status edit. It creates one normal, `historicalRentalImport`
shipment or return document for a visible client, a current cabin-version fence
and a non-future warehouse-local date. A shipment may retain a complete selected
driver snapshot/worker identity pair, while a null pair means unknown; a return
rejects driver data. Neither variant creates a route or driver task. An imported
shipment first calls the private maintenance closure boundary: ordinary
repair becomes system-completed and eligible pre-start capital/movement work is
cancelled with `Автоматически закрыто в связи с отгрузкой.`; started or stale
work remains a conflict for reconciliation. Maintenance releases the fenced
cabin to `FREE`, then logistics performs its ordinary shipment lease and
`RENTED` effect. When maintenance instead proves that a repair-free cabin is
already `RENTED`, it returns `ALREADY_RENTED` with the unchanged asset version;
logistics reaches `SHIPPED` without another lease, status transition, hold or
driver effect. An imported return instead enters the ordinary fenced intake
from `RENTED` to `INSPECTION_REQUIRED`, so estimates and repairs retain their
existing owners and commands.

The historical command writes the exact `CREATE_HISTORICAL_RENTAL_MOVEMENT`
operation into logistics' durable idempotency receipt. The Java invariant and
the database check admit the same value; V57 changes only that allow-list and
does not rewrite documents or receipts. The version-fenced public correction
command writes `UPDATE_HISTORICAL_RENTAL_MOVEMENT`, admitted by V58, and changes
only the client snapshot/reference, optional driver pair and warehouse-local
physical date of the same imported shipment. It never creates another document
or replays physical effects.

Operator cancellation is deliberately narrower than correction. Only an
imported shipment in `CONFLICT` or `RECONCILIATION_REQUIRED` may enter it, and
logistics first rejects any completed or unknown shipment asset-confirm effect.
Known acquired holds/leases are released through their durable capabilities. A
lease acquisition with a lost response is replayed under its unchanged
operation ID, then its exact same-owner lease is released even when natural
expiry has already made it terminal; a proven acquire rejection needs no
compensation. Open reconciliation rows are retained with the operator identity
and resolution reason, while a successful imported shipment is never converted
back into cancellation.

In the absence of a live logistics
shipment, the panel may label legacy passport facts `Отгружена` only when both
shipment date and a non-blank tenant exist. This is a read-only compatibility
projection; any live logistics document remains authoritative.

Rental counterparties are logistics domain records, not OAuth clients. Every
new client has a normalized required phone and an authenticated responsible
manager; a legal entity also requires a contact person. Reads are
manager/warehouse scoped, and an inaccessible duplicate is reported as a
generic conflict without disclosing its identity. Client-owned and order-owned
additional name/phone contacts remain separate from the primary contact and
are combined only in a deterministic driver/task snapshot. Rental-order create
and ordinary manager edit commands carry only the client, primary phone and
comment. Order state may retain one to five distinct date-only preferences from
supported historical facts. A normal public presentation confirmation owns the
delivery address, optional complete coordinate pair, order-owned additional
contacts and positive rental duration; nullable contacts normalize to an empty
list. For a current `NORMAL` public presentation,
the server advertises exactly the warehouse-local dates `today+2` through
`today+5`, and confirmation accepts one to four dates only from that advertised
set. These are requestable preferences, not a capacity reservation or promise.
`REPLACEMENT` advertises no dates and accepts none. Desired-delivery windows remain order-owned
read state: legacy physical time columns can retain old values, while the
dedicated CustomerApp projection exposes only its confirmed exact slot. A
draft can save without client delivery facts, but rental shipment creation
requires the confirmed address, primary
phone and desired day. Wishes remain advisory: actual document `scheduledDate`
is separate and may fall outside it.

The assistant-facing cabin boundary remains logistics-owned. Facets expose
current characteristics and exact type-to-dimension relations; catalog lookup
is read-only. Clarifications advance in one sequential conversation. An
inquiry may retain its assistant conversation or be manual with no hidden chat;
either can target the same `DRAFT` or normally editable `SAVED` order and is
rediscoverable by order. The order warehouse is fixed by its first selected
cabin and then constrains search, presentation and confirmation. Selection
replacement stores a durable exact-byte command receipt before calling
asset-service. Removing items sends the complete retained set, which releases
removed holds immediately and gives retained holds a new settings-derived
expiry; an empty set releases the whole selection.

Furniture requirements are logistics-owned per order cabin, while equipment
catalogue, physical contents, shared availability, per-cabin maximums and
reservation effects remain asset-owned. A normal presentation exposes every
active equipment row even at zero global availability, the atomically held
cabin contents and only true unassigned same-order physical surplus. One asset
transaction converts the selected holds and replaces the authoritative
all-order per-cabin composition; concurrent channels therefore consume one
shared pool. A rejected booking can republish the same inquiry as a new
revision; pending and completed bookings remain fenced. The same durable
`PresentationBooking` receipt stores normalized `NORMAL` delivery facts and a
positive client-selected initial rental duration only. Its order transition
creates terms only for newly converted cabins, includes the chronologically normalized dates, duration,
address, coordinates and contacts in replay checksums, and never rewrites a
pre-existing cabin term on replay. A `REPLACEMENT` confirmation rejects all of
those normal-only values and preserves current order wishes/terms. Shipment term
assignment derives `returnDate` from actual shipment date plus that duration;
the retained extension command is available only for selected shipped cabins.
Order read permissions expose the server-derived extension affordance separately
from ordinary editability so `FULFILLED` orders can still show the supported
extension action.

A cabin photo presentation is a logistics-owned public capability, not a new
media owner or a mutable cabin projection. Creation requires warehouse EDIT,
the current asset-owned cabin version and a subject-scoped idempotency key.
Media returns the full logical active-folder image count separately from its
bounded READY references. Logistics freezes the ordered set of one to 100
media IDs and generations plus the cabin-number snapshot only when those two
cardinalities are equal; a processing gap or an over-100 folder fails closed.
Older folders remain in the full CABIN archive and are excluded from a newly
created presentation. Exact replay returns the same record and
different bytes under the same key conflict. The signed public token and stored
row do not expire. Anonymous metadata exposes only cabin number, creation time
and presentation-scoped photo URLs, while every SMALL/LARGE byte read must match
the frozen media ID and generation before logistics proxies the private
media-service content. That private service-only read resolves the retained
canonical cabin association and exact frozen variant generation, then reads its
pinned object version; a later media generation or soft deletion does not
invalidate an already signed presentation. Current gallery and presentation
creation semantics do not change. The panel's public `/photos/{token}` page
owns no domain state and reveals no warehouse, client, passport or object-storage
locator.

The optional standalone warehouse planner does not move order or warehouse
ownership. With exact machine scope `logistics.planning`, it reads active
canonical warehouse identities, warehouse-qualified driver identities and a
warehouse/date-bounded feed of SAVED, not-yet-shipped cabin units with only the
planning facts it needs. It has no RWMS database access and never fabricates a
local warehouse or worker when a directory dependency fails.

For a representative warehouse the planner evaluates local staff shifts, active
operational assignments and every calendar-eligible support link. A support candidate uses directed truck-road time from its
real origin, vehicle/trailer capacity and the complete served-day route; the
resource cannot open a slot before arrival, warehouse operations and the
configured buffer. `CROSS_WAREHOUSE_SERVICE` keeps the driver's base and
operational warehouse unchanged and may include a useful-cargo transfer draft.
An explicit transfer `RESOURCE_REPOSITION` instead activates the destination
assignment only after physical arrival. Candidate evaluation is side-effect
free and exposes structured reason codes; inventory, driver and vehicle holds
remain confirmation effects.

Vehicle relocation has its own logistics-owned aggregate rather than changing
the planner catalog's `warehouse_id`. Confirmation persists separate trip and
reposition roles with optimistic versions and stable per-vehicle transaction
locks. `PLANNED`/`IN_TRANSIT` rows reserve travel, `ACTIVE` is the factual
destination placement, and completed/cancelled rows remain history. A temporary
placement resolves back to its source after its bounded end; a permanent
placement stays at the destination until a later reposition actually arrives.
The planner consumes only the live chain closure and fails closed on a duplicate,
terminal, overlapping or topologically impossible payload.

The planning feed preserves two independent warehouse facts: the regional
`serviceWarehouseId` and one exact physical source for every reserved cabin.
That unit/source mapping participates in `sourceRevision`, while `orderVersion`
remains the optimistic order fence. A confirmed plan may publish one indivisible
task slice only when all cabins in that slice share a source. Shift publication
also carries the proven route origin and exact support-link identity; the
logistics owner rechecks the current reservation locations and dated support
calendar before creating shipments. It then builds the ordered planner-stop and
positioning operation snapshot and publishes it only after every route
assignment for that driver/date succeeds or replays. Candidate evaluation still
has no inventory side effect. Immediately before publication, logistics-service may enrich an exact
positioning leg only with confirmed, reserved and workflow-ready transfer plans whose driver,
vehicle, endpoints and planned instants all match. The resulting `sourceTransferId` operations still
do not themselves mutate asset location. Actual mutation remains in the transfer aggregate: the
assigned transfer task becoming current drives version-fenced departure, and completion evidence
drives factual arrival through a retry-stable durable relay. Logistics does not infer an executor for
a warehouse-pool transfer whose canonical driver identity is absent.

Task-board also owns a city-specific hired-company contact catalog with INN,
contact person, phone, email, address and notes. INN is unique per home city.
Company creates replay a stable ID, edits/deletion require the observed version,
and warehouse VIEW/EDIT authorizes the public catalog. A contractor's optional
company must belong to the same immutable home city, enforced by a composite
database foreign key. Existing independent drivers remain independent. Deleting
a company with drivers is rejected; explicit profile editing can detach a driver
without deleting its trip history. Company contacts carry no dated assignments.

A contractor is a reusable task-board-owned on-demand workforce profile and not
a staff route candidate. It requires no known vehicle, capacity profile, shift,
cycle or profile-level availability dates. The standalone UI uses the planning
date selected in the header and may assign all supported unassigned work
automatically or an explicit set manually. Generated/local pickups remain
supported. Because the canonical assignment boundary has no contractor-pickup
command, a real RWMS pickup is excluded from automatic batches and rejected
before side effects in manual/direct handoff; supported deliveries in a mixed
batch continue independently. Each direct `CONTRACTOR_HANDOFF` stages one
durable immutable command while selected requests remain reserved in `DRAFT`.
External directory and assignment calls execute outside the database transaction
and reuse the command UUID as their idempotency key. The UUID includes both the
order version and planning source revision, so a changed unit/source mapping can
be retried after a terminal rejection without colliding with the old immutable
command. Full success finalizes the
requests and invalidates mutable plans; full domain rejection with no earlier
applied result releases reservations. A lost response, transient failure,
incomplete response or mixed applied/rejected result remains `PENDING`, keeps the
plan and reservations intact and is retried by a leased `SKIP LOCKED` worker.
The service therefore never reports an unproven assignment, releases a possibly
applied request, fabricates optimizer resources or converts generated demand into
RWMS.

After a complete real handoff, the standalone projection preserves the immutable
command UUID, exact task-board task IDs and a contiguous request sequence. Only a
complete, unique, single-worker `CONTRACTOR_HANDOFF` group can create a route
share, and only after the dispatcher explicitly requests it. Logistics-service
then owns an expiring/revocable capability whose immutable ordered bindings point
to those exact tasks; it does not copy the route state. Every read re-proves the
active contractor assignment in task-board. Before `START`, logistics checks all
bound tasks in persisted order and permits only the first route entry not already
`DONE` or `CANCELLED`; task-board applies the final version/idempotency-fenced
transition. Completion requires exact READY evidence reserved by task-board and
stored by media-service. Evidence identity, capture time, content type, declared
and actual length and SHA-256 are immutable replay facts. A capability never
grants contractor credentials, a staff shift, a vehicle/cycle or access to any
other task.

The same request projection retains the nullable confirmed delivery amount and
hourly tariff tier as one complete pair, with absence meaning not calculated.

The standalone planning workspace resolves one main warehouse plus its directly
served representative warehouses as a non-transitive dated group. Selecting
either endpoint opens the same root plan: regional requests retain their
`serviceWarehouseId`, while the optimizer evaluates local resources of every
admitted member plus every calendar-eligible support warehouse. A candidate
retains its physical origin depot and support-link identity; driver and vehicle
overlap is validated across variants. The day-plan aggregate remains rooted at
the main warehouse. Exact excluded dates override allowed dates, which override
the recurring weekday set. Generated/manual workload stays
simulator-owned and is never converted into an RWMS order, notification or
assignment.

A successful warehouse/date planning feed is the complete still-unplanned
snapshot for that inclusive range. Omission is therefore an authoritative
inactive transition only for mutable `READY`/`UNASSIGNED` RWMS projections:
the request and task identities, accepted dates, source payload and historical
references are retained while lifecycle becomes `CANCELLED`, and only mutable
plan heads are archived. Confirmed plan references and requests beyond that
planning boundary are immutable. Reappearance of the same source identity
restores the retained request/tasks without duplication. Presence is collected
before row validation, so one invalid source row cannot be retired as absent.

The dispatcher warehouse selector presents that ownership explicitly: one root
row followed by indented direct representatives, then every other authorized
routable warehouse, including representatives outside the active planning group.
Directory metadata adds labels but never grants workspace access.
The selected warehouse controls the visible context, while `RoutePlan.warehouse_id`
continues to control planning commands, depot labels and root-driver shift time.
Consequently, opening a representative never relabels a root-depot departure as
an arrival at the selected representative.

Planning-day policy and incident recovery extend this existing root plan rather
than creating another logistics owner. The root warehouse stores one mode for
the complete direct-representative group. An empty day accepts the constraint
without inventing a notice, customer action or move; a populated day retains
every conflicting task in the source revision while the standalone owner
persists the structured event, impact, notice, action, dispatcher decision and
one current recovery proposal. Delay outcomes use the proposal version as their
fence, and a recommendation never changes a customer promise by itself. Base
tasks are read-only, low-priority candidates evaluated for the calculated
warehouse-return time.

The day-plan incident UI displays recorded warehouse-local event time, resource
identity, reason and server recommendations. Fast incident buttons still require
explicit confirmation. An automatic-recovery `APPLIED` receipt invalidates the
affected plan queries; pending or failed proposals never produce a successful
replacement message. Changing the root warehouse or day discards only the
unfinished incident form, not a previously submitted server fact. See the
[`operations panel`](../../logistics/frontend/src/features/operations/OperationsPanel.tsx).

Changing the policy advances the planning generation and queues a complete
capacity publication for every group member. `DELIVERIES_ONLY` excludes pickups
from subsequent optimization; `PICKUPS_ONLY` excludes deliveries and makes
customer slot calculation, hold and confirmation unavailable while invalidating
older holds through the logistics-owned capacity projection. Applying a change
to an already published lineage uses logistics-service's durable recovery saga
and task-board's atomic `PREPARE`/`COMMIT`/`RELEASE` hold. Reschedule and factual
cancellation share the forward path `PENDING` → `PREPARED` →
`OWNER_COMMITTED` → `BOARD_COMMITTED` → `COMPLETE`; release is legal only before
the owner commits. Task-board retains the cancelled removed-task tombstone and
the remaining source lineage. Database uniqueness plus row/advisory locking
allow only one unfinished saga and hold per source plan, remote calls execute
outside long transactions, and local versions are rechecked after every
response. After `OWNER_COMMITTED`, retry can only drive the same operation
forward or quarantine it; it cannot silently restore the former customer or
board commitment. Evidence:
[`dynamic operations model`](../../logistics/backend/app/models/operations.py),
[`dynamic recovery workflow`](../../logistics/backend/app/services/dynamic_recovery.py),
[`PlanningPublishedRescheduleSagaService`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/PlanningPublishedRescheduleSagaService.java),
[`PlanningReplanHoldService`](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/PlanningReplanHoldService.java),
[`V85`](../../services/logistics-service/src/main/resources/db/migration/V85__published_cancellation_withdrawal.sql), and
[`V45`](../../services/task-board-service/src/main/resources/db/migration/V45__single_active_planning_replan_hold.sql).

Warehouse-service's canonical IANA timezone also owns every date-only default
and "today" comparison. Task-board external-task registration, panel shipment
and furniture scheduling, Manager transfer editing and the standalone planning
header resolve the relevant warehouse metadata before deriving a date. Browser,
Android and server-host calendars are not valid domain fallbacks. A standalone
warehouse switch clears the previous warehouse's date and workspace state until
the new local date is initialized.

The repository's fixture bridge is deliberately not another task owner. In
explicit development/test mode it translates only confirmed generated-only
route plans into the existing private task-board task contract, preserving exact
stop order and driver audience under stable UUIDv5 identities. It is dry-run by
default, rejects production targets and mixed sources, and can cancel only
version-matched pre-start tasks. No production planner, queue, identity or
offline-execution invariant depends on it.

Customer promises are intentionally narrower than planner candidates. A
non-representative warehouse keeps the existing fixed-window and full-day
capacity behavior. A representative warehouse exposes only `DURING_DAY`, and
that date is offered only from the same confirmed feasible capacity result.
Support edges and calendars remain planner topology; without a durable reserved
external-capacity token they cannot open a customer slot. A potential external
resource therefore never fabricates a shift, resource reservation, date promise
or guaranteed exact window.

The transfer's DriverApp/WorkerApp representation is a frozen execution
projection, not a second movement aggregate. Logistics derives exact cabin
characteristics, actual/required furniture differences, loose furniture, the
dispatcher comment and ordered load/travel/unload instructions from the
confirmed transfer and owner snapshots. Source-cabin media is resolved through
the existing media gallery and frozen as generation-aware references. When the
dispatcher selects active capital-repair cabins at the representative
destination, the same create transaction also creates a linked concrete-line
reverse transfer back to the main warehouse; the trip driver owns that return
task, but the outbound cargo is unloaded before reverse capacity is evaluated.
It stores canonical JSON beside the durable driver intent and registers the same
task-board `taskText`, `works`, `materials` and `comments` fields already used by
offline sync. Presentation rows never mutate stock; asset reservations and
transfer departure/arrival remain the only physical custody commands. For an exact assigned
transfer, the logistics-owned task relay connects those existing commands to task-board execution:
`CURRENT` starts departure, completion evidence freezes the source-side cabin cover, and arrival
completes before the local driver task can become terminal. Evidence:
[`TransferDriverTaskContentService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/TransferDriverTaskContentService.java),
[`CapitalRepairDriverTaskContentService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/CapitalRepairDriverTaskContentService.java),
[`DriverLogisticsTask.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/domain/DriverLogisticsTask.java), and
[`TransferPlanWorkflowStore.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/TransferPlanWorkflowStore.java), and
[`DriverTransferExecutionService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverTransferExecutionService.java).

The standalone dispatcher is a presentation client of that same transfer
aggregate. Its optional cabin checkbox captures catalog IDs and per-cabin
furniture quantities as a requirement while leaving `allocatedCabins` empty;
only the logistics-service confirmation transition may allocate and reserve
physical stock. Its planned-arrival preview is also side-effect free: the
simulator routes one source-local vehicle snapshot with the current zero-to-two
cabin `VehicleLegState` and returns exact road time. The selected route vehicle
is then persisted as the canonical trip vehicle; capacity still remains
server-validated by the existing route and transfer owners rather than by a
second browser calculator. Reverse capital-repair lines are sent as their own
top-level command component and never mixed into the outbound cabin requirement.
Evidence:
[`TransferDraftDialog.tsx`](../../logistics/frontend/src/features/transfers/TransferDraftDialog.tsx),
[`transfer-client.ts`](../../logistics/frontend/src/features/transfers/transfer-client.ts), and
[`routing.py`](../../logistics/backend/app/api/routing.py).

Opening a warehouse workspace reads one exact persisted warehouse-local date
without external calls or writes. Requests are keyset-paginated with a bounded
limit, total and UUID cursor; the browser discards late pages from a superseded
workspace generation and clusters map points in one GeoJSON source. A separate
PostgreSQL-fenced server worker imports each routing-ready warehouse's 31-day
RWMS horizon in bounded pages. Directory/import commits precede support-network
HTTP and the separate auto-planning transaction. A confirmed fixed CustomerApp
date carries its exact hard window; a confirmed `DURING_DAY` date carries null
planner bounds and remains a soft full-day option. Both carry the
site/trailer-access fact and informational depot travel band. The planner may
display the band, but only explicit hard windows and exact directed Valhalla legs decide
route feasibility. Closing request acceptance freezes demand and removes the
date from new slot capacity, but does not publish assignments. A separate
version-fenced apply accepts only the reviewed `CONFIRMED` plan, reuses the
logistics shipment transition and validates current order versions and
non-overlapping unit IDs. Read-only status and exact idempotent retry remain
recovery boundaries, not a routine exchange dialog.

When separately enabled, the planner queues one complete active capacity
snapshot for the selected warehouse in the same transaction as its local
capacity mutation. The local generation and publication cursor expose
`PENDING`, `PUBLISHED` or `FAILED` independently of the already committed
business change. A leased `SKIP LOCKED` worker retries only due current
generations with bounded backoff; delayed completion of an older generation
cannot hide newer pending work. The explicit endpoint remains an operational
reconciliation boundary rather than a browser-owned retry loop.
It contains generated delivery and return-pickup jobs, active period shifts and
that warehouse's complete hourly isochrone tariff list. Jobs carry exact window, service, quantity,
mandatory
and trailer-access facts; shifts carry stable identity, date range, local
start/end, break and vehicle capacity. A break must be shorter than the
actual daytime or overnight interval at the form, API, application, transport
and database boundaries. A shift period may cross a month boundary but never
exceeds 31 days; `end < start` ends on the next local day and equality is
invalid. An accepted local shift therefore cannot later be rejected only by
the canonical capacity consumer. Tariffs start at 60 minutes, advance in
contiguous 60-minute steps and carry one non-negative whole-ruble price each,
while exact truck legs remain authoritative. The URL
owns warehouse identity and the body carries no duplicate workspace identity.
Logistics-service replaces the projection idempotently and keeps it separate
from real slots and orders. A monotonic per-warehouse `sourceGeneration`
distinguishes a new prior-shaped workload from a delayed command and rejects an
older unaccepted generation. Assignment apply still accepts only `RWMS`
deliveries; generated/manual work and pickups remain planner-owned.

The server-owned ingestion worker refreshes from each selected warehouse's
local current day through day +30 independently of workspace reads. Stable
`(warehouse, RWMS, orderId)` identity and source-revision upsert semantics
preserve real RWMS demand independently of generated workload. The imported
exact window is a hard constraint; the source travel band is explanatory only.
Every route remains subject to exact Valhalla,
capacity, service, warehouse turnaround, later trips and shift-end feasibility.
The warehouse setting `allow_soft_overtime` makes only its configured
`soft_overtime_limit_minutes` feasible beyond a normal shift end; that time is
penalized and warned, and capacity publication extends the corresponding dated
shift by the same bound without crossing the local calendar day.

The automatic warehouse planner ranks delivery urgency independently from
pickup urgency and normally adds a return pickup after its outbound deliveries.
Automatic and manually edited cycles keep deliveries before pickups. A manual
move is clamped into the selected task's phase while preserving relative order
inside each phase, then the complete affected driver-day is rescheduled and
validated for load state, capacity, windows, service, trailer access, travel and
shift finish. A later
cycle in the same shift may unload, load new deliveries and leave again.
Dynamic-slot search checks insertion before, between and after existing
deliveries, a separate trip between existing trips, and every compatible
driver. It resimulates every later stop and final warehouse operation. A
multi-cabin order is split into deterministic one- or two-cabin trips according
to both vehicle and site capacity.

For automatically generated routes, return pickups are first tested after a
trip's final delivery. The bounded search compares zero, one and, where
capacity permits, both orders of a pair; warehouse unload, the next load and
every later trip are included. A pickup is
deferred whenever it risks an existing or newly confirmed delivery. Only after
hard constraints pass are candidates ranked lexicographically by minimum slack,
incremental travel, depot-trip count, useful pickups, distance and waiting.

The warehouse planner distinguishes an open acceptance day from a finalized
day without reserving return capacity artificially. An open-day pre-plan already
tries compatible pickups after singleton or full outbound deliveries and may
create a pickup-only trip after no remaining delivery candidate fits. Delivery
coverage remains lexicographically protected. The one-way date closure persists
per warehouse/date, recalculates only that date against the final request set
and removes the date's shifts from published customer-slot capacity. A repeated
close is idempotent. With RWMS sync enabled, each close
attempt applies assigned
RWMS-sourced deliveries from the same exact final plan/version; unassigned,
manual, generated and pickup tasks are excluded. The automatic draft is replaceable, but confirmed or manually
changed plan versions are archived rather than deleted. For every state, one
driver's later trip starts only after the prior depot return plus warehouse
turnaround and the configured route buffer.

The visual map has separate default-off truck-road layers for every configured hourly tariff
for connected warehouses and for the one explicitly selected request or
slot-check point. A disabled layer makes no contour request. Exact directed matrix legs,
not polygon containment or an isochrone intersection, determine delivery and
slot feasibility. Slot responses therefore omit contour/intersection polygons;
missing visual contours never produce a fabricated circle.
The private graph is one source-manifested union of the Central and Northwestern
Federal District extracts; the derived restriction overlay deduplicates any OSM
object shared by their boundaries under the same `OSM_DATA_VERSION`. Selecting
a warehouse marker does not move the common map or change the active workspace.
The explicit “go to warehouse” control activates that exact warehouse UUID and
recentres it when requested, so several depot markers can be
compared without forced zoom. Other warehouse markers remain available for
navigation. Capacity, hard windows, truck-road availability, load
state, warehouse operations, later trips and shift end remain decisive. This
planner-only rule does not change RWMS order or assignment ownership.

A planner customer stop's `planned_arrival` is its actual service start.
When a later window would create idle time, the heuristic first shifts the
complete routed prefix at the warehouse while preserving every earlier window.
Only the configured residual wait may remain between customer stops
(`max_customer_wait_minutes`, 120 by default). A larger forced gap makes that
combination infeasible so the tasks can be assigned to separate warehouse
cycles. Exact Valhalla legs use the resulting actual departure timestamps.
The configuration editor lives in `/admin/logistics`; day-operation history
is opened on demand from the logistics day plan. Planner configuration has
an isolated administrative boundary under
`/api/logistics-planner/v1/admin/warehouses/{canonicalWarehouseId}`. It requires
the `rwms-admin-web` client, `SYSTEM_ADMIN` and `admin.manage`; private planner IDs
are never accepted as canonical warehouse identities. Settings and the complete
isochrone tariff ladder share one observed warehouse version. Exceptional map
policies retain their own version fence and idempotent create receipts, and
representatives keep their own settings. The existing planner owner handles
capacity publication after commit and invalidation of mutable policy-stale plans.
Evidence: [`admin settings API`](../../logistics/backend/app/api/admin_settings.py)
and [`canonical contract`](../../contracts/openapi/logistics-planner-service.yaml).

The planner timeline starts at the earliest assigned shift, keeping every
pre-cycle warehouse wait seekable as `WAITING_SHIFT` at the depot.

Every planner READY request eligible for the selected day requires two
explicit dispatcher facts before planning: either one positive service window
or a soft full-day choice on that accepted date, and whether a truck with its
trailer can reach the address. Full-day input keeps nullable bounds instead of
inventing an interval. A
negative answer converts automatic transport parts to one cabin each and makes
every trailer-attached cycle visiting that address infeasible; a positive
answer admits that address configuration but cannot override the effective
truck profile or Valhalla/OSM road restrictions. The Plan page may request an
explicit one/two-cabin split and move a task between driver cycles, but the
backend remains the owner of the full capacity, ordering, window, shift,
overlap and truck-route validation before any versioned edit is accepted.
Changing the shared planning date from the Deliveries inspector preserves that
inspector section; changing a date is not itself a command to open the Day plan.
An unassigned `TIME_WINDOW_CONFLICT` exposes the same-day **Change time window**
action only when the planner supplied a nearest feasible instant. The action
reuses the existing request editor, while moving the request to another day
remains a distinct operator choice; either mutation returns to backend-owned
replanning rather than editing a route only in the browser.

The planner creates a missing pre-plan automatically after a complete
server-owned RWMS ingestion batch, generated-workload replacement and through an
idempotent ensure when the operator opens a date. It waits until every eligible
READY request also has a complete cargo profile and until one active
driver/vehicle shift exists. It then persists the existing heuristic's exact
driver, vehicle, delivery-first cycle, optional return pickups, service-start
ETAs and warehouse return instead of requiring a browser build command. A
current non-archived plan is preserved; an authoritative input mutation removes
only the affected stale date before the coordinator runs again. The group
workspace preserves that plan identity across pure reads. The ingestion worker
invalidates mutable root plans only after a failure-free import reports a
change; a partial import commits valid siblings but keeps the last plan until a
later complete batch can include every durable valid sibling. CustomerApp's
standard `09:00-12:00`, `12:00-15:00`, `15:00-18:00` choices do not restrict a
dispatcher-negotiated hard interval such as `09:00-15:00`. The persisted return
time is consumed as current-plan context by dynamic-slot search, which still
owns all later-trip and return-leg feasibility.
The browser fences accumulated workspace pages and cancels an older ensure
request when its date or group changes, so a late response cannot mix task
references from different exact-date generations.
It persists only presentation state: the exact warehouse, a warehouse-scoped
planning date and map viewport, selected application/menu sections, the shift
visibility filter, and map tools/layers. Operational aggregates are never
restored from browser storage. A representative context suppresses root-plan
map fitting, while generated-workload commands target the same planning root
that owns the visible group plan. The shift editor's overlap preflight mirrors
the bounded recurring/overnight interval rule for immediate feedback; the
backend's optimistic version, advisory locks and overlap validation remain the
authoritative command boundary.

The planner requests exact directed truck submatrices in deterministic
time-window and overlapping spatial partitions of at most 32 points instead of
allocating one full day `N×N` matrix. Matrix preparation and candidate
optimization use consecutive monotonic deadlines, each bounded by the
configured optimization duration. A completed matrix therefore cannot exhaust
the first exact candidate's search interval. A partially evaluated candidate
is discarded; on timeout only already fully validated best-known cycles survive.

Approving a valid warehouse plan creates one idempotent local test-message log
per assigned source request. It aggregates split visits and records the plan
date, agreed window, assigned quantity, arrival, driver and vehicle. Passport
details are included only by an explicit request preference; missing details
for any assigned driver reject confirmation before plan status or logs change.
No SMS, messenger, push provider or production RWMS notification is invoked.
Transient UI notifications remain visible for eight seconds by default, retain
a bounded in-memory history under the bell with an unread count, and can be
cleared together. The operator may change the duration in Settings.

The planner can atomically replace a bounded deterministic workload for one
warehouse. **Create workload** uses the explicit warehouse planning date and
generator settings. The generic generator supports a one-to-31-day horizon.
Replacement removes only generated requests whose preferred date is
inside the horizon and saved plans for those dates; manual/RWMS requests,
other dates and other warehouses remain unchanged. Any generation or road-snap
failure restores the previous complete state.

Generation uses stable external source IDs, and all point creation must produce
an exact truck route covered by the warehouse's farthest configured isochrone.
Generated deliveries
use hard windows round-robin `09:00-12:00`, `12:00-15:00` and `15:00-18:00`;
pickups use the warehouse workday and remain optional return-leg work. A request
stores its mandatory delivery/pickup choice; preparation details stay with the
request rather than the plan.

For RWMS demand, `orderVersion` remains the later assignment fence and a
separate source revision covers the complete exported planning snapshot. A
confirmed-slot or unplanned-cabin change may advance that revision without
fabricating an order version; different payload under one revision is rejected.
The feed has no cargo dimensions or mass, so a new cargo-less delivery receives
the warehouse's explicit standard-cargo profile, while measured enrichment is
never overwritten. New or changed demand invalidates route plans only on the
union of its prior and current dates; exact replay is a no-op.

Large matrices are reconstructed from bounded directed blocks rather than
falling back to mock distance. Each warehouse owns one to twelve non-negative
whole-ruble hourly isochrone tariffs; their minute boundaries start at 60 and
remain contiguous. Separate versioned exceptional policy state can reject an
in-boundary address, force the no-trailer profile or replace its price; it never
acts as ordinary coverage and never changes the exact-route requirement.

A distinct explicit operator choice may publish one selected unassigned
delivery as future `WAREHOUSE_DRIVERS` work. It carries no concrete driver, may
target tomorrow, cannot target the warehouse-local current day, and does not
expose other hidden `UNASSIGNED` shipments. The planner status read is derived
only from logistics-owned planner-created shipment documents and their local
driver-task projections. After a claim it returns the authoritative
`ASSIGNED_DRIVER` worker snapshot, but no ordinary document or customer contact.

Cabin replacement is not an ordinary edit. An authorized warehouse manager
may replace an exact pre-start unit directly with a nonblank reason, or publish
an exact-cardinality replacement presentation. A single ordered asset batch
swaps all reservations, and one local transaction transfers existing furniture
requirements and every affected document/task member in the same order. The
regional order's service warehouse remains immutable while an explicit
`inventorySourceWarehouseId` identifies the physical source. A different
source is accepted only through an active support link that permits inventory
and direct fulfilment; replacing the source atomically releases the prior cabin
and source-partitioned furniture holds before acquiring the new set. A shipment
contains one physical source and its driver route starts there; direct customer
delivery never creates a false receipt at the service warehouse. The
existing shipment-furniture checkpoint and equipment-movement task provide
crash recovery: unfinished old-cabin filling is cancelled before swap,
executing work blocks it, and completed physical contents create an exact
old-to-new move without releasing the order furniture reservation. Readiness
stays false until required movement work is done.

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
[`CustomerController.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/api/CustomerController.java),
[`CustomerDeliverySlotService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerDeliverySlotService.java),
[`CustomerDeliveryCapacityFence.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/capacity/service/CustomerDeliveryCapacityFence.java),
[`CustomerCheckoutService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerCheckoutService.java),
[`PresentationBookingService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/PresentationBookingService.java),
[`ClientDeliveryDatePolicy.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/ClientDeliveryDatePolicy.java),
[`RentalInquiryCabinSelectionStore.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/RentalInquiryCabinSelectionStore.java),
[`RentalOrderUnitReplacementService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderUnitReplacementService.java),
[`RentalOrderPlanningIntegrationService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderPlanningIntegrationService.java),
[`HistoricalRentalMovementCoordinator.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/HistoricalRentalMovementCoordinator.java),
[`LogisticsShipmentCancellationRecovery.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsShipmentCancellationRecovery.java),
[`HistoricalShipmentRepairClosureService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/HistoricalShipmentRepairClosureService.java),
[`CabinPhotoPresentationService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/photo/CabinPhotoPresentationService.java),
[`CabinPhotoPresentationTokenService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/photo/CabinPhotoPresentationTokenService.java),
[`V47__order_contacts_windows_and_inquiry_target.sql`](../../services/logistics-service/src/main/resources/db/migration/V47__order_contacts_windows_and_inquiry_target.sql),
[`V54__historical_rental_documents.sql`](../../services/logistics-service/src/main/resources/db/migration/V54__historical_rental_documents.sql),
[`V59__customer_app_booking_and_delivery_slots.sql`](../../services/logistics-service/src/main/resources/db/migration/V59__customer_app_booking_and_delivery_slots.sql),
[`V66__customer_delivery_slot_kind.sql`](../../services/logistics-service/src/main/resources/db/migration/V66__customer_delivery_slot_kind.sql),
[`V75__bounded_customer_booking_recovery.sql`](../../services/logistics-service/src/main/resources/db/migration/V75__bounded_customer_booking_recovery.sql),
[`planner workspace client`](../../logistics/frontend/src/api/client.ts),
and
[`V55__cabin_photo_presentations.sql`](../../services/logistics-service/src/main/resources/db/migration/V55__cabin_photo_presentations.sql).

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

The board's `currentDate` is authoritative warehouse-local time. Its public
date columns never precede that value: an overdue active task-board column is
folded into the current-date column without hiding or duplicating cards, and
public movement/capital scheduling rejects a past target. A bounded relay pass
may reopen only a generic dependency-failure `RECONCILIATION_REQUIRED` row
after reading the matching authoritative task-board task. It may bind an
already-created remote registration whose response was lost, then resumes only
the owner-reported lane/status. Compensation and business reconciliation codes
remain terminal, while a full bounded page advances the next-pass cursor to
prevent their rows from starving later recoverable work. This recovery changes
no domain owner and does not rewrite data directly.

Each newly scheduled shipment, return or transfer persists one logistics-owned
driver intent for the whole document, with a stable order trip number,
immutable document-line/cabin members and a client snapshot. One
warehouse-local setting caps every grouped trip at 1–100 cabins (the
compatibility default is one); a request above it is rejected before the intent
is created. No new line-derived task is created. Waiting historical line tasks
are cancelled before regrouping; any started member prevents conversion and
remains truthful history. The internal task-board priority is fixed, and
completion evidence is applied idempotently to every grouped cabin. Shipment
and return use assigned or unassigned audiences; transfer is warehouse-shared
and identity-free.

The owner projection retains structured trip facts including contacts, desired
and actual dates, cabin contents and movement/readiness state. The panel's
dated logistics board deliberately renders only grouped shipment/return cards
with that projection: it hides raw legacy cards, task/trip numbers and desired
dates, labels the actual scheduled day as `Дата выполнения задания`, and shows
actual cabin contents with exactly one final filling status. Drag-and-drop
moves only the whole grouped task. Logistics locks and verifies
the document is pre-start before the bounded version-fenced task-board call,
then synchronizes the document date and rental terms while retaining the desired
delivery date; a later status poll converges a lost local
confirmation. Audience and member order never change through board movement.

A task-board driver feed exposes an unstarted future `WAREHOUSE_DRIVERS` trip
only to a currently qualified driver. A same-warehouse DriverApp worker may
then preview its logistics-owned rich detail. The public claim command rereads
task-board owner versions; task-board revalidates qualification, moves the
existing entry to `ASSIGNED_DRIVER` for that driver, and logistics confirms its
projection. It does not perform task-board `TAKE` or start work. Same-driver
replay is idempotent; another driver's concurrent win, today's shared work, a
warehouse mismatch or missing qualification fails explicitly. DriverApp keeps
these candidates in a separate “Дополнительные
задания” section and can open the address/coordinates as a prefilled Yandex
Maps route before claim.

An exact `ASSIGNED_DRIVER` task is different from that shared pool: its driver
may retain a home warehouse different from the task's physical warehouse.
Task-board resolves the entry warehouse server-side for detail, action, content
and evidence ownership, while the JWT/offline lease stay anchored to the home
warehouse. Another driver and every identity-free remote pool task remain
invisible. The daily shift summary counts exact non-cancelled work for the
driver/date across physical warehouses; it does not infer participation from a
warehouse match.

Evidence:
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml),
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml),
[`RepairPlaceService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/RepairPlaceService.java),
[`MaintenanceApplicationService.java`](../../services/maintenance-service/src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java),
[`DriverQueueScheduler.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverQueueScheduler.java),
[`DriverTaskRelay.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverTaskRelay.java),
[`FutureDriverTaskClaimService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/FutureDriverTaskClaimService.java),
[`DriverBoardService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverBoardService.java),
[`DriverTripProjectionService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DriverTripProjectionService.java),
[`DocumentDriverTaskPlanner.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/service/DocumentDriverTaskPlanner.java),
[`ShipmentTaskSettingsService.java`](../../services/logistics-service/src/main/java/dev/buhanzaz/rwms/logistics/driver/settings/service/ShipmentTaskSettingsService.java),
and
[`V46__shipment_task_grouping.sql`](../../services/logistics-service/src/main/resources/db/migration/V46__shipment_task_grouping.sql).

### Media

`media-service` owns media metadata, upload/finalize, private stored payloads,
variants and processing state. MinIO remains private; other domains reference
media IDs and ownership contexts rather than object keys or credentials.

A WORKER media request must carry exactly one native task scope:
`worker.tasks` for WorkerApp or `driver.tasks` for DriverApp. Missing both or
combining both is rejected, and the existing worker, warehouse and media-owner
proofs still apply. Scope separation changes neither media ownership nor the
private-object boundary.

ManagerApp and WorkerApp normalize captured or selected still-image pixels to
their upright orientation, then durably encode exactly `SMALL`, `MEDIUM` and
`LARGE` WebP parts with an aggregate one-MiB ceiling. Only the app-private
original is rendered locally; it is not uploaded, and the app deletes that
original plus all generated parts only after every part and finalization have
succeeded and the media owner reports the asset as `READY`. Media-service
neither rotates, decodes nor recompresses these image parts. Its historical
`rotationDegrees` read field remains only for compatible display of old
assets, not as current media state to mutate.

Video uploads retain the immutable original and, after finalization, are
processed asynchronously into a bounded MP4 `PLAYBACK` derivative. The
derivative uses H.264/AAC, strips mutable metadata, fits within 1280x720 without
upscaling and remains subject to an explicit output-byte limit. Accepted image
objects and video playback are media-owned; clients resolve only a scoped READY
variant URL. A compatible legacy image source is pinned once and exposed
through logical variant aliases without a server-side byte transformation.
ManagerApp and WorkerApp bound both photo-level and part-level transfer
parallelism and do not retain a transfer slot while polling processing
readiness. The panel
renders a selected local original immediately, reports authenticated
content-transfer byte progress below it and withholds cover/delete commands
until the matching server item is ready; the blob preview remains disposable
UI state rather than media truth.

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
[`ImageUploadBundleEncoder.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/media/ImageUploadBundleEncoder.kt),
[`WorkerEvidenceBundlePreparer.kt`](../../worker-app/core-media/src/main/java/dev/buhanzaz/rwms/worker/core/media/WorkerEvidenceBundlePreparer.kt),
[`validator.go`](../../services/media-service/internal/auth/validator.go),
[`media upload API`](../../services/media-service/internal/api/server.go),
[`consumer.go`](../../services/media-service/internal/worker/consumer.go),
[`worker.go`](../../services/media-service/internal/persistence/worker.go),
[`video_transcoder.go`](../../services/media-service/internal/media/video_transcoder.go),
[`V12__video_playback_variant.sql`](../../services/media-service/db/migration/V12__video_playback_variant.sql),
[`V16__client_image_variants.sql`](../../services/media-service/db/migration/V16__client_image_variants.sql),
[`V11__bounded_media_processing_recovery.sql`](../../services/media-service/db/migration/V11__bounded_media_processing_recovery.sql),
[`upload_session_recovery_integration_test.go`](../../services/media-service/internal/persistence/upload_session_recovery_integration_test.go).

### Read Projections And Assistant

`dossier-service` projects cross-domain cabin activity and
`analytics-service` projects KPI/dashboard facts. Neither becomes the command
owner for source aggregates. `assistant-service` owns conversation state and
tool-call history, while rental availability and inquiry decisions remain in
`logistics-service`.

Assistant clarifications form one durable ordered queue per conversation. Only
the head is `PENDING` and visible; later `QUEUED` questions cannot be answered
or rendered until each preceding answer activates the next. An intermediate
answer parks continuation, while the final answer resumes the assistant turn.
The historical `branchKey` remains immutable metadata and does not grant an
independently advancing branch. The server, not the LLM or browser, validates
exact facet relationships before searching: a single compatible dimension may
be selected automatically, while multiple dimensions produce buttons.
Approximate six-metre input resolves only through the current 6x2.4 relation;
module and security-post choices are never guessed. Read-only reference lookup
by number or text reports current types, finishes, dimensions,
characteristics and linoleum without creating or renewing holds.

An order-linked assistant conversation does not depend on timely delivery of
the booking event to become reusable. Order-filtered list/create rechecks the
exact logistics inquiry/client/order links outside its local transaction;
terminal exact links are archived under the order fence and a fresh linked
conversation can open immediately. The later booking fact is fenced by both
the old conversation and inquiry IDs, so it cannot archive that fresh winner.

Evidence:
[`AssistantClarificationService.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantClarificationService.java),
[`AssistantTurnService.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantTurnService.java),
[`AssistantConversationService.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantConversationService.java),
[`AssistantConversationCreationStore.java`](../../services/assistant-service/src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantConversationCreationStore.java),
and
[`V6__order_linked_sequential_conversations.sql`](../../services/assistant-service/src/main/resources/db/migration/V6__order_linked_sequential_conversations.sql).

Dossier `PARTIAL` means that the requested cabin's active generation has
hidden or unresolved proven coverage. Globally unlinked facts, raw validation
failures and legacy DLT rows without exact cabin/generation proof stay in
operational recovery state but do not make every cabin partial. Resolving or
rebuilding coverage never deletes its audit evidence, and relay delivery state
does not substitute for projection completeness.

When `media-service` attaches a completed task's evidence to the cabin photo
library, its canonical `rwms.media.cabin-photo.v1` cover fact is journaled by
dossier. Only a non-null `taskBoardEntryId` creates the
`MEDIA_TASK_EVIDENCE_ATTACHED` cabin activity; a direct cover change remains
journal-only. The read API returns only the opaque media ID, generation and
task-entry ID. The panel resolves that reference through the public,
authorization-checked task-entry media scope, so neither dossier nor the panel
publishes an object-store path or signed URL.

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
[`DossierProjectionService.java`](../../services/dossier-service/src/main/java/dev/buhanzaz/rwms/dossier/service/DossierProjectionService.java),
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
