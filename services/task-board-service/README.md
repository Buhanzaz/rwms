# RWMS Task Board Service

[Русская версия](README.ru.md)

`task-board-service` owns the operational queue catalog, warehouse queue
projections, worker registry and groups, assignments, task-board state, worker
execution, evidence linkage, and KPI settings/evidence. Source domains may
request tasks through explicit private contracts, but they do not mutate task
state or workforce tables directly.

## Why it exists

Managers may suspend a taken ordinary task with version-fenced `tasks/{taskId}/suspend`
and restore it through `tasks/{taskId}/restore`. Suspension leaves the task `ACTIVE` with
`suspended=true`, keeps its complete route visible on the manager board, stops timers and
ends active/paused assignments while preserving evidence and immutable time history. The next
responsibility segment starts with the remaining budget (or the original budget if exhausted),
as when active work is returned after a group is disabled.
The unfinished route is excluded from worker availability, so another task can be taken.
Restore retains the same task and entry IDs without reassigning previous workers.
Both commands reject worker principals and do not cancel the source-domain repair or
emit terminal task/entry cancellation events. Older board-task events without `suspended`
mean `false`; new events carry the flag explicitly.

Maintenance, logistics, inventory, and managers all need operational work, but
one queue entry must have one owner for ordering, assignment, execution, pause,
completion, and history. Task-board centralizes that operational truth while
keeping source-domain facts and decisions with their original owners.

| Concern | Task-board responsibility | External responsibility |
| --- | --- | --- |
| Queue standard | Global definitions, warehouse bindings, ordering, capabilities, and usage references | Maintenance/logistics register only contract-defined references |
| Workforce | Worker classes, workers, groups, current membership, and credentials workflow | Auth-service owns credential material and token issuance |
| Operational work | Tasks, route entries, assignment, pinning, pause/resume/complete, and history | Source domain owns why the work exists and its aggregate state |
| Native execution | Separate driver/worker feeds, offline action leases, evidence reservation, SSE and transactional FCM invalidation | DriverApp and WorkerApp refresh authoritative REST state and upload media through media-service |
| Driver daily shift | Warehouse-local work date, preparation/closing state machine, inspection snapshot, defects, audit timestamps and media proof | Logistics supplies the reviewed driver/vehicle/day plan; warehouse owns identity/timezone; media owns bytes |
| KPI | Installation-wide display palette, work-schedule revisions, and emitted daily evidence | Analytics owns the KPI read projection |
| Warehouse lifecycle | Local operation marks, admission fence, draining blockers, exact-version readiness | Warehouse-service owns lifecycle state and admission decisions |

The service does not own users and roles, warehouse identity, repair or
logistics aggregates, media bytes, analytics projections, or gateway routing.

Every public warehouse-bound operation resolves the current warehouse identity
from warehouse-service before applying the signed principal's role, warehouse
grant, or worker-home rules. `SYSTEM_ADMIN` and `WMS_ADMIN` do not bypass those
warehouse authorization rules when a warehouse ID is supplied directly.

## Command and task flow

Flyway V53 adopts workers and groups that lack an event stream using a baseline at their
existing projection version. It preserves profiles, credentials and membership, emits no
business outbox event, and leaves existing streams untouched. Conflicting historical evidence
blocks adoption rather than replacing history. Subsequent workforce commands retain normal
version fencing.

```text
manager / source service
          |
          v
public or exact-credential private command
          |
          v
authorization + warehouse admission + idempotency/expectedVersion
          |
          v
task / route / queue-entry transaction
          |
          +--> append-only history + domain event + outbox
          +--> worker invalidation
          +--> Kafka fact for projections/owners

driver app --> primary feed/detail --> take/action/evidence reservation
worker app --> ordinary work + active slinger feed --> join/action/evidence reservation
           --> media upload --> media fact --> shared completion
logistics-service --> exact contractor task snapshot --> evidence reservation --> start/complete
```

A source-owned task uses a stable external identity so a retry finds the same
task instead of creating a duplicate. Mutable entry operations are fenced by
the contract-defined version and status. Route order, eligibility, assignment,
and terminal transitions remain server-owned.

Pre-start route replacement validates queue purpose against the stored task source before any
mutation. Only logistics driver tasks may use driver queues, and they require driver queues.

The private pre-start replacement treats only a value-identical complete snapshot as a no-op and
returns its current registration even if the caller retained an older task version after a lost
response. Any changed title, metadata or route still requires the exact current version and an
entirely unstarted task; it fails with a conflict otherwise.

The ordinary repair board is one aggregate warehouse view, not a calendar. Each manager queue
returns every unfinished `REAL` and `SHADOW` entry. Registration priority is already reflected in
the persisted queue position; reads do not sort by priority a second time. Active work stays first,
waiting real work follows in pinned/queue-position order, and future `SHADOW` entries follow. The
physical queue's `availableTaskLimit` (six initially) marks the first waiting real cards in the
manager's daily plan without truncating that complete manager response. The global queue definition
supplies only the initial value for a new warehouse projection. Afterwards the warehouse queue owns
both this count and `workerFeedEnabled`.
Maintenance routes are normalized to `SES -> welding -> exterior -> interior -> electrical ->
plumbing`. The first existing unfinished phase is `REAL` by default; absent or completed phases are
skipped, and every later stage starts as `SHADOW`. An `EDIT` user may version-fence a still-future
ordinary `WAITING` entry and explicitly switch it between `SHADOW` and `REAL`. A future `REAL` is
eligible for WorkerApp publication and parallel execution without completing the earlier ordinary
stage first; the warehouse queue switch, waiting-real plan and worker qualification still apply.
The earliest unfinished stage cannot be demoted, and an unfinished SES stage forbids making any
later stage available. Promotion restores the future entry's persisted place ahead of later
unpinned work and refreshes its media-owner proof in the same transaction, granting or revoking
the matching source-evidence read audience; a pinned real card stays ahead. Every `REAL` route gate
may be taken, while a `SHADOW` is never actionable.

A canonical SES stage remains the only executable card for its task until treatment finishes,
including for retained definitions whose historical queue type is `REPAIR`. The manager snapshot
contains its later read-only shadows so the panel can reveal the complete route explicitly;
WorkerApp still receives only the SES gate during treatment. The public board has no date selector,
cross-queue entry move, date swap, daily-capacity scheduling, or overdue rollover scan. A manager
may reorder unpinned `WAITING REAL` cards within their existing queue under entry and queue version
fences plus the observed target-card identity; active work, pinned cards, shadows and queue identity
do not move. The daily plan creates no dated schedule.
Dated driver and shipment planning remains on the separate logistics surfaces. These invariants are defined by
[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml),
[`TaskBoardReadProjectionService`](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardReadProjectionService.java),
[`TaskBoardFutureAvailabilityService`](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardFutureAvailabilityService.java),
and [`OrdinaryQueueAvailabilityPolicy`](src/main/java/dev/buhanzaz/rwms/taskboard/service/OrdinaryQueueAvailabilityPolicy.java).

The KPI display palette and work schedule form one installation-wide, version-fenced settings head.
No warehouse is selected for either setting.
One activated schedule applies the same effective local-calendar date, shift, breaks and days off
to every warehouse, while each operational clock interprets those values in its own
authoritative timezone. Saving keeps a revision in `DRAFT`; activation schedules it idempotently,
and a revision effective on the current UTC configuration date is promoted immediately. A future
revision remains `SCHEDULED` until its date, and a date before the current UTC date is rejected.
Activating a replacement for the same date retires the prior scheduled/active revision under the
existing receipt and shared optimistic-concurrency fence. This behavior is owned by
[`KpiSettingsService`](src/main/java/dev/buhanzaz/rwms/taskboard/service/KpiSettingsService.java) and
the [canonical contract](../../contracts/openapi/task-board-service.yaml).

Flyway V31 normalizes existing ordinary `queue_entry.queue_position` values into that aggregate
sequence and corrects the real/shadow shape of a fully waiting holding route. It does not rewrite
the append-only `domain_event` history. Replay comparison therefore accepts only `queuePosition`
and `entryType` differences from queue-entry tails recorded no later than the successful V31
cutover; every later event tail and every other field remain exact. The cutover is read from
`flyway_schema_history`, so a rebuild preserves immutable facts without weakening post-migration
drift detection. The rule lives in
[`TaskBoardReplayVerifier`](src/main/java/dev/buhanzaz/rwms/taskboard/eventing/TaskBoardReplayVerifier.java).

Flyway V33 fixes the six global column positions and reorders only active maintenance routes whose
entries are all still `WAITING`; started, paused, completed, logistics and non-maintenance routes
are unchanged. A durable projection-migration ledger lists the exact work queues and entries whose
event-sourced projection changed. Replay compatibility is limited to those aggregate IDs and
pre-V33 tails, and only to `sortOrder`, `routeIndex` and `entryType`; all later tails remain exact.

Flyway V34 adds `worker_feed_enabled=true` to every existing physical queue without changing its
stored plan count. A `MANAGE` user can change the switch and count for one warehouse queue. WorkerApp
receives no card from a disabled ordinary queue, including active work; in an enabled queue it never
receives a `SHADOW`, retains active `REAL` work, and receives only the first configured number of
waiting `REAL` cards. Native detail, media-reader proof and `TAKE`/`JOIN` repeat the same server-side
fence. Work-queue facts add the two controls compatibly, while replay removes them only when
comparing immutable historical facts that predate the addition.

Route stages inherit their worker access from the physical queue's worker-class bindings. The
manager board neither accepts per-stage audience selectors in route requests nor returns them on
`BoardEntry`; native `WorkerTaskDetail` still returns the effective queue-derived
`audienceSelectors` used by WorkerApp and DriverApp.

Logistics driver tasks additionally carry one persisted audience:
`UNASSIGNED`, `ASSIGNED_DRIVER`, or `WAREHOUSE_DRIVERS`. Only the exact
logistics-service driver-task source may set it. Only assigned work carries a worker identity; that
worker must be active. Staff workers must have the primary qualification of the target driver
queue. An exact active `CONTRACTOR` assignment is the narrow exception because contractor profiles
have no staff qualification, group or app credential; contractors never become candidates for the
identity-free `WAREHOUSE_DRIVERS` pool. An exact `ASSIGNED_DRIVER` may retain a different home
warehouse; identity-free work remains same-warehouse only.
Task-board ignores a caller-supplied display name and stores its authoritative worker snapshot.
Unassigned tasks remain dispatcher work. An assigned task is visible only to that driver. A waiting
identity-free shared task is visible to every qualified warehouse driver until one takes it, after
which only the actual assignee retains access. DriverApp receives visible primary work from both
`SCHEDULED` and `CURRENT` lanes through `/api/driver/v1/**`, so its dated screen can show assigned
future work, exact cross-warehouse assignments and shared future candidates. Remote detail,
actions, evidence ownership and task content resolve the physical warehouse from the server-owned
entry; the worker/JWT home warehouse is unchanged. WorkerApp receives ordinary work and only active
`CURRENT` secondary logistics work through `/api/worker/v1/**`; scheduled driver work never leaks
to the slinger surface. A driver take persists a `TASK_JOIN_AVAILABLE` push for eligible
slingers. The waiting logistics task is announced only on the DriverApp SSE surface; WorkerApp
receives neither a pre-take `NEW_TASK` nor a cross-surface entry ID. A slinger joins from the
current group; if that worker was executing another group task, task-board pauses the whole
previous entry and resumes it after the shared task closes. Every
logistics-driver secondary binding is optional for native execution and remains interrupting when
the slinger joins. The driver may close before a slinger joins; once joined, either active
participant may close with at least one READY result photo from either participant. A group-less
primary assignment never becomes a secondary assignment, even when that driver also has the
slinger qualification. Only the
private source replan boundary may replace audience under the shared task/entry version fence; the
public ordinary board exposes only same-queue waiting-card reorder, never logistics replanning or a
cross-queue move.

The existing DriverApp SSE subscription remains home-warehouse scoped. Exact remote work still
converges through the authoritative REST feed: DriverApp's opaque revision uses the maximum value
from the one global revision sequence, so any warehouse mutation changes the comparison token.
That conservative token can cause an extra refresh for an unrelated warehouse, while response data
remains exact-audience filtered; a remote event does not yet produce a targeted home SSE item.

The exact logistics-service private driver directory resolves workforce ownership on every read.
For one warehouse it returns only `{workerId, displayName}` for active workers whose active primary
qualification matches that warehouse's active logistics-driver queue and definition, ordered by
normalized display name and then UUID. Secondary bindings, inactive workers, queues, definitions or
qualifications are excluded; login, group, contact, credential and other personal fields never
cross this boundary.

The private contractor-execution boundary accepts only the exact logistics-service SERVICE
credential with the sole `task-board.logistics` scope. Every read and command proves source client
`logistics-service`, source type `LOGISTICS_DRIVER_TASK`, `ASSIGNED_DRIVER`, the exact active
`WorkerEmploymentType.CONTRACTOR`, and exact route membership. Its snapshot contains ordered
entry/status/version facts, worker-visible title, description, unit, task text, works, materials,
comments and immutable source-media identities, generation and content type. It contains no phone,
credentials, general board, unrelated tasks or bearer-only media read paths. Task-board has no
separate structured client address/coordinates on this boundary; an address is available only when
the source already supplied it in worker-visible text. `START` and `COMPLETE` delegate to
`TaskBoardWorkerExecutionService`, preserve route order and optimistic entry versions, and share
the native ready/selected result-evidence invariant. The stable operation UUID uses the existing
immutable `worker_action_receipt`; the response returns the changed entry version for the next
command. While that exact route entry is `IN_PROGRESS`, the same private boundary may reserve a
result-evidence identity through the existing `worker_task_evidence` and entry-owner-proof
pipeline. Task, route, warehouse, worker and logistics source facts are server-derived, every
request and replay re-proves the live contractor assignment, and native offline leases are neither
accepted nor issued. The response exposes only owner and declared media metadata needed for a
mediated upload; it contains no upload/read path. No schema migration is required.

External task registration never invents a global Moscow or server-calendar
date. An explicit `scheduledDate` remains authoritative; otherwise the
deadline instant, or the injected server instant when no deadline exists, is
converted with `WarehouseTimeZoneGateway` for the task warehouse. The same
warehouse-local date decides whether a scheduled task emits today's worker
availability notification.

## Driver daily shift lifecycle

Task-board is the authoritative owner of one Driver Up shift per
`driverId + workDate`. Logistics first registers its reviewed driver/vehicle
plan through a stable source-shift identity; only then may the driver's startup
read freeze that plan into a shift. The startup transaction loads the current
warehouse identity and IANA timezone from warehouse-service, applies the
configured 06:00 warehouse-local boundary, locks the matching plan, and creates
at most one shift. Repeated or concurrent reads return the same aggregate.

The registered plan may add one contiguous operation list. Task-board validates
sequence, temporal order, endpoint identity and the load chain, then stores the
operations under the replaceable plan in
`driver_shift_route_operation`. A newer source plan may replace the complete
list only before a real shift freezes the plan; afterwards both plan and
operations are immutable. `GET /shift/today` returns the ordered snapshot with
planned arrival/departure instants and load transitions. Local routes remain
compatible with an omitted/empty list, while a cross-warehouse snapshot must
include origin start, inbound positioning and return positioning around its
service-warehouse/customer operations.

The immutable vehicle snapshot may add its exact effective `cabinCapacity`. Transfer cargo uses
paired `TRANSFER_LOAD`/`TRANSFER_UNLOAD` operations with one canonical `sourceTransferId` at the
route origin and inbound destination. Task-board rejects duplicate or unbalanced identities,
reversed load changes, wrong endpoints and a per-leg cabin load above capacity. A furniture-only
transfer is still a physical pair of operations but intentionally leaves the cabin load unchanged.
Legacy plans may omit capacity only while they contain no transfer-cargo operation.

Published-plan recovery reuses the same task and shift aggregates through three logistics-only
commands: `PREPARE`, `COMMIT`, and `RELEASE` below
`/internal/task-board/v1/logistics/planning-replan-holds/**`. `PREPARE` locks the complete source
membership and exact task, entry, shift, warehouse, date and version fences. `COMMIT` atomically
stores the removed member's lineage tombstone and replaces every remaining task/shift revision;
`RELEASE` is valid only while the hold is still prepared. An owner-cancelled removed task is
accepted only as the exact pre-start `CANCELLED/SCHEDULED/CANCELLED` task/entry pair and is not
cancelled a second time; every remaining member must still be `ACTIVE/SCHEDULED/WAITING` and
unassigned. Flyway [`V44`](src/main/resources/db/migration/V44__published_plan_reschedule_hold.sql)
owns the hold and tombstone fields, while
[`V45`](src/main/resources/db/migration/V45__single_active_planning_replan_hold.sql) permits only one
prepared hold per source lineage.

The DriverApp JWT continues to identify the worker and the worker's immutable
home warehouse. Before creating a new shift, task-board derives the current
operational warehouse from assignment history. Only an `ACTIVE` temporary
assignment or a completed permanent assignment can select the destination;
`PLANNED` and `IN_TRANSIT` assignments cannot expose or create a destination
shift. The destination warehouse's IANA timezone controls the work date and
frozen shift. Once created, an unfinished shift is resumed by its exact
driver/shift identity even after the temporary assignment ends, while every
command still verifies the JWT home warehouse against the worker profile. Shift
photos and other warehouse-owned effects use the frozen shift warehouse rather
than the home claim.

The explicit adjacent state machine is
`DAILY_BRIEFING_REQUIRED -> MEDICAL_CHECK_REQUIRED ->
VEHICLE_INSPECTION_REQUIRED -> READY_TO_START -> SHIFT_ACTIVE ->
SHIFT_CLOSING -> RETURN_TO_WAREHOUSE_REQUIRED ->
END_VEHICLE_CHECK_REQUIRED -> SHIFT_READY_TO_CLOSE -> SHIFT_CLOSED`.
Every public response includes `nextRequiredAction`; Android resumes from that
server projection after restart and never advances the workflow with local
booleans. Business confirmations use server time. The current medical step is
explicitly `SELF_CONFIRMATION_TEST`, while nullable external-check fields and
`EXTERNAL_MEDICAL_SYSTEM` are retained for a later provider integration.

Starting a shift snapshots the active database-backed inspection template for
`TRUCK`, `TRUCK_WITH_TRAILER`, or `TRUCK_WITH_CRANE`. Each required item keeps
its own version and `NOT_CHECKED`, `OK`, or `DEFECT` result. A reported defect
cannot be erased by switching the item to OK; new inspection defects are
conservatively `BLOCKING` and prevent the ordinary start transition. The
existing logistics task screen remains the execution surface. Task-board moves
an active shift toward closing only when at least one exact-driver task exists
for that date across physical warehouses and all such tasks are `DONE`.

Closing separately records manual warehouse return, vehicle condition,
odometer and fuel. Odometer may not decrease; a configured suspicious jump
requires explicit confirmation. An end-of-shift defect is linked to the common
vehicle-defect model and requires a correlated `END_SHIFT_DEFECT` media item in
authoritative `READY` state before close. Optional overview photos use the same
media owner, upload and event path. Shift transitions and photo reservations
are version-fenced and receipt-backed, so exact retries are idempotent while a
changed replay conflicts.

Daily weather is informational. `MetNoWeatherProvider` normalizes free MET
Norway Locationforecast data, shares a bounded ETag-aware cache by rounded
warehouse coordinates, and returns an unavailable DTO after bounded
timeout/retry failure. `WeatherHazardRules` derives only configurable advisory
wording; it never claims an official emergency warning. Weather availability
does not affect any shift transition.

## Internal application structure

`TaskBoardService` is a stable transactional facade over cohesive collaborators. It keeps
the existing controller/private-boundary method surface while the following
components own the decisions:

| Collaborator | Owned responsibility |
| --- | --- |
| `TaskBoardReadProjectionService` | Board/task reads and response projections |
| `DailyBrigadeActivityService` | Warehouse-local current-day projection of actual assignment take/finish intervals, including bounded overlap coalescing of legacy duplicate rows |
| `TaskBoardExternalRegistrationService` | Source-owned task creation, identity, route and canonical retry fingerprint |
| `TaskBoardExternalMutationService` | Source-authorized pre-start update/cancel, lane movement and relocation |
| `TaskBoardLogisticsTaskService` | Logistics equipment/driver task boundary |
| `TaskBoardWorkerExecutionService` | Assignment, timing, interruption, cancellation and worker execution |
| `TaskBoardFutureAvailabilityService` | Version-fenced promotion and demotion of still-future ordinary entries, including the SES gate, transactional media-reader proof and post-commit WorkerApp invalidation |
| `TaskBoardPinningService` | Version-fenced manager pin/unpin command |
| `TaskBoardEntryOrderingService` | Same-queue reorder of unpinned waiting real cards under entry/queue/target-identity fences |
| `WorkerQueuePlanService` | Warehouse-local WorkerApp publication switch and waiting-real plan command |
| `WorkerQueuePlanPolicy` | Shared WorkerApp feed, detail, TAKE and media-reader publication fence |
| `TaskBoardQueuePositionCoordinator` | Advisory locks, stream fences and persisted queue/pin ordering only |
| `TaskBoardRoutePayloadCodec` | The single canonical route JSON and fingerprint codec |
| `DriverTaskAudienceService` | Logistics-driver audience shape, qualification, visibility and execution authorization |
| `LogisticsDriverDirectoryService` | Least-privilege active primary logistics-driver directory for the exact logistics-service caller |
| `ContractorDriverService` / `WorkerOperationalAssignmentService` | Warehouse-owned on-demand contractor catalog and dated operational assignments; contractor profiles never require a vehicle or internal route-cycle model |
| `ContractorTaskExecutionService` | Exact logistics-owned contractor route snapshot, evidence reservation and replay-safe START/COMPLETE adapter over the existing worker state machine and evidence invariant |
| `DriverShiftService` | Driver plan registration, work-date resolution, shift/inspection/defect transitions, receipts and startup projection |
| `HttpWarehouseIdentityGateway` | Exact private warehouse identity/timezone/coordinates read for the shift owner |
| `MetNoWeatherProvider` / `WeatherHazardRules` | Fail-open normalized weather cache and configurable advisory derivation |
| `MobileTaskSurfacePolicy` | Non-overlapping DriverApp primary and WorkerApp secondary capabilities |
| `WorkerTaskAccessService` | Shared worker/group/qualification queue audience for native task reads and media proofs |
| `WorkerFeedCountProjection` | One-query route cardinality and READY-evidence counts for a bounded native feed page |
| `WorkerFeedRevisionStore` | Transactional, warehouse-scoped opaque revision advanced by authoritative task-board facts |
| `WorkerActionReceiptStore` | Advisory-locked immutable native and exact-contractor action request and frozen-response receipts |
| `TaskBoardEntryOwnerProofReconciler` | Bounded idempotent repair of legacy or workforce-stale media proof audiences |
| `WorkerPushOutbox` / `WorkerPushDispatcher` | Transactional slinger notification, leased FCM delivery and bounded recovery |
| `WorkforceService` | Stable worker/group API facade over three lifecycle owners |
| `WorkforceProfileService` | Worker profile and qualification mutation with the credential lock span |
| `WorkforceCredentialLifecycleService` | Durable auth credential intents, completion/failure fencing, reconciliation and deletion recovery |
| `WorkforceGroupService` | Group membership, availability and current-group intervals |
| `WorkforceReadProjectionService` | Read-only worker and group DTO assembly |

The dependency graph is acyclic. Registration and mutation share the route
codec; registration, mutation, worker execution and pinning use the narrow
queue-position coordinator. Neither technical collaborator owns authorization,
source status, worker transitions or another domain aggregate.

The workforce graph is also acyclic. Profile commands call the narrow
credential lifecycle and read projection, while group commands use only the
read projection. Credential recovery never calls the facade or the group
owner, and no collaborator exposes more than 15 direct dependencies.

A group create/update may include `currentGroupChanges` alongside the complete
membership replacement. `WorkforceGroupService` locks changed workers in stable
UUID order, checks each worker version, membership, availability and active-task
guard, and commits membership plus current-group intervals/events/KPI facts in
one transaction. Omitting the field preserves the previous current groups for
older callers; the dedicated worker current-group endpoint remains compatible.

Task-entry owner proofs separate result uploaders (`allowedWorkerIds`) from
readers (`readerWorkerIds`). While an entry is open, the read audience is
calculated by the same native worker/group/qualification and driver rules as
feed/detail access, so a worker may view a waiting task's source/result photos
without gaining upload authority. Task creation publishes the initial proof in
the same local transaction. When an entry closes, the reader audience is reduced
to every historical assignee and evidence worker. A bounded reconciler runs at startup and after a
warehouse audience-revision change, emits only changed proofs, and does not poll every task while
the warehouse is idle. Failed passes do not advance the revision watermark. Inactive proofs never
authorize a new upload or finalization.

## Worker problem reports

An assigned WorkerApp participant can report a problem on an `IN_PROGRESS` entry
with a 1–2000-character comment and up to ten optional photos. One idempotent
command locks the entry and stores the immutable report plus every evidence
reservation in one transaction, before any media upload. The operation UUID is
the report ID. An exact author-owned replay remains valid after task closure;
changing the command under that identity is a conflict. The original worker can
read the report and its changing attachment states after leaving the task.

Problem photos reuse the existing media owner proof, encrypted client upload and
media-event inbox. Their nullable `problem_report_id` distinguishes them from task
results: they never count toward completion, enter completion selection, appear
as result evidence in task feeds/details, or emit a task-result evidence fact.
Owner proofs still include them so a reserved upload can finish after closure.

Warehouse-authorized `USER` readers with `rwms.read` and `VIEW` receive bounded
cursor pages with a personal unread count. Marking a report read stores an
idempotent per-user receipt; it does not mutate the report or task and later photo
readiness does not reopen it. The panel uses the public gateway for both routes.

Reports mark the task as problematic; the installation-wide palette exposes
`problemColor` (initially `#FF3B30`). A nonempty `missingItemIds` also requires the
observed entry `expectedVersion` and immediately marks the selected work/material
and its frozen catalog-linked component `MISSING`. Maintenance owns requirement
identities, normative seconds and deep links, exposed through its private
`maintenance.task-requirements` read scope. Existing tasks resolve the same source
snapshot on demand; no database backfill or label matching is used.
Set `MAINTENANCE_SERVICE_URL` to the private maintenance-service origin. Outside the
`dev` profile there is no default: missing configuration rejects requirement reads
explicitly. The `dev` default is `http://127.0.0.1:8087`.

Workers can undo a missing mark through
`POST /worker/v1/entries/{entryId}/requirements/{itemId}/restore`. The active participant supplies
`expectedVersion`, an owned offline lease and an `operationId` matching `Idempotency-Key`.
Only missing members of the selected dependency group become `RESTORED`; unrelated and completed
rows and immutable reports remain unchanged. V56 admits `RESTORE_ITEM` in the native durable receipt
store so exact retries cannot undo a later missing mark. Catalog `FOLLOW_UP` paths never couple
availability. Refreshing frozen links preserves all recorded states and work accounting.

`tasks/{taskId}/requirements` supplies the recovery checklist. Completing available
work while resources are missing keeps the task `ACTIVE`, `suspended` and
`incomplete`; source stages are not falsely completed. Completed work volume uses
normative work seconds. Only that portion of the remaining budget enters the
existing time-based KPI formula; partial closure does not increment completed-task
count. Item completion persists across recovery, so later execution earns only the
remaining budget. Resource-only remainders have zero normative budget.

Restoration confirms exact `availableItemIds`; all missing members of each selected
linked group must be explicitly included. Confirmed rows become `RESTORED`, while
other missing groups remain blocked. A manager with warehouse `EDIT` can apply a
report to all active tasks in that warehouse using `reportId/apply-to-all` and an
idempotent operation UUID. Matching uses stable catalog node identities, never
names; custom positions without catalog identities cannot be applied globally.

## HTTP boundaries

The canonical contract is
[`contracts/openapi/task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml).
The public gateway maps `/api/task-board/**` to this service's downstream
`/api/**`; clients never call its private address.

| Boundary | Audience | Purpose |
| --- | --- | --- |
| `/api/queue-definitions/**` | Authenticated manager/admin policy | Global queue catalog, ordering, and reference-safe deletion |
| `/api/worker-classes/**` | Authenticated manager/admin policy | Worker qualification catalog |
| `/api/warehouses/{warehouseId}/workers/**` | Warehouse-authorized manager | Workers, groups, credential operations, and reconciliation |
| `/api/warehouses/{warehouseId}/logistics-drivers/contractors` and `.../{workerId}` | Warehouse-authorized manager | Complete on-demand contractor catalog, creation, version-fenced profile replacement and deletion of unused profiles; list includes inactive profiles |
| `/api/warehouses/{warehouseId}/work-queues` | Warehouse-authorized user | Physical queue projections and capabilities |
| `/api/warehouses/{warehouseId}/task-board/**` | Warehouse-authorized user | Aggregate ordinary-board read and supported task commands |
| `/api/warehouses/{warehouseId}/task-problem-reports` and `.../{reportId}/read` | User with `rwms.read` and warehouse `VIEW` | Problem notification pages and personal read receipts |
| `/api/warehouses/{warehouseId}/task-board/daily-brigade-activity` | Warehouse-authorized user | Actual task-assignment intervals overlapping the current warehouse-local day |
| `/api/kpi-palette` | Authenticated user; global management for `PUT` | One version-fenced KPI palette shared by every installation warehouse |
| `/api/kpi-settings/**` | Authenticated user; global management for mutations | One version-fenced work schedule shared by every installation warehouse; no warehouse selector |
| `/api/worker/v1/**` | Worker credential and `worker.tasks` scope | Context, feed, detail, actions, evidence reservations, problem reports, devices, and events |
| `/api/driver/v1/**` | Worker credential and `driver.tasks` scope | Driver-only context, primary feed, actions, evidence reservations, devices, and events |
| `/api/driver/v1/shift/today` and `/api/driver/v1/shifts/{shiftId}/**` | Exact driver identity and `driver.tasks` | Startup aggregate and version-fenced daily-shift transitions |
| `/api/internal/task-board/v1/inventory/warehouses/{warehouseId}/work-calendar` | Exact `inventory-service` SERVICE identity and sole `task-board.inventory-calendar.read` scope | Bounded effective object-calendar snapshot: timezone, schedule revision, `daysOff` result and fingerprint for inventory planning; no browser access and no Driver Up shift semantics |
| `/api/internal/task-board/v1/maintenance/**` | Exact maintenance-service identity | Routing and catalog preflight |
| `/api/internal/task-board/v1/tasks/**` | Exact source service identity | Idempotent task synchronization and evidence reads |
| `/api/internal/task-board/v1/logistics/**` | Exact logistics-service identity | Driver/equipment task integration |
| `/api/internal/task-board/v1/logistics/warehouses/{warehouseId}/drivers` | Exact logistics-service identity and scope | Active primary-qualified driver identities only |
| `/api/internal/task-board/v1/logistics/contractor-execution/workers/{workerId}/tasks/{externalTaskId}`, `.../entries/{entryId}/actions` and `.../evidence-reservations` | Exact logistics-service identity and sole `task-board.logistics` scope | Exact assigned active-contractor route snapshot, result-evidence reservation and START/COMPLETE without credentials, contact disclosure, native offline lease, media bearer path or general-board access |
| `/api/internal/task-board/v1/driver-shift-plans/{sourceShiftId}` | Exact logistics-service identity and `task-board.driver-shifts.plan` | Idempotent reviewed driver/vehicle/day plan registration |
| `/api/internal/queue-definitions/**` | Allow-listed service identity | Durable queue usage references |

Private paths are service-to-service boundaries and are never exposed as client
shortcuts. Their exact `principal_type`, `client_id`, scope, source ownership,
and warehouse checks are part of the contract.
Contractors remain `WorkerEmploymentType.CONTRACTOR` records with a contact,
note and active flag. The catalog stores no availability dates: the selected
planning day belongs to the downstream assignment. It does not provision
credentials, require a vehicle or make the worker eligible for the ordinary
primary-driver optimizer. A task becomes service-executable only after logistics registers an
exact `ASSIGNED_DRIVER` audience for that contractor; the catalog alone grants no task access.

The same owner stores hired-company contacts at
`/api/warehouses/{warehouseId}/logistics-drivers/companies`: name, INN, contact person,
phone, email, address and notes. INN is unique within one city; a separate record may
exist in another city. Creates use a stable company ID, edits/deletion require
`expectedVersion`, and warehouse VIEW/EDIT grants match the driver catalog. A driver's
nullable `companyId` can only reference its immutable home city. Explicit null removes
membership without deleting the driver or trip history; updates must include that field.
Companies with drivers cannot be deleted. Flyway V51 adds this contact catalog and an
enforced same-city membership key; existing independent drivers remain unassigned.
Trip dates and execution still belong to existing driver assignment workflows.

The daily-brigade activity read uses persisted assignment `startedAt` from TAKE
and `finishedAt` from completion. Shift bounds select and position the display
but never replace those timestamps. Overlapping joined-worker or legacy
same-brigade/task/physical-queue rows are one interval; non-overlapping retakes
remain distinct, and a live interval has a null finish. The projection is
read-only, warehouse-authorized and owned entirely by task-board.

## Native streams and offline execution

Subscription registration and last-subscriber cleanup are atomic for each warehouse/surface/worker
key. Closing an old stream cannot remove a concurrent replacement subscription.

`GET /api/worker/v1/events` is an SSE invalidation stream. The current producer
emits a `FEED_CHANGED` signal when a client subscribes and for subsequent
changes; the worker app also performs periodic authoritative REST refresh. A
reconnect opens a fresh subscription and is followed by an authoritative REST
feed refresh; this contract has no cursor replay or `Last-Event-ID` dependency.
Payloads are not a complete task projection. A subscription is keyed by the
authenticated warehouse, native surface and worker, so a fact from another
warehouse cannot advance or notify this stream. A feed page reads its
warehouse revision and projection under one repeatable-read snapshot; cursors
remain valid across unrelated warehouse changes. Its weak ETag is scoped to
the authenticated warehouse, native surface and worker as well as that revision.

`GET /api/driver/v1/events` has the same invalidation-only semantics: reconnect
opens a fresh subscription and the DriverApp refreshes its authoritative REST
feed. Device
registrations are surface-bound and accept current Firebase Installation IDs
(`targetKind=FID`) plus legacy registration tokens. A driver's successful TAKE
stores the slinger notification in `worker_push_outbox` in the same transaction;
the leased dispatcher retries transient FCM failures, revokes invalid targets,
and never sends slinger calls to DriverApp installations.

`WorkerTaskDetail.source` always appears and is null for ordinary work. For source-owned work it
contains only the existing immutable source type and ID. A worker client can use a
`LOGISTICS_DRIVER_TASK` ID to load the logistics-owned trip details; task-board does not copy that
payload or its business state.

For `MAINTENANCE_REPAIR`, consecutive route entries assigned to the same physical queue are one
worker execution package. Detail aggregates the complete segment's work, material, comment, and
source-media snapshots; its duration and timer use only the still-unfinished members. TAKE keeps
one representative assignment and KPI segment. When no requirements are missing,
one version-fenced COMPLETE atomically marks that
representative and every later unfinished shadow member done, records assignment/time audit for
each, emits one existing queue-entry completion fact per source-mapped repair stage, and promotes
only the next different route segment. Non-maintenance sources remain entry-scoped. This changes no
persistence schema or event payload shape.

`WorkerTaskDetail.sourceMedia` preserves source order, including a source-defined cover in the
first position. Each `WorkerWork.sourceMediaIds` is the exact link from a work line to its own
references in that array; task-board does not flatten or infer that association.

Every `WorkerFeedEntry` exposes raw zero-based `routeIndex`, zero-based `routeStepIndex`, positive
`routeStepCount`, required `entryType` and required `pinned`; `WorkerTaskDetail` exposes both route
indices and the same positive authoritative package count. `routeIndex` remains the persisted
route-row and evidence identity.
`routeStepIndex` is the worker execution-package ordinal. WorkerApp receives only server-selected `REAL` entries from
enabled queues: active work plus the queue's bounded waiting plan. Future `SHADOW` stages stay in
the manager snapshot and are never published to WorkerApp. For maintenance, only consecutive rows
of one physical queue share a package: A-A-B has two packages and A-B-A has three. Other task
sources use one package per persisted route row. Package coordinates and READY evidence counts are
loaded for the selected feed page in one database projection rather than one query per card.

The worker action path validates the worker identity, current assignment,
entry version, action/status transition, and offline lease where applicable.
The 24-hour window remains strict for `TAKE`, `JOIN`, `PAUSE` and `RESUME`.
For an already assigned WorkerApp task, its signed lease may instead carry the
result photo and `COMPLETE` beyond that window: task state, assignment,
expected version, photo gate and non-future occurrence time remain mandatory.
`deadlineAt` remains operational task metadata and never vetoes a valid
completion. Each accepted full completion emits the canonical `QUEUE_ENTRY_COMPLETED`
fact; maintenance consumes the final mapped repair-stage fact to place the
repair into pending acceptance. Partial completion with missing requirements emits no
source-stage completion fact and retains the incomplete task for recovery.

Before any live task read, the action path serializes attempts for the
`operationId` with a transaction-scoped advisory lock. The first successful
request stores its complete canonical request identity and frozen response in
`worker_action_receipt` in the same transaction as the event/outbox effects.
An exact retry returns that original response unchanged; any changed surface,
worker, warehouse, entry or request field returns `409`. A pre-V36 event with
the same correlation ID but no receipt also fails closed with `409`, because
its original response cannot be reconstructed safely. Volatile SSE
invalidation is dispatched only after commit. The private contractor adapter reuses this receipt
table and lock while adding the external task and service channel to its canonical request; it
re-proves the exact active contractor assignment before accepting a replay.

Evidence is first reserved with a stable client reference, then uploaded to
media-service. A media fact links the processed generation back to the reserved
evidence before completion may rely on it. A legacy `image/jpeg` declaration may be at most 15 MiB;
an `image/webp` logical client bundle may be at most 1 MiB and its `sha256` is the deterministic
bundle-manifest checksum. Both formats retain one logical evidence row, and reservation replays
must match the original entry, operation, route step, capture time, MIME type, size, and checksum.
The exact-contractor reservation endpoint applies the same declaration and replay rules while
deriving its route and owner facts on the server and requiring an `IN_PROGRESS` entry plus the exact
live contractor assignment before both a first reservation and replay.
The same format and byte limits are enforced by
[`V32__support_worker_evidence_webp_bundles.sql`](src/main/resources/db/migration/V32__support_worker_evidence_webp_bundles.sql).

A finalized worker photo is the first externally visible fact of its task-evidence stream, so that
stream always starts at aggregate version `0` independently of the internal reservation-row
version. Migration
[`V35__repair_task_evidence_stream_origins.sql`](src/main/resources/db/migration/V35__repair_task_evidence_stream_origins.sql)
adds deterministic missing origins only to entirely unpublished `TASK_EVIDENCE` streams and
requeues their original aggregate-gap-quarantined facts in order. It does not rewrite the original
facts or touch an already published stream; the event payload contract is unchanged.

[`V36__worker_feed_revision_and_action_receipts.sql`](src/main/resources/db/migration/V36__worker_feed_revision_and_action_receipts.sql)
adds the warehouse revision sequence/projection and immutable worker-action receipts. Existing
warehouse rows are backfilled above the former global revision fence; no domain event, task or
evidence row is rewritten.

SSE reconnect is intentionally invalidation-only. The controllers and mobile
clients do not accept or send a replay cursor; reconnect triggers an
authoritative REST refresh, while local event IDs remain available only for
invalidation deduplication and audit.

## Persistence and eventing

The service owns one PostgreSQL database and immutable Flyway migrations under
[`src/main/resources/db/migration`](src/main/resources/db/migration/).
Hibernate always validates and never creates or updates the schema.

Task-board records owner state, append-only domain facts, outbox rows, inbox
deduplication, aggregate checkpoints, sanitized DLT metadata, worker evidence,
warehouse lifecycle intent, and recovery state locally. Kafka publication uses
an aggregate-keyed, synchronously acknowledged, leased outbox relay. Consumer
processing validates envelopes and payloads, retries a bounded number of times,
and blocks/quarantines aggregate version gaps instead of silently skipping them.

Produced families include worker class, worker, group, queue, usage reference,
board task, queue entry, owner-proof, task-evidence, and group-KPI-day facts.
The service consumes warehouse facts for metadata/lifecycle projection and
media facts for worker evidence.

Migration
[`V28__driver_task_audience.sql`](src/main/resources/db/migration/V28__driver_task_audience.sql)
adds the audience and planned assigned-worker snapshot to `board_task`.
Existing logistics driver tasks are backfilled as warehouse-shared. Board-task
events add only optional audience and worker-ID fields, so retained V1 events
without them remain valid; display names are not published in those events.

Migration
[`V29__remove_shared_driver_identity.sql`](src/main/resources/db/migration/V29__remove_shared_driver_identity.sql)
clears obsolete worker IDs/names from `WAREHOUSE_DRIVERS` tasks and tightens the database constraint
so shared and unassigned audiences remain identity-free.

[`V30__driver_worker_surfaces_and_push_outbox.sql`](src/main/resources/db/migration/V30__driver_worker_surfaces_and_push_outbox.sql)
separates WorkerApp/DriverApp installations, adds the leased push outbox, normalizes every configured
logistics secondary binding to required/interrupting/notified, and raises its result-photo minimum
to one. The current mobile surface policy deliberately exposes configured secondaries as optional:
it retains notification and group interruption when a slinger joins, but never blocks driver
completion waiting for one. The migration does not invent a missing slinger class.

[`V39__driver_daily_shift.sql`](src/main/resources/db/migration/V39__driver_daily_shift.sql)
adds the reviewed plan, daily shift, versioned inspection snapshot/results,
shared vehicle defects, shift photos, immutable command receipts and media
inbox. It seeds editable template configuration without encoding checklist
items as boolean columns, extends the event-store aggregate allow-list, and
does not rewrite existing tasks, facts or driver data.

[`V40__contractor_profiles_without_availability_range.sql`](src/main/resources/db/migration/V40__contractor_profiles_without_availability_range.sql)
removes contractor availability columns and their range index. Contractor profiles become an
on-demand warehouse address book; an exact date is stored only by the logistics assignment that
uses the contractor.

[`V41__driver_shift_route_operations.sql`](src/main/resources/db/migration/V41__driver_shift_route_operations.sql)
adds the immutable ordered operation children below a replaceable-until-frozen shift plan.
[`V42__driver_shift_transfer_route_operations.sql`](src/main/resources/db/migration/V42__driver_shift_transfer_route_operations.sql)
adds nullable effective cabin capacity and canonical transfer identity, expands the operation-kind
and identity constraints, and leaves every existing plan compatible through null/default absence.

## Security and isolation

- All API chains validate JWT issuer/audience; worker and driver routes require
  the non-interchangeable `worker.tasks` and `driver.tasks` scopes.
- Manager commands enforce user role, warehouse access, and command-specific
  write permission in `WarehouseAccessAuthorizer` and services.
- Internal task, queue-reference, maintenance, and logistics operations require
  exact service identity/scope and validate source ownership.
- Driver-shift plans accept only the exact logistics-service credential and
  `task-board.driver-shifts.plan`; mobile shift routes accept only the matching
  `WORKER` identity, warehouse and `driver.tasks` scope.
- Cross-warehouse DriverApp actions retain that home-warehouse token fence and additionally require
  the exact remote `ASSIGNED_DRIVER` audience; entry IDs never grant warehouse access by themselves.
- Auth-service remains the credential owner. Task-board persists only the
  operational credential workflow state needed for reconciliation.
- CORS uses explicit panel, worker, and driver origins. Browser/mobile clients use the
  gateway; services use private routes and client credentials.
- All six OAuth registrations (worker credentials, warehouse-lifecycle read/confirm,
  warehouse timezone/identity and worker profile media) require the same external
  `TASK_BOARD_CLIENT_SECRET` in every profile, including `dev`. There is no fallback;
  startup rejects the retired repository credential even when supplied externally.
  Configure the same new value in auth-service and task-board, and increase auth's
  `TASK_BOARD_CLIENT_REVISION` above its stored revision when rotating a secret
  (default revision `6`). Auth removes stored machine grants once during rotation;
  already issued JWTs retain their own `exp` (the configured default is five minutes).
- Dev auth bypass is allowed only with an explicit dev profile and is rejected
  outside local/test operation.

## Concurrency, idempotency, and recovery

- Mutable board/queue/workforce commands carry expected versions or another
  contract-defined fence and return `409` when stale.
- Retried source task creation/synchronization uses a stable external task ID
  and source identity.
- Driver plans are unique by source shift and by driver/work date. Creation is
  serialized on the plan row, and every mutable shift command uses root/child
  versions plus an immutable operation receipt.
- Native action retries use one durable operation receipt; exact requests return
  the frozen first response, while divergent or unreconstructable legacy
  replays return `409` without applying another effect.
- Credential reset/disable/delete workflows preserve pending/ambiguous states
  and have explicit reconciliation commands rather than local rollback.
- Worker/group deletion archives the directory identity after checking unfinished assignments.
  Worker auth deletion must succeed first. Completed assignments, time events and KPI intervals
  retain their foreign keys; current membership is detached and open intervals are closed.
  Archived profiles cannot be edited or selected again; a deleted group's name can be reused.
- Worker media inbox rows remain pending until their referenced evidence exists;
  a reconciler retries them idempotently.
- Slinger push is at least once: an expired lease can be reclaimed, transient
  failures use bounded delay, invalid installations are revoked, and exhausted
  rows remain `DEAD` for operations rather than pretending delivery.
- Warehouse readiness cannot succeed while task/queue/credential/evidence or
  operation-mark work is unresolved.
- Outbox and sanitized DLT recovery preserve the immutable envelope and audit
  the reviewed action.

## Observability and failure behavior

Actuator exposes health/readiness and Prometheus; Micrometer tracing, ECS logs,
and `X-Correlation-Id` connect public requests, remote effects, and facts. The
service must not log passwords, Bearer tokens, worker secrets, raw rejected
events, or personal task content.

Dependency timeouts are explicit failures or durable reconciliation states.
They are not converted into mock data or command success. Kafka delivery
failure leaves the local outbox recoverable and does not undo a committed task.

The repository still contains a Rabbit-to-Kafka cutover rehearsal runtime and
historical mapping table even though the current product has no active cutover
program. Do not enable that runner as a normal operational feature; its
contract-safe removal is planned in the architecture audit.

## Local development

Start local dependencies:

```bash
docker compose --profile core up -d task-board-db kafka
```

Start auth and warehouse when the tested flow needs credentials or lifecycle,
then run task-board:

```bash
bash ./gradlew :services:task-board-service:bootRun --args='--spring.profiles.active=dev'
```

Dev defaults use PostgreSQL `127.0.0.1:5434`, auth
`http://localhost:9000`, warehouse-service `http://localhost:8083`, Kafka
`localhost:9092`, and service port `8081`. Keep the public browser/mobile entry
at the gateway.

## Required production configuration

Provide database credentials, HTTPS auth issuer and token URI, the
task-board service secret, worker offline-lease secret, private auth worker-
credential URL, private warehouse lifecycle URL, explicit CORS origins, Kafka
brokers, `TASK_BOARD_KAFKA_ENABLED=true`, `TASK_BOARD_FCM_ENABLED=true`,
`TASK_BOARD_FCM_PROJECT_ID`, and Google Application Default Credentials.

Enable the new flow with `DRIVER_DAILY_SHIFT_ENABLED=true`. Configure
`DRIVER_SHIFT_DAY_START`, `DRIVER_SHIFT_SUSPICIOUS_ODOMETER_JUMP_KM`, an
identifying nonsecret `MET_NO_USER_AGENT`, bounded `MET_NO_*` transport/cache
settings, and the `WEATHER_HAZARD_*` thresholds. The task-board OAuth client
also needs `warehouse.identity.read` and its existing private warehouse base
URL; no weather API key is used or returned to Android.

`TaskBoardProductionSafetyValidator` rejects missing/insecure endpoints,
disabled Kafka, topic drift, unsafe binder retry/DLT settings, topic
auto-creation, non-acknowledged publishing, and publish timeouts that can exceed
the outbox lease outside dev/test.

## Verification

From the repository root:

```bash
bash ./gradlew :services:task-board-service:test
bash ./gradlew :services:task-board-service:javadoc
bash ./gradlew :platform:architecture-tests:test
```

Changes to native actions or streams require WorkerApp and DriverApp
contract/unit tests and exact APK builds. Changes to source-task integration, Kafka families, or
warehouse lifecycle require focused producer/consumer, idempotency, version-gap,
dependency-outage, and recovery coverage.

## Safe change rules

1. Keep queue, workforce, assignment, task execution, and worker evidence state
   owned here; keep source-domain decisions with their producer.
2. Start public/private API changes from the canonical OpenAPI and update
   gateway/client/service consumers together.
3. Preserve warehouse authorization, source identity, stable external IDs,
   expected-version fencing, and exact task ownership.
4. Commit state, history, and outbox atomically; preserve inbox deduplication,
   aggregate ordering, bounded retry, and gap recovery.
5. Treat SSE as invalidation unless a new producer-owned durable replay contract
   is explicitly approved and implemented end to end.
6. Remove obsolete cutover runtime only in a reviewed slice that preserves
   required historical evidence and never deletes live data implicitly.

## Primary implementation references

- [Canonical OpenAPI](../../contracts/openapi/task-board-service.yaml)
- [Task-board controller](src/main/java/dev/buhanzaz/rwms/taskboard/api/TaskBoardController.java)
- [Worker API](src/main/java/dev/buhanzaz/rwms/taskboard/api/WorkerTaskBoardController.java)
- [Driver API](src/main/java/dev/buhanzaz/rwms/taskboard/api/DriverTaskBoardController.java)
- [Driver shift API](src/main/java/dev/buhanzaz/rwms/taskboard/api/DriverShiftController.java)
- [Driver shift owner](src/main/java/dev/buhanzaz/rwms/taskboard/service/DriverShiftService.java)
- [MET weather adapter](src/main/java/dev/buhanzaz/rwms/taskboard/service/MetNoWeatherProvider.java)
- [Task-board application service](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardService.java)
- [Task-board read projection](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardReadProjectionService.java)
- [External task registration](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalRegistrationService.java)
- [External task mutation](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalMutationService.java)
- [Queue position coordinator](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardQueuePositionCoordinator.java)
- [Worker application service](src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerTaskBoardService.java)
- [Mobile surface policy](src/main/java/dev/buhanzaz/rwms/taskboard/service/MobileTaskSurfacePolicy.java)
- [Push outbox](src/main/java/dev/buhanzaz/rwms/taskboard/push/WorkerPushOutbox.java)
- [Workforce service](src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkforceService.java)
- [Production safety validator](src/main/java/dev/buhanzaz/rwms/taskboard/config/TaskBoardProductionSafetyValidator.java)
- [Event store](src/main/java/dev/buhanzaz/rwms/taskboard/eventing/TaskBoardEventStore.java)
- [Runtime flow map](../../docs/project-knowledge/runtime-flows.md)

## Linked queues and group continuation

`PUT /api/queue-definitions/{id}/link` links or unlinks two global working
queues atomically under both definition versions. A queue has at most one
partner, and both need a common primary worker class. Holding and driver queues
are excluded. The board returns the partner queue ID and name on both columns.

For ordinary work, one current group member takes the task for every active
current member; any assigned member may complete it for the whole group. After
completion, the consecutive real stage of the same task in the linked queue is
reserved for that group. Both members receive that continuation in WorkerApp;
the next timer starts on TAKE. A published continuation takes precedence over
unrelated waiting work for its group. Queue publication, plan limits and group
availability still apply; an unpublished continuation does not block other
published work. Unlinking releases the continuation. Route order is preserved.

The continuation owner comes from persisted completion assignments with a
recorded primary TAKE role. That immutable role is included in queue-entry
events, so later group-class changes cannot change who took the stage. Older
assignments with an unknown role keep their prior behavior; no historical role
is guessed or backfilled. Flyway V50 adds the pair and assignment-role columns.
