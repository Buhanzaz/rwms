# RWMS Maintenance Service

[Русская версия](README.ru.md)

## Purpose and ownership

`maintenance-service` owns maintenance catalog versions, estimates, repairs, repair places and
capacities, acceptance/write-off decisions, and its side of inventory-publication reconciliation.
It is the authority for those transitions; other services do not write its PostgreSQL tables or
reconstruct repair state from Kafka.

The authoritative HTTP and event contracts are
[`contracts/openapi/maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml)
and [`contracts/events/maintenance-events.yaml`](../../contracts/events/maintenance-events.yaml).
Read [`docs/project-knowledge/domain-logic.md`](../../docs/project-knowledge/domain-logic.md)
as an index, then verify any rule against current contracts and service code.

## Public and private HTTP boundary

Public user operations are versioned below `/api/maintenance/v1/**`; they cover catalog, estimate,
repair, repair-place/settings and property-disposition work. Mutable commands use the
contract-defined idempotency key and expected-version fields. Clients must handle the canonical
`409` conflict rather than send a changed retry.

Interactive panel and Android clients reach this namespace only through the public
`api-gateway-service` `/api/maintenance/**` route. They must not call this module host or an
`/api/internal/**` route directly.

Private routes are narrow by design:

- `/api/internal/maintenance/v1/inventory/**` supports inventory-owned planning, source and
  publication-reconciliation work.
- `/api/internal/maintenance/v1/logistics/**` supports logistics return-estimate and
  repair-place orchestration.

Inventory publication preflight keeps `findings` required and bounded, accepts an empty list when
the final inventory plan contains no maintenance work, and always returns an empty `candidates`
array for each work finding. The latest completed inventory is authoritative, so an active estimate
or repair is predecessor evidence rather than a collision that requires a caller-selected merge or
replacement.

Every completed-inventory finding with work targets one full frozen `REPAIR`, including
`AFTER_RENT`; the old inventory-only estimate materialization path no longer exists. Historical
`CREATE`, `REPLACE`, and `MERGE` values remain valid immutable request evidence, but all execute as
full authoritative replacement. The exact no-work boundary
`PUT /api/internal/maintenance/v1/inventory/outcomes/{inventoryId}/findings/{findingId}/no-work`
creates no estimate or repair and leaves no non-terminal maintenance target for a `FREE` finding.

`POST /api/internal/maintenance/v1/inventory/cabin-write-offs` is the narrow companion for a cabin
left missing after inventory shipment review. It accepts only the exact inventory-service
credential and one stable source/key, loads current cabin contents from asset-service, and freezes
them as `DISPOSE_WITH_CABIN` in a normal `PENDING_APPROVAL` write-off decision. It does not apply a
terminal status; the existing global-administrator approval and durable property-disposition saga
remain the only terminal path.

Both work and no-work flows durably capture every non-terminal DRAFT estimate and active repair,
cancel maintenance-owned task-board work and driver movement, release operation leases and repair
places through their owners, and only then supersede local estimates, repairs, and stages. Rows,
lines, stages, evidence, media references, and event history are retained; the workflow does not
delete maintenance history. Terminal `ACCEPTED` or `WRITTEN_OFF` repair truth, warehouse/asset
mismatch, and stale or ambiguous completed-inventory ordering fail before any local or remote
effect.

Apply requires `authoritativeAssetVersion`, returned by asset-service after it applies the
inventory outcome. A lagging local asset projection is accepted only while its version is within
the inclusive `assetVersion..authoritativeAssetVersion` range. `LOST`, `WRITTEN_OFF`, warehouse
mismatch, and a projection outside that range remain conflicts. A completed idempotency key returns
its frozen response without new effects. A new key for the same immutable source re-discovers and
supersedes non-terminal predecessors created after the previous success while preserving the exact
same-source repair and never creating a duplicate.
If that exact work coordinator is already `APPLIED` and the local projection later advances above
the request's original authority, the new-key recovery remains deliberately narrow. The immutable
request, warehouse, applied coordinator, watermark and active bound repair must still match. A
current immutable source row must name that repair; when an equivalent corrected plan intentionally
has no current source row, an immutable different-plan source must already own the exact retained
repair. Its classification must already match current `REPAIR`/`CAPITAL_REPAIR` asset truth.
The current status may equal the stored desired status, or a stored `REPAIR` may have been promoted
to `CAPITAL_REPAIR`. Recovery then reasserts only the bound repair's existing
status/task/driver/lease reconciliation effects, issues no asset-status transition and does not
rewrite the coordinator's request, authoritative version or response. `FREE`, `RENTED`, `BOOKED`, a
desired `CAPITAL_REPAIR` with current `REPAIR`, terminal state, changed source, missing or
non-`APPLIED` coordinator, an unbound or mismatched repair, and stale plan/watermark remain
conflicts. This fence is owned by
[`InventoryPublicationAssetFence`](src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryPublicationAssetFence.java)
and
[`InventoryAuthoritativeOutcomeStore`](src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryAuthoritativeOutcomeStore.java).
For no-work reassertion, an increased `authoritativeAssetVersion` from the same immutable source is
treated as a newer technical fence, not as a different inventory decision. The original outcome
coordinator remains immutable, while the new idempotency key retains its exact request fingerprint
and response in a separate receipt.

A corrected completed plan may advance the same inventory/finding at the exact same completion
instant only through a strictly higher `finalPlanVersion` after the previous outcome is `APPLIED`.
An unchanged work correction adopts the existing repair and advances the outcome watermark;
because the original immutable source row already owns that repair, the corrected coordinator and
receipt reference it without creating a duplicate source or repair. When later completed receipts
already replaced the predecessor coordinator's original repair, the newest receipt-bound live
repair is retained. Driver reassertion resolves the newest `APPLIED` coordinator with a bounded
completion-time/plan-version lookup because corrected plans intentionally leave the same repair in
more than one historical coordinator. A retry from `EFFECTS_SETTLED` clears any compensation guard
recorded before that binding was known and reasserts the retained repair's task, driver and lease work. Changed
finding revision, observed asset version, fingerprint, priority, dates, media, selected target,
movement, ordinary or capital routing, and `WORK`/`NO_WORK` classification instead run the same
durable remote compensation and local supersession used by a later inventory. The former active
outcome remains history and exactly one corrected repair, or no repair for `NO_WORK`, remains
active. Lower
versions, same-version drift, changed inventory/finding/warehouse/completion identity, a predecessor
that is not yet `APPLIED`, and terminal accepted or written-off predecessor work remain conflicts.

If compensation durably cancelled the retained ordinary task before an equivalent correction was
recognized, maintenance cannot reuse that task identity. Only an inventory-owned, queued,
no-movement, pre-start repair whose stages still prove the cancelled mappings rotates to one
deterministic replacement task ID, clears only those mappings and registers the replacement once.
The same recovery marks the released operation lease, acquires and persists a new fence before
status reconciliation, and then confirms task delivery. A later reassertion also honors the durable
local `RECONCILIATION_REQUIRED` fence when the release ledger belongs to an older plan version: it
must reacquire the lease instead of confirming an already-matching asset status. That new fence
completes local reconciliation only when an ordinary task is already generated and no movement is
pending; otherwise task or driver confirmation remains the final fence. Started, completed,
movement and capital routes are never reopened; an exact replay neither rotates the task nor
acquires another lease.

Publication registrations and immutable source rows written before the V45 coordinator remain
audit history and do not block the latest completed inventory. An operation-only row or a source
that produced an estimate is treated as predecessor evidence. If an older repair source was
previously frozen into an already-`APPLIED` coordinator, a new idempotency key atomically creates
one replacement repair, supersedes that legacy repair, and binds the current generation in a new
completed receipt without rewriting the old source, outcome, or receipt. The outcome lock
serializes generation creation, and a repair-specific compatibility queue key cannot collide with
the legacy repair's quarantined `QUEUE_REPAIR`. Same-key replay remains frozen; a current V45
repair source is reasserted rather than replaced.
Repair reads expose `inventorySource` through the read-only
[`InventoryRepairSourceReadProjection`](src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryRepairSourceReadProjection.java).
It resolves the newest completed receipt whose frozen response names the repair, then the
authoritative outcome's original `targetRepairId`, and finally a legacy `InventoryRepairSource`.
Authoritative references retain the inventory/finding identity, `findingRevision`,
`finalPlanSha256` as the plan fingerprint, and `requestSha256` as the source fingerprint, so a
client can address the immutable inventory-finding media owner without rewriting media rows.
A legacy repair whose exact lease owner and fencing token remain in `RECONCILIATION_REQUIRED` is
released through the asset owner before replacement. The asset response may prove either
`RELEASED` or naturally `EXPIRED`; the lease ID, owner and fencing token must still match and its
version cannot move backwards. An already locally `RELEASED` lease is not called again.
When a newer inventory generation preserves that current repair, maintenance reasserts the
calculated `REPAIR`/`CAPITAL_REPAIR` asset status and recreates missing execution work under
generation-specific keys. Ordinary inbound `DELIVER_TO_REPAIR` work identifies the authoritative
inventory finding even when the current repair was adopted from a pre-V45 source. An explicit
capital choice is mutually exclusive with inbound movement and remains on the active capital route.

A work apply first persists an `INVENTORY` repair in `DRAFT` with the full frozen plan and one
durable `ASSET/QUEUE_REPAIR` reconciliation. The existing queue reconciler acquires the lease and
changes the asset status. For `forceCapitalRepair=true`, it uses `QUEUE_TO_CAPITAL_REPAIR`, routes
the local repair as active external capital (`QUEUED/NOT_READY`, `EXTERNAL_CAPITAL`) without treating
inventory publication as completed work, and creates no ordinary task-board task. For ordinary work
it queues the repair and either registers the task-board task directly or, when
`movementToRepair=true`, creates the logistics
`DELIVER_TO_REPAIR` driver task and registers repair work after delivery.
Before an estimate or repair plan is persisted, and again before an ordinary task is published,
maintenance applies the fixed phase sequence `SES -> welding -> exterior -> interior -> electrical
-> plumbing`. Missing phases do not create route entries, completed entries no longer block
promotion, and multiple work stages inside one phase retain their submitted order. Existing repairs
are published in that order without rewriting their append-only maintenance history; task-board's
projection migration corrects fully waiting active routes, while started and completed history
remains immutable.
Every ordinary repair-stage snapshot puts the selected repair cover first in ordered
`sourceMedia`; the remaining aggregate photos retain their stable order, while each work line lists
only its own photo IDs. The common task title is the maintenance-calculated complexity label
(`Лёгкий ремонт`, `Средний ремонт`, `Тяжёлый ремонт` or `Капитальный ремонт`) instead of the
technical `Maintenance repair`; task-board stores that source-owned title through its existing
contract. This worker-facing projection is built by
[`MaintenanceTaskBoardSupport`](src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceTaskBoardSupport.java).
The public repair collection applies warehouse, state, cabin, and optional bounded `repairIds`
filters plus paging in PostgreSQL before assembling repair DTOs. Task-board consumers use that
additive ID filter in batches of at most 200, so a board refresh never hydrates every repair in a
warehouse; the unfiltered endpoint retains its existing paged behavior. This read path is owned by
[`MaintenanceRepairUseCases`](src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceRepairUseCases.java).
At startup, maintenance idempotently enqueues the existing pre-start task update workflow for
every already registered queued repair. This owner-local pass performs no remote I/O and lets the
normal reconciliation worker correct old presentation snapshots, including cover order and
complexity title, without mutating task-board storage directly. Presentation generation v4 uses a
fresh stable key, so eligible queued work receives the canonical stage order, cover and title while
quarantined v2/v3 work is neither resumed nor changed directly. A quarantined stable refresh in the current generation
remains quarantined for reviewed resume and is counted then skipped; it cannot fail the
application-ready event or start a restart loop. Any other stable-identity conflict still fails
closed. The existing task-board pre-start command fences a task started during that bounded
recovery, and the refresh cannot degrade the repair's delivery state; see
[`MaintenanceWorkerCoverReconciliation`](src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceWorkerCoverReconciliation.java).
When catalog-enforced capital work retains `movementToRepair=true`, the same frozen choice creates
or reuses `CAPITAL_TO_PRODUCTION`; recalculation never clears that choice merely because the target
repair is capital. The capital repair still remains `QUEUED/NOT_READY` until execution completes.
The private logistics response owns the effective driver-task date. Its HTTP boundary still
requires a non-null date for `AUTO`; for `FIXED_DATE` it rejects a date before the immutable request
but permits a later effective result. Before confirming durable `CREATE_DRIVER_TASK` work,
maintenance resolves the current warehouse-local day outside its database transaction. A current
or future fixed request must match exactly, while an overdue request accepts an effective date only
in the inclusive range from the original request through that local day. A later day is rejected by
the existing bounded reconciliation failure path. The repair's requested date and stable
idempotency key remain unchanged, and the confirmation receipt records the logistics-owned
effective date. Identity, source, kind, priority and state checks are unchanged. See the
[`MaintenanceLogisticsHttpClient`](src/main/java/dev/buhanzaz/rwms/maintenance/integration/MaintenanceLogisticsHttpClient.java)
transport fence and
[`MaintenanceTaskReconciliationUseCases`](src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceTaskReconciliationUseCases.java)
confirmation fence.
No-work publication uses the separate authoritative cleanup and leaves the cabin `FREE`. Acceptance
and rework reads/commands reject inventory-origin rows without task-board execution evidence, which
also keeps historical publication-created capital rows out of those surfaces. Reapplying their
completed inventory outcome restores the former `COMPLETED/PENDING` rows to the active
`QUEUED/NOT_READY` capital route.

Publication validates the fingerprint of the exact raw frozen snapshot before any compatibility
adaptation. For schema version 1 only, a historical snapshot that copied the same non-empty
aggregate media list onto every line is executed with those line copies cleared in memory; the
aggregate evidence remains attached once. No other version-1 shape is normalized, and version 2
continues to use the strict current validation. The raw snapshot and fingerprint are stored
unchanged, so this requires no database rewrite or migration.

Private callers use service credentials, not a forwarded user token. The authorizer checks exact
service identity, audience and single-purpose scope for inventory and logistics. The HTTP security
chain is stateless OAuth2/JWT; dev auth bypass is limited to the `dev` profile and rejected by the
production validator.

## Warehouse isolation and transaction rule

User operations are authorized for the requested warehouse and access level. Maintenance keeps its
catalog, repair and disposition state in its own database; it never joins another service database.

Remote truth is accessed through `MaintenanceDependencyGateway` with client credentials for asset,
task-board, media, logistics and warehouse-service. Do not make a remote call while a local write
transaction holds maintenance locks. The application flow commits local preparation before remote
preflight/effects, then continues local fenced state through durable recovery records.

Each warehouse also owns a version-fenced estimate-creation window setting (`1..3650` days,
default `7`). A new manual estimate reads the latest physical return arrival from logistics; the
automatic return source carries the same immutable arrival evidence. Maintenance evaluates an
inclusive deadline in the warehouse timezone. Arrival on 1 August with seven days is allowed
through 8 August and fails from 9 August with `ESTIMATE_CREATION_WINDOW_EXPIRED`; the independent
direct-repair command remains available.

## Explicit capital-repair choice

Estimate, primary-repair, inventory freeze, and inventory publication commands may carry an
optional `forceCapitalRepair`; omission means `false`, while an explicit JSON `null` is rejected.
The choice is persisted independently of catalog flags and is copied into every estimate revision,
repair response, inventory snapshot, and newly produced ESTIMATE/REPAIR v1 Kafka fact. For replay
compatibility, consumers accept the field's omission in historical v1 facts as `false`; explicit
non-boolean values remain invalid. Repair complexity is CAPITAL when this explicit choice is true or
any current catalog WORK forces capital repair. Inventory freeze and stored snapshot boundaries
reject a plan that also requests repair movement, so maintenance never owns two competing
destinations for one finding. Capital routing and the separate active-capital list remain
server-owned. Inventory publication alone never opens acceptance or rework; those transitions
require authoritative execution completion. Clients do not create those effects themselves.
Migration [`V44__manual_capital_repair_selection.sql`](src/main/resources/db/migration/V44__manual_capital_repair_selection.sql)
backfills existing estimates, revisions, and repairs as `false`.

## Furniture settings and booked-cabin conflicts

The existing maintenance furniture editor exposes the asset-owned nullable maximum per cabin below
the cabin-characteristic mapping. Maintenance persists only the durable catalog-node link intent,
the expected asset version and the last confirmed observation; it does not own a second equipment
catalogue or balance. A save completes local preparation, synchronously runs the existing private
asset ensure/update command outside the local write transaction, and confirms the returned version
and maximum before the catalog mutation succeeds. Catalog reads enrich confirmed links with current
asset-owned values. Migration `V42__furniture_equipment_maximum_sync.sql` expands that existing link
intent. `V43__backfill_furniture_equipment_link_intents.sql` seeds one `PENDING` intent for each
pre-durable furniture node UUID, preferring its active catalog snapshot over draft or superseded copies,
so the existing reconciler can establish its missing asset-service external reference without inventing
local settings or remapping the catalog. Asset-service remains the source of truth.

When asset-service rejects a maintenance lease or fenced status command because the cabin still has
an active order reservation, `MaintenanceHttpTransport` allow-lists only the exact upstream `409`
code `BOOKED_UNIT_REPLACEMENT_REQUIRED`. The public maintenance Problem Details keeps that code and
a replacement-oriented message. All other dependency codes, including unknown upstream `409`
values, remain `MAINTENANCE_DEPENDENCY_UNAVAILABLE`; maintenance does not create or decide a cabin
replacement workflow.

After an atomic cabin replacement, asset-service also rejects maintenance lease acquisition while
the existing replacement furniture movement has a live source hold. Maintenance does not copy that
readiness state or create another task type; after the existing movement executes (or its hold is no
longer live), the same maintenance acquisition may be retried.

## Internal application structure

`MaintenanceApplicationService` is a stable six-collaborator compatibility facade. It preserves
the controller/private-boundary API and transaction annotations while delegating to cohesive
application owners:

| Collaborator family | Owned responsibility |
| --- | --- |
| `MaintenanceCatalogUseCases` plus catalog model/support types | Catalog-version reads, draft mutation, forking, validation and activation |
| `MaintenanceEstimateUseCases`, `MaintenanceEstimateCreationUseCases` and estimate/furniture/revision supports | Estimate lifecycle facade; creation admission/deadline/idempotency; lines, plans, furniture admission and immutable revisions |
| `MaintenanceRepairUseCases` plus repair lifecycle/model/media/task-board supports | Repair creation, queueing, execution, acceptance and rework preparation |
| `MaintenanceTransferUseCases` and `MaintenanceTransferSupport` | Transfer departure/arrival maintenance continuation |
| `MaintenanceInboundUseCases` and `MaintenanceInboundFactProjectionUseCases` | Owner-fact ingestion and projection updates |
| `MaintenanceReconciliationUseCases` | Claim dispatch, media-owner proof and failure recording only |
| Asset, task and repair-lifecycle reconciliation use cases | The three independent remote-effect/recovery branches |
| `InventoryMaintenanceService` | Stable private inventory-boundary facade over freeze, upsert and repair-snapshot projection |
| Inventory maintenance freeze/upsert/validation/transaction collaborators | Frozen-plan admission, catalog/routing/media validation and isolated local transactions |
| `InventoryAuthoritativeOutcomeStore` and `InventoryAuthoritativeOutcomeService` | Ordering, compatibility and idempotent application of original or corrected completed-plan outcomes |
| `InventoryPublicationReconciliationService` | Stable completed-inventory facade over preflight projection and durable apply |
| Inventory authoritative outcome/source/target/materialization collaborators | Completed-at watermark, receipt replay/reassertion, predecessor compensation, local supersession and sole full-plan repair materialization |
| `PropertyDispositionApplicationService` | Stable decision facade over creation, furniture materialization, review/recovery, reads and processing callbacks |
| Property-disposition boundary, persistence, actor, repair-chain and finalization collaborators | Transaction/lock/hash mechanics, event parity, repair-chain validation and terminal repair effects |
| `HttpMaintenanceDependencyGateway` | Stable private transport facade over warehouse, asset, logistics, task-board and media owners |
| `MaintenanceHttpTransport` and owner HTTP clients | Exact-scope OAuth/HTTP/error policy plus owner-local endpoints, DTO validation and fence checks |

`MaintenanceCommandSupport` centralizes only the original local transaction, lock, hash and JSON
command mechanics; event-payload/model supports remain narrow mappers. The use-case dependency
graph is acyclic, no collaborator refers back to the facade, and every constructor/direct
dependency surface is at most 15. Package-local workflow records avoid coupling collaborators to
the facade's public nested result type; the facade alone adapts that carrier back to its unchanged
public response.

The inventory maintenance boundary follows the same rule. Its facade has only
three collaborators; plan validation and allocation form a dedicated boundary,
freeze and upsert own their separate commands, and snapshot projection is
read-only. Routing and warehouse preflight calls run only after the short local
transaction has rolled back and released its locks.

Completed-inventory publication uses the same remote-outside-lock pattern. Its authoritative
outcome coordinator owns permanent receipts, per-asset completed-at ordering, target-effect
ledgers, lost-response recovery, local supersession, exact-source reassertion and receipt-bound
compatibility generations for immutable pre-coordinator source history. Source, target, repair and
plan components remain one-way dependencies of the apply owner. Preflight is an independent read
projection and cannot initiate a publication effect.

Property disposition has five one-way application branches. Creation,
furniture materialization and review/recovery use the same narrow command,
decision-persistence, actor and repair-chain leaves; reads remain projection
only, and processing callbacks own mixed decision/repair stream ordering. No
branch calls back into the facade or performs remote admission while holding
the final local decision lock.

The private HTTP gateway follows a separate transport-only DAG. Five owner
clients keep their endpoint and wire-validation vocabulary, while one shared
transport owns only exact-scope client credentials, HTTP exchange and the
established error policy. Owner clients do not call peers or application use
cases, and none refers back to the gateway facade.

## Persistence, events and recovery

Flyway migrations under `src/main/resources/db/migration/` are the only schema authority. JPA uses
`ddl-auto=validate`; do not enable Hibernate schema mutation or add cross-service foreign keys.

Migration
[`V45__authoritative_inventory_maintenance_outcomes.sql`](src/main/resources/db/migration/V45__authoritative_inventory_maintenance_outcomes.sql)
adds permanent outcome, receipt, per-asset watermark, and predecessor-effect ledger rows. Remote
attempts are committed before calls made outside local transactions; a lost response is resolved by
owner readback or the same stable cancellation identity, never by fabricated success. Local domain
rows are marked historical only after every required remote ledger is terminal.

A committed aggregate fact is written to the local event stream, snapshot/checkpoint and
transactional outbox in the same PostgreSQL transaction. Kafka delivery is at-least-once: the relay
revalidates an envelope and marks an outbox row only after broker acknowledgement; consumers
deduplicate and retain version-gap/recovery state; sanitized terminal failures use the service-owned
DLT path rather than copying raw message bodies into logs.

The inbound task-board validator accepts both the exact historical event shapes and the current
canonical shapes: board-task facts require `lane` and may carry schedule, priority, pinning and the
driver-audience pair; queue-entry facts carry nullable original/current budgets and no historical
`queueName`. Validation failures happen before replay staging and are therefore non-replayable;
after a validator correction, recovery republishes the immutable source envelopes in aggregate
version order instead of editing inbox or domain rows.

`MAINTENANCE_KAFKA_ENABLED` controls Kafka-specific relay and consumer beans. It may be `false`
only in an explicit `dev` or `test` profile; every other profile fails startup if delivery is
disabled. The canonical ordered owner outputs are catalog-version, estimate, repair,
property-disposition and sanitized DLT. They match the channel addresses in the
[`maintenance-events` contract](../../contracts/events/maintenance-events.yaml) and are shared by
startup validation and output binding initialization.

## Runtime configuration

The default HTTP port is `8087`. Configure the maintenance database, `AUTH_ISSUER`, CORS origin,
and, for real dependencies, the token URI, client ID/secret and private base URLs listed in
`src/main/resources/application.yaml`. Never commit live credentials.

The no-op dependency boundary is limited to disabled `dev`/`test` configurations. A production
runtime must enable real dependencies; the production validator checks dependency configuration and
rejects dev auth bypass. The service does not put business aggregation in the API gateway.

## Observability and operations

Actuator exposes `health`, `info` and `prometheus`. Console logs use ECS format and tracing
sampling is configured by `MAINTENANCE_TRACING_SAMPLING_PROBABILITY`. Investigate delayed work via
local outbox/inbox/recovery records and the correlation ID before changing domain data or replaying
an effect.

Prometheus exposes the low-cardinality `rwms.maintenance.outbox.backlog`,
`rwms.maintenance.outbox.oldest.age.seconds` and `rwms.maintenance.outbox.terminal` gauges. They
read pending/in-flight age and DLT/quarantine work without tags derived from events and without
draining or rewriting rows. The terminal count can scan retained terminal rows because the current
schema has no terminal-status partial index; an index remains a separately reviewed Flyway
follow-up.

## Local development

From the repository root:

```bash
bash ./gradlew :services:maintenance-service:bootRun --args='--spring.profiles.active=dev'
```

Use an isolated dependency configuration only for development or tests. Service integrations use
private URLs and client credentials, never the public gateway or a browser bearer token.

## Production Kafka safety

The base profile has no Kafka-enable fallback. Outside explicit `dev`/`test`, startup requires the
exact five-topic order, explicit non-loopback `host:port` brokers, disabled topic auto-creation,
synchronous `acks=all` idempotent publishing, positive bounded timeouts whose maximum publish wait
is shorter than the outbox lease, and the outbox relay, sanitized-DLT relay and output-binding
beans. A production profile takes precedence when combined with a local profile. The validator
does not drain a backlog; recovery remains an explicit reviewed operation.

## Safe change rules

- Change the OpenAPI or AsyncAPI contract and all affected producers/consumers together.
- Keep aggregate transitions, expected-version fencing, idempotency and compensation in this service.
- Add immutable, service-local Flyway migrations and validate affected JPA mappings.
- Preserve outbox/inbox deduplication, aggregate ordering and operator-reviewed recovery.
- Test the focused public/private contract plus persistence and failure/retry path.

## Primary implementation references

- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceCatalogUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceEstimateUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceRepairUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceReconciliationUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryMaintenanceService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryPublicationReconciliationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryAuthoritativeOutcomeService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionApplicationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/MaintenanceEventStore.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceKafkaOutboxRelay.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceTransportTopics.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceEventingMetrics.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/config/MaintenanceProductionSafetyValidator.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/integration/MaintenanceDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/integration/HttpMaintenanceDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/security/MaintenanceAuthorizer.java`
