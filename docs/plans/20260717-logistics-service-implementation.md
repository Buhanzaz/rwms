# Stage 8 Logistics Service Implementation Plan

Status: `READY_FOR_SCOPED_COMMIT`

This plan implements the approved backend-only contract in
`20260717-logistics-service-contract.md`. It is authorized as a parallel
exception while Stage 7 remains independently in progress. It must not modify
the logistics panel or merge Stage 7 evidence/commit scope.

## Development approach

- Testing is TDD: write focused failing tests before each production behavior,
  then keep the affected suite green before the next task.
- Every stateful service profile uses Flyway with
  `hibernate.ddl-auto=validate`; no legacy/browser import, schema generation,
  cross-database FK, or shared mutable model is allowed.
- Apply `rwms.spring-service` and `rwms.mapstruct`: Spring Data JPA entities
  use field access, generated UUID IDs, `@Version`, explicit schema names, and
  only safe Lombok (`@Getter` plus protected no-args). MapStruct maps
  entity/projection reads to API/event DTOs under the shared Spring,
  constructor-injection, `unmappedTargetPolicy=ERROR` convention; it never
  maps commands to mutable entities or implements domain transitions.
- The only service-to-service path is direct exact-scope client credentials.
  The gateway stays stateless and is added only after the service passes its
  focused contract/security/runtime checks.
- The current shared worktree contains unfinished Stage 7 changes. Stage 8
  changes must use new logistics paths where possible and minimal additive
  hunks in shared platform/auth/gateway files; no Stage 7 hunk may be reverted
  or staged as Stage 8 evidence.

## Delivered foundation (2026-07-17)

- The isolated `logistics-service` module, its canonical OpenAPI/event
  contracts and clean Flyway V1 are present. JPA is the application model,
  Lombok supplies safe boilerplate only, and MapStruct maps entity reads and
  sanitized event payloads with constructor injection and
  `unmappedTargetPolicy=ERROR`.
- The currently executable public slice is deliberately limited to idempotent
  `DRAFT` creation, reads and warehouse-scoped lists for return, shipment and
  transfer documents. It writes the local event stream, projection checkpoint
  and transactional outbox in the same PostgreSQL transaction.
- The Kafka relay validates the canonical envelope/checksum before a broker
  acknowledgement, preserves aggregate ordering, uses 1s/2s/4s bounded
  retries, quarantine and a sanitized per-aggregate DLT. No business payload
  is copied into a DLT record.
- Focused verification passed on Java 25: `:services:logistics-service:test`
  reports 24 tests with zero failures. An isolated PostgreSQL 17 probe also
  applied V1 and accepted one valid event/outbox/DLT row; it returned one row
  from the full envelope-integrity predicate.
- At the foundation checkpoint, return registration/acceptance, shipment
  preparation/confirmation/cancel, transfer departure/arrival and inbound
  consumers were intentionally absent. The later numbered steps record their
  completed implementation after the exact service-owned boundaries were
  independently verified.

## Implementation steps

### 1. Add Stage 8 architecture guards

**Files:**

- Create: `platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/logistics/`
- Modify: `platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/architecture/ServiceBoundaryArchitectureTest.java`

- [x] prohibit logistics code from importing browser, legacy, panel, or another
  service's mutable domain model;
- [x] require service-local event-store/outbox/inbox/checkpoint/quarantine
  infrastructure and exact-scope clients;
- [x] reject broad `asset.internal`, generic media, reservation, company,
  location-correction, and panel-runtime leakage;
- [x] write positive and negative architecture tests;
- [x] run focused architecture tests before prerequisite work.

Completed 2026-07-17: `LogisticsSourcePolicy` rejects forbidden direct service
imports/project dependencies, browser/panel/legacy references, broad scopes,
out-of-bound JDBC and request-to-entity MapStruct mutation. It also requires
the logistics-local event/outbox/inbox/checkpoint/quarantine Flyway
infrastructure and exact-scope direct client verifier. ArchUnit now checks the
compiled service boundary and constructor injection. Java 25 focused
architecture classes passed 13 tests with zero failures; the complete
`:platform:architecture-tests:test --rerun-tasks` suite then passed 33 tests.

### 2. Add the least-privilege logistics OAuth client

**Files:**

- Modify: `services/auth-service/`
- Create/modify: focused auth-service tests and test configuration

- [x] declare the disabled `logistics-service` client and external secret name;
- [x] enforce one exact downstream scope per token request;
- [x] reject combined, wrong-client, wrong-audience, USER, and missing-scope
  requests;
- [x] write positive and negative token/client-provisioning tests;
- [x] run the focused auth-service suite.

Completed 2026-07-17: Java 25 focused verification passed 25 tests; the forced
complete `:services:auth-service:test` regression passed 153 tests with zero
failures and one skipped test. A preceding unforced full run timed out only in
the unchanged Kafka-recovery class; two forced isolated class runs and the final
forced full run passed. The client remains disabled without
`LOGISTICS_CLIENT_ENABLED=true` and an external `LOGISTICS_CLIENT_SECRET`.

### 3. Add narrow upstream logistics contracts

**Files:**

- Modify: `services/warehouse-service/`, `services/asset-service/`,
  `services/task-board-service/`, `services/maintenance-service/`, and
  `services/media-service/` only at the approved logistics boundaries;
- Modify: their canonical OpenAPI/event schemas and focused tests.

- [x] add exact private warehouse identity validation for logistics;
- [x] add fenced asset lease/status/warehouse/return-settlement and
  equipment-hold operations restricted to `asset.logistics`;
- [x] add logistics-only task registration/status/cancellation semantics;
- [x] add source-keyed maintenance shortage upsert and logistics media
  reference validation without transferring ownership;
- [x] test each prerequisite's authorization, replay, CAS, retry, and
  recovery behavior before consuming it.

Warehouse completion (2026-07-17): the private identity route is exact-scope
and exact-service only, returns no topology/location fields, and deliberately
returns an inactive warehouse with `active=false` rather than falsely treating
it as an active origin/destination. Java 25 focused verification passed 16
tests; full `:services:warehouse-service:test` passed 29 tests with zero
failures or skips. The asset, task-board, maintenance and media subgates remain
open.

Asset completion (2026-07-17): the private exact-scope receiver returns a
sanitized snapshot only, accepts typed opaque operation leases and
shipment-line holds, and applies only closed fenced logistics actions. Return
settlement, shipment confirmation and transfer effects remain asset-owned;
arrival transfers attached non-zero cabin balances only through immutable
`CABIN_TO_CABIN` movements. Asset Flyway V4 adds the corresponding sanitized
event-type constraint without altering historical migrations. Java 25 focused
verification passed 13 tests and the complete
`:services:asset-service:test --rerun-tasks` suite passed 63 tests with zero
failures. The unchecked asset checklist item above is superseded by this
completed record. Only the task-board subgate is now next; maintenance and
media stay open.

Task-board completion (2026-07-17): a dedicated logistics-only private
registration/status/cancellation boundary keeps queue, worker, route, title
and free-text authority in `task-board-service`. It accepts only a stable
external task identity plus constrained duration/deadline, creates the task in
task-board-owned `UNASSIGNED`, returns a safe immutable snapshot and cancels
only an unfinished source-owned task with a fixed safe reason. The exact
receiver JWT is `logistics-service` SERVICE with one
`task-board.logistics` scope. Java 25 focused verification passed 4 tests
and the forced complete `:services:task-board-service:test --rerun-tasks`
suite passed 104 tests with zero failures. The unchecked task-board checklist
item above is superseded by this completed record. Only maintenance is now the
next receiver subgate; media stays open.

Maintenance completion (2026-07-17): the dedicated source receiver accepts
only a permanent return/line identity, canonical warehouse/rental-item
version and immutable equipment-shortage quantities. It persists a
maintenance-owned source record and returns it through a MapStruct read mapper;
it does not create or mutate an estimate, repair, task, lease or asset state.
The exact receiver JWT is `logistics-service` SERVICE with one
`maintenance.logistics` scope. Maintenance Flyway V3 adds only
`logistics_return_shortage` and has no foreign key or SQL reference to the
parallel Stage 7 inventory tables. Java 25 focused verification passed 25
tests and the forced complete `:services:maintenance-service:test --rerun-tasks`
suite passed 131 tests with zero failures/skips. The unchecked maintenance
checklist item above is superseded by this completed record. Only media is now
the next receiver subgate.

Media completion (2026-07-17): the dedicated private read-only receiver is
`POST /api/internal/media/v1/logistics/references/validate`. It accepts only
the exact `logistics-service` SERVICE JWT with one `media.logistics` scope and
derives `<documentId>:<lineId>` rather than accepting an arbitrary owner ID.
It validates 1–20 unique `{mediaId,generation}` values against the exact
declared logistics owner type, warehouse and current `READY` generation, then
returns only those opaque values. It never exposes a URL/object key/file
metadata, creates an upload/owner binding/event/outbox row, changes schema or
uses the Stage 7 inventory owner-proof projection. Focused Go 1.25
`internal/auth`, `internal/api` and `internal/contract` tests passed; full
`go test ./...` passed 10 test packages with two intentional no-test packages.
The unchecked combined prerequisite checklist item above is superseded. The
next work is logistics-service consumption/sagas, not another receiver change.

### 4. Create canonical Logistics OpenAPI and event schemas

**Files:**

- Create: `contracts/openapi/logistics-service.yaml`
- Create: `contracts/events/logistics-events.yaml`
- Create: `contracts/events/logistics/logistics-events-v1.schema.json`
- Create: schema-parity tests under `services/logistics-service/src/test/`

- [x] define public document/list/command Problem Details and ETag/version
  contract;
- [x] define return, shipment, transfer state, snapshot, attempt, and safe
  error schemas;
- [x] define sanitized aggregate-family event envelopes and DLT restrictions;
- [x] write foundation JSON schema/OpenAPI checks, including PII-field
  rejection;
- [x] run the focused schema checks in the logistics-service test suite.

### 5. Create the isolated `logistics-service` module and Flyway V1

**Files:**

- Create: `services/logistics-service/`
- Modify: `settings.gradle.kts`, `compose.yaml` only for an isolated local
  `logistics-db` test/development dependency;
- Create: `services/logistics-service/src/main/resources/db/migration/V1__logistics_schema.sql`

- [x] configure Spring/JPA/Flyway/Kafka/OAuth2/observability with production
  fail-closed defaults;
- [x] create service-local document, line, snapshot, idempotency, event-store,
  outbox, inbox, checkpoint, quarantine, and DLT schema;
- [x] add JPA entity mappings, safe Lombok boilerplate, MapStruct read/event
  mappers, and repository constraints that match V1 exactly;
- [x] write clean/repeat/checksum/unversioned/JPA validation Testcontainers
  tests;
- [x] run migration, JPA validation and foundation domain tests (24 focused
  tests passed on Java 25).

### 6. Implement return workflow

**Files:**

- Create: return domain, application, API, eventing, integration, and tests
  under `services/logistics-service/`.

- [x] implement `DRAFT -> REGISTERING -> INSPECTION_REQUIRED` with durable
  warehouse/asset attempts, exact `RENTED` snapshot/version/warehouse checks,
  a typed lease/fence and fenced `RETURN_INTAKE`; canonical tenant comparison
  remains an explicit `UNKNOWN` because the approved asset boundary is
  deliberately tenant-free;
- [x] persist immutable expected/factual contents and line-scoped
  `{mediaId,generation}` evidence, with stable warehouse/asset/media attempts;
- [x] implement undamaged acceptance and source-keyed maintenance-estimate
  requests with visible conflict/reconciliation states;
- [x] publish sanitized return facts through the transactional outbox;
- [x] write state, authorization, idempotency, CAS, stale-fence, outage, and
  replay tests.

Return workflow completed 2026-07-17: V2 adds durable registration attempt
identities; V3 adds line-scoped media generations, return shortage snapshots
and the `ACCEPT_RETURN`/`REQUEST_RETURN_ESTIMATE` idempotency vocabulary.
Acceptance waits for its scoped media/asset settlement path, while a shortage
snapshot is persisted before the source-keyed maintenance request. Permanent
dependency rejection is visible as `CONFLICT`; unknown/exhausted 1s/2s/4s
outcomes are `RECONCILIATION_REQUIRED`, never a guessed compensating effect.

### 7. Implement shipment workflow

**Files:**

- Create: shipment domain, application, API, eventing, integration, and tests
  under `services/logistics-service/`.

- [x] implement plan snapshot validation, lease acquisition, allocation-hold
  creation, and stable per-cabin task registration;
- [x] implement preparation confirmation with hold commit and fenced shipment
  action;
- [x] implement pre-confirmation cancellation/release and durable recovery;
- [x] publish sanitized shipment facts through the transactional outbox;
- [x] write allocation, duplicate, partial-effect, cancel, retry, and replay
  tests.

Shipment workflow completed 2026-07-17: V4 adds the shipment operation
vocabulary plus local versioned task/hold references. The saga keeps external
attempt IDs stable across retry/replay and does not expose task-board queue,
worker or arbitrary task-content authority to logistics.

### 8. Implement transfer workflow

**Files:**

- Create: transfer domain, application, API, eventing, integration, and tests
  under `services/logistics-service/`.

- [x] implement distinct origin/destination validation and per-line departure;
- [x] implement immutable in-transit snapshot, explicit arrival validation,
  destination assignment, and conflict/reconciliation handling;
- [x] reject post-departure cancellation and unsupported location/accounting
  correction commands;
- [x] publish sanitized transfer facts through the transactional outbox;
- [x] write per-line CAS, stale-fence, mismatch, partial-arrival, and replay
  tests.

Transfer workflow completed 2026-07-17: V5 adds the dedicated transfer
operation vocabulary. Departure and arrival are fenced, line-CAS operations;
arrival retains immutable contents evidence. Cancellation is intentionally
limited to the pre-departure draft state.

### 9. Implement inbound processing, reconciliation, and gateway route

**Files:**

- Create: logistics inbox/consumer/reconciliation/replay components and tests;
- Modify: `services/api-gateway-service/` only for stateless
  `/api/logistics/**` routing after service verification.

- [x] consume only declared asset/task-board/maintenance/media facts using
  inbox deduplication and aggregate checkpoints;
- [x] add retry/DLT/quarantine/outage recovery and deterministic shadow replay;
- [x] add public API security/Problem Details/contract parity tests;
- [x] add a stateless gateway route and narrow gateway security/route tests;
- [x] run focused logistics and gateway tests.

V6 accepts only the six declared source topics, preserves validated envelopes
for operator-reviewed replay, quarantines version gaps and emits a hash-only
consumer DLT after the bounded retry policy. It preserves pre-V6 inbox rows
without fabricating source evidence. The gateway now routes only
`/api/logistics/**`, strips cookies, denies logistics internal/private paths,
and rejects a production loopback upstream.

### 10. Complete verification and memory

**Files:**

- Modify: the relevant numbered knowledge, migration, history, decisions, and
  unknowns documents without deleting audit history.

- [x] run the complete logistics Testcontainers/Kafka/Flyway/security matrix
  (51 tests in 20 suites, zero failures/errors/skips);
- [x] run the affected architecture (35 tests) and gateway (37 tests) suites;
  verified prerequisite evidence remains in the Stage 8 migration record;
- [x] independently review only Stage 8 hunks and close findings;
- [x] reconcile durable memory and record unimplemented correction policy as
  `UNKNOWN`;
- [ ] create one Stage 8-only reviewed human commit when all accepted checks
  pass. It is intentionally not created here: the shared worktree contains
  independent Stage 7/user changes and no commit was requested. Panel
  verification remains deferred pending explicit panel cutover authority.

## Explicit non-goals

- No panel adapter or route changes.
- No company/customer/reservation/driver directory.
- No location or accounting correction implementation.
- No reverse shipment/transfer workflow after irreversible confirmation.
- No Stage 7 modification, completion claim, or mixed commit.
