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

Interactive panel and Android clients reach this namespace only through the public
`api-gateway-service` `/api/asset/**` route. They must not call this module host or an
`/api/internal/**` route directly.

Private boundaries are deliberately limited to:

- `/api/internal/asset/v1/maintenance/**` for maintenance leases, snapshots, fenced effects and
  furniture custody;
- `/api/internal/asset/v1/logistics/**` for logistics leases, holds, reservations, movement plans
  and asset effects; and
- `/api/internal/asset/v1/inventory/**` for inventory capture, validation, source-asset and
  furniture-reconciliation work.

Private callers use service credentials, not a forwarded user token. The security chain requires
the exact service identity and single-purpose scope for each of those namespaces.

Within the logistics namespace, `/cabin-facets` returns availability-backed
type, finish, dimension, category and characteristic values plus exact
type-to-dimension relations. `/cabin-catalog` is a separate bounded, read-only
facts lookup by warehouse and required query. It searches number, type, finish,
dimension, category, characteristics and linoleum across all current statuses;
it does not check availability or create, renew or release a hold.

## Internal application structure

`AssetService` is a stable controller-facing facade with five exact application
collaborators. It preserves public transaction boundaries while keeping each
independently changing workflow separate:

| Collaborator family | Owned responsibility |
| --- | --- |
| `AssetRentalItemService` and `AssetRentalProjectionService` | Rental-item commands, canonical status mutations, notes and read/event projection |
| `AssetLogisticsService` | Logistics leases, rental effects, reservations, shipment holds and task-bound equipment moves |
| `AssetEquipmentService` | Equipment availability, stock commands, general holds and physical transfers |
| `AssetMaintenanceService` | Maintenance leases, fenced rental effects, characteristics and furniture custody entry |
| `AssetClassifierService` | Classifier aggregate commands and event facts |
| Lease, catalog, ledger and hold services | Exact locking, fencing, catalog binding, physical balances and allocation expiry |
| `AssetJsonCodec` and `AssetBalanceRow` | Canonical JSON/idempotency decoding and an immutable physical-balance carrier only |
| `InventoryAssetService` | Stable private inventory facade over capture, projection, furniture reconciliation and source creation |
| Inventory capture/projection/furniture/source services | Frozen capture lifecycle, current validation reads, reviewed absolute furniture counts and permanent source identity |
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
