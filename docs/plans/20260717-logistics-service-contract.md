# Stage 8 Logistics Service Contract

Status: `APPROVED`

The user's 2026-07-17 instruction to begin Stage 8 while Stage 7 remains in
progress explicitly authorizes this constrained, parallel Stage 8 contract.
That authorization is recorded in `docs/plans/ACTIVE_STAGE.md`; it neither
marks Stage 7 complete nor authorizes a logistics panel cutover.

## Authority and evidence classification

### Approved target requirements

- `logistics-service` owns rental-return workflows and conflicts, shipment
  planning/preparation, inter-warehouse transfer workflow state, logistics
  saga attempts, and immutable party/driver/passport/equipment snapshots.
- It owns one logistics bounded context with three document aggregate types:
  `RETURN`, `SHIPMENT`, and `TRANSFER`. They share workflow guards because
  they compete for the same cabins, while each type keeps its own event stream
  and aggregate-family topic.
- Canonical cabins, cabin status, warehouse assignment, equipment balances,
  operation leases, fencing tokens, and equipment allocation holds remain
  `asset-service` aggregates. `logistics-service` stores only opaque returned
  IDs, versions, fences, and observed state for reconciliation.
- A return is never silently discarded after a canonical cabin mutation. A
  timeout or conflicting downstream observation becomes a durable
  reconciliation state with audit/retry data.
- A shipment obtains every required equipment allocation hold before work and
  commits every hold only after preparation confirmation. A cancellation
  releases task, hold, and lease effects idempotently.
- A transfer changes a cabin to `IN_TRANSFER` at departure; its warehouse
  changes only at a separately confirmed destination arrival. Departure and
  arrival are irreversible, distinct commands.
- Every mutable public command has optimistic concurrency. Retriable create
  and effect commands require a subject-bound `Idempotency-Key`.
- Domain facts use the service-local event store, transactional outbox, Kafka
  aggregate-family topics, consumer inbox deduplication, bounded retry, DLT,
  and aggregate-version-gap quarantine.

### Legacy and browser evidence, not transport authority

- Legacy proves rental statuses/events, reservation rows, worker `DRIVER`
  classification, and media building blocks; it does not prove a complete
  return, shipment, or transfer command aggregate.
- `panel/src/features/logistics/` persists browser envelopes, recovery
  journals, mock task attempts, and IndexedDB media. These are workflow
  evidence only. No browser DTO, local-storage row, seed UUID, ten-minute
  claim TTL, or client-side lock becomes a production API or migration input.
- Legacy `/api/mobile/**` anonymous-admin behavior is rejected. All public
  logistics APIs validate a local Bearer JWT and downstream services validate
  their own service credentials.

### Explicit v1 boundaries for unresolved product capability

| Capability | v1 decision |
|---|---|
| Company, tenant, contract, and driver directory | Store a trimmed immutable text snapshot only. No directory, legal identity, alias, or mutable foreign key is owned or inferred. |
| Reservation linkage | Not represented in the logistics schema or API. A shipment does not assert, create, update, or complete a reservation. |
| Location/accounting correction | No command, route, event, table, or compensating movement is implemented until its dual-warehouse approval policy is separately approved. |
| Post-shipment reversal | Not a cancellation. An irreversible shipped/ departed document enters durable reconciliation on a downstream discrepancy; reverse logistics remains `UNKNOWN`. |
| Retention/orphan/legal-hold policy | Remains media-service policy. Logistics stores only opaque media IDs and never object keys, URLs, or signed access tokens. |

The first three rows deliberately satisfy the roadmap's product gates by
refusing to invent an owner or behavior. They remain explicit Stage 8 exit
constraints, not hidden fallbacks.

## Service boundary

`logistics-service` owns:

- document identity, type, lifecycle/version, warehouse scope, actor and
  correlation snapshots;
- immutable party, driver, cabin, passport, expected/factual-contents, and
  source-allocation snapshots;
- per-cabin logistics workflow guards and truthful guard/reconciliation state;
- external attempt ledgers for asset, task-board, maintenance, warehouse, and
  media interactions;
- opaque asset lease/hold references, task references, maintenance source
  references, and media references;
- service-local event streams/projections/snapshots, idempotency records,
  outbox, inbox, checkpoints, quarantines, and DLT records.

It does not own or directly mutate:

- rental-item number, status, warehouse, passport, tenant data, or equipment
  balance;
- physical equipment allocation, stock movement, hold, lease, or fence;
- warehouse identity/timezone, company/customer/reservation/contract records,
  worker/driver directory, task-board execution, maintenance repair, media
  bytes, credentials, dossier projection, or analytics projection.

There are no cross-database foreign keys, joins, shared entities, shared
mutable DTOs, or gateway-mediated service-to-service calls.

## Aggregate family and state model

Each document has a UUID, an `aggregateVersion`, a document `version`, a
warehouse scope, a deterministic command fingerprint, and an append-only
attempt/audit sequence. A child line has its own revision only where a late
attempt must not overwrite another line; it never bypasses the document CAS.

### Return document

One return document can contain multiple cabin lines. It is an operator-facing
receipt, but it is a real logistics aggregate rather than a browser envelope.

| State | Allowed transition | Meaning |
|---|---|---|
| `DRAFT` | `REGISTERING`, `CANCELLED` | Local details are editable; no asset effect exists. |
| `REGISTERING` | `INSPECTION_REQUIRED`, `CONFLICT`, `RECONCILIATION_REQUIRED` | The durable intake saga is validating an exact rented-cabin/tenant/version snapshot and acquiring its canonical lease. |
| `INSPECTION_REQUIRED` | `ACCEPTING`, `ESTIMATE_PENDING`, `CONFLICT`, `RECONCILIATION_REQUIRED` | Asset has accepted the fenced transition to `AFTER_RENT`; factual contents and return media are frozen. |
| `ACCEPTING` | `ACCEPTED`, `CONFLICT`, `RECONCILIATION_REQUIRED` | The undamaged acceptance saga validates 1–20 ready inspection-media references and invokes the fenced asset settlement. |
| `ESTIMATE_PENDING` | `ESTIMATE_REQUESTED`, `CONFLICT`, `RECONCILIATION_REQUIRED` | A proven shortage is being registered with maintenance by stable return/line source identity. |
| `ESTIMATE_REQUESTED` | terminal for v1 | The downstream maintenance workflow owns repair/acceptance. Logistics retains immutable source/reconciliation evidence only. |
| `ACCEPTED`, `CANCELLED` | terminal | The service records the immutable outcome and releases every held external effect. |
| `CONFLICT`, `RECONCILIATION_REQUIRED` | explicit retry/reconcile only | A visible server state, never a transient dialog or implicit rollback. |

Return intake requires a cabin currently `RENTED`, an exact normalized match to
the cabin's current tenant snapshot, the submitted asset version, and an asset
lease/fence. It advances the canonical cabin only through the narrow fenced
asset command to `AFTER_RENT`. A return from another warehouse, a written-off
cabin, a stale tenant/version, an active competing workflow, or a duplicated
active line is a `409` conflict.

For undamaged acceptance, the return contains 1–20 READY inspection media IDs
and a factual-contents snapshot accepted by the asset settlement command. The
canonical terminal transition is `AFTER_RENT -> FREE`; tenant and shipment
date are cleared only by asset-service. A shortage never silently writes off or
returns physical equipment: logistics persists the exact delta and requests a
maintenance source upsert keyed by `returnId:lineId`.

Once a line has crossed the asset intake boundary it is not deleted or
silently restored to `RENTED`. Any attempted correction after that point is a
new explicit reconciliation record; its final business policy remains
`UNKNOWN`.

### Shipment document

One shipment has one origin warehouse, a party and driver snapshot, and one or
more distinct target-cabin lines. A line records immutable pre-plan and
planned-contents snapshots plus source equipment allocations.

| State | Allowed transition | Meaning |
|---|---|---|
| `DRAFT` | `PREPARING`, `CANCELLED` | Local planning only; no hold or lease exists. |
| `PREPARING` | `AWAITING_CONFIRMATION`, `CONFLICT`, `RECONCILIATION_REQUIRED`, `CANCELLED` | Asset leases, all required equipment holds, and external preparation-task attempts are durable. |
| `AWAITING_CONFIRMATION` | `CONFIRMING_PREPARATION`, `CONFLICT`, `RECONCILIATION_REQUIRED`, `CANCELLED` | Every required task is registered; incompatible plan edits are frozen. |
| `CONFIRMING_PREPARATION` | `SHIPPED`, `CONFLICT`, `RECONCILIATION_REQUIRED` | The service commits holds and uses matching asset leases/fences to assign cabin rental state. |
| `SHIPPED`, `CANCELLED` | terminal | Shipment confirmation/cancellation is immutable for v1. |
| `CONFLICT`, `RECONCILIATION_REQUIRED` | explicit retry/reconcile only | Durable truth while a downstream effect is uncertain or rejected. |

Planning accepts only distinct `FREE` target cabins at the origin warehouse,
their submitted asset versions, a nonblank party snapshot, a nonblank driver
snapshot, shipment date, and valid allocation snapshots. Each target has a
stable `externalTaskId`; exact replays return the same task reference while a
changed target/plan under the same key conflicts. The task-board must accept a
single logistics preparation task per target before the shipment may enter
`AWAITING_CONFIRMATION`.

`CONFIRMING_PREPARATION` first confirms the task state, commits every active
asset hold, and then applies the fenced asset shipment action. Asset-service
performs the canonical `FREE -> RENTED` transition and stores party/shipment
snapshot according to its own invariant. A partial/ambiguous multi-effect
outcome stays in `RECONCILIATION_REQUIRED`; it never declares `SHIPPED` from a
client-side assumption.

Cancellation is legal only before confirmation begins. It sends idempotent
task cancellation, releases every active/committed hold as applicable, and
releases every active lease. It reaches `CANCELLED` only after each external
effect has a confirmed terminal/reconciled result.

### Inter-warehouse transfer document

One transfer has one origin and one distinct destination warehouse, plus
independently tracked cabin lines. It contains only snapshot party/driver data
where needed for audit; it does not become a vehicle or transport-document
directory.

| Document state | Line state | Meaning |
|---|---|---|
| `DRAFT` | `PENDING` | Validated local plan only. |
| `DEPARTING` | `DEPARTING` | Asset lease/fence and source snapshot are being applied. |
| `IN_TRANSIT` | `DEPARTED` | Asset has set the cabin to `IN_TRANSFER`; its canonical warehouse has not changed. |
| `ARRIVING` | `ARRIVING` | Destination arrival validates exact contents and the required durable media. |
| `COMPLETED` | `ARRIVED` | Asset atomically assigns the destination warehouse and restores the approved eligible status. |
| `CONFLICT` / `RECONCILIATION_REQUIRED` | `CONFLICT` | A mismatch, stale fence, or ambiguous downstream result requires explicit audit/retry. |
| `CANCELLED` | `CANCELLED` | Allowed only while every line remains `PENDING`. |

Departure and arrival are separately versioned idempotent commands. Departure
is irreversible once asset has accepted `IN_TRANSFER`; cancellation then is
rejected rather than trying to infer a compensating physical movement. Arrival
requires the submitted cabin version/fence, destination warehouse validation,
the immutable expected/factual contents snapshots, and its required READY media
references. A contents/media mismatch remains `CONFLICT` and must not change
the canonical warehouse.

## Cross-flow guard and saga rules

Creating a document does not grant a universal cabin lock. For each affected
line, logistics acquires a canonical asset operation lease with owner type
`LOGISTICS_RETURN`, `LOGISTICS_SHIPMENT`, or `LOGISTICS_TRANSFER` and owner ID
`<documentId>:<lineId>`. Asset enforces the matching active lease and fencing
token on every restricted status/warehouse/settlement command.

The logistics projection exposes a guard only after the asset lease is known
to be active. A transient failure, expiry, or stale fence becomes an explicit
attempt/reconciliation state; it never causes an optimistic local guard to
outlive the canonical lease. Services that own other cabin workflows continue
to rely on asset's canonical lease enforcement, not on a cross-service lookup
of the logistics database.

Every external interaction has:

- a public command operation ID and canonical idempotency fingerprint;
- a stable technical attempt ID and immutable requested payload digest;
- a target service, opaque external ID/version/fence, result class, response
  digest, timestamps, retry count, and correlation/causation IDs;
- a terminal result of `CONFIRMED`, `PERMANENT_REJECTION`, or
  `RECONCILIATION_REQUIRED`.

Initial request plus three bounded retries use 1s/2s/4s backoff for transient
infrastructure failures. Validation and authorization failures are not
retried. An aggregate version gap quarantines that aggregate and blocks later
effects until reconciliation. No saga uses distributed transactions or assumes
that a timeout means failure.

## Public HTTP contract

The canonical API is `/api/logistics/v1`. It uses RFC 7807 Problem Details,
UTC timestamps, UUID identifiers, correlation IDs, JSON camel case, and ETags
or explicit `expectedVersion` as specified below.

| Capability | Endpoint | Idempotency/concurrency |
|---|---|---|
| List/read documents | `GET /returns`, `/returns/{id}`, `/shipments`, `/shipments/{id}`, `/transfers`, `/transfers/{id}` | Read-only warehouse filtering; response includes `version` and ETag. |
| Create return | `POST /returns` | Required `Idempotency-Key`; input carries each current asset ID/version and tenant snapshot. |
| Register/inspect return | `POST /returns/{id}/register`, `POST /returns/{id}/accept-undamaged`, `POST /returns/{id}/request-estimate` | Required `expectedVersion` and `Idempotency-Key`; accepted media IDs are immutable after registration. |
| Create/plan shipment | `POST /shipments`, `PUT /shipments/{id}/plan` | Required key/version; plan replacement is forbidden after task registration begins. |
| Confirm/cancel shipment | `POST /shipments/{id}/confirm-preparation`, `POST /shipments/{id}/cancel` | Required key/version; cancellation fails after confirmation begins. |
| Create/depart/arrive/cancel transfer | `POST /transfers`, `POST /transfers/{id}/lines/{lineId}/depart`, `POST /transfers/{id}/lines/{lineId}/arrive`, `POST /transfers/{id}/cancel` | Required key/version; departure/arrival use line revision plus document revision. |
| Retry/reconcile a visible conflict | `POST /{documentType}/{id}/reconcile` | Required `MANAGE`, expected version, idempotency key, and a nonblank operator reason. |

`400` means missing/invalid version, idempotency key, value shape, or state
precondition. `401`/`403` remain fail-closed. `404` never reveals an
out-of-scope warehouse resource. `409` covers stale versions, same-key changed
commands, active lease/hold/task conflicts, invalid lifecycle transitions,
asset version/fence mismatch, and already irreversible effects. `422` covers
semantic input such as an empty party/driver snapshot, invalid allocation,
invalid media count/state, impossible contents delta, same origin/destination,
or a prohibited unknown capability.

## Authorization

Public read requires a `USER` principal, `rwms.read`, and warehouse `VIEW` for
every document warehouse. Standard return/shipment command requires `USER`,
`rwms.write`, and `EDIT` for each affected warehouse. A transfer departure,
arrival, cancellation, or reconciliation requires `MANAGE` in both origin and
destination warehouses. `WMS_ADMIN` and `SYSTEM_ADMIN` retain their established
global authorization semantics. Production has no development bypass.

`logistics-service` uses a declared client-credentials client with audience
`rwms-services`. Each request acquires exactly one of these scopes; a combined,
omitted, foreign, USER, or wrong-client token is rejected:

| Scope | Target and minimum capability |
|---|---|
| `warehouse.logistics` | Exact private origin/destination identity, active flag, version, timezone response. |
| `asset.logistics` | Exact logistics lease, fenced return/shipment/transfer, equipment hold, and snapshot commands. |
| `task-board.logistics` | Logistics external-task registration, status lookup, and idempotent cancellation only. |
| `maintenance.logistics` | Stable return-shortage source upsert/read only. |
| `media.logistics` | Opaque media reference readiness/ownership validation only. |

The auth client has no `asset.internal`, broad `warehouse.read`, user,
queue-registry, worker, generic media, inventory, or maintenance-management
scope. Each receiving service validates issuer, audience, `principal_type`,
`sub`, `client_id`, and the exact one-scope claim locally.

## Narrow prerequisite contracts

These are Stage 8-owned prerequisite changes, delivered and tested one
deployable at a time before `logistics-service` depends on them.

1. `auth-service` declares the disabled-by-default `logistics-service`
   client, its external secret name, exact scopes/audience, and one-scope
   client-credentials validation. No repository secret is added.
2. `warehouse-service` exposes a logistics-only private identity endpoint with
   exactly `{id, version, active, timeZone}` and a client/scope allowlist. It
   never exposes topology or location data.
3. `asset-service` adds an `asset.logistics` private surface. It validates
   lease owner type/ID, asset version, active fencing token, and permissible
   transition while atomically applying the canonical effect. It keeps
   equipment holds/ledger movements asset-owned and returns opaque references.
4. `task-board-service` accepts one stable logistics external task identity,
   returns an immutable registration/status snapshot, and cancels only the
   matching unfinished logistics task idempotently. Queue/worker selection
   remains task-board authority.
5. `maintenance-service` accepts a source-keyed immutable shortage snapshot
   only for `LOGISTICS_RETURN`; it does not give logistics repair ownership.
6. `media-service` accepts only declared logistics owner types and validates
   opaque READY media references without revealing URLs, object keys, or media
   policy internals.

Every prerequisite has positive, wrong-client/wrong-scope, duplicate,
concurrency, timeout/recovery, and payload-shape tests. A prerequisite may not
widen an existing inventory, maintenance, asset, warehouse, media, or
task-board credential.

### Asset logistics private contract

Only `logistics-service` with exactly `asset.logistics` may call
`/api/internal/asset/v1/logistics/**`. This is a separate surface from
`asset.internal`, `asset.maintenance`, and `asset.inventory`; all of those
credentials fail closed at both the security matcher and controller
authorization boundary.

The caller can acquire, renew, and release an opaque operation lease only with
one owner type from `LOGISTICS_RETURN`, `LOGISTICS_SHIPMENT`, or
`LOGISTICS_TRANSFER`. It supplies `documentId` and `lineId`; asset-service
derives the persisted owner reference as `<documentId>:<lineId>`, checks it on
every renewal/release/effect, and never accepts an arbitrary owner string.
`GET .../rental-items/{id}/snapshot` returns exactly the canonical
`assetId`, `version`, `warehouseId`, `status`, and `{equipmentId, quantity}`
contents. It excludes display number, passport, comments, tags, balance
location, catalog code/name, tenant data, URLs, and generic asset projection
fields.

The fenced effect API takes an action, never a raw target status. Its closed
mapping is `RENTED -> AFTER_RENT` for `RETURN_INTAKE`,
`AFTER_RENT -> FREE` or `WAITING_ESTIMATE_CONFIRMATION` for the two return
settlements, `FREE -> RENTED` for `SHIPMENT_CONFIRM`, `FREE -> IN_TRANSFER`
for `TRANSFER_DEPART`, and `IN_TRANSFER -> FREE` for `TRANSFER_ARRIVE`.
Arrival alone requires a distinct active destination warehouse and atomically
relocates attached non-zero cabin balances through asset-owned
`CABIN_TO_CABIN` movements and immutable ledger rows. It never accepts a
location/accounting-correction command.

Equipment holds are shipment-line only: the input is
`shipmentId`/`shipmentLineId`, asset derives
`LOGISTICS_SHIPMENT:<shipmentId>:<shipmentLineId>`, and responses expose only
opaque hold identity/version/state/timestamps. Acquire/renew/commit/release
check the same shipment-line owner, expected version, and subject-bound
idempotency. All effects return the original success only for an identical
replay under the active matching fence; expiry, a wrong owner, a changed
payload, a stale version, or a competing lease/hold remains a `409`.

## Events and data hygiene

`contracts/events/logistics-events.yaml` is the canonical event schema. The
service publishes aggregate-family topics keyed by document UUID:

- `rwms.logistics.return.v1`;
- `rwms.logistics.shipment.v1`;
- `rwms.logistics.transfer.v1`.

Approved event types are versioned facts such as
`logistics.return.created.v1`, `logistics.return.registered.v1`, `logistics.return.accepted.v1`,
`logistics.return.conflicted.v1`, `logistics.shipment.planned.v1`,
`logistics.shipment.preparation-confirmed.v1`,
`logistics.shipment.cancelled.v1`, `logistics.transfer.departed.v1`,
`logistics.transfer.line-arrived.v1`, and
`logistics.transfer.conflicted.v1`. The exact event stream is finalized with
the schema before implementation; a cancelled/failed topic split is forbidden.

Facts contain only IDs, opaque references, versions, warehouse IDs, lifecycle
states, counts, safe status codes, UTC facts, correlation/causation, and a
sanitized opaque actor reference. They exclude party/tenant/driver text,
email/login/display name, passport values, equipment descriptions, comments,
reasons, media IDs/URLs/object keys, JWTs, credentials, and source payloads.

## Database and migration boundary

`logistics-service` owns one clean PostgreSQL database. `V1__logistics_schema.sql`
creates immutable/append-only and projection tables for:

- logistics document and line projections, lifecycle/version and immutable
  party/driver/cabin/contents snapshots;
- logistics guard, asset lease reference, equipment-hold reference, task
  registration/cancellation attempt, maintenance-source attempt, media
  reference, and reconciliation records;
- subject-bound idempotency records with payload/response hashes and bounded
  retention policy to be approved with implementation;
- `domain_event`, `event_stream_head`, snapshots, projection checkpoints,
  transactional outbox, inbox, consumer aggregate checkpoints, quarantine,
  and sanitized DLT metadata.

No browser data, legacy row, SQL release data, or seed identifier is imported.
`baselineOnMigrate=false`; all target profiles use `hibernate.ddl-auto=validate`.
The migration gate proves clean install, repeat safety, checksum drift
rejection, non-empty unversioned rejection, JPA validation, and later
previous-version upgrade when V2 exists.

### JPA, Lombok, and MapStruct implementation rules

The service uses Spring Data JPA as its persistence model and Flyway as its
only schema authority. Each entity uses field access, a generated UUID ID,
`@Version`, and explicit lower-snake-case table, column, foreign-key, and
unique-constraint names. References to another service remain opaque UUIDs;
there are no cross-service JPA associations or cross-database foreign keys.

Lombok reduces only safe boilerplate: JPA entities may use `@Getter` and a
protected no-arguments constructor, while application/integration components
may use constructor injection through `@RequiredArgsConstructor`. Entities do
not use `@Data`, `@Builder`, generated `equals`, `hashCode`, or `toString`, or
setters for IDs, versions, timestamps, or domain invariants. State changes
remain explicit domain methods.

The module applies the shared `rwms.mapstruct` convention plugin. Its mappers
use Spring component model, constructor injection, and
`unmappedTargetPolicy=ERROR`. They map only persisted read projections to API
responses and sanitized event payloads; commands are not mapped into entities,
and mappers never perform state transitions, authorization, optimistic-version
mutation, secret handling, or outbox/checksum construction.

## Required verification

- deterministic event-store/snapshot replay and live/shadow projection parity;
- CAS, same-key replay, same-key changed-payload conflict, concurrent document
  guard, lease expiry/reacquire, stale fence, hold commit/release, and no
  orphan-cabin tests;
- duplicate, out-of-order/gap, retry, DLT, quarantine, broker outage, database
  recovery after broker acknowledgement, and eventual saga reconciliation;
- exact public/service authorization and no-PII/secret event/DLT payload tests;
- Testcontainers PostgreSQL/Kafka Flyway/JPA matrix and OpenAPI/event-schema
  parity;
- narrow upstream prerequisite tests and stateless gateway route tests.

Panel typecheck/lint/build/Vitest/Playwright are intentionally outside this
parallel Stage 8 authorization. They become required only after the user
explicitly authorizes the logistics panel cutover.

## Delivery order

1. Add architecture guards that prohibit non-logistics source ownership and
   broad service credentials.
2. Deliver the six narrow prerequisite contracts in their stated order, with
   their focused verification before proceeding.
3. Add the canonical OpenAPI/event schemas and clean service Flyway V1.
4. Implement document state machines, event-store/outbox/inbox, direct clients,
   saga attempt/reconciliation projections, and service tests.
5. Add the stateless gateway route only after the service tests and contract
   parity pass.
6. Reconcile durable memory, independently review the final Stage 8 diff, and
   create one Stage 8-only human commit. Stage 7 remains independently active
   and must not be staged or committed with it.

## Completed task-board private prerequisite (2026-07-17)

The dedicated receiver is
`POST|GET /api/internal/task-board/v1/logistics/preparation-tasks` and
`POST /api/internal/task-board/v1/logistics/preparation-tasks/{externalTaskId}/cancel`.
It validates an exact local `logistics-service` SERVICE principal with
`sub=client_id=logistics-service` and one `task-board.logistics` scope.

Registration accepts only `warehouseId`, stable `externalTaskId`,
`plannedDurationMinutes` and optional `deadlineAt`. It creates a
task-board-owned `UNASSIGNED` preparation task; logistics cannot select a
queue, worker, route, title, description or cancellation reason. The safe
status snapshot is exactly task ID/version, warehouse ID, external task ID,
status and completion time. Same-identity replay is idempotent; changed input
or another source's identity collision is a conflict. Cancellation affects
only an unfinished matching logistics source and records the fixed
`LOGISTICS_PREPARATION_CANCELLED` reason. The receiver does not authorize
any logistics client invocation yet.

## Completed maintenance private prerequisite (2026-07-17)

The dedicated receiver is
`GET|PUT /api/internal/maintenance/v1/logistics/returns/{returnId}/lines/{lineId}/shortage`.
It validates an exact local `logistics-service` SERVICE principal with
`sub=client_id=logistics-service` and one `maintenance.logistics` scope.

The permanent `returnId:lineId` key accepts only warehouse ID, rental-item ID,
rental-item version and one to one hundred unique positive
`{equipmentId, missingQuantity}` values. Input is canonically ordered and
stored as an immutable maintenance source snapshot; identical replay returns
the same snapshot, while a changed source conflicts. The receiver cannot
create or alter a repair/estimate, asset lease/status, task, media reference,
inventory source or arbitrary maintenance work. It does not authorize any
logistics client invocation yet.

## Completed media private prerequisite (2026-07-17)

The dedicated receiver is
`POST /api/internal/media/v1/logistics/references/validate`. It validates an
exact local `logistics-service` SERVICE principal with
`sub=client_id=logistics-service` and exactly one `media.logistics` scope.

The request permits only `LOGISTICS_RETURN`, `LOGISTICS_SHIPMENT` or
`LOGISTICS_TRANSFER`, UUID `documentId`, `lineId` and `warehouseId`, plus one
to twenty unique opaque `{mediaId,generation}` values. Media derives the
owner ID as `<documentId>:<lineId>`; logistics cannot submit an arbitrary
owner reference. Every value must match that exact owner/warehouse and be the
current `READY` generation. A missing, wrong-owner, deleted, processing,
failed or stale-generation reference returns one opaque `409`, without
revealing which condition applied.

The successful response repeats only owner type, document/line/warehouse IDs
and the validated `mediaId,generation` values. It never leaks a signed URL,
object key, filename, MIME, status, retention decision or media internals. It
is read-only: no upload, binding, migration, event, outbox, Kafka consumer or
logistics invocation is created, and the query deliberately does not use the
unfinished Stage 7 inventory owner-proof projection.
