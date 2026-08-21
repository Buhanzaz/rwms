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

The ordinary repair board is one aggregate warehouse view, not a calendar. Each queue exposes all
`IN_PROGRESS` and `PAUSED` real entries plus only the first `availableTaskLimit` waiting real entries
in canonical priority order. Active work stays at the front in aggregate-position order; waiting
work follows by priority and aggregate position. A take is rejected when the card is outside that
server-owned window.
Future route stages remain hidden until their predecessor completes; a holding/SES stage is the only
ordinary card for its task until treatment finishes. The public board has no date selector, shadow
mode, manual entry move, date swap, daily-capacity scheduling, or overdue rollover scan. Dated driver
and shipment planning remains on the separate logistics surfaces. These invariants are defined by
[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml),
[`TaskBoardReadProjectionService`](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardReadProjectionService.java),
and [`OrdinaryQueueAvailabilityPolicy`](src/main/java/dev/buhanzaz/rwms/taskboard/service/OrdinaryQueueAvailabilityPolicy.java).

Flyway V31 normalizes existing ordinary `queue_entry.queue_position` values into that aggregate
sequence and corrects the real/shadow shape of a fully waiting holding route. It does not rewrite
the append-only `domain_event` history. Replay comparison therefore accepts only `queuePosition`
and `entryType` differences from queue-entry tails recorded no later than the successful V31
cutover; every later event tail and every other field remain exact. The cutover is read from
`flyway_schema_history`, so a rebuild preserves immutable facts without weakening post-migration
drift detection. The rule lives in
[`TaskBoardReplayVerifier`](src/main/java/dev/buhanzaz/rwms/taskboard/eventing/TaskBoardReplayVerifier.java).

Logistics driver tasks additionally carry one persisted audience:
`UNASSIGNED`, `ASSIGNED_DRIVER`, or `WAREHOUSE_DRIVERS`. Only the exact
logistics-service driver-task source may set it. Only assigned work carries a worker identity; that
worker must be active in the same warehouse and have the primary qualification of the driver queue.
Task-board ignores a caller-supplied display name and stores its authoritative worker snapshot.
Unassigned tasks remain dispatcher work. An assigned task is visible only to that driver. A waiting
identity-free shared task is visible to every qualified warehouse driver until one takes it, after
which only the actual assignee retains access. DriverApp receives only primary bindings through
`/api/driver/v1/**`; WorkerApp receives ordinary work and only active secondary logistics work
through `/api/worker/v1/**`. A driver take persists a `TASK_JOIN_AVAILABLE` push for eligible
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
public ordinary board exposes no entry-movement command.

## Internal application structure

`TaskBoardService` is a stable six-collaborator transactional facade. It keeps
the existing controller/private-boundary method surface while the following
components own the decisions:

| Collaborator | Owned responsibility |
| --- | --- |
| `TaskBoardReadProjectionService` | Board/task reads and response projections |
| `TaskBoardExternalRegistrationService` | Source-owned task creation, identity, route and canonical retry fingerprint |
| `TaskBoardExternalMutationService` | Source-authorized pre-start update/cancel, lane movement and relocation |
| `TaskBoardLogisticsTaskService` | Logistics equipment/driver task boundary |
| `TaskBoardWorkerExecutionService` | Assignment, timing, interruption, cancellation and worker execution |
| `TaskBoardPinningService` | Version-fenced manager pin/unpin command |
| `TaskBoardQueuePositionCoordinator` | Advisory locks, stream fences and persisted queue/pin ordering only |
| `TaskBoardRoutePayloadCodec` | The single canonical route JSON and fingerprint codec |
| `DriverTaskAudienceService` | Logistics-driver audience shape, qualification, visibility and execution authorization |
| `MobileTaskSurfacePolicy` | Non-overlapping DriverApp primary and WorkerApp secondary capabilities |
| `WorkerTaskAccessService` | Shared worker/group/qualification queue audience for native task reads and media proofs |
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

## Native streams and offline execution

`GET /api/worker/v1/events` is an SSE invalidation stream. The current producer
emits a `FEED_CHANGED` signal when a client subscribes and for subsequent
changes; the worker app also performs periodic authoritative REST refresh.
Payloads are not a complete task projection.

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

The worker action path validates the worker identity, current assignment,
entry version, action/status transition, and offline lease where applicable.
Evidence is first reserved with a stable client reference, then uploaded to
media-service. A media fact links the processed generation back to the reserved
evidence before completion may rely on it.

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
