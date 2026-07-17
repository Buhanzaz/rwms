---
roadmap: docs/plans/20260712-panel-microservices-decomposition.md
roadmap_status: APPROVED_WORKING_ROADMAP
state: STAGE_7_INVENTORY_SERVICE
status: COMPLETE
sequence: F0 -> F1 -> F2 -> F3 -> F1C -> F4K -> F4MA -> F4MT -> F4A -> F4T -> F4R -> F4G -> W1 -> STAGE_2_TASK_BOARD_SERVICE -> STAGE_3_4_MEDIA_SERVICE -> STAGE_5_ASSET_SERVICE -> STAGE_6_MAINTENANCE_SERVICE -> STAGE_7_INVENTORY_SERVICE
service_owner: inventory-service
delivery_owner: RWMS lead/reviewer
next_state: STAGE_8_LOGISTICS_SERVICE
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
- Stage 8 work;
- deployment/hosting/Kubernetes/CI/CD or production operations work.

### Approved entrance gate and delivery order

`docs/plans/20260717-inventory-service-contract.md` has status `APPROVED`.
The approved delivery order is strict:

`media runtime -> auth -> warehouse -> asset V3 -> maintenance V2 ->`
`architecture guards -> inventory contracts/Flyway V1/runtime -> gateway ->`
`panel -> full exit review and one commit`.

Every subgate contained success and negative tests and passed before the next
began. The media runtime closure is part of the verified Stage 7 evidence.

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

All implementation, independent review and verification subgates are complete.
Inventory business persistence is JPA; Flyway V1 is the sole schema authority.
Low-level SQL is restricted by service and shared architecture policies to the
six exact technical CAS/outbox/inbox/checkpoint/retry/DLT eventing adapters.
Idempotency persists the exact response under a lease-locked reservation, and
capture release runs only after the containing transaction completes.

The final Stage 7-only candidate suites passed inventory 46/46, asset 57/57,
maintenance 131/131, Stage 7 architecture 27/27, auth 11/11, warehouse 12/12,
gateway 36/36 and media's canonical real PostgreSQL, drift-PostgreSQL, Kafka
and MinIO matrix 73/73, all with zero failures, errors or skips. Media also
passed a reproducible build. Panel typecheck, lint and build passed with Vitest
48 and Playwright desktop/tablet/mobile 9/9. Earlier shared asset 64/64,
maintenance 136/136 and architecture 33/34 runs mixed in Stage 8 diagnostics
and are not Stage 7 closure totals. Closure is recorded by the containing
scoped Stage 7 commit; no SHA is invented in advance. `next_state` remains
metadata only.
