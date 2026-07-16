# F4 Event-Driven Modernization Governance

Date: 2026-07-13

## 2026-07-14 service-only supersession

The product direction now excludes deployment work. F4I is superseded rather
than closed, and the active sequence is `F4K -> F4MA -> F4MT -> F4A -> F4T ->
F4R -> F4G -> W1`. Kubernetes/Helm/Kind/hosting artifacts were removed. The
F4I candidate notes below are retained as historical audit evidence only and
must not be used to restore deployment scope or block W1.

Governance approved: 2026-07-13

F4K foundation closure commit: `52c0702`

F4MA governance correction: `844e55d`

F4MA auth Flyway closure commit: `334576a`

F4MT task-board Flyway closure commit: `01cb9c2`

Prerequisite F1C closure commit: `d50922d`

Ordered sequence:

`F4K -> F4MA -> F4MT -> F4A -> F4T -> F4R -> F4G -> W1`

This note records the approved target and durable F4K/F4MA/F4MT closure evidence. It does
not move the operational pointer or authorize a later gate; only
`docs/plans/ACTIVE_STAGE.md` does that.

## F4K closure evidence

F4K is implemented by commit `52c0702` without changing application business
behavior. It added the approved version catalog/convention plugins, guarded
Lombok/MapStruct processing, conditional Kafka/Cloud Stream starter,
`DomainEventEnvelopeV2`, canonical event-store/delivery schemas and Kafka 4.3.1
KRaft core infrastructure while preserving RabbitMQ.

Verification:

- full Gradle build passed 254 tests with zero failures and two documented skips;
- default and `core` Compose configurations validate;
- dependency convergence resolves Kafka client 4.3.1;
- gateway runtime has no Kafka client and auth/task-board runtime has no
  MapStruct dependency;
- event schemas validate and PII/secret scans are clean except for the
  intentional private-key negative-test sentinel;
- `git diff --check` and independent Java/platform, messaging/security and
  database/event-store reviews passed.

Kafka business publication and application event stores are not implied by this
closure. They remain the F4A/F4T migration work.

## F4MA closure evidence

Commit `334576a` changes only `auth-service` and adopts Flyway 12.4.0 with
cumulative versioned `V2__auth_schema.sql`, explicit baseline version 2 for an
existing database, `baselineOnMigrate=false` and JPA validation in every target
profile. Governance correction `844e55d` replaced B2 after runtime evidence
showed that applied baseline-migration drift was not rejected by `validate`.

Verification:

- dependency insight, Amplicode rebuild and analysis passed;
- the auth suite passed 72 tests with zero failures/errors and one expected
  restored-backup environment skip;
- clean V2, repeat, checksum drift, non-empty rejection, exact preflight,
  explicit baseline, row digests and JPA validation passed;
- historical releases, history tables and current auth/OAuth data remain
  preserved;
- an unrelated npm `ENOTEMPTY` blocked one forced rerun, while the successful
  normal full suite provides the closing evidence.

F4MA itself added no event store or Kafka business publication; F4MT was closed
separately by the evidence below.

## F4MT closure evidence

Commit `01cb9c2` changes only `task-board-service` and adopts Flyway 12.4.0
with cumulative versioned `V4__task_board_schema.sql`, explicit baseline
version 4 for an existing database, `baselineOnMigrate=false` and JPA
validation in every target profile. The historical V0001-V0004 catalog and
release checksums, existing rows, Rabbit outbox/inbox, `rwms_schema_history`
and `databasechangelog*` remain preserved.

Verification:

- dependency insight resolved Flyway 12.4.0;
- the exact historical/cumulative catalog digest is
  `2b204139b97ff46298b9818800cc3f9e`, and all historical release SHA-256 values
  match;
- 12 targeted clean/repeat/checksum/non-empty/preflight/baseline/digest and
  clean/adopted JPA checks passed;
- the forced full task-board suite passed 87 tests with zero failures/errors and
  one expected restored-backup environment skip;
- Amplicode rebuild/analysis and independent database review passed;
- no task-board API, domain or Rabbit behavior changed.

F4MT adds no event store or Kafka business publication. F4A is now the next
authorized gate through `docs/plans/ACTIVE_STAGE.md`; F4T remains later.

## Gate boundaries

- **F4K**: Kafka 4.3.1, Spring Cloud Stream and shared technical conventions;
  no business deployable.
- **F4MA**: auth-service only. Adopt Flyway with cumulative V2 for clean
  databases, explicit baseline version 2 for existing databases,
  `baselineOnMigrate=false` and JPA validation only; no event sourcing.
- **F4MT**: task-board-service only. Adopt Flyway with cumulative V4 for clean
  databases, explicit baseline version 4 for existing databases,
  `baselineOnMigrate=false` and JPA validation only; no F4T behavior.
- **F4A**: auth-service only. Event-source classified non-secret identity/access
  aggregates through Flyway V3 while retaining credentials and OAuth JDBC state operationally;
  add and verify auth-service telemetry in this gate.
- **F4T**: task-board-service only. Event-source its business aggregates through
  Flyway V5 and
  rebuild service-local relational projections; add and verify task-board
  telemetry in this gate.
- **F4R**: drain and retire RabbitMQ from target Spring integration only after
  Kafka parity and recovery proof. Keep isolated media-compat RabbitMQ solely
  for the unchanged legacy Go worker until the combined Stage 3–4 media-service
  cutover.
- **F4G**: change only the gateway deployable to add gateway telemetry. The
  gateway remains stateless and event-free; the closing gate verifies
  cross-deployable telemetry across auth, task-board and gateway without
  changing the two already closed services.
- **F4I**: superseded by the service-only product decision; it has no active
  scope or exit gate.
- **W1** resumes after the completed F4G gate; its current authorization is
  defined only by `ACTIVE_STAGE.md`.

F4MA runtime evidence against Flyway `12.4.0` showed that checksum drift in an
already applied `B2` baseline migration was not rejected by `validate`.
Consequently auth uses cumulative versioned `V2__auth_schema.sql` so checksum
validation remains authoritative. Governance correction `844e55d` preceded
implementation commit `334576a`.

The same runtime fact was applied in F4MT: task-board clean install uses
cumulative versioned `V4__task_board_schema.sql`, not B4, and existing
task-board databases remain explicit baseline version 4. Commit `01cb9c2`
provides the implementation evidence.

## Persistence and transport target

Each stateful owner keeps its own PostgreSQL tables for immutable domain events,
aggregate stream heads, synchronous projections, transactional outbox,
consumer inbox and projection/checkpoint metadata. An aggregate append, its
projection update and any integration outbox record commit atomically.

Flyway is the sole active schema migration/version/checksum authority after
F4MA/F4MT. Hibernate only validates in every target profile and
`baselineOnMigrate=false`. Historical custom releases,
`rwms_schema_history` and `databasechangelog*` remain read-only evidence.

Kafka uses aggregate-family topics and `aggregateId` as the record key. Event
type and schema version remain explicit in the envelope. Delivery is
at-least-once: consumers validate envelope/hash/version, deduplicate `eventId`
in their own inbox, reject aggregate-version gaps, retry only bounded transient
failures and route poison/exhausted records to a consumer DLT. Kafka
transactions do not replace the PostgreSQL outbox.

F4K adds `DomainEventEnvelopeV2` side-by-side with the existing V1
`EventEnvelope`; it does not mutate V1 in place. V2 has nullable `occurredAt`,
required `recordedAt`, and a sanitized opaque actor reference without display
name or raw PII. At F4R existing V1 `EventEnvelope` and `ActorSnapshot` leave
target Spring integration and are isolated to media-compat RabbitMQ for the
unchanged legacy Go worker until the combined Stage 3–4 media-service cutover. Kafka
records, event-store rows and new outbox payloads use V2 only after the owning
gate's compatibility tests pass.

Migration baseline events contain the current proven state and preserve the
existing optimistic version. They are labelled migration baselines, are not
published as historical business activity and do not invent an occurrence
time. Replay is first proven in shadow projections and compared to the live
state before read/write cutover.

RabbitMQ remains the factual F0/F2 implementation until F4R. Its existing
outbox/inbox, confirm/return, retry/DLQ and recovery evidence is not rewritten
as Kafka evidence. F4R removes it from target Spring integration, but does not
break the unchanged legacy Go media worker: an isolated media-compat RabbitMQ
runtime/topology remains until the combined Stage 3–4 media-service cutover and cannot
be reused for new business integration.

## Protected and operational exclusions

Raw PII, password/hash material, OAuth tokens and codes, client/signing secrets,
sessions and unrestricted authorization records are forbidden in domain-event,
outbox, Kafka, DLT, log and trace payloads. Replayable aggregates use opaque
references to service-local PII vault/operational state where protected identity
is required.

Credential/OAuth state, encryption material, delivery leases, outbox/inbox
status, consumer offsets/checkpoints and technical saga attempts remain
operational persistence. A redacted audit event may prove an operation occurred
without becoming a source for secret reconstruction.

## Code-generation rules

Lombok is allowed for constructors, logging and immutable value boilerplate;
MapStruct is allowed for explicit API/event/projection mapping. JPA entities do
not use `@Data`, generated entity equality or unrestricted builders. Generated
mappers do not own validation, authorization, aggregate transitions or secret
redaction. Shared generated domain models remain forbidden.

## Readiness qualification

F4A and F4T own telemetry changes for auth and task-board respectively. F4G
changes the gateway only and then performs cross-deployable verification.
Deployment dashboards, orchestration and hosting are outside the repository
scope. Production topology and operations remain out of scope rather than an
active F4 decision.

## F4A broker-recovery evidence (2026-07-14)

The auth producer bindings are created at application startup without sending a
synthetic domain fact. Outbox publication remains synchronous Cloud Stream
`StreamBridge` publication and a row is completed only after the binder reports
success.

The recovery defect was a transport-key type mismatch: the Kafka binder uses
`ByteArraySerializer`, but `KafkaHeaders.KEY` was a `String`. The shared
publisher now supplies the aggregate UUID as UTF-8 bytes. The approved bounded
policy remains unchanged: one initial attempt followed by retries at 1s, 2s and
4s, then DLT.

A real-broker outage/recovery test now proves that due retries recover without a
manual outbox reschedule. Because a client timeout may race an accepted broker
write, raw Kafka delivery can contain an at-least-once duplicate. The proof
therefore requires exactly two unique event IDs, ordered aggregate versions
`0, 1`, sanitized records and exactly two service-owned inbox effects after
deduplication. This evidence closes only the reported recovery defect, not the
full F4A gate.

The completed F4A verification additionally proves clean and adopted V2 to V3
migration, checksum rejection, JPA validation, deterministic baseline, shadow
replay parity, CAS/multi-stream atomicity, snapshot threshold, database and
broker recovery, inbox deduplication and version-gap quarantine. Full auth tests
passed 129/129 with one conditional skip; task-board and gateway regressions
passed 87 and 26 tests respectively. V3 remains expand-only and preserves all
legacy/OAuth rows.

## F4T implementation (2026-07-14)

Current implementation scope is limited to `task-board-service` plus its
canonical event schema:

- Flyway V5 expands cumulative V4 with the task-board event store, deterministic
  non-published baselines, snapshots/checkpoints, Kafka outbox, inbox,
  aggregate-version quarantine, sanitized DLT and replay audit;
- seven streams are implemented: `WORKER_CLASS`, `WORKER`, `WORKER_GROUP`,
  `WORK_QUEUE`, `QUEUE_USAGE_REFERENCE`, `BOARD_TASK` and `QUEUE_ENTRY`;
- assignment, time-event and interruption state is embedded in `QUEUE_ENTRY`
  facts; credentials and technical saga state remain operational;
- `TaskBoardProjectionWriter` requires caller-owned transactions, while the
  architecture guard forbids event-sourced repository mutations outside that
  writer; stream heads are locked in stable aggregate-type/UUID order;
- canonical task-board schemas reject names, descriptions, comments, task text,
  passwords, tokens, secrets and credential-operation details. Existing worker
  profile PII stays in the operational projection and is represented in events
  only by an opaque revision marker;
- Kafka has an explicit dev/test opt-out, fails closed in non-local profiles,
  and uses the PostgreSQL outbox/inbox/DLT/quarantine path. Rabbit remains for
  dual-run evidence until F4R; the cutover rehearsal preserves legacy rows and
  records idempotent mappings in an append-only ledger.

Flyway/replay, runtime retry/recovery, cutover parity and affected service
regressions have run, and the independent closing review reported no actionable
P0-P3 findings. Commit `0c0957e` closes F4T and the active stage advances to
F4R Rabbit retirement.

## F4R implementation (2026-07-14)

- Removed AMQP dependencies, common Rabbit auto-configuration and task-board
  Rabbit relay/listener/dual-write runtime.
- Preserved Flyway V4/V5, historical Rabbit tables/rows/checksums and V1
  contracts without destructive cleanup.
- Isolated Rabbit to Compose profile `media-compat-rabbit` for the unchanged Go
  worker, retaining its `rabbitmq` alias and durable volume.
- Local cutover evidence retained one historical unpublished row, retargeted
  exactly one on the first rehearsal and zero on repeat, with zero unresolved
  and zero unmappable rows at readiness.
- Focused task-board verification passed 51 tests, starter retirement passed 2,
  both Compose configurations validated, and independent review found no
  actionable P0-P3 runtime defect. Commit `c58c9fb` closes F4R.

## F4G implementation (2026-07-14)

- Added gateway health/liveness/readiness, authenticated Prometheus, OTLP
  tracing configuration and ECS JSON stdout logging without changing another
  deployable.
- Preserved W3C trace context through downstream HTTP and kept the independent
  `X-Correlation-Id` contract unchanged. Kafka tracing remains downstream
  service instrumentation; the gateway has no binder.
- Expanded architecture enforcement to reject DB/JPA/Flyway, Kafka/Rabbit,
  Redis, workflow, domain, persistence and eventing state from the gateway.
- Focused gateway evidence passed: 31 unchanged tests in the batch plus both
  corrected observability-configuration tests, gateway compilation and an
  independent review with no actionable P0-P3 finding. Auth/task-board were not
  reopened; their closed regression evidence remains the cross-deployable base.
- Scoped implementation commit `08d4037` closes F4G and authorizes only the
  historical F4I infrastructure-readiness gate, later superseded by the
  service-only product decision.

## Archived F4I infrastructure-readiness candidate (2026-07-14)

- Added isolated Compose profiles for core, observability, cache, search,
  analytics, workflow and media-compatible Rabbit without adding an application
  deployable or deferred business state.
- Added a pinned Helm umbrella, bounded Kind smoke, external-secret references,
  namespaces, PVCs, resource bounds, NetworkPolicy and PDB.
- Added deterministic validation for profile isolation, secret/business-use
  exclusion, bounded Helm rendering and kubeconform. The final bounded result is
  `13 PASS`, `0 FAIL`, `0 BLOCKED`.
- Runtime health passed for core, cache and media compatibility. Full profile
  and Kind completion remains incomplete. The pinned Strimzi `1.1.0` archive
  now resolves through its official OCI registry, but the official Camunda
  `14.6.1` release asset still returns `EOF` after three bounded attempts. The
  default Kind node image now pulls, while Docker Desktop reports cgroup v1 and
  a diagnostic bootstrap did not complete; the smoke preflights cgroup v2
  before cluster creation. This historical candidate is superseded and no
  longer blocks W1.

## Archived F4I static catalog revalidation (2026-07-14)

- The full temporary dependency catalog now resolves all eight pinned
  third-party charts, including Strimzi through its official OCI registry.
  Helm lint passes all 9 charts.
- Full rendering passes the secret/business-use guard and strict kubeconform
  validates 119 Kubernetes built-in resources. Helm render/lint separately
  processes 36 CRDs and 53 vendor custom resources; generic kubeconform does
  not claim schema validation for those vendor-owned kinds.
- The default raw-GitHub schema endpoint returns `EOF` from the current
  workstation. The validation source is therefore the same public schema
  repository at immutable revision `6575cbe6397e3c1cb0946a41a200c37377fafe29`,
  retrieved through jsDelivr with a 90-second process ceiling and ephemeral
  cache.
- A fresh observability image pull and two bounded retries failed at
  `registry-1.docker.io` with `TLS handshake timeout` before a container was
  created. It is not runtime-health evidence. Docker cgroup v2 is still required
  for the live Kind smoke. This historical result is superseded and does not
  affect W1 authorization.

## Archived F4I repeatability blocker (2026-07-14)

- The successful full-catalog validation above proves the current manifest and
  safety logic, but does not close F4I: the final fresh dependency build again
  exhausted all three bounded attempts on the official Camunda `14.6.1` release
  asset with `EOF`.
- A locally cached archive matched the official Helm-index SHA-256, but it is
  diagnostic evidence only. The validation must not silently substitute a
  mutable local cache or a third-party chart mirror for a failed official
  networked preparation step.
- The full-catalog retrieval, remaining Compose runtime health and the cgroup-v2
  Kind smoke were exit-gate blockers at the time; they are no longer active
  project work.
