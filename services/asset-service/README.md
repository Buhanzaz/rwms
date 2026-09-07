# RWMS Asset Service

[Русская версия](README.ru.md)

## Purpose and ownership

`asset-service` owns cabins and rental items, their equipment/content and status, equipment
catalogue and balances, operation leases, presentation holds, and asset-side inventory source
records. It is the source of truth for those transitions; maintenance, logistics and inventory use
narrow contracts instead of writing the asset database or reconstructing mutable state from Kafka.

The authoritative HTTP and event contracts are
[`contracts/openapi/asset-service.yaml`](../../contracts/openapi/asset-service.yaml) and
[`contracts/events/asset-events.yaml`](../../contracts/events/asset-events.yaml). Start with
[`docs/project-knowledge/domain-logic.md`](../../docs/project-knowledge/domain-logic.md) as an
index, then verify any rule against current contracts and service code.

## Public and private HTTP boundary

Public user operations are versioned below `/api/asset/v1/**`. They cover rental items, cabin
settings and classifiers, equipment, HTML import, administrative corrections, event streaming and
operator-reviewed outbox recovery. Mutable commands use the contract-defined expected-version and
idempotency fields where applicable; callers must handle a canonical `409` conflict rather than
send a changed retry.

SSE subscriber registration and last-subscriber cleanup are atomic per warehouse. Reconnecting
while the previous stream closes retains the new subscription for subsequent invalidations.

Interactive panel and Android clients reach this namespace only through the public
`api-gateway-service` `/api/asset/**` route. They must not call this module host or an
`/api/internal/**` route directly.

Private boundaries are deliberately limited to:

- `/api/internal/asset/v1/maintenance/**` for maintenance leases, snapshots, fenced effects and
  furniture custody;
- `/api/internal/asset/v1/logistics/**` for logistics leases, holds, reservations, movement plans
  and asset effects; and
- `/api/internal/asset/v1/inventory/**` for inventory capture, validation, source-asset,
  furniture-reconciliation and completed-outcome work.

Private callers use service credentials, not a forwarded user token. The security chain requires
the exact service identity and single-purpose scope for each of those namespaces.

`GET /api/internal/asset/v1/logistics/rental-items/{rentalItemId}/reserves?warehouseId=…`
provides logistics with a repeatable-read current occupancy snapshot and database `serverTime`.
Only live ACTIVE selection holds and the actual ACTIVE order reservation are returned; reads never
expire, renew, convert or release them. The warehouse must match the cabin's physical warehouse.
The exact logistics-service identity and `asset.logistics` scope are required. Hold scopes and actor
provenance remain private inputs for logistics visibility checks, not public client fields.

For an imported historical rental shipment, maintenance may invoke only the fenced
`CLOSE_FOR_HISTORICAL_SHIPMENT` action under its existing `MAINTENANCE_REPAIR` lease. The asset
policy accepts it only from `REPAIR`, `CAPITAL_REPAIR` or `WAITING_REPAIR_CHECK` and releases the
cabin to `FREE`; `IN_TRANSFER`, terminal and unrelated statuses remain conflicts. Logistics then
acquires and applies its own normal shipment lease rather than writing cabin state directly.

Within the logistics namespace, `/cabin-facets` returns availability-backed
type, finish, dimension, category and characteristic values plus exact
type-to-dimension relations. `/cabin-catalog` is a separate bounded, read-only
facts lookup by warehouse and required query. It searches number, type, finish,
dimension, category, characteristics and linoleum across all current statuses;
it does not check availability or create, renew or release a hold.
`/cabin-pricing-catalog` exposes the current global TYPE and CATEGORY identities,
including inactive and unused values; deleted values disappear from the result.
`/cabin-pricing-references` returns the exact type/category UUIDs and cabin version
for one to 100 distinct cabins in one warehouse. Missing or foreign-warehouse
cabins reject the complete request with 404. Neither read exposes passport data,
changes reservations, or owns the logistics rental-price policy.
`/equipment-pricing-catalog` returns current FURNITURE identities and labels, including inactive
and out-of-stock items, but excludes other equipment categories. The exact logistics service
credential is required; monthly prices per one unit remain owned by logistics.
`/customer-cabin-catalog` is the customer catalog read with an optional
`holdScopeId`. An absent scope excludes every live presentation hold for guest browsing.
It returns only `FREE` cabins that have no active order
reservation or operation lease, excludes live holds of other scopes, and keeps
the requesting inquiry's own live holds visible. Type, finish, dimensions,
category and linoleum filters are normalized exact matches; all repeated
characteristics must be present. Results retain the asset-owned stable order and
are paged only after the availability and filter decisions. Due holds are
expired during this read, but no live hold is created, renewed or released.
Customer-originated presentation and order-reservation commands preserve
`CUSTOMER` as their audit role; Flyway V41 extends only the corresponding role
constraints.
Logistics-only order-unit release commands and empty equipment replacements also accept
`LOGISTICS_SERVICE` audit provenance for automatic release. This does not grant a public JWT
role or allow creating cabin/furniture reservations; V49 widens only the release audit constraint.
`/rental-items/{id}/photo-presentation-snapshot` is a separate least-privilege read for one
logistics-owned public photo snapshot. It returns identity/version/warehouse fencing, number,
dimensions, finishing, category, ordered characteristic names and nullable linoleum; status,
rental type, passport JSON, comments, tags and equipment are excluded.

## Global cabin status colors

`GET/PUT /api/asset/v1/cabin-settings/status-colors` owns one presentation palette
for all warehouses, seeded by Flyway V48. Reads require an interactive USER with
`rwms.read`; replacement requires `rwms.write` and SYSTEM_ADMIN or WMS_ADMIN.
Every canonical status must have one hex color. Complete replacement is fenced
by `expectedVersion` and JPA optimistic locking; stale/concurrent changes return
409. Colors do not change lifecycle transitions, availability or event facts.

## Mandatory-photo cabin creation

`POST /api/asset/v1/rental-item-creation-intents` is the interactive cabin-creation command when
the caller has selected mandatory source photos. In one asset transaction it creates the ordinary
`FREE` rental item, a durable pending intent and an asset-owned `CABIN_CREATION` operation lease.
That lease excludes the new cabin from availability until the intent reaches a terminal state;
the existing rental-item create endpoint remains available and unchanged for creation without a
photo intent. Creation retries use the user-scoped `Idempotency-Key` and must carry the same
payload.

The request contains an ordered one-to-twenty-photo manifest of index, SHA-256, content type and
content length. The intent stores that manifest, one stable upload command UUID per position, a
gallery folder UUID and a canonical manifest hash; it stores neither source filenames nor media
bytes. Pending intents can be listed by an authorized warehouse or read by ID, so an interrupted
client can reconstruct the exact remaining upload work.

Completion uses `expectedVersion` and a stable idempotency key. Before releasing the creation
lease, asset-service reads
`POST /api/internal/media/v1/assets/cabin-creation-snapshots` with its service identity and exact
`media.asset` scope. The media call runs outside the asset database transaction. The final asset
transaction rechecks the intent and accepts only the same cabin, warehouse and active folder with
the exact planned number and manifest of current `READY` gallery photos and a current cover.
Failure is fail-closed and leaves the lease active. Explicit abandonment also uses
`expectedVersion`; it changes an incomplete `FREE` cabin to the existing non-rentable
`WAREHOUSE` status, emits the ordinary rental-item/lease events, marks the intent abandoned and
then releases only that intent's creation lease. Flyway V45 owns the intent and ordered-manifest
tables, constraints and lookup indexes.

## Order furniture and cabin replacement

`equipment_catalog_item.maximum_per_cabin` is the nullable asset-owned limit for one equipment
position in one cabin, introduced by Flyway V36; `null` preserves the earlier unlimited behavior.
Order equipment commands
receive requirements grouped by active order cabin, validate every configured limit, aggregate the
same order-wide reservation and serialize unit/furniture composition with the order advisory lock.
Shared availability subtracts only reservation quantities not already fulfilled by physical
furniture inside that order's active cabins, so booked contents are never counted twice.

Presentation conversion may include the authoritative full post-conversion unit requirements. It
materializes selected cabin reservations and replaces the order-wide equipment reservation in one
transaction; a capacity or maximum conflict leaves the presentation holds active. `AvailableCabin`
snapshots include physical `contents`, allowing a presentation consumer to combine shared global
availability only with contents of the actually selected held cabins, never all alternatives.
`ReplacePresentationHoldsResponse.cabins` freezes those snapshots after the canonical locks and in
the requested cabin order. While a presentation hold is live, direct transfer and equipment
movement acquire/execute commands reject that cabin as a source or known target, so the published
contents cannot drift before conversion or release.

The standard order equipment movement plan uses warehouse-side physical surplus from another
active `CABIN_NON_RENTED` unit of the same order before legacy allocatable sources. Acquiring such a
line supplies `orderId`, `targetRentalItemId` and the authoritative `units` collection together.
The existing allocation hold stores that nullable order/target/unit context, plus optional released
source-reservation provenance for an atomic replacement, as introduced by Flyway V37; the shared
order/equipment/balance lock order caps the hold by both source surplus and target deficit,
including earlier active source and inbound holds. `CABIN_RENTED` contents may fulfil the aggregate
reservation but are never offered as a warehouse movement source.

`POST /api/internal/asset/v1/logistics/orders/{orderId}/units/replace` replaces one or more ordered
old/new pairs atomically in the same order. It validates the complete post-swap composition,
requires every old cabin to still be `BOOKED`, converts the selected presentation holds and releases
all unselected alternatives in that scope, globally locks every old furniture source and pre-creates
the existing `LOGISTICS_EQUIPMENT_MOVEMENT` reservations before releasing any old order reservation.
Each pre-created line durably records the post-swap composition and released old reservation;
ordinary acquire can only replay it, and execution revalidates the released source, active target,
warehouse and order-wide furniture reservation. A failure in any pair rolls back the batch and every
presentation hold transition. The old cabin remains non-bookable `BOOKED` without an active order
reservation. A live replacement furniture hold blocks maintenance acquisition until the existing
movement executes (an expired hold is not treated as live); its furniture reservation remains
order-wide and is not released during replacement.

## Completed inventory authority

`POST /api/internal/asset/v1/inventory/source-assets` registers a permanent isolated proposal
and stable asset UUID. It does not persist a `RentalItem`, characteristics, balances or created
event. Only caller-addressed private inventory snapshots/validation/furniture reads can expose
the proposal; ordinary warehouse, rental and customer reads cannot see it. Completed furniture
reconciliation accepts optional `sourceOutcomes`, each carrying the exact final-plan asset outcome.
It materializes matching proposals with their final `FREE`, `REPAIR` or `CAPITAL_REPAIR` status,
passport and reviewed furniture atomically. A number taken by normal warehouse operations causes
a conflict without overwriting that cabin. Permanent source replay keeps the original registration
response, and requests without source outcomes retain the previous reconciliation fingerprint.
Previously materialized source cabins remain real cabins; no live-data conversion is automatic.

`PUT /api/internal/asset/v1/inventory/outcomes/{inventoryId}/findings/{findingId}` accepts only the
exact `inventory-service` credential and immutable completed-plan evidence. For a found,
non-terminal cabin, the latest completed inventory is authoritative: a first or newer `FREE`,
`REPAIR`, `CAPITAL_REPAIR` or `RENTED` source releases active operation leases, order-unit reservations and
presentation holds, clears transfer state and then becomes the current cabin status. Rows and
ordinary lease/asset events are preserved; media metadata and object storage are not part of this
command.

`RENTED` is valid only with the shipment's required (possibly empty) `shipmentContents`. Every
unique line must reference an active `FURNITURE` catalog row at the exact version. Under the cabin
locks, asset exact-replaces all cabin equipment buckets with those quantities in `CABIN_RENTED`;
omitted prior contents become zero. It neither reads stock availability nor changes `STOCK`.
Flyway V40 extends the permanent outcome watermark hash so exact replay and strictly newer plan
correction fence this furniture payload together with status and passport evidence.

The same command carries the inventory finding's frozen passport observation. `ABSENT` preserves
the current passport. `PRESENT` authoritatively replaces cabin type, compatible dimensions,
finishing, category, characteristics and nullable linoleum even when the requested status already
matches. Names must resolve exactly to active asset-service catalog rows; no catalog value is
created. Every characteristic string and every array element is comma-split, trimmed and
de-duplicated; a missing characteristic field clears the set, while missing or non-boolean
linoleum clears that nullable value. Catalog resolution happens before any binding is released, so
an unknown, inactive or incompatible value rolls the whole command back. Passport relation rows,
the rental item version, ordinary passport/status events, watermark and receipt commit in one
asset-owned transaction. See the canonical
[`InventoryOutcomeRequest`](../../contracts/openapi/asset-service.yaml) and
[`InventoryAssetOutcomeService`](src/main/java/dev/buhanzaz/rwms/asset/service/InventoryAssetOutcomeService.java).

An outcome with `preserveOperationalState=true` is the passport-only publication mode.
`desiredStatus` and `shipmentContents` must both be `null`. Under the same cabin lock and
completed-at/final-plan watermark, a `PRESENT` observation may update the passport and
characteristics, but the command never changes the cabin warehouse, current status, transfer
state, equipment contents, operation leases, order reservations or presentation holds. This lets
historical inspection evidence remain applicable after the cabin has moved to another warehouse;
the response reports its actual full `RentalItemStatus`. `LOST` and `WRITTEN_OFF` remain terminal.
The mode is rejected for inventory source proposals, which still require an explicit local
status-applying outcome. Omitted or false `preserveOperationalState` retains the pre-V51 request
fingerprint and behavior.

New status-applying outcomes may include the final plan's frozen `expectedAssetVersion`. Under the
same cabin lock, asset-service rejects with `INVENTORY_OPERATIONAL_STATE_CHANGED` before any
mutation when a different current version is already `RENTED` or `IN_TRANSFER` and the requested
status is not `RENTED`. This prevents a delayed inventory repair/free publication from replacing a
new departure, while still allowing a same-version historical rented return. Passport-only
outcomes must omit this field and remain deliberately non-strict. Legacy requests that omit it keep
their exact pre-guard fingerprint and behavior.

Flyway V38 adds a permanent successful idempotency receipt and a per-cabin completed-at watermark.
The same key and request return the frozen result. A new key for the same latest final-plan finding
reasserts status, reservations, holds and transfer state. At the same completion time, the service
also accepts a strictly greater final-plan version only when the inventory and finding IDs match
the current watermark; a lower version or same-version hash drift returns `409`. A reassertion or
plan correction releases every active operation lease for `FREE`. For `REPAIR` and
`CAPITAL_REPAIR`, it retains only `MAINTENANCE_REPAIR`, which may already belong to the repair
created from that finding, and releases stale logistics or rental leases. A strictly older
completed inventory, another equal-time source, `LOST`, and `WRITTEN_OFF` always reject without a
partial status or receipt. A wrong warehouse also rejects status-applying outcomes; only the
explicit passport-only mode accepts historical cross-warehouse evidence without moving the cabin.

Flyway
[`V39__inventory_outcome_passport_watermark.sql`](src/main/resources/db/migration/V39__inventory_outcome_passport_watermark.sql)
adds a nullable passport-observation hash to the watermark without changing the V38 receipt or
response shape. `NULL` identifies a pre-V39 status-only watermark and permits one adoption only
when every original source/status field is identical. The successful reapplication stores the
hash; later equal-time payload drift conflicts.

Furniture reconciliation keeps `expectedSnapshotSha256` as mandatory immutable source evidence in
the permanent request identity, but does not compare it with a new full snapshot: non-terminal
cabin status/version and physical quantities may legitimately drift before reconciliation. Under
current locks it still requires every selected cabin to exist in the requested warehouse and be
non-terminal, the cabin scope to match exactly, and the active FURNITURE catalog set/version to stay
unchanged. It ends conflicting cabin leases, order and presentation bindings, order-wide furniture
reservations and live allocation holds, preserves their audit rows/events, and then overwrites the
current quantities with reviewed absolute counts. A maintenance or logistics lease-release retry
may read the same-owner `RELEASED` or `EXPIRED` terminal lease with the original fencing token; a
wrong owner or token remains fenced. Logistics release first materializes natural expiry under the
same rental-item/lease lock, so an expired-but-not-yet-observed row is returned as `EXPIRED` instead
of producing a false stale-lease conflict.

## Internal application structure

`AssetService` is a stable controller-facing facade with five exact application
collaborators. It preserves public transaction boundaries while keeping each
independently changing workflow separate:

| Collaborator family | Owned responsibility |
| --- | --- |
| `AssetRentalItemService` and `AssetRentalProjectionService` | Rental-item commands, canonical status mutations, notes and read/event projection |
| `AssetLogisticsService` | Narrow rental/photo-presentation reads, logistics leases, rental effects, reservations, shipment holds and task-bound equipment moves |
| `AssetEquipmentService` | Equipment availability, stock commands, general holds and physical transfers |
| `AssetMaintenanceService` | Maintenance leases, fenced rental effects, characteristics and furniture custody entry |
| `AssetClassifierService` | Classifier aggregate commands and event facts |
| Lease, catalog, ledger and hold services | Exact locking, fencing, catalog binding, physical balances and allocation expiry |
| `AssetJsonCodec` and `AssetBalanceRow` | Canonical JSON/idempotency decoding and an immutable physical-balance carrier only |
| `InventoryAssetService` | Stable private inventory facade over capture, projection, furniture reconciliation, completed outcomes and source creation |
| Inventory capture/projection/furniture/outcome/source services | Frozen capture lifecycle, current validation reads, reviewed absolute furniture counts, completed-at outcome ordering and permanent source identity |
| `InventoryAssetSnapshotTransaction` and `InventoryAssetCodec` | Repeatable-read snapshot boundary and canonical inventory JSON/hash mechanics only |
| `RentalItemHtmlImportService` | Stable HTML-import facade over read projection, plan decisions, commit recovery and media recovery |
| HTML-import projection, plan, commit and media services | Raw intake/read mapping, durable row decisions, materialization and retry/replace/skip workflows |
| `RentalItemHtmlImportCodec` | Canonical persisted JSON, bounded hashes, stable command keys and media-key redaction only |
| `PropertyDispositionService` | Stable private facade over eligibility snapshots, prepared fences and approved terminal effects |
| Disposition snapshot, preparation and application services | The three externally visible asset-side phases of an approved maintenance decision |
| Disposition ledger, eligibility, decision-store and codec services | Physical balances/holds/movements, lease/reservation proofs, durable replay/audit and canonical JSON |

The facade has no repositories or transport client. Collaborators do not refer
back to it, use an inherited dependency surface or share a universal context;
their dependency graph is acyclic and no direct surface exceeds 15.

The inventory facade follows the same dependency direction. Capture and source
commands may consume the read projection, while furniture reconciliation is an
independent lock/fact owner. Only the projection and furniture branches use the
isolated repeatable-read snapshot transaction; none refers back to the facade.

The HTML-import facade has four exact collaborators. Projection may use plan
mapping, commit may use projection and plan, and media recovery may use commit
and projection; all three depend only downward on the JSON codec. No extracted
owner refers back to the facade or shares an inherited dependency context.

Property disposition keeps its decision-ID lock and durable replay in the
decision store, while the ledger alone owns physical balance, hold and
movement locks. Preparation performs the existing warehouse check before its
local transaction; application and snapshot consume the same eligibility and
ledger leaves without calling one another or the facade.

A lagging `RENTED` status is disposition-eligible only after logistics has released every active
lease and no reservation, foreign hold or disposition fence remains. Preparation and application
repeat those guards under lock. Releasing the logistics lease does not rewrite the rental-item
status or version; only the final approved maintenance APPLY may advance it to `WRITTEN_OFF`.

## Security, warehouse isolation and fencing

The HTTP layer is a stateless OAuth2/JWT resource server. `AssetAuthorizer` enforces user
read/write scope, warehouse access level and the narrower administrator-only operations. Dev auth
bypass is effective only in the `dev` profile and is disabled whenever a production profile is
active.

Asset data remains in its own PostgreSQL database; no cross-service JPA entity, shared table or
cross-database join is valid. The warehouse registry is read through the private client boundary.
Incoming physical custody requires warehouse admission, while draining-warehouse readiness is
reconciled from local asset facts.

Lease, hold, reservation and effect endpoints carry the stable operation identities and
contract-defined fencing values needed to make retry and uncertain-response handling owner-side.
Do not turn a client retry into an un-fenced direct update.

`POST /api/internal/asset/v1/logistics/cabin-searches` requires an
`Idempotency-Key`. The existing `AssetIdempotencyStore` binds it atomically to
the exact `service:logistics-service` subject, `logistics.cabin-search` scope
and SHA-256 fingerprint of the complete request. The original status-200 body
is frozen in the same transaction as its presentation holds. An identical
retry returns that body with `Idempotency-Replayed: true`; the same key with a
different request returns `409`, and the advisory lock serializes concurrent
retries. This uses the existing asset idempotency schema and adds no asset
Flyway migration.

Cabin search filters type, finish, dimension and category exactly,
characteristics by normalized case-insensitive text and linoleum by its boolean
value. Missing or null `resultMode` is replacement-safe `REPLACE`; `APPEND` is
honored only when explicit. One transaction expires due holds, locks the inquiry
scope, computes the exact result, mutates holds and freezes the idempotent
response. REPLACE releases every old inquiry hold not selected by the result,
including an empty result. APPEND excludes already held cabins from new matches,
preserves and renews existing inquiry holds and adds only the returned new
cabins. TTL expiry remains asset-owned.

## Persistence, events and recovery

Flyway migrations under `src/main/resources/db/migration/` are the only schema authority. JPA uses
`ddl-auto=validate`; Hibernate schema mutation and cross-service foreign keys are prohibited.

An asset transition records its local fact and transactional outbox in the owning database
transaction. Kafka is at-least-once transport: the outbox store leases one ordered aggregate head,
the relay validates and acknowledges the stored envelope, and terminal failures use a sanitized DLT
path. The service keeps local inbox/replay and invalidation handling rather than treating Kafka as
the source of mutable asset state. Terminal outbox recovery is an administrator-reviewed,
checksum-validated requeue of an existing fact, not event reconstruction.
Its response is the accepted command receipt: event and aggregate identity/version, `state=PENDING`,
review version and review time. An exact retry returns that same receipt even after publication;
it does not report the outbox row's later delivery state.

`ASSET_KAFKA_ENABLED` controls Kafka relay and consumer beans. It may be false only in an explicit
`dev` or `test` profile; any other profile fails startup if delivery is disabled. The startup fence
also requires the exact asset topic allow-list, non-loopback brokers, disabled topic auto-creation,
synchronous `acks=all` idempotent publishing, a publish wait shorter than the outbox lease, and the
outbox, sanitized-DLT and output-binding beans. A production profile takes precedence when combined
with a local profile. `ASSET_WAREHOUSE_REGISTRY_ENABLED` and `ASSET_MEDIA_IMPORT_ENABLED` control the
private warehouse and media boundaries used by live asset work.

## Runtime configuration

The default HTTP port is `8086`. Configure the asset database, `AUTH_ISSUER`, CORS origin and,
for live private integrations, the token URI, client ID/secret and internal base URLs in
`src/main/resources/application.yaml`. Never commit credentials or use the public gateway for a
service-to-service call.

The production validator rejects a disabled warehouse-registry or media-import client. The base
profile has no Kafka-enable fallback, so managed environments must explicitly set
`ASSET_KAFKA_ENABLED=true`. Local isolated development can use disabled dependency boundaries, but
only under an explicit `dev` or `test` profile.

## Observability and operations

Actuator exposes `health`, `info` and Prometheus metrics. Logs use ECS format and tracing sampling
is configured by `ASSET_TRACING_SAMPLING_PROBABILITY`. Investigate delayed work through the local
outbox, inbox, recovery state and correlation ID before retrying or correcting domain data.

The existing `rwms.asset.outbox.backlog`, `rwms.asset.outbox.oldest.age.seconds`, and
`rwms.asset.outbox.terminal` gauges expose pending/in-flight age and reviewed DLT/quarantine work.
They are read-only observations; they do not drain or rewrite the outbox.

## Local development

From the repository root:

~~~bash
bash ./gradlew :services:asset-service:bootRun --args='--spring.profiles.active=dev'
~~~

Use disabled dependency boundaries only for isolated development or tests. Live integrations use
private URLs and client credentials; browser callers use the gateway.

The module test task limits Spring's test-context cache to one context. Asset integration suites
use many distinct Testcontainers application contexts, while the shared Gradle test worker is
bounded to 512 MiB; immediate eviction prevents a broad test run from retaining unrelated
contexts until `OutOfMemoryError`. Run the complete gate serially with
`bash ./gradlew :services:asset-service:test --rerun-tasks --max-workers=1 --no-parallel`.

## Production Kafka safety

Non-local startup is fail-closed: missing enablement, an unapproved destination, blank or loopback
brokers, topic auto-creation, asynchronous or non-idempotent producer settings, an unsafe publish
timeout, or a missing relay/binding bean aborts application startup before it is treated as ready.
Backlog draining remains a separately authorized runtime action and is not performed by the startup
validator.

## Safe change rules

- Change OpenAPI/AsyncAPI contracts and every affected producer/consumer together.
- Keep cabin, equipment, hold and lease transitions in this service, with contract fencing and
  stable idempotency identities.
- Add immutable service-local Flyway migrations and validate affected JPA mappings.
- Preserve outbox/inbox deduplication, aggregate ordering and administrator-reviewed recovery.
- Test focused public/private authorization, conflict, timeout/retry and replay paths.

## Primary implementation references

- `src/main/java/dev/buhanzaz/rwms/asset/service/AssetService.java`
- `src/main/java/dev/buhanzaz/rwms/asset/service/PresentationHoldService.java`
- `src/main/java/dev/buhanzaz/rwms/asset/service/AssetIdempotencyStore.java`
- `src/main/java/dev/buhanzaz/rwms/asset/service/InventoryAssetService.java`
- `src/main/java/dev/buhanzaz/rwms/asset/service/RentalItemHtmlImportService.java`
- `src/main/java/dev/buhanzaz/rwms/asset/disposition/PropertyDispositionService.java`
- `src/main/java/dev/buhanzaz/rwms/asset/eventing/AssetEventStore.java`
- `src/main/java/dev/buhanzaz/rwms/asset/eventing/AssetKafkaOutboxStore.java`
- `src/main/java/dev/buhanzaz/rwms/asset/eventing/AssetEventingMetrics.java`
- `src/main/java/dev/buhanzaz/rwms/asset/config/AssetProductionSafetyValidator.java`
- `src/main/java/dev/buhanzaz/rwms/asset/security/AssetAuthorizer.java`
