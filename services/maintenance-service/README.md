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
For no-work reassertion, an increased `authoritativeAssetVersion` from the same immutable source is
treated as a newer technical fence, not as a different inventory decision. The original outcome
coordinator remains immutable, while the new idempotency key retains its exact request fingerprint
and response in a separate receipt.

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
inventory finding even when the current repair was adopted from a pre-V45 source. An external
capital repair with `movementToRepair=true` instead owns a separate outbound
`CAPITAL_TO_PRODUCTION` task whose source is that capital repair.

A work apply first persists an `INVENTORY` repair in `DRAFT` with the full frozen plan and one
durable `ASSET/QUEUE_REPAIR` reconciliation. The existing queue reconciler acquires the lease and
changes the asset status. For `forceCapitalRepair=true`, it uses `QUEUE_TO_CAPITAL_REPAIR`, completes
the local repair as external capital (`COMPLETED/PENDING`, `EXTERNAL_CAPITAL`), creates no ordinary
task-board task, and, when `movementToRepair=true`, creates its separate logistics
`CAPITAL_TO_PRODUCTION` driver task. For ordinary work it queues the repair and either registers the
task-board task directly or, when `movementToRepair=true`, creates the logistics
`DELIVER_TO_REPAIR` driver task and registers repair work after delivery.

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

## Explicit capital-repair choice

Estimate, primary-repair, inventory freeze, and inventory publication commands may carry an
optional `forceCapitalRepair`; omission means `false`, while an explicit JSON `null` is rejected.
The choice is persisted independently of catalog flags and is copied into every estimate revision,
repair response, inventory snapshot, and newly produced ESTIMATE/REPAIR v1 Kafka fact. For replay
compatibility, consumers accept the field's omission in historical v1 facts as `false`; explicit
non-boolean values remain invalid. Repair complexity is CAPITAL when this explicit choice is true or
any current catalog WORK forces capital repair. Inventory freeze and stored snapshot boundaries
reject a plan that also requests repair movement, so maintenance never owns two competing
destinations for one finding. The
existing capital-repair logistics, separate active-capital list, acceptance, and return-to-FREE
cycle remain the only downstream implementation; clients do not create those effects themselves.
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
| `MaintenanceEstimateUseCases` plus estimate/furniture/revision supports | Estimate lifecycle, lines, plans, furniture admission and immutable revisions |
| `MaintenanceRepairUseCases` plus repair lifecycle/model/media/task-board supports | Repair creation, queueing, execution, acceptance and rework preparation |
| `MaintenanceTransferUseCases` and `MaintenanceTransferSupport` | Transfer departure/arrival maintenance continuation |
| `MaintenanceInboundUseCases` and `MaintenanceInboundFactProjectionUseCases` | Owner-fact ingestion and projection updates |
| `MaintenanceReconciliationUseCases` | Claim dispatch, media-owner proof and failure recording only |
| Asset, task and repair-lifecycle reconciliation use cases | The three independent remote-effect/recovery branches |
| `InventoryMaintenanceService` | Stable private inventory-boundary facade over freeze, upsert and repair-snapshot projection |
| Inventory maintenance freeze/upsert/validation/transaction collaborators | Frozen-plan admission, catalog/routing/media validation and isolated local transactions |
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
