# Stage 8 Logistics Service Foundation (2026-07-17)

## Authority and scope

The user explicitly authorized a parallel Stage 8 exception while Stage 7
remains in progress. `docs/plans/ACTIVE_STAGE.md` remains the operational
record: this is neither a Stage 7 completion claim nor authority for a panel
cutover, gateway route, upstream service change, deployment work or a mixed
commit.

## Clean database boundary

`services/logistics-service/src/main/resources/db/migration/V1__logistics_schema.sql`
creates one clean, service-owned PostgreSQL schema. It contains local document
and line projections, guards, external-attempt/reconciliation records,
idempotency, event stream heads/events/snapshots/checkpoints, transactional
outbox, inbox/checkpoint/quarantine and sanitized-DLT tables.

There is no legacy or browser-data import, no shared table/entity, no
cross-database foreign key and no Liquibase path. Flyway remains authoritative
with `baselineOnMigrate=false`; JPA uses `hibernate.ddl-auto=validate` in every
profile.

## Implemented foundation

The service currently supports idempotent `DRAFT` creation, reads and
warehouse-scoped lists for return, shipment and transfer documents. JPA owns
the application model; Lombok is restricted to safe getters/no-args and
constructor injection; MapStruct maps entity reads and a sanitized event
projection only. A subject/operation/idempotency-key PostgreSQL advisory lock
prevents concurrent retries from creating two documents.

Creation appends a local creation fact, synchronous checkpoint/snapshot and
outbox row inside one transaction. The relay checks canonical checksums and
the stored envelope before a broker acknowledgement, preserves per-aggregate
order, uses 1s/2s/4s retry, quarantine and a per-aggregate sanitized DLT. The
DLT has only identifiers, hashes, safe code and timestamp metadata; it never
copies a party, tenant, driver, passport, media reference, JWT or source
payload.

## Evidence

- Java 25 focused verification:
  `:services:logistics-service:test` — 24 tests passed, zero failures.
- An isolated PostgreSQL 17 probe applied V1 and inserted a valid return event,
  outbox record and sanitized DLT row. The complete envelope-integrity
  predicate returned exactly one row.

## Completed auth prerequisite (2026-07-17)

This prerequisite has no logistics schema migration. `auth-service` now owns a
disabled-by-default `logistics-service` client whose secret is external only:
`LOGISTICS_CLIENT_SECRET`. Its configured scope set is exactly
`warehouse.logistics`, `asset.logistics`, `task-board.logistics`,
`maintenance.logistics` and `media.logistics`, but each client-credentials
request may select exactly one scope and has audience `rwms-services`.

The authorization server rejects combined, omitted, foreign, wrong-client,
USER-principal and mismatched subject/client/audience/resource overrides.
Focused Java 25 auth verification passed 25 tests; the forced complete auth
suite passed 153 tests, zero failures and one skipped test. This does not add a
receiver endpoint, a cross-database relationship, a logistics migration, or a
permission for logistics to invoke any upstream service.

## Completed warehouse prerequisite (2026-07-17)

This prerequisite also has no logistics schema migration. `warehouse-service`
now owns the private route
`GET /api/internal/warehouse/v1/warehouses/logistics/{id}/identity`. It
requires the exact `logistics-service` SERVICE JWT with matching subject and
client ID plus exactly `warehouse.logistics`; resource-server issuer/audience
validation remains local to warehouse-service.

The response is MapStruct-mapped and exactly `{id, version, active, timeZone}`.
It intentionally returns `active=false` for a known inactive warehouse so the
calling workflow can report a truthful origin/destination conflict; it never
exposes topology or location. Java 25 focused verification passed 16 tests and
the full warehouse suite passed 29 tests, zero failures and zero skips.

## Open work and retained UNKNOWNs

No return registration/acceptance, shipment preparation/confirmation/cancel,
transfer departure/arrival, external-effect call, inbound consumer, gateway
route or panel adaptation is implemented. Those steps require the exact
asset/task-board/maintenance/warehouse/media boundaries and their independent
authorization/recovery tests. Company/customer/reservation ownership,
location/accounting correction, post-departure reversal, media retention and
production operations remain `UNKNOWN` and excluded from this foundation.

## Completed asset prerequisite (2026-07-17)

`asset-service` owns the private logistics boundary; there is no shared model,
cross-database relationship or logistics-side persistence. The exact
`asset.logistics` receiver exposes only sanitized rental snapshots, typed
opaque operation leases, closed fenced effects and shipment-line equipment
holds. It does not expose a raw status setter, arbitrary owner reference,
asset passport/comment/catalog projection, location correction or accounting
correction command.

Asset Flyway `V4__logistics_asset_boundary.sql` is the only schema change. It
extends the immutable asset-domain-event check constraint for the sanitized
`asset.rental-item.logistics-effect-applied.v1` fact; it does not modify V1–V3
or create a logistics migration. Java 25 focused verification passed 13 tests
and the forced complete `:services:asset-service:test --rerun-tasks` suite
passed 63 tests with zero failures. No logistics client invocation, event
consumer, gateway route or panel cutover is enabled by this prerequisite.

## Task-board prerequisite (2026-07-17)

The completed task-board receiver reuses existing task-board JPA aggregates,
`TaskSyncSource` source ownership and Flyway history; it adds no migration,
schema object, cross-database relationship or logistics-side persistence.
The only private route is the dedicated logistics preparation-task
registration/status/cancellation surface under the exact
`task-board.logistics` service credential. Queue, worker, route and task
content remain task-board-owned; source-key replay/collision protection
remains in the existing task-board transaction.

Java 25 focused verification passed 4 tests and the forced complete
`:services:task-board-service:test --rerun-tasks` suite passed 104 tests
with zero failures. No logistics client invocation, maintenance/media behavior,
gateway route, panel cutover or Stage 7 persistence change is enabled.

## Maintenance prerequisite (2026-07-17)

Maintenance Flyway `V3__logistics_return_shortage.sql` creates only the
maintenance-owned `logistics_return_shortage` source table. Its compound
`return_id,line_id` primary key, snapshot/source digests and JSON-array check
make the return-line source permanent and immutable. It has no cross-database
foreign key and no SQL reference to the Stage 7 `inventory_repair_source`
tables; the fact that its version follows the current shared maintenance
history does not make the receiver call or depend on Stage 7 runtime code.

The JPA entity has safe Lombok getters only and is mapped through MapStruct
solely for the private entity-read DTO. The receiver creates no repair,
estimate, task, lease, asset effect or outbox fact. Java 25 focused
verification passed 25 tests and the forced complete
`:services:maintenance-service:test --rerun-tasks` suite passed 131 tests
with zero failures/skips. No logistics client invocation, media behavior,
gateway route or panel cutover is enabled.

## Media prerequisite (2026-07-17)

The media receiver changes no Flyway migration or schema object. Its read-only
query uses only the base `media_asset` owner, warehouse, status, generation and
deletion fields; it neither reads nor writes the concurrent Stage 7
inventory-owner proof/projection tables. A successful private check requires
the exact owner/warehouse and the requested current `READY` generation.

No media upload, owner binding, object, event, outbox or Kafka consumer is
created by `POST /api/internal/media/v1/logistics/references/validate`.
Focused Go 1.25 `internal/auth`, `internal/api` and `internal/contract` tests
passed; full `go test ./...` passed 10 test packages, while
`db/migration` and `internal/testsupport` have no test files. No logistics
client invocation, gateway route or panel cutover is enabled.

## Return-registration consumer slice (2026-07-17)

Logistics Flyway `V2__return_registration_attempts.sql` preserves immutable
V1 and adds only the `REGISTER_RETURN` idempotency operation plus unique stable
attempt identities. It creates no cross-service table, foreign key or shared
entity. JPA maps the existing logistics-owned guard, external-attempt and
reconciliation tables with safe Lombok accessors; expected/factual equipment
contents remain JSON snapshots local to a document line.

`POST /api/logistics/v1/returns/{documentId}/register` persists
`DRAFT -> REGISTERING`, a sanitized return event/outbox row and the warehouse
attempt before it calls any dependency. The relay uses direct
client-credentials calls with one exact scope at a time:
`warehouse.logistics` for identity and `asset.logistics` for snapshot, typed
lease and `RETURN_INTAKE`. Its stable external attempt UUID is the asset
idempotency key, so timeout recovery replays rather than guesses a canonical
mutation. Permanent rejections are visible `CONFLICT` state; unknown outcomes
or exhausted 1s/2s/4s retries create `RECONCILIATION_REQUIRED` with an audit
row.

The asset receiver intentionally has no tenant field in its narrow snapshot or
fenced request. Therefore the submitted trimmed `tenantSnapshot` is immutable
local evidence only; an exact canonical tenant comparison remains `UNKNOWN`
and no fake check is performed. At the V2 registration checkpoint,
acceptance/media and shortage-estimate commands remained separate work; V3
completion evidence below records their later implementation.

## Logistics service V3--V6 completion (2026-07-17)

Flyway V3 extends only the local idempotency vocabulary, local guard/media
generation evidence and the logistics-owned return-shortage snapshot. It enables
line-scoped return acceptance and maintenance-source request sagas without a
cross-database relationship. V4 adds only local shipment operation vocabulary
and versioned hold/task-reference data; V5 adds only local transfer operation
vocabulary. Each migration is immutable and preserves all preceding versions.

V6 adds the local inbound/replay/observation/reconciliation tables and expands
the local idempotency constraint for a reconciliation-request record. Historical
`inbox_message` rows remain valid with null new source-envelope columns; new
consumer rows always retain validated source topic, event type and envelope.
The migration adds no foreign key outside `logistics-service`, and the DLT is a
hash-only local record that is relayed independently of business outbox topics.

Java 25 verification passed full
`:services:logistics-service:test --rerun-tasks` (51 tests in 20 suites, zero
failures/errors/skips), including Testcontainers PostgreSQL/Flyway/JPA and real
Kafka recovery proof. The focused gateway tests and full gateway suite (37)
passed; full architecture tests (35) passed after adding the narrow inbox/replay
technical-adapter allowlist. No panel cutover or Stage 7 schema change is part
of this evidence.
