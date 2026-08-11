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
| Operational work | Tasks, route entries, assignment, pinning, movement, pause/resume/complete, and history | Source domain owns why the work exists and its aggregate state |
| Worker execution | Scoped feed/context, offline action lease, evidence reservation, SSE/FCM invalidation | Worker app refreshes authoritative REST state and uploads media through media-service |
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

worker app --> feed/detail --> take/action/evidence reservation
          --> media upload --> media fact --> evidence state --> completion
```

A source-owned task uses a stable external identity so a retry finds the same
task instead of creating a duplicate. Mutable entry operations are fenced by
the contract-defined version and status. Route order, eligibility, assignment,
and terminal transitions remain server-owned.

Logistics driver tasks additionally carry one persisted audience:
`UNASSIGNED`, `ASSIGNED_DRIVER`, or `WAREHOUSE_DRIVERS`. Only the exact
logistics-service driver-task source may set it. Only assigned work carries a worker identity; that
worker must be active in the same warehouse and have the primary qualification of the driver queue.
Task-board ignores a caller-supplied display name and stores its authoritative worker snapshot.
Unassigned tasks remain dispatcher work. An assigned task is visible only to that driver. A waiting
identity-free shared task is visible to every qualified warehouse driver until one takes it, after
which only the actual assignee retains access. The worker feed always emits nullable
`driverAudience`: null classifies ordinary work, while a non-null value classifies logistics driver
work. Only the private source replan boundary may replace audience under the shared task/entry
version fence; public board movement does not.

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
| `TaskBoardOrderingService` | Manager-owned move, swap, pin and rollover operations |
| `TaskBoardQueuePositionCoordinator` | Advisory locks, stream fences and persisted queue/pin ordering only |
| `TaskBoardRoutePayloadCodec` | The single canonical route JSON and fingerprint codec |
| `DriverTaskAudienceService` | Logistics-driver audience shape, qualification, visibility and execution authorization |
| `WorkforceService` | Stable worker/group API facade over three lifecycle owners |
| `WorkforceProfileService` | Worker profile and qualification mutation with the credential lock span |
| `WorkforceCredentialLifecycleService` | Durable auth credential intents, completion/failure fencing, reconciliation and deletion recovery |
| `WorkforceGroupService` | Group membership, availability and current-group intervals |
| `WorkforceReadProjectionService` | Read-only worker and group DTO assembly |

The dependency graph is acyclic. Registration and mutation share the route
codec; registration, mutation, worker execution and ordering use the narrow
queue-position coordinator. Neither technical collaborator owns authorization,
source status, worker transitions or another domain aggregate.

The workforce graph is also acyclic. Profile commands call the narrow
credential lifecycle and read projection, while group commands use only the
read projection. Credential recovery never calls the facade or the group
owner, and no collaborator exposes more than 15 direct dependencies.

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
| `/api/warehouses/{warehouseId}/task-board/**` | Warehouse-authorized user | Board reads and operational commands |
| `/api/warehouses/{warehouseId}/task-board/kpi-settings/**` | Warehouse manager/admin | Palette and effective schedule revisions |
| `/api/worker/v1/**` | Worker credential and `worker.tasks` scope | Context, feed, detail, actions, evidence reservations, devices, and events |
| `/api/internal/task-board/v1/maintenance/**` | Exact maintenance-service identity | Routing and catalog preflight |
| `/api/internal/task-board/v1/tasks/**` | Exact source service identity | Idempotent task synchronization and evidence reads |
| `/api/internal/task-board/v1/logistics/**` | Exact logistics-service identity | Driver/equipment task integration |
| `/api/internal/queue-definitions/**` | Allow-listed service identity | Durable queue usage references |

Private paths are service-to-service boundaries and are never exposed as client
shortcuts. Their exact `principal_type`, `client_id`, scope, source ownership,
and warehouse checks are part of the contract.

## Worker stream and offline execution

`GET /api/worker/v1/events` is an SSE invalidation stream. The current producer
emits a `FEED_CHANGED` signal when a client subscribes and for subsequent
changes; the worker app also performs periodic authoritative REST refresh.
Payloads are not a complete task projection.

`WorkerTaskDetail.source` always appears and is null for ordinary work. For source-owned work it
contains only the existing immutable source type and ID. A worker client can use a
`LOGISTICS_DRIVER_TASK` ID to load the logistics-owned trip details; task-board does not copy that
payload or its business state.

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

## Security and isolation

- All API chains validate JWT issuer/audience; worker routes require the narrow
  worker scope.
- Manager commands enforce user role, warehouse access, and command-specific
  write permission in `WarehouseAccessAuthorizer` and services.
- Internal task, queue-reference, maintenance, and logistics operations require
  exact service identity/scope and validate source ownership.
- Auth-service remains the credential owner. Task-board persists only the
  operational credential workflow state needed for reconciliation.
- CORS uses explicit panel and worker origins. Browser/mobile clients use the
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
brokers, and `TASK_BOARD_KAFKA_ENABLED=true`.

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

Changes to worker actions or streams require worker-app contract/unit tests and
an exact APK build. Changes to source-task integration, Kafka families, or
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
- [Task-board application service](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardService.java)
- [Task-board read projection](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardReadProjectionService.java)
- [External task registration](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalRegistrationService.java)
- [External task mutation](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalMutationService.java)
- [Queue position coordinator](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardQueuePositionCoordinator.java)
- [Worker application service](src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerTaskBoardService.java)
- [Workforce service](src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkforceService.java)
- [Production safety validator](src/main/java/dev/buhanzaz/rwms/taskboard/config/TaskBoardProductionSafetyValidator.java)
- [Event store](src/main/java/dev/buhanzaz/rwms/taskboard/eventing/TaskBoardEventStore.java)
- [Runtime flow map](../../docs/project-knowledge/runtime-flows.md)
