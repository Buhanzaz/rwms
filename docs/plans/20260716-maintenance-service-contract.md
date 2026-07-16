# Stage 6 Maintenance Service Contract

Status: `APPROVED`

The user approved this combined contract, its four narrow prerequisites and
the Stage 6 panel cutover by responding `Продолжай` to the explicit approval
gate. Browser DTOs and legacy Jmix entities cited below remain evidence only.

## Authority and evidence

- `docs/plans/ACTIVE_STAGE.md` authorizes Stage 6 evidence and contract work.
- `docs/plans/20260712-panel-microservices-decomposition.md` assigns catalog,
  estimate, repair, rework, acceptance, maintenance write-off decisions,
  task-board reconciliation and opaque media references to one initial
  `maintenance-service` bounded context.
- Durable decisions define empty/non-empty estimate completion, direct-repair
  separation, amendment start guards, repair/acceptance/write-off transitions
  and child rework semantics.
- Legacy `RepairEstimate`, `RepairEstimateLine`,
  `RepairEstimateTaskPlan`, `RepairProcess`, catalog node/link entities and
  their tests prove historical fields and behavior, not target transport or
  persistence shapes.
- Existing target contracts prove that asset owns operation leases and fenced
  cabin status, task-board owns external task registration, and media owns
  upload/finalization and owner media.

### Evidence gaps that the prerequisites must close

- `task-board-service` already appends and publishes Kafka V2 facts including
  `task-board.board-task.completed.v1` and
  `task-board.queue-entry.completed.v1`. Its checked-in canonical
  `contracts/events/task-board-events.yaml` still describes the historical
  Rabbit V1 created/cancelled contract. Stage 6 must reconcile the canonical
  AsyncAPI with the implemented Kafka facts before maintenance consumes them.
- The task-board public registration endpoint already has stable
  `externalTaskId` replay/conflict semantics, but its authorization is USER +
  `rwms.write` + warehouse EDIT. No least-privilege SERVICE registration
  contract is currently proved.
- Asset already exposes acquire/renew/release lease and fenced-status
  primitives. Their single `asset.internal` check also authorizes equipment
  hold operations, so granting that scope would violate least privilege.
- `media-service` has a canonical OpenAPI, Flyway V1 schema, media-event schema
  and in-process transformation/domain tests. The current tree does not prove
  the HTTP/JWT runtime behind that OpenAPI. Stage 6 cannot claim secure media
  attachment merely from UUID syntax, but it can validate opaque references
  from its own inbox-deduplicated projection of canonical media facts without
  receiving a media service credential.

## Boundary

`maintenance-service` owns:

- immutable catalog versions with nodes, links and frozen routing/price/time
  metadata;
- estimates, lines, amendments and completion plans;
- primary repairs, direct repairs, stages and child reworks;
- acceptance and maintenance-driven write-off decisions;
- task-board publication/reconciliation state;
- opaque asset lease, task-board and media references plus sanitized actor and
  source snapshots.

It does not own cabin status or locks, worker/queue execution state, media
objects, equipment balances, credentials, warehouse identity or inventory
findings. `/acceptance` and `/write-offs` are maintenance read projections,
not separate aggregates.

## Aggregate and state contract

### Catalog version

One catalog-version stream owns its nodes and links. Proposed lifecycle:
`DRAFT -> ACTIVE -> SUPERSEDED`. Exactly one version is active. Published
versions are immutable. A new import creates a draft; activation is a separate
CAS command. Codes are unique inside a version. Links cannot self-reference or
cross versions. Node/link graph validation rejects missing endpoints, duplicate
typed edges and cycles in dependency edges.

Money is represented as exact decimal strings at HTTP boundaries and integer
minor units internally. Duration is integer minutes. Queue, material,
furniture and equipment references are frozen opaque snapshots; activation
does not mutate another service.

The migration/import source is the reviewed legacy database snapshot, not the
empty packaged bootstrap file and not browser storage. Current evidence
contains 232 catalog-node inserts and 254 catalog-link inserts in
`old_db/hsqldb/wmspanel.script`; the packaged
`repair-catalog-seed.json` contains empty node/link arrays and bootstrap is
disabled by default. The snapshot contains 10 CATEGORY, 30 SUBCATEGORY,
89 WORK, 91 MATERIAL, 3 LOCATION and 9 OPTION nodes plus 72 DEPENDENCY and
182 FOLLOW_UP links. Its exact catalog INSERT-line evidence hash is
`94bacdcf7114e9dfb1935a162b29714e25ed36b406980c3b9551c7d27c687721`;
this is evidence identity, not a Flyway checksum. A controlled import therefore
creates a complete DRAFT catalog version with a source SHA-256, row counts and
validation report. Replay
of the same source hash is idempotent; a different payload never overwrites an
ACTIVE version. Activation remains an explicit MANAGE command after count,
reference and graph reconciliation.

The historical workbook importer is evidence only. It updates rows in place by
code or type/name, clears routing/parent fields and silently substitutes some
invalid numeric values. Those destructive/coercing behaviors are rejected by
the target versioned import: invalid rows produce a validation report and no
catalog stream commit.

### Estimate

Lifecycle: `DRAFT -> COMPLETED`. Ordinary draft replacement is forbidden after
completion. A dedicated amendment appends a new immutable estimate revision
only while the linked repair has not started. The command captures and checks
both estimate and linked-repair versions in one local transaction.

Completing an empty estimate records completion, produces no repair or board
task, and uses the active asset lease to set the cabin to `FREE`. Completing a
non-empty estimate atomically records the completed estimate, line and plan
snapshots, creates one `ESTIMATE` primary repair and its publication intents,
and appends outbox facts. A task-board outage never rolls this transaction
back.

Recommended zero-line amendment policy: reject it when a linked repair exists,
even if that repair has not started. A completed empty estimate with no repair
may still be amended to a non-empty revision and create its first repair. This
preserves history without inventing repair/task cancellation semantics. There
is no delete, reopen or ordinary correction command for a completed estimate;
all accepted corrections append an amendment revision.

### Repair

Origin is `ESTIMATE` or `DIRECT_REPAIR`; a direct repair never creates an
estimate. Kind is `PRIMARY` or `REWORK`. Execution and acceptance are separate
state dimensions so task-board execution is not conflated with maintenance
decision state:

- execution: `DRAFT -> QUEUED -> IN_PROGRESS -> COMPLETED` with `CANCELLED`
  allowed only before start;
- acceptance: `NOT_READY -> PENDING -> IN_REWORK -> ACCEPTED | WRITTEN_OFF`.

Completion of the final outstanding stage, when every planned stage is `DONE`,
moves the primary repair to `COMPLETED/PENDING` and uses the active fenced asset
command to set the cabin to `WAITING_REPAIR_CHECK`. An intermediate queue-entry
completion updates only the matching stage and does not make the whole repair
ready for acceptance.
`CANCELLED` is not successful completion. The initial Stage 6 contract exposes
no queued/in-progress repair cancellation command; abandoned DRAFT repairs have
no asset lease or task-board side effect.
Acceptance sets the repair to `ACCEPTED` and the cabin to `FREE`. Write-off
sets the repair to `WRITTEN_OFF` and the cabin to terminal `WRITTEN_OFF`.

A rework is a child repair with `kind=REWORK` and `sourceRepairId`. Creating it
as DRAFT does not change the source. Queueing it atomically changes the source
from `PENDING` to `IN_REWORK`. Child completion returns the
source to `PENDING`. Acceptance or write-off cascades the terminal decision to
the source chain. Only queued or in-progress sibling reworks block another
rework command.

Task plans may contain `REPAIR_WORK`, `MOVE_TO_REPAIR` and
`MOVE_FROM_REPAIR` stages. Automatic planning produces the approved canonical
order; manual planning preserves the submitted order. These movement stages
are task-board work only and do not claim logistics ownership or mutate a
physical transfer. Empty estimates ignore movement input. Stage 6 publishes
queue routing only; worker/group assignment and membership remain task-board
runtime state, not maintenance snapshots or durable targeted routing.

### Integration state

Each repair owns:

- opaque asset `leaseId`, `fencingToken`, lease expiry and reconciliation
  state;
- a stable UUID `externalTaskId` and task-board registration version;
- public task-plan generation state
  `PENDING_GENERATION -> GENERATED | FAILED`, plus a separate technical
  delivery state `PENDING | RETRY_PENDING | DELIVERED | QUARANTINED`;
- opaque media IDs and owner snapshots, never signed URLs or object keys.

Lease acquire/renew/release and every fenced status command are retryable with
stable idempotency keys. A terminal maintenance outcome is not complete until
the lease is released or a truthful reconciliation record is persisted.
Transient task-board failures leave generation `PENDING_GENERATION` with
`RETRY_PENDING`; `FAILED` is used only for a permanent validation rejection or
an operator-owned quarantine. `GENERATED` requires a confirmed task-board
registration for the stable external ID.

Recommended lease policy: acquire before completing a non-empty estimate or
queueing a direct repair, persist the returned lease/fencing snapshot in the
same local commit as the repair, and retain/renew that one lease for the full
primary-repair/rework chain. Child reworks reuse the root repair's lease and
must not acquire a competing cabin lease. Use the asset default 15-minute TTL,
renew before the final five minutes, and block new fenced transitions whenever
renewal truth is unknown. Empty-estimate completion and early write-off use a
short acquire/fenced-command/release saga. Any ambiguous timeout persists
`RECONCILIATION_REQUIRED`; it never assumes success, reacquires over an
unexpired lease or bypasses a stale fence.

Maintenance consumes `rwms.media.media.v1` into a local reference projection.
An estimate/repair command may attach only a known media generation whose
owner type, owner UUID and warehouse match the aggregate and whose status is
`READY`. Approved owner types are `MAINTENANCE_ESTIMATE`,
`MAINTENANCE_REPAIR`, `MAINTENANCE_ACCEPTANCE` and
`MAINTENANCE_CATALOG_NODE`. The persisted business reference is only
`{mediaId, generation}` plus immutable safe metadata. `FAILED`, `DELETED`,
wrong-owner, wrong-warehouse and stale-generation references are rejected.

## HTTP contract

Public endpoints are rooted at `/api/maintenance/v1`:

- `/catalog/versions`, `/catalog/versions/{id}/nodes`,
  `/catalog/versions/{id}/links`, `/catalog/imports`, and
  `/catalog/versions/{id}/activate`;
- `/estimates`, `/estimates/{id}`, `/estimates/{id}/complete`, and
  `/estimates/{id}/amendments`;
- `/repairs`, `/repairs/direct`, `/repairs/{id}`,
  `/repairs/{id}/plan`, `/repairs/{id}/reworks`,
  `/repairs/{id}/accept`, and `/repairs/{id}/write-off`;
- read-only `/acceptance` and `/write-offs` projections.

All reads and writes are warehouse-scoped. Public reads require USER,
`rwms.read` and VIEW; public writes require USER, `rwms.write` and EDIT;
catalog activation/import and write-off require MANAGE. Production remains
fail-closed.

Mutable commands carry `expectedVersion` in the body. Retried creates and
effects require UUID `Idempotency-Key`. Identical replay returns the original
successful response; key reuse with a different canonical request hash returns
`409`. Create returns `201`; committed transition commands return `200`; a
locally committed result awaiting downstream delivery returns `200` with an
explicit delivery snapshot, not `202` and not a rollback.

Errors use Problem Details and stable codes. The status classes are `400`
malformed input, `401/403` authentication/authorization, `404` scoped absence,
`409` version/idempotency/state/lease/fence conflicts, `422` domain validation,
and `503` when a required dependency is unavailable before any local commit.
Canonical codes are `MAINTENANCE_VALIDATION_FAILED`,
`MAINTENANCE_NOT_FOUND`, `MAINTENANCE_VERSION_CONFLICT`,
`MAINTENANCE_IDEMPOTENCY_CONFLICT`, `MAINTENANCE_STATE_CONFLICT`,
`MAINTENANCE_LEASE_CONFLICT`, `MAINTENANCE_FENCE_CONFLICT`,
`MAINTENANCE_MEDIA_NOT_READY`, `MAINTENANCE_TASK_SYNC_CONFLICT`,
`MAINTENANCE_DEPENDENCY_UNAVAILABLE` and `MAINTENANCE_FORBIDDEN`.

Idempotency records are subject- and command-scope-bound and retained for
seven days, matching the completed asset-service baseline. The response body
and status are replayed only for an identical canonical request hash.

`dispatchDate` is an ISO `YYYY-MM-DD` calendar date, not an instant; Stage 6
does not infer a deadline or timezone conversion from it. Any future task
deadline is an explicit offset date-time. `sourceParty`, reasons and comments
are service-local business text with bounded lengths and never enter sanitized
Kafka payloads. Actor snapshots contain only opaque `actorId` and `actorType`;
login/display name is not persisted or emitted by maintenance-service.

## Narrow cross-service prerequisites

These changes do not transfer domain ownership and must be separately approved
as part of Stage 6:

1. `auth-service`: register a disabled-by-default `maintenance-service`
   client-credentials client for audience `rwms-services` and exact private
   scopes below.
2. `asset-service`: authorize exact scope `asset.maintenance` only for
   acquire/renew/release operation lease and fenced rental-item status. Bind it
   to the approved client ID. Do not expose equipment holds or reuse broad
   `asset.internal`. Bind lease ownership to
   `MAINTENANCE_ESTIMATE:<estimate UUID>` or
   `MAINTENANCE_REPAIR:<root repair UUID>`, recheck that owner on renew/release,
   and make fenced-status subject-bound idempotent with mandatory
   `Idempotency-Key`. The maintenance-specific fenced command accepts only the
   approved source/target transition table; it must not expose the generic
   rental-item status enum.
3. `task-board-service`: add a private service-principal contract with exact
   scope `task-board.task-sync` under
   `/api/internal/task-board/v1/tasks`:
   register/get/pre-start-update/cancel by stable `externalTaskId`. It must not
   grant worker, queue, workforce or settings
   mutation. `externalTaskId` is mandatory; persist authenticated source client
   ownership and allow reconciliation/cancellation only for tasks created by
   that source. Pre-start update requires expected task version and rejects if
   any route entry or assignment has started; it is the only task-board
   mutation available to estimate amendment. Reconcile the canonical AsyncAPI
   with the already implemented
   Kafka V2 board-task and queue-entry facts, including completion.
4. `api-gateway-service`: add only the public `/api/maintenance/**` route.
   Private calls use internal addresses and never traverse the gateway.

The maintenance credential must not receive USER `rwms.write`, gateway tokens,
`asset.internal`, queue-management or any media capability. Auth issues
separate exact-scope tokens for `asset.maintenance` and
`task-board.task-sync`, never one combined downstream token.

Recommended asset transition allowlist:

- `FREE|WAREHOUSE|OWN_NEEDS|AFTER_RENT -> REPAIR` when a non-empty estimate or
  direct repair is queued;
- `WAITING_ESTIMATE_CONFIRMATION -> REPAIR` only for a proven linked-return
  estimate;
- `REPAIR -> WAITING_REPAIR_CHECK` only after the final outstanding stage is
  completed;
- `WAITING_REPAIR_CHECK -> FREE` on acceptance;
- an empty estimate may move
  `FREE|WAREHOUSE|OWN_NEEDS|AFTER_RENT|WAITING_ESTIMATE_CONFIRMATION -> FREE`,
  with the linked-return proof required for the last source;
- early or acceptance write-off may move any of the preceding eligible states,
  `REPAIR` or `WAITING_REPAIR_CHECK` to `WRITTEN_OFF`.

`RENTED`, `BOOKED`, `RESERVED`, `IN_TRANSFER`, `SALE`, `USED_SALE`,
`CAPITAL_REPAIR`, `NEW` and `WRITTEN_OFF` are never maintenance sources. A
same-status retry succeeds only through the matching idempotency record and
active fence, not through a generic no-op transition.

## Event and recovery contract

PostgreSQL service-local event streams are authoritative. Kafka carries
versioned facts on aggregate-family topics keyed by aggregate ID:

- `rwms.maintenance.catalog.v1`;
- `rwms.maintenance.estimate.v1`;
- `rwms.maintenance.repair.v1`.

Exact integration event types are:

- catalog: `maintenance.catalog-version.imported.v1`,
  `maintenance.catalog-version.changed.v1`,
  `maintenance.catalog-version.activated.v1`, and
  `maintenance.catalog-version.superseded.v1`;
- estimate: `maintenance.estimate.created.v1`,
  `maintenance.estimate.draft-changed.v1`,
  `maintenance.estimate.completed.v1`, and
  `maintenance.estimate.amended.v1`;
- repair: `maintenance.repair.created.v1`,
  `maintenance.repair.plan-changed.v1`, `maintenance.repair.queued.v1`,
  `maintenance.repair.stage-completed.v1`,
  `maintenance.repair.pending-acceptance.v1`,
  `maintenance.repair.rework-created.v1`,
  `maintenance.repair.accepted.v1`, and
  `maintenance.repair.written-off.v1`.

Every message uses `DomainEventEnvelopeV2`, aggregate version and event version
1. Kafka integration payloads contain opaque IDs, versions and sanitized
snapshots only: no login, display name, comments, media URLs, object keys or
other PII. Actor is an opaque UUID/service reference. Service-local event-store
payloads may retain approved business text required for deterministic replay;
they are never copied blindly into Kafka integration payloads.

Maintenance consumes task-board completion/cancellation facts in its own
consumer group. Inbox deduplication, domain effect and aggregate checkpoint are
committed in one database transaction. Version gaps quarantine the aggregate
and block later effects. Asset events are reconciliation input only; they do
not replace fenced HTTP commands.

Outbox publication requires broker acknowledgement before marking a row
published. Delivery is at-least-once. Consumers receive the first attempt plus
three bounded retries at 1s/2s/4s; validation failures go directly to a
consumer-owned sanitized DLT, while transient failures retry first. DLT replay
is an explicit operator-reviewed action. The task-board/media consumer DLT is
`rwms.maintenance.dlt.v1` and contains only failure
code, message SHA-256 and recorded timestamp, never the original body.

## Persistence and verification gate

Flyway V1 will own projections, append-only `domain_event`, stream heads,
snapshots, projection checkpoints, transactional outbox, inbox, consumer
aggregate checkpoints, idempotency records and integration reconciliation.
JPA uses `ddl-auto=validate`; Flyway `baselineOnMigrate` remains false.

The gate must prove clean install, repeat, checksum drift rejection,
non-empty-unversioned rejection and JPA validation; deterministic replay and
shadow parity; CAS and multi-stream atomicity; duplicate/order/gap/quarantine;
retry/DLT/replay; PostgreSQL and Kafka outage recovery; task-board late replay;
asset lease expiry/renew/release/reconciliation and stale-fence rejection; and
PII/secret exclusion.

## Approval record

The approval covers the four narrow cross-service prerequisites, the split
repair state model, zero-line amendment rejection-with-history policy, service
scopes, HTTP status/idempotency rules, event families, actor/media PII boundary
and the Stage 6 panel cutover. Implementation must preserve every constraint in
this document; later changes require a new durable decision.
