---
roadmap: docs/plans/20260712-panel-microservices-decomposition.md
roadmap_status: APPROVED_WORKING_ROADMAP
state: STAGE_9_DOSSIER_SERVICE
status: CONTRACT_APPROVED_IMPLEMENTATION_AUTHORIZED
sequence: F0 -> F1 -> F2 -> F3 -> F1C -> F4K -> F4MA -> F4MT -> F4A -> F4T -> F4R -> F4G -> W1 -> STAGE_2_TASK_BOARD_SERVICE -> STAGE_3_4_MEDIA_SERVICE -> STAGE_5_ASSET_SERVICE -> STAGE_6_MAINTENANCE_SERVICE -> STAGE_7_INVENTORY_SERVICE -> STAGE_8_LOGISTICS_SERVICE
service_owner: dossier-service
delivery_owner: RWMS lead/reviewer
next_state: STAGE_10_ANALYTICS_SERVICE
---

# Active RWMS Implementation Stage

This file is the only operational pointer for the currently authorized stage.

## User-confirmed prior stages

The user has confirmed completion of Stages 1–4. The approved product decision
combines the former Stages 3 and 4 into one stateful Go `media-service`; there
is no separate target `photo-processing-service`. This change does not recreate
or infer technical exit evidence, commits, test totals, or commit SHA.

| Stage | Confirmation status | Evidence statement in this pointer |
|---|---|---|
| 1 — `warehouse-service` | user-confirmed | No SHA, test result, or reconstructed exit evidence is asserted here. |
| 2 — `task-board-service` | user-confirmed | No SHA, test result, or reconstructed exit evidence is asserted here. |
| 3–4 — combined Go `media-service` | user-confirmed | The confirmation covers the former stage numbers 3 and 4; no SHA, test result, or reconstructed exit evidence is asserted here. |

## Completed gate: `STAGE_5_ASSET_SERVICE`

### Allowed scope

- `asset-service` ownership, Flyway V1 schema, canonical asset aggregates,
  ledger balances, holds, leases, event store, outbox/inbox, contracts and
  service tests;
- narrow least-privilege warehouse registry access for `asset-service`, its
  declarative disabled auth client, and a stateless `/api/asset/**` gateway
  route;
- the explicitly authorized panel cutover for asset registry/detail/passport,
  comments/notes/contents, equipment, equipment dispositions, and global asset
  settings;
- an isolated local `asset-db` Compose dependency and local/test-only fixtures.

### Forbidden scope

- Kubernetes, Helm, Kind, VPS/VM, hosting, CI/CD, ingress/TLS, deployment
  topology, operational runbooks, or Compose deployment settings;
- legacy ETL, browser-data import, hard delete or rental-number reuse;
- ownership of tenant/shipment, topology, reservations, repairs, inventory,
  logistics, dossier, analytics, or any Stage 6+ workflow;
- gateway state, database, Kafka participation, business aggregation, or an
  extra media gateway route;
- replacement or reinterpretation of user-confirmed Stage 1–4 evidence.

### Stage 5 exit gate

- clean/repeatable Flyway install, checksum-drift and non-empty-unversioned
  schema rejection, plus JPA validation in Testcontainers;
- deterministic event-store/snapshot replay, atomic balance movement, CAS,
  idempotency, hold/lease expiry and fencing, authorization, registry client,
  outbox/inbox/retry/DLT/quarantine and outage-recovery tests;
- OpenAPI/event-schema parity, stateless gateway routing, panel typecheck,
  lint, build, relevant Vitest and affected desktop/tablet/mobile Playwright
  flows;
- completed memory reconciliation and one reviewed scoped human commit.

The gate remained in progress until the complete matrix and reviewed initial
history were recorded; no unchecked result is implied by this pointer.

Focused evidence recorded on 2026-07-16: the Java 26.0.1 test runtime passed
25 `asset-service` tests and 16 shared architecture tests; panel typecheck,
lint, production build and 243 Vitest tests passed. The initial responsive
Playwright attempt was blocked before page execution because its configured
Chrome distribution was absent. Narrow changed-boundary checks also passed for
the declarative auth client (1), warehouse registry endpoint (3) and gateway
route (18). These were partial results; the later verification below
supersedes the initial browser blocker.

Readiness audit update on 2026-07-16: actual MapStruct equipment entity-read
mapping and Lombok constructor injection are now covered by 26 passing focused
asset tests; shared mapper/JPA policy coverage was expanded to include
warehouse-service. The audit also proved the gate remains open: fenced cabin
write-off is rejected by the manual transition, active leases do not guard all
cabin mutations, hold commit and deterministic replay are absent, classifier
writes bypass the event store, and the real Kafka retry/outage/recovery matrix
has not run.

Final policy repeat on the reconciled tree passed all 16 shared architecture
tests and every root/subproject approved-dependency verifier. A sequential root
`test --rerun-tasks` is not green: it stopped in the shared Spring starter with
54 of 56 tests passing. One test still addresses the obsolete Compose
`networks.default` path instead of isolated `rwms-media-compat`; the real-broker
test exposes a production mismatch between a byte-array Kafka message key and
the enforced `StringSerializer`. Closed platform runtime code was not reopened
inside the Stage 5-only scope.

Implementation/recovery update on 2026-07-16: the fenced path now writes off
only after validating its active lease/fencing token; public cabin mutations
covered by the asset API reject active leases. Equipment holds support the
asset-local `COMMITTED` state, classifiers append their own event facts, and
`AssetReplayVerifier` proves deterministic shadow replay/projection parity.
The Kafka binder uses a byte-array key serializer aligned with the publisher;
asset consumes its aggregate-family topics through one multiplexed functional
consumer rather than competing listeners in one group. The final Java 25 run
of `:services:asset-service:test` passed 31 tests with 0 failures/errors/
skips, including the real Kafka/PostgreSQL outage, duplicate, gap and DLT test.
This is Stage 5 evidence only: the pointer remains in progress pending the
other listed exit-gate checks and a reviewed human commit.

Final verification reconciliation on 2026-07-16: Flyway migration tests now
prove an in-place V1-to-V2 upgrade without baseline or clean, followed by
repeat validation. The affected `panel/e2e/asset-cutover.spec.ts` flow passed
all three configured Playwright projects (`desktop`, `tablet`, `mobile`), 3/3,
using bundled Chromium in an isolated test container. Direct TypeScript,
ESLint and production-build checks passed. The obsolete AMQP isolation check
was aligned with `rwms-media-compat`; its focused test and the asset migration
test passed. A final root `test` graph completed successfully after a transient
auth Kafka recovery timing failure was disproved by both an isolated recovery
repeat and the complete 129-test auth-service repeat.

The technical verification and memory items are reconciled. The user approved
an initial-history strategy, and reviewed root commit `3c509d6` establishes the
current repository baseline while preserving the absence of reconstructed
historical commits. The separately scoped closure change advances this sole
pointer only after that baseline exists. Scoped commit `4e473ac` records the
verified closure and Stage 6 transition. Stage 5 is complete.

## Completed gate: `STAGE_6_MAINTENANCE_SERVICE`

### Allowed scope

- read-only evidence collection across approved requirements, panel ports and
  stores, current tests, legacy Java/database artifacts and migration memory;
- approval of `maintenance-service` ownership, aggregate/state-machine,
  OpenAPI/events, errors, scopes, idempotency, concurrency, replay, migration
  and integration contracts;
- after explicit contract approval, only the Stage 6 `maintenance-service`,
  its service-owned PostgreSQL/Flyway schema, contracts, local dependency and
  tests, plus the approved narrow auth/asset/task-board/gateway prerequisites;
- the explicitly approved Stage 6 panel cutover for maintenance catalog,
  estimates, repairs, acceptance and write-off surfaces.

### Forbidden scope

- Stage 7 inventory implementation or contracts before the complete Stage 6
  exit gate and reviewed commit;
- guessing backend contracts from browser DTOs, localStorage/IndexedDB or seed
  identifiers;
- changing auth-service, task-board-service, asset-service, media-service or
  gateway behavior without an approved narrow prerequisite decision;
- panel work outside the approved Stage 6 maintenance cutover;
- deployment/hosting/Kubernetes/CI/CD or production operations work.

### Stage 6 contract entrance gate (completed)

Before database or implementation work, approve the final maintenance HTTP and
event contracts, aggregate/version boundaries, task-board service-to-service
registration, asset lease/fenced-status authority, media-reference lifecycle,
catalog import/version semantics, estimate/direct-repair/amendment/rework/
acceptance state machines, compensation and actor/source snapshots. Unresolved
items remain `UNKNOWN`; browser behavior is evidence only.

Evidence update on 2026-07-16: three read-only audits reconciled final legacy
catalog evidence (232 nodes, 254 links), target estimate/repair state conflicts
and current service security/integration capabilities. The consolidated
contract is `docs/plans/20260716-maintenance-service-contract.md` with status
`APPROVED`. The user's `Продолжай` response approves the combined contract,
four narrow prerequisites and Stage 6 panel cutover. Flyway and implementation
may proceed; Stage 7 remains forbidden until the complete Stage 6 exit commit.

### Stage 6 exit gate (completed)

- `/estimates`, `/repairs`, `/acceptance`, maintenance write-off and approved
  settings surfaces use the production HTTP boundary;
- draft/complete/amend/direct-repair/rework transitions are versioned and
  idempotent where retried;
- task registration failures remain retryable, task completion is inbox-
  deduplicated, and Kafka retry/DLT/quarantine/outage recovery passes;
- every terminal/retry outcome releases or truthfully reconciles the canonical
  asset operation lease, and write-off uses only the fenced asset command;
- Flyway clean/V1-upgrade/repeat/checksum/unversioned/JPA validation,
  deterministic replay, authorization, concurrency and legacy catalog import
  evidence pass;
- durable memory is reconciled and one reviewed scoped human commit exists.

Final verification on 2026-07-17 passed 18 maintenance-service test classes,
116 tests with 0 failures, errors or skips in 3m20s, including the exact 11/11
legacy catalog import matrix. Panel verification passed 261 Vitest tests and
the affected Playwright desktop/tablet/mobile matrix, 3/3. Reviewed scoped
commit `1e15a4b` (`Complete Stage 6 maintenance service`) records the Stage 6
implementation. Stage 6 is complete.

## Completed gate: `STAGE_7_INVENTORY_SERVICE`

The user explicitly authorized starting Stage 7 on 2026-07-17. The subsequent
instruction `Начинай Stage 7 все разрешаю`, followed by `Продолжай`, approves
the complete inventory contract, its narrow sequential prerequisites, the
separate media-runtime closure and the Stage 7 panel cutover. Implementation is
authorized under `docs/plans/20260717-inventory-service-implementation.md`.
Reviewed scoped commit `51460a3` records the complete Stage 7 exit. The later
Stage 8 work did not alter its ownership, contracts or verification evidence.

### Allowed scope

- close the separately authorized prior media HTTP/JWT/PostgreSQL/outbox runtime
  as one bounded, verified subgate before inventory depends on it;
- implement only the approved narrow auth, warehouse, asset V3 and maintenance
  V2 prerequisites, one deployable at a time with focused positive and negative
  tests passing before the next subgate;
- extend architecture guards, then create only `inventory-service`, canonical
  inventory OpenAPI/events, clean Flyway V1, isolated local `inventory-db`
  dependency and the approved runtime/recovery tests;
- add only the stateless `/api/inventory/**` gateway route after inventory
  implementation passes;
- cut over only the approved inventory panel ports/routes/adapters after the
  gateway subgate passes, preserving the current UI and keeping mocks as
  explicit development fixtures;
- run the complete exit matrix, reconcile durable memory, independently review
  the final diff and create one scoped human Stage 7 commit.

### Forbidden scope

- beginning a later subgate before the current subgate's success, negative,
  security and recovery checks pass and its diff is reviewed;
- widening auth, warehouse, asset, maintenance, media or gateway behavior
  beyond the exact approved inventory prerequisites;
- adding an inventory task-board client/scope, generic `asset.internal`, asset
  equipment holds, a session-wide asset lease/fence or a media credential;
- changing panel code outside the inventory cutover or treating its browser
  envelope as a production contract or migration source;
- treating browser DTOs, local storage, legacy mobile admin behavior, legacy
  deletion or seed identifiers as a production contract or migration source;
- Stage 8 work outside the separately approved parallel exception below. The
  exception must not alter Stage 7 contracts, ownership, data, routes, events,
  media types or exit evidence, nor represent Stage 7 as complete;
- deployment/hosting/Kubernetes/CI/CD or production operations work.

### Approved entrance gate and delivery order

`docs/plans/20260717-inventory-service-contract.md` has status `APPROVED`.
The approved delivery order is strict:

`media runtime -> auth -> warehouse -> asset V3 -> maintenance V2 ->`
`architecture guards -> inventory contracts/Flyway V1/runtime -> gateway ->`
`panel -> full exit review and one commit`.

Every subgate contains success and negative tests and must pass before the next
begins. The media runtime remains missing current implementation evidence; its
approval removes the authority blocker, not the verification requirement.

### Stage 7 exit gate

- media upload/finalize/access runtime, ownership checks, PostgreSQL/outbox and
  recovery are production-capable and verified without inventing unresolved
  retention/orphan/legal-hold policy;
- auth/warehouse/asset/maintenance prerequisites enforce their exact service
  identities/scopes, stable capture/source identities, plan freeze/upsert and
  maintenance-owned lease/task reconciliation contracts;
- inventory Flyway clean/repeat/checksum/unversioned/JPA validation, domain,
  authorization, concurrency, idempotency, point-in-time validation,
  statistics, replay, outbox/inbox/retry/DLT/quarantine and outage recovery
  matrices pass;
- canonical OpenAPI/event schemas match runtime behavior, the gateway remains
  stateless and the approved panel production cutover passes typecheck, lint,
  build, affected Vitest and desktop/tablet/mobile Playwright;
- durable memory is reconciled, independent review findings are closed and one
  reviewed scoped human commit records the complete Stage 7 exit.

### Stage 7 verified completion (2026-07-17)

Inventory business persistence is JPA and Flyway V1 is the sole schema
authority. Exactly six named technical eventing adapters retain low-level SQL.
The final Stage 7-only verification recorded inventory 46/46, asset 57/57,
maintenance 131/131, architecture 27/27, auth 11/11, warehouse 12/12, gateway
36/36 and media real PostgreSQL/drift/Kafka/MinIO 73/73, plus the reproducible
media build and the approved panel typecheck/lint/build, Vitest 48 and
Playwright 9/9. Commit `51460a3` closes the gate.

## Completed gate: `STAGE_8_LOGISTICS_SERVICE`

The user initially authorized Stage 8 as an isolated parallel exception. That
historical ordering did not transfer Stage 7 ownership. Stage 7 was then closed
by `51460a3`; Stage 8 implementation was recorded by `08c262f` and its final
closure/fix evidence is recorded by the containing scoped closure commit.

### Allowed scope

- perform the Stage 8 evidence and contract lifecycle for
  `logistics-service` while preserving every unresolved rule as `UNKNOWN`;
- after the contract gives each implemented capability an approved ownership,
  state machine, authorization, idempotency, concurrency, migration and
  integration boundary, implement and test only `logistics-service`, its
  service-local PostgreSQL/Flyway schema, contracts and local/test dependencies;
- add a stateless logistics gateway route only after the service boundary has
  passed its focused verification;
- maintain isolated Stage 8 memory, verification and commit evidence.
- the user's `Начни 1` authorization on 2026-07-17 permits the first
  prerequisite only: an `auth-service` disabled-by-default
  `logistics-service` client, its exact five scopes/audience/external-secret
  contract, and focused auth-service tests.
- after the verified auth prerequisite, the next sequential subgate is limited
  to `warehouse-service`: its exact `warehouse.logistics` private identity
  boundary, canonical contract update and focused positive/negative tests.
- after the verified warehouse prerequisite, the next sequential subgate is
  limited to `asset-service`: its exact `asset.logistics` private lease,
  fenced canonical effect, equipment-hold and read-snapshot contract, plus
  focused positive/negative/recovery tests.
- after the verified asset prerequisite, the next sequential subgate is
  limited to `task-board-service`: its exact `task-board.logistics` task
  registration/status/cancellation contract, plus focused
  positive/negative/recovery tests.

### Guardrails

- the preceding parallel guardrail is historical: Stage 7 is complete in
  `51460a3`, and Stage 8 is independently complete;
- no behavior may be inferred from browser envelopes, seed data, legacy mobile
  endpoints or missing company/reservation/location/media decisions;
- this exception does not implicitly authorize panel changes. A logistics panel
  cutover still needs explicit user authorization under the service-only
  delivery boundary;
- Stage 8 must not modify or rely on unfinished Stage 7 runtime behavior.
- the verified warehouse subgate authorizes only the asset receiver next.
  Task-board, maintenance and media receiver changes remain unauthorized until
  the asset subgate is green and its narrow diff is reviewed; logistics still
  must not consume a private endpoint yet.
- That warehouse-to-asset ordering statement is now historical. The verified
  asset subgate authorizes only the task-board receiver next. Maintenance and
  media receiver changes remain unauthorized until task-board is green and its
  narrow diff is reviewed; logistics still must not consume a private endpoint.

### Current Stage 8 foundation evidence

- `logistics-service`, canonical logistics OpenAPI/events, Flyway V1, JPA
  mappings, safe Lombok boilerplate, MapStruct read/event mappings and the
  transactional outbox relay are implemented only within the allowed isolated
  service scope.
- The focused Java 25 command
  `:services:logistics-service:test` passed 24 tests with zero failures. It
  includes Flyway clean/repeat/checksum/non-empty-schema checks, JPA validation,
  domain/idempotency/concurrency behavior and relay/DLT safety checks.
- This paragraph records the earlier foundation checkpoint. The later closure
  below supersedes its then-open return/ship/transfer, inbound, gateway,
  review and commit items. Panel cutover remains intentionally outside Stage 8.
- The first sequential prerequisite is complete: `auth-service` declares a
  disabled-by-default `logistics-service` confidential client with no
  repository/development secret. It can mint exactly one of
  `warehouse.logistics`, `asset.logistics`, `task-board.logistics`,
  `maintenance.logistics` or `media.logistics`, for audience `rwms-services`.
  Combined, omitted, foreign, wrong-client, USER and mismatched-override
  requests fail closed. No upstream receiver or logistics HTTP consumption was
  enabled by this subgate.
- Java 25 focused auth verification passed 25 tests. The forced complete
  `:services:auth-service:test` regression passed 153 tests with zero failures
  and one skipped test. A preceding non-forced full run had two timeouts only in
  the unchanged Kafka recovery class; the class passed twice with forced
  isolated execution before the final complete rerun.
- The second sequential prerequisite is complete: `warehouse-service` exposes
  only `GET /api/internal/warehouse/v1/warehouses/logistics/{id}/identity`.
  It requires the exact `logistics-service` SERVICE principal, matching
  `sub`/`client_id`, and the one `warehouse.logistics` scope. Its MapStruct
  response is exactly `{id, version, active, timeZone}`; inactive state remains
  explicit and topology/location data cannot cross this boundary.
- Java 25 focused warehouse verification passed 16 tests and the full
  `:services:warehouse-service:test` suite passed 29 tests with zero failures
  or skips.
- The third sequential prerequisite is complete: `asset-service` exposes only
  the private `/api/internal/asset/v1/logistics/**` surface to the exact
  `logistics-service` SERVICE JWT with matching `sub`/`client_id` and one
  `asset.logistics` scope. It exposes safe asset snapshots, typed opaque
  operation leases, fenced closed-action effects and shipment-line equipment
  holds; raw status, arbitrary owner strings and generic asset projections do
  not cross this boundary.
- The closed actions are return intake/settlement, shipment confirmation and
  transfer departure/arrival only. Transfer arrival atomically moves attached
  cabin balances through asset-owned `CABIN_TO_CABIN` ledger movements. The
  event check-constraint extension is immutable asset Flyway V4; no logistics
  schema, client invocation, gateway or panel change is implied.
- Java 25 focused asset verification passed 13 tests; the forced complete
  `:services:asset-service:test --rerun-tasks` suite passed 63 tests with zero
  failures. This enables only the next task-board receiver subgate.
- That asset-to-task-board ordering statement is now historical. The verified
  task-board subgate authorizes only the maintenance receiver next. Media
  receiver changes remain unauthorized until maintenance is green and its
  narrow diff is reviewed; logistics still must not consume a private endpoint.
- The fourth sequential prerequisite is complete: `task-board-service`
  exposes only `/api/internal/task-board/v1/logistics/preparation-tasks` to
  the exact `logistics-service` SERVICE JWT with matching
  `sub`/`client_id` and one `task-board.logistics` scope. Registration
  accepts a stable external task ID and constrained duration/deadline only;
  task-board retains the `UNASSIGNED` queue/worker/route choice and no
  caller-controlled title, queue, worker, route or free-text crosses the
  boundary.
- The safe read snapshot contains task ID/version, warehouse ID, external task
  ID, status and completion time only. Cancellation is source-owned,
  idempotent and uses the fixed task-board reason
  `LOGISTICS_PREPARATION_CANCELLED`; no arbitrary reason or task mutation is
  available. Java 25 focused verification passed 4 tests and the forced
  complete `:services:task-board-service:test --rerun-tasks` suite passed
  104 tests with zero failures. No logistics invocation, maintenance/media
  behavior, gateway, panel or Stage 7 runtime changed.
- That task-board-to-maintenance ordering statement is now historical. The
  verified maintenance subgate authorizes only the media receiver next.
  Logistics still must not consume a private endpoint, and media remains
  unauthorized until its narrow diff is green and reviewed.
- The fifth sequential prerequisite is complete: `maintenance-service` exposes
  only `GET|PUT /api/internal/maintenance/v1/logistics/returns/{returnId}/lines/{lineId}/shortage`
  to the exact `logistics-service` SERVICE JWT with matching `sub`/`client_id`
  and one `maintenance.logistics` scope. It binds the permanent
  `returnId:lineId` key to an immutable, canonically ordered
  `{equipmentId, missingQuantity}` shortage snapshot; identical replay is
  harmless and changed input is a conflict.
- The receiver creates no estimate, repair, task, lease, asset effect or
  inventory call. Its MapStruct read mapping exposes only the accepted source
  metadata/snapshot. Maintenance Flyway V3 creates only
  `logistics_return_shortage` with no foreign key or SQL reference to the
  unfinished Stage 7 inventory tables. Java 25 focused verification passed 25
  tests and the forced complete `:services:maintenance-service:test --rerun-tasks`
  suite passed 131 tests with zero failures or skips. No logistics invocation,
  media behavior, gateway, panel or Stage 7 runtime behavior changed.
- The sixth and final receiver prerequisite is complete: `media-service`
  exposes only
  `POST /api/internal/media/v1/logistics/references/validate` to the exact
  `logistics-service` SERVICE JWT with matching `sub`/`client_id` and one
  `media.logistics` scope. It accepts a declared logistics owner type,
  document/line/warehouse UUIDs and 1–20 unique opaque
  `{mediaId,generation}` references; the owner ID is derived locally as
  `<documentId>:<lineId>`.
- The endpoint reads only base `media_asset` fields and succeeds only when
  every supplied reference is the exact owner/warehouse's current `READY`
  generation. It returns only the caller's opaque IDs/generations and no URL,
  object key, filename, MIME, status or media-policy detail. It creates no
  upload, owner binding, migration, event, outbox row, Kafka consumer or
  logistics invocation and deliberately does not call the unfinished Stage 7
  inventory owner-proof projection.
- Focused Go 1.25 checks for `internal/auth`, `internal/api` and
  `internal/contract` passed. The full `go test ./...` passed 10 test packages;
  `db/migration` and `internal/testsupport` correctly have no tests. This
  completed the private prerequisite chain. Later Stage 8 evidence closes the
  logistics clients/sagas, gateway and backend exit matrix; panel cutover was
  not authorized and remains outside the completed backend gate.

### Stage 8 verified completion (2026-07-18)

- `logistics-service` owns return, shipment and transfer workflows through
  service-local JPA business persistence and immutable Flyway V1--V7. V7 adds
  optimistic versions only to mutable guard, external-attempt and media-
  reference projections. The idempotency advisory lock is invoked through a
  Spring Data JPA repository; no business service is JDBC-allowlisted.
- Named low-level SQL remains only in technical event-store, outbox, inbox,
  recovery, DLT and deterministic replay adapters. The replay verifier runs in
  one `REPEATABLE_READ` transaction; its 6/6 suite proves deterministic live/
  snapshot/shadow parity and rejects recomputed-checksum actor, correlation and
  authoritative-payload tampering. The real Kafka business-outbox outage/ack/
  recovery gate passed 1/1.
- The focused JPA/Flyway/idempotency package passed 8/8 and its exact
  architecture policy passed 7/7. Final Java 25 verification passed logistics
  60/60 and architecture 36/36; gateway remains 37/37. The earlier 51/51 and
  35/35 totals are retained only as the historical pre-closure baseline.
- `08c262f` is the reviewed implementation commit. The containing scoped
  closure/fix commit records V7, replay/outbox recovery, memory reconciliation
  and the pointer transition without inventing its SHA in advance. No panel
  cutover, location/accounting correction or post-departure reversal is
  claimed.

## Active gate: `STAGE_9_DOSSIER_SERVICE`

The user approved `docs/plans/20260718-dossier-service-contract.md` and the
bounded service-side implementation. Stage 7 and Stage 8 are complete; Stage 9
is now the sole active implementation gate.

### Allowed scope

- canonical dossier OpenAPI and event schemas;
- one isolated `dossier-service` with service-owned PostgreSQL, JPA mappings,
  immutable Flyway migrations and `hibernate.ddl-auto=validate`;
- Kafka validation, inbox/checkpoint/gap quarantine, bounded retry/DLT,
  deterministic replay, append-only activity/read projections and sanitized
  Stage 10 outbox facts;
- read-only API authorization, architecture guards, service tests and the
  stateless `/api/dossier/**` gateway route;
- durable memory, independent review and one scoped Stage 9 commit.

### Forbidden scope

- panel changes or a dossier panel cutover;
- commands, source-aggregate ownership, producer changes, cross-service database access,
  shared mutable JPA models, source-service contract changes or synthesized
  dates, actors or legacy history;
- direct producer HTTP/database reads, inferred cabin subjects, free-form
  source text, signed media URLs or operational command-path dependencies;
- Stage 10 analytics runtime or KPI implementation.

## Deferred next gate: `STAGE_10_ANALYTICS_SERVICE`

The user explicitly deferred Stage 10/KPI implementation on 2026-07-18. The
evidence record `docs/plans/20260718-analytics-service-contract.md` has status
`DEFERRED_BY_USER_2026-07-18`; it supplies no runtime authority.

### Allowed scope

- retain the evidence-only ownership boundary and unresolved KPI/product
  decisions as durable `UNKNOWN`s;
- resume only after Stage 9 completion and new explicit user authorization.

### Forbidden scope

- creating `analytics-service`, migrations, JPA entities, Kafka consumers,
  OpenAPI, gateway routes, KPI values or panel changes;
- treating a browser chart, legacy row, seed value, event count or service
  database as an approved KPI formula or reporting period;
- command ownership, source-service database access, shared mutable JPA
  models, synchronous source calls or any dependency that can block an
  operational command.
