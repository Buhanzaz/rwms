# RWMS Task Board Service

[Русская версия](README.ru.md)

`task-board-service` owns the operational queue catalog, warehouse queue
projections, worker registry and groups, assignments, task-board state, worker
execution, evidence linkage, and KPI settings/evidence. Source domains may
request tasks through explicit private contracts, but they do not mutate task
state or workforce tables directly.

## Why it exists

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
| KPI | Warehouse palette/schedule revisions and emitted daily evidence | Analytics owns the KPI read projection |
| Warehouse lifecycle | Local operation marks, admission fence, draining blockers, exact-version readiness | Warehouse-service owns lifecycle state and admission decisions |

The service does not own users and roles, warehouse identity, repair or
logistics aggregates, media bytes, analytics projections, or gateway routing.

## Command and task flow

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
```

A source-owned task uses a stable external identity so a retry finds the same
task instead of creating a duplicate. Mutable entry operations are fenced by
the contract-defined version and status. Route order, eligibility, assignment,
and terminal transitions remain server-owned.

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

KPI schedule revisions may start on the warehouse-local current date or a future date. Saving keeps
the revision in `DRAFT`; explicit activation makes a current-date revision `ACTIVE` in the same
command and applies it to the whole current local calendar day. A future revision remains
`SCHEDULED` until its date, and a past date is rejected. Activating a replacement for the same date
retires the prior scheduled/active revision under the existing receipt and optimistic-concurrency
fences. This behavior is owned by
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

Logistics driver tasks additionally carry one persisted audience:
`UNASSIGNED`, `ASSIGNED_DRIVER`, or `WAREHOUSE_DRIVERS`. Only the exact
logistics-service driver-task source may set it. Only assigned work carries a worker identity; that
worker must be active in the same warehouse and have the primary qualification of the driver queue.
Task-board ignores a caller-supplied display name and stores its authoritative worker snapshot.
Unassigned tasks remain dispatcher work. An assigned task is visible only to that driver. A waiting
identity-free shared task is visible to every qualified warehouse driver until one takes it, after
which only the actual assignee retains access. DriverApp receives visible primary work from both
`SCHEDULED` and `CURRENT` lanes through `/api/driver/v1/**`, so its dated screen can show assigned
future work and shared future candidates. WorkerApp receives ordinary work and only active
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
| `MobileTaskSurfacePolicy` | Non-overlapping DriverApp primary and WorkerApp secondary capabilities |
| `WorkerTaskAccessService` | Shared worker/group/qualification queue audience for native task reads and media proofs |
| `WorkerFeedCountProjection` | One-query route cardinality and READY-evidence counts for a bounded native feed page |
| `WorkerFeedRevisionStore` | Transactional, warehouse-scoped opaque revision advanced by authoritative task-board facts |
| `WorkerActionReceiptStore` | Advisory-locked immutable native-action request and frozen-response receipts |
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
| `/api/warehouses/{warehouseId}/work-queues` | Warehouse-authorized user | Physical queue projections and capabilities |
| `/api/warehouses/{warehouseId}/task-board/**` | Warehouse-authorized user | Aggregate ordinary-board read and supported task commands |
| `/api/warehouses/{warehouseId}/task-board/daily-brigade-activity` | Warehouse-authorized user | Actual task-assignment intervals overlapping the current warehouse-local day |
| `/api/warehouses/{warehouseId}/task-board/kpi-settings/**` | Warehouse manager/admin | Palette and effective schedule revisions |
| `/api/worker/v1/**` | Worker credential and `worker.tasks` scope | Context, feed, detail, actions, evidence reservations, devices, and events |
| `/api/driver/v1/**` | Worker credential and `driver.tasks` scope | Driver-only context, primary feed, actions, evidence reservations, devices, and events |
| `/api/internal/task-board/v1/maintenance/**` | Exact maintenance-service identity | Routing and catalog preflight |
| `/api/internal/task-board/v1/tasks/**` | Exact source service identity | Idempotent task synchronization and evidence reads |
| `/api/internal/task-board/v1/logistics/**` | Exact logistics-service identity | Driver/equipment task integration |
| `/api/internal/queue-definitions/**` | Allow-listed service identity | Durable queue usage references |

Private paths are service-to-service boundaries and are never exposed as client
shortcuts. Their exact `principal_type`, `client_id`, scope, source ownership,
and warehouse checks are part of the contract.

The daily-brigade activity read uses persisted assignment `startedAt` from TAKE
and `finishedAt` from completion. Shift bounds select and position the display
but never replace those timestamps. Overlapping joined-worker or legacy
same-brigade/task/physical-queue rows are one interval; non-overlapping retakes
remain distinct, and a live interval has a null finish. The projection is
read-only, warehouse-authorized and owned entirely by task-board.

## Native streams and offline execution

`GET /api/worker/v1/events` is an SSE invalidation stream. The current producer
emits a `FEED_CHANGED` signal when a client subscribes and for subsequent
changes; the worker app also performs periodic authoritative REST refresh.
Payloads are not a complete task projection. A subscription is keyed by the
authenticated warehouse, native surface and worker, so a fact from another
warehouse cannot advance or notify this stream. A feed page reads its
warehouse revision and projection under one repeatable-read snapshot; cursors
remain valid across unrelated warehouse changes. Its weak ETag is scoped to
the authenticated warehouse, native surface and worker as well as that revision.

`GET /api/driver/v1/events` has the same invalidation-only semantics. Device
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
one representative assignment and KPI segment. One version-fenced COMPLETE atomically marks that
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
completion. Each accepted completion emits the canonical `QUEUE_ENTRY_COMPLETED`
fact; maintenance consumes the final mapped repair-stage fact to place the
repair into pending acceptance.

Before any live task read, the action path serializes attempts for the
`operationId` with a transaction-scoped advisory lock. The first successful
request stores its complete canonical request identity and frozen response in
`worker_action_receipt` in the same transaction as the event/outbox effects.
An exact retry returns that original response unchanged; any changed surface,
worker, warehouse, entry or request field returns `409`. A pre-V36 event with
the same correlation ID but no receipt also fails closed with `409`, because
its original response cannot be reconstructed safely. Volatile SSE
invalidation is dispatched only after commit.

Evidence is first reserved with a stable client reference, then uploaded to
media-service. A media fact links the processed generation back to the reserved
evidence before completion may rely on it. A legacy `image/jpeg` declaration may be at most 15 MiB;
an `image/webp` logical client bundle may be at most 1 MiB and its `sha256` is the deterministic
bundle-manifest checksum. Both formats retain one logical evidence row, and reservation replays
must match the original entry, operation, route step, capture time, MIME type, size, and checksum.
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

The current OpenAPI text mentions `Last-Event-ID`, but controller and client do
not implement durable replay. Current reconnect is safe because it triggers a
fresh invalidation and periodic pull; the semantic mismatch and required
decision are recorded in the full audit.

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

## Security and isolation

- All API chains validate JWT issuer/audience; worker and driver routes require
  the non-interchangeable `worker.tasks` and `driver.tasks` scopes.
- Manager commands enforce user role, warehouse access, and command-specific
  write permission in `WarehouseAccessAuthorizer` and services.
- Internal task, queue-reference, maintenance, and logistics operations require
  exact service identity/scope and validate source ownership.
- Auth-service remains the credential owner. Task-board persists only the
  operational credential workflow state needed for reconciliation.
- CORS uses explicit panel, worker, and driver origins. Browser/mobile clients use the
  gateway; services use private routes and client credentials.
- The worker-credential, warehouse-lifecycle read/confirm, and warehouse-timezone
  OAuth registrations all use `TASK_BOARD_CLIENT_SECRET`. The `dev` profile
  supplies the same local fallback to every registration; the base/production
  configuration has no fallback and still requires the deployment secret.
- Dev auth bypass is allowed only with an explicit dev profile and is rejected
  outside local/test operation.

## Concurrency, idempotency, and recovery

- Mutable board/queue/workforce commands carry expected versions or another
  contract-defined fence and return `409` when stale.
- Retried source task creation/synchronization uses a stable external task ID
  and source identity.
- Native action retries use one durable operation receipt; exact requests return
  the frozen first response, while divergent or unreconstructable legacy
  replays return `409` without applying another effect.
- Credential reset/disable/delete workflows preserve pending/ambiguous states
  and have explicit reconciliation commands rather than local rollback.
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
