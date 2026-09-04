# `task-board-service` API

Status: reference guide for the current API. The canonical machine-readable
specification is the [OpenAPI contract](../../contracts/openapi/task-board-service.yaml).
If this guide, the contract, and the code disagree, the OpenAPI contract and
current service code take precedence.

## Why this service exists

`task-board-service` is the sole owner of the worker and qualification registry,
queues, assignments, and operational task state. `maintenance-service` and
`logistics-service` may request their work through a private contract, but they
do not execute queue transitions or mutate its data directly.

That solves practical distributed-WMS problems:

- A task has one owner for status, queue position, assignment, and timing facts;
  the browser, worker app, and source services cannot drift into competing task
  states.
- A shared process is not manually copied warehouse by warehouse: a global queue
  produces stable warehouse projections while history and assignments stay local.
- A retried command, worker-app reconnect, or Kafka delivery cannot create a
  second task, worker action, or photo reservation.
- A worker sees only authorized work, and the JWT fixes warehouse and
  `workerId`; neither can be substituted through request data.
- A draining warehouse does not accept new work, and a relocation checks
  direction at both warehouses before the local mutation.

## Why it is designed this way

- **One command owner.** An external source describes work with a stable
  `externalTaskId`; this service creates route entries, positions, assignments,
  timers, and facts. There is no browser saga and no two services changing one
  task concurrently.
- **Global definition, local projection.** `QueueDefinition` holds the shared
  `GENERAL` standard: order, visibility, limits, and qualifications.
  `WorkQueue` is a derived warehouse queue with its own stable UUID, preserving
  task history. The special `LOGISTICS_DRIVER` queue is configured only for its
  own warehouse.
- **A version is not cosmetic.** Commands use `expectedVersion`; a routed task
  also distinguishes entry and task versions. This prevents lost concurrent
  changes to position, state, or route composition.
- **Idempotency where the network is unreliable.** A source task is identified
  by `client_id + externalTaskId`; a worker action and evidence reservation
  require an `Idempotency-Key` equal to `operationId`; KPI activation has its
  own operation UUID. These mechanisms do not substitute for one another.
- **Offline is still controlled.** Context returns a time-limited offline lease.
  On reconnect, the service accepts `occurredAt` only within that lease and
  checks the action against its version and operation ID.
- **SSE is invalidation, not truth.** The stream contains no task snapshot. The
  worker app reloads its authorized feed after a signal, avoiding disclosure and
  stale long-lived projections.
- **Private paths are recipient-specific.** An exact service credential and
  scope prevent `maintenance-service` from using logistics routes, and prevent
  logistics from changing the shared catalog or another source's task.

## How to connect

OpenAPI uses the service-local base `/api`. The gateway publishes this service
under `/api/task-board/**`: for example,
`GET /api/task-board/worker/v1/feed` reaches the service-local
`GET /api/worker/v1/feed`. The browser uses same-origin `/api/**`; Android apps
use the public gateway address.

Paths in the tables below are relative to service-local `/api`. Every public
request uses `Authorization: Bearer <JWT>`. Do not expose or call
`/api/internal/**`, service ports, or service databases from the panel, manager
app, or worker app. The private API is only for service-to-service callers with
separate credentials.

## Model and key invariants

| Entity                       | Purpose and rule                                                                                                                                                        |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `WorkerClass`                | Global qualification. It can be deleted only when no queue binding, group, or worker qualification references it; an in-use class must be deactivated.                  |
| `QueueDefinition`            | Shared `GENERAL` standard. A complete reordering sends the full catalog and each item version; holding queues remain terminal.                                          |
| `WorkQueue`                  | Physical warehouse projection with a stable UUID. A manager does not manually replace a `GENERAL` queue.                                                                |
| `Worker` and `WorkerGroup`   | Warehouse-scoped entities. A group has one worker class, active memberships, and independent operational availability. Disabling a group first returns its active work. |
| `BoardTask`                  | Task with source-facing `externalTaskId`, date, priority, lane, and route. `taskVersion` fences task-wide commands.                                                     |
| `QueueEntry`                 | One route step in a queue. Its `version` fences take/pause/resume/complete and entry movement.                                                                          |
| `Assignment` and `TimeEvent` | Server facts for assignment and time, not UI-local state.                                                                                                               |
| `TaskEvidence`               | Server-attributed reservation/result. A worker never supplies trusted `workerId` or group data in the body.                                                             |
| KPI settings                 | One company palette and work schedule share a version fence; a schedule is first pending, then separately and idempotently activated for every warehouse.             |

On `409`, read the current projection and deliberately decide whether to retry.
Do not substitute a newer version into a command whose business decision was
made against stale data.

## Authorization

| Surface                                     | Requirement                                                                                                                                                                             |
| ------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Read global classes and definitions         | `principal_type=USER` and `rwms.read`.                                                                                                                                                  |
| Change classes and global queue definitions | `USER`, `rwms.write`, global `SYSTEM_ADMIN` or `WMS_ADMIN` role.                                                                                                                        |
| Warehouse queues, workforce, and KPI        | `USER`, the matching `rwms.read`/`rwms.write`, and warehouse grant at least `VIEW`/`MANAGE`.                                                                                            |
| Operational board                           | User: `rwms.read` or `rwms.write` with a warehouse grant; worker: `WORKER`, `worker.tasks`, and its own `warehouse_id`. Manager-only commands require `USER`, `rwms.write`, and `EDIT`. |
| Worker API                                  | `WORKER` and `worker.tasks`; `worker_id` and `warehouse_id` come exclusively from the JWT.                                                                                              |
| Queue reference                             | `SERVICE` with the exact allow-listed maintenance credential and `queue-registry.write`.                                                                                                |
| Maintenance preflight and task sync         | `SERVICE` with matching `sub`/`client_id` and the exact task-sync scope; the service derives source ID from the credential.                                                             |
| Dedicated logistics API                     | Only `logistics-service` with exact `task-board.logistics`.                                                                                                                             |

The development bypass is constrained to the `dev` profile and is not a
production contract.

## Public API: catalog and queues

Table paths are relative to `/api`. JSON fields, enums, and responses are in
[OpenAPI](../../contracts/openapi/task-board-service.yaml).

| `operationId`                   | Method and path                                    | Purpose and important rule                                                                                                             |
| ------------------------------- | -------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `listQueueDefinitions`          | `GET /queue-definitions`                           | Returns the shared catalog. `GENERAL` definitions are one standard for all warehouses; the driver definition is configured separately. |
| `createQueueDefinition`         | `POST /queue-definitions`                          | Creates a global `GENERAL` definition and derived warehouse projections.                                                               |
| `updateQueueDefinition`         | `PUT /queue-definitions/{id}`                      | Version-fenced replacement of a definition and synchronization of projections.                                                         |
| `deleteQueueDefinition`         | `DELETE /queue-definitions/{id}?expectedVersion=`  | Deletes only an unused global definition.                                                                                              |
| `reorderQueueDefinitions`       | `PUT /queue-definitions/order`                     | Accepts the complete version-fenced global-catalog order.                                                                              |
| `listWorkQueues`                | `GET /warehouses/{warehouseId}/work-queues`        | Returns the selected warehouse's stable physical queues.                                                                               |
| `updateDriverQueue`             | `PUT /warehouses/{warehouseId}/driver-queue`       | Creates or updates only that warehouse's `LOGISTICS_DRIVER` queue; the request cannot choose a definition.                             |
| `getWarehouseQueueCapabilities` | `GET /warehouses/{warehouseId}/queue-capabilities` | Returns active visible routing capabilities.                                                                                           |
| `listWorkerClasses`             | `GET /worker-classes`                              | Returns the global qualification catalog.                                                                                              |
| `createWorkerClass`             | `POST /worker-classes`                             | Creates a global worker class.                                                                                                         |
| `updateWorkerClass`             | `PUT /worker-classes/{id}`                         | Version-fenced class update.                                                                                                           |
| `deleteWorkerClass`             | `DELETE /worker-classes/{id}?expectedVersion=`     | Deletes only an unused class.                                                                                                          |

## Public API: workforce

| `operationId`                      | Method and path                                                             | Purpose and important rule                                                                          |
| ---------------------------------- | --------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `listWorkers`                      | `GET /warehouses/{warehouseId}/workers`                                     | Lists warehouse workers.                                                                            |
| `createWorker`                     | `POST /warehouses/{warehouseId}/workers`                                    | Creates a profile and, where requested, starts credential provisioning through the auth boundary.   |
| `updateWorker`                     | `PUT /warehouses/{warehouseId}/workers/{id}`                                | Version-fenced replacement of profile, qualifications, and the necessary credential operation.      |
| `deleteWorker`                     | `DELETE /warehouses/{warehouseId}/workers/{id}?expectedVersion=`            | Deletes a worker after dependency checks and the credential-side operation.                         |
| `setWorkerCurrentGroup`            | `PUT /warehouses/{warehouseId}/workers/{id}/current-group`                  | Version-fenced current-group change.                                                                |
| `resetWorkerCredentials`           | `POST /warehouses/{warehouseId}/workers/{id}/credentials/reset`             | Resets a credential through an explicit external operation; the password never enters facts/logs.   |
| `disableWorkerCredentials`         | `POST /warehouses/{warehouseId}/workers/{id}/credentials/disable`           | Starts version-fenced credential disabling.                                                         |
| `enableWorkerCredentials`          | `POST /warehouses/{warehouseId}/workers/{id}/credentials/enable`            | Starts version-fenced credential enabling.                                                          |
| `reconcileWorkerCredentialDisable` | `POST /warehouses/{warehouseId}/workers/{id}/credentials/reconcile-disable` | Reconciles an expired or failed credential operation with auth-service instead of assuming success. |
| `reconcileWorkerDeletion`          | `POST /warehouses/{warehouseId}/workers/{id}/deletion/reconcile`            | Retries deletion reconciliation after the external credential step.                                 |
| `listWorkerGroups`                 | `GET /warehouses/{warehouseId}/worker-groups`                               | Lists groups with current operational status.                                                       |
| `createWorkerGroup`                | `POST /warehouses/{warehouseId}/worker-groups`                              | Creates a group of one worker class with memberships.                                               |
| `updateWorkerGroup`                | `PUT /warehouses/{warehouseId}/worker-groups/{id}`                          | Version-fenced replacement of group and membership.                                                 |
| `deleteWorkerGroup`                | `DELETE /warehouses/{warehouseId}/worker-groups/{id}?expectedVersion=`      | Deletes only an unused group.                                                                       |
| `disableWorkerGroup`               | `POST /warehouses/{warehouseId}/worker-groups/{id}/disable`                 | Returns the group's active work, then marks it unavailable; a reason is mandatory.                  |
| `enableWorkerGroup`                | `POST /warehouses/{warehouseId}/worker-groups/{id}/enable`                  | Restores the group to operational availability.                                                     |

## Public API: manager task board and KPI

| `operationId`                            | Method and path                                                                                   | Purpose and important rule                                                                                                           |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `getTaskBoard`                           | `GET /warehouses/{warehouseId}/task-board`                                                        | Aggregate ordinary board: all active real cards and the bounded waiting window per queue. `If-None-Match` uses a weak semantic ETag. |
| `getDriverLogisticsBoard`                | `GET /warehouses/{warehouseId}/task-board/logistics`                                              | Separate driver board: sticky current lane plus non-empty scheduled-date columns, without ordinary repair queues.                    |
| `getCompanyKpiPalette`                   | `GET /task-board/kpi-palette`                                                                      | Returns the one shared KPI palette for the authenticated company; company is derived from the signed principal.                      |
| `replaceCompanyKpiPalette`               | `PUT /task-board/kpi-palette`                                                                      | Global-management, company-version-fenced replacement of shared ranges and overdue color.                                            |
| `getCompanyKpiSettings`                  | `GET /task-board/kpi-settings`                                                                    | Returns the palette and schedule shared by the authenticated company; no warehouse request value is accepted.                        |
| `replacePendingCompanyKpiWorkSchedule`   | `PUT /task-board/kpi-settings/work-schedule`                                                      | Stores the company's one pending current-UTC-day or future-effective schedule.                                                       |
| `deletePendingCompanyKpiWorkSchedule`    | `DELETE /task-board/kpi-settings/work-schedule/pending?expectedVersion=`                          | Deletes the company's pending schedule.                                                                                              |
| `activateCompanyKpiSettings`             | `POST /task-board/kpi-settings/activate`                                                          | Activates the shared pending schedule; UUID `Idempotency-Key` is required.                                                           |
| `listEligibleWorkerGroups`               | `GET /warehouses/{warehouseId}/task-board/queues/{queueId}/eligible-groups`                       | Returns currently eligible groups for a physical queue.                                                                              |
| `getTaskEntryHistory`                    | `GET /warehouses/{warehouseId}/task-board/entries/{entryId}/history`                              | Durable entry time-event history.                                                                                                    |
| `registerBoardTask`                      | `POST /warehouses/{warehouseId}/task-board/tasks`                                                 | Creates a manager-originated task with a complete ordered route.                                                                     |
| `getBoardTaskByExternalId`               | `GET /warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}`                  | Returns the source-facing task registration in this warehouse scope.                                                                 |
| `cancelBoardTaskByExternalId`            | `POST /warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}/cancel`          | Version-fenced task cancellation with an auditable reason.                                                                           |
| `takeTaskEntry`                          | `POST /warehouses/{warehouseId}/task-board/entries/{entryId}/take`                                | Version-fenced entry take; for a worker caller, the actor is derived from the JWT.                                                   |
| `pauseTaskEntry`                         | `POST /warehouses/{warehouseId}/task-board/entries/{entryId}/pause`                               | Version-fenced pause of an active entry.                                                                                             |
| `resumeTaskEntry`                        | `POST /warehouses/{warehouseId}/task-board/entries/{entryId}/resume`                              | Resumes a paused entry using its version.                                                                                            |
| `completeTaskEntry`                      | `POST /warehouses/{warehouseId}/task-board/entries/{entryId}/complete`                            | Completes an entry using its version and completion rules.                                                                           |
| `setTaskPinned`                          | `POST /warehouses/{warehouseId}/task-board/tasks/{taskId}/pin`                                    | Pins or unpins every route queue without changing position.                                                                          |

### Read a board with ETag

```http
GET /api/task-board/warehouses/{warehouseId}/task-board
Authorization: Bearer <JWT>
If-None-Match: W/"task-board-..."
```

For unchanged semantic state the service returns `304` and the ETag. Do not
treat a falling `remainingSeconds` alone as a board change: clients derive the
rolling timer from server time, timer state, and `nextTransitionAt`.

The ordinary board has no date or shadow query dimensions and no public move or date-swap
commands. Each queue returns all real `IN_PROGRESS`/`PAUSED` cards plus its first
`availableTaskLimit` real `WAITING` cards in server priority order; `TAKE` enforces the same window.
Dated driver and shipment planning remains on the logistics board.

## Public API: worker app

These operations use the gateway namespace `/api/task-board/worker/v1` and a
worker JWT only.

| `operationId`               | Method and path                                           | Purpose and important rule                                                                                                                              |
| --------------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `getWorkerContext`          | `GET /worker/v1/context`                                  | Returns profile, groups, qualifications, visible categories, server-time anchor, revision, and a 24-hour offline lease.                                 |
| `getWorkerFeed`             | `GET /worker/v1/feed?cursor=&limit=`                      | Cursor page of visible entries; cursor is opaque and limit is 1–50. A changed revision between pages returns `409`; the first page supports ETag/`304`. |
| `getWorkerTaskDetail`       | `GET /worker/v1/entries/{entryId}`                        | Sanitized detail. An invisible entry is indistinguishable from `404`.                                                                                   |
| `streamWorkerEvents`        | `GET /worker/v1/events`                                   | Authorization-filtered SSE invalidations; payload contains ID/revision, not a snapshot.                                                                 |
| `applyWorkerAction`         | `POST /worker/v1/entries/{entryId}/actions`               | `Idempotency-Key` must equal `operationId`; the action is accepted only within its referenced offline lease.                                            |
| `reserveWorkerTaskEvidence` | `POST /worker/v1/entries/{entryId}/evidence-reservations` | Reserves a stable `evidenceId` before media upload; an exact replay returns the existing reservation.                                                   |
| `registerWorkerDevice`      | `PUT /worker/v1/devices/{installationId}`                 | Creates or replaces a binding only for the current worker.                                                                                              |
| `unregisterWorkerDevice`    | `DELETE /worker/v1/devices/{installationId}`              | Deletes only the authenticated worker's installation binding.                                                                                           |

### Offline worker action

```http
POST /api/task-board/worker/v1/entries/{entryId}/actions
Authorization: Bearer <worker JWT>
Idempotency-Key: 1f0a7c23-7e4f-4f96-96ef-7a03c2cb2b87
Content-Type: application/json

{
  "operationId": "1f0a7c23-7e4f-4f96-96ef-7a03c2cb2b87",
  "action": "COMPLETE",
  "expectedVersion": 12,
  "occurredAt": "2026-08-07T10:45:00Z",
  "offlineLeaseId": "bc7e8b75-e1b5-4f90-bf74-8926eb8b9b11"
}
```

An exact replay returns the earlier applied result. A different body with the
same key, expired lease, stale version, or invalid state returns `409`; do not
create a new operation ID until it is clear that the first command was not
accepted.

## Private API: queue and maintenance preflight

These are service-local private paths: `/api/internal/...`. They are not routed
to interactive clients.

| `operationId`                           | Method and path relative to `/api`                                                            | Recipient and rule                                                                                                       |
| --------------------------------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `registerQueueReference`                | `POST /internal/queue-definitions/{queueDefinitionId}/references`                             | Maintenance credential only. Registers a typed usage reference and blocks deletion of an in-use definition.              |
| `deleteQueueReference`                  | `DELETE /internal/queue-definitions/references/{type}/{externalReferenceId}?expectedVersion=` | Maintenance credential only. Deletes a known typed reference.                                                            |
| `preflightMaintenanceRouting`           | `POST /internal/task-board/v1/maintenance/routing-preflight`                                  | Read-only bulk check of UUID-based global definitions and warehouse bindings; it creates, reserves, and changes nothing. |
| `preflightMaintenanceCatalogRouting`    | `POST /internal/task-board/v1/maintenance/catalog-routing-preflight`                          | Read-only global-catalog check before a maintenance command is prepared.                                                 |
| `getInternalWarehouseQueueCapabilities` | `GET /internal/task-board/v1/warehouses/{warehouseId}/queue-capabilities`                     | Narrow private view of active visible process capabilities for a task-sync credential.                                   |

`MaintenanceRoutingPreflightResponse` separately reports a missing global
definition, a missing warehouse binding, and a mismatch (`TYPE`, `ACTIVE`, or
`HIDDEN`). A source must not guess a queue name or create one as a side effect
of preflight.

## Private API: source task sync and logistics

| `operationId`                             | Method and path relative to `/api`                                                        | Recipient and rule                                                                                                       |
| ----------------------------------------- | ----------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `registerExternalBoardTask`               | `POST /internal/task-board/v1/tasks`                                                      | Maintenance or logistics with exact task-sync credential. Source client + `externalTaskId` define replay-safe ownership. |
| `getExternalBoardTask`                    | `GET /internal/task-board/v1/tasks/{externalTaskId}`                                      | Returns only the authenticated source's task.                                                                            |
| `updateExternalBoardTaskBeforeStart`      | `PUT /internal/task-board/v1/tasks/{externalTaskId}`                                      | Changes source content only before execution begins.                                                                     |
| `cancelExternalBoardTask`                 | `POST /internal/task-board/v1/tasks/{externalTaskId}/cancel`                              | Version-fenced cancellation of source-owned work.                                                                        |
| `cancelExternalBoardTaskIfPreStart`       | `POST /internal/task-board/v1/tasks/{externalTaskId}/cancel-if-pre-start`                 | Returns typed outcome: cancelled, already cancelled, started, or version conflict.                                       |
| `relocateExternalBoardTask`               | `POST /internal/task-board/v1/tasks/{externalTaskId}/relocate`                            | Warehouse relocation after OUTGOING admission at source and INCOMING admission at target.                                |
| `setExternalDriverTaskLane`               | `POST /internal/task-board/v1/tasks/{externalTaskId}/lane`                                | Changes a source-owned driver-task lane under `expectedTaskVersion`.                                                     |
| `getExternalDriverTaskCompletionEvidence` | `GET /internal/task-board/v1/tasks/{externalTaskId}/completion-evidence`                  | Returns selected READY result photo after DONE only in the permitted source scope.                                       |
| `registerLogisticsEquipmentMovementTask`  | `POST /internal/task-board/v1/logistics/equipment-movement-tasks`                         | Logistics only. Logistics provides immutable equipment facts; task-board derives title, route, and queue.                |
| `getLogisticsEquipmentMovementTask`       | `GET /internal/task-board/v1/logistics/equipment-movement-tasks/{externalTaskId}`         | Typed snapshot of the matching logistics-owned movement task.                                                            |
| `cancelLogisticsEquipmentMovementTask`    | `POST /internal/task-board/v1/logistics/equipment-movement-tasks/{externalTaskId}/cancel` | Version-fenced cancellation of a typed logistics task.                                                                   |
| `getInternalDriverLogisticsBoard`         | `GET /internal/task-board/v1/logistics/warehouses/{warehouseId}/board`                    | Logistics-only driver board.                                                                                             |
| `moveInternalDriverLogisticsTask`         | `POST /internal/task-board/v1/logistics/tasks/{externalTaskId}/move`                      | Version-fenced logistics driver-entry move by lane/date/index.                                                           |

## Typical safe flow

1. An administrator defines global worker classes and `GENERAL` queue
   definitions. The service creates and synchronizes physical queues on active
   warehouses itself.
2. A warehouse manager creates workers and groups, configures its local driver
   queue, and defines KPI scheduling.
3. `maintenance-service` performs a read-only preflight before its own command.
   A source service then registers external work with a stable `externalTaskId`.
4. A manager reads the board with an ETag; the worker app reads context, feed,
   and events. Every transition carries the current version.
5. Before upload, a worker reserves evidence. Task-board publishes an owner
   proof that media-service uses to constrain task-media access.
6. After completion, a source service reads only contract-defined completion
   evidence or events; it never reads the task-board database.

## Errors, concurrency, and retries

Errors use `application/problem+json` with typed `code`, field violations, and
correlation data.

| Status | Expected when                                                                                                                                    | Client action                                                                               |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------- |
| `400`  | UUID/date/body is invalid, a required value is absent, a schedule is invalid, or request validation fails.                                       | Correct the command; do not blindly retry it.                                               |
| `401`  | Bearer token is missing, expired, or invalid.                                                                                                    | Obtain a valid token.                                                                       |
| `403`  | Principal type, scope, global role, warehouse grant, or private service identity is insufficient.                                                | Use exactly the contract-defined credential; do not broaden scope speculatively.            |
| `404`  | A resource is absent or intentionally hidden by source/worker authorization.                                                                     | Verify identity and scope; do not create a replacement by name.                             |
| `409`  | Version is stale, a task-state/queue rule fails, an idempotent replay differs, lease expires, feed revision changes, or date swap is incomplete. | Read current state and decide whether a new deliberate command is needed.                   |
| `502`  | An auth credential operation or other declared dependency is unavailable.                                                                        | Preserve context and use the dedicated reconciliation route when the contract provides one. |

## Events

The mutation and its outbox fact are committed in one local PostgreSQL
transaction. AsyncAPI: [task-board events](../../contracts/events/task-board-events.yaml);
JSON Schema: [v1](../../contracts/events/task-board/task-board-events-v1.schema.json).

- Kafka delivery is at-least-once; the key is `aggregateId`, while `eventId` is
  the consumer inbox-deduplication identity.
- Facts are published for board tasks, queue entries, workers/classes/groups,
  work queues, queue references, media owner proof, task evidence, and daily
  group KPI.
- `rwms.task-board.entry-owner-proof.v1` carries a monotonic authorization
  proof. Media-service rejects WORKER access until the proof is active and
  contains the authenticated worker ID.
- The task-evidence channel carries a server-attributed fact to a downstream
  owner; human worker and group names are intentionally not replicated.
- `group-kpi-day` is replaceable warehouse-local evidence for
  `analytics-service`, not a source of commands back into task-board.

## What this API intentionally does not do

- It does not expose private endpoints to browser or Android clients.
- It does not trust `workerId`, warehouse identity, or source client identity
  supplied by a public body.
- It does not let a warehouse edit the shared `GENERAL` process standard as a
  local copy.
- It does not turn SSE into a task-data transport or an offline cache into
  authoritative state.
- It does not move workflow or compensation into the gateway or UI.
- It does not let a source service mutate a task-board queue, assignment,
  time event, or database directly.

Those constraints provide predictable retries, restart recovery, and correct
warehouse isolation while several services work in parallel.

## Authoritative sources

- [OpenAPI contract](../../contracts/openapi/task-board-service.yaml)
- [AsyncAPI event contract](../../contracts/events/task-board-events.yaml)
- [Task board application service](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardService.java)
- [Queue registry owner](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/RegistryService.java)
- [Worker API service](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerTaskBoardService.java)
- [Authorization boundaries](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/security/)
- [Gateway route boundary](../../services/api-gateway-service/README.md)
