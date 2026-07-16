# AGENTS.md

# RWMS Terra Delivery Rules

## Product And Stack

RWMS is an actively developed warehouse and rental-management product. The
target is a staged microservice platform, not a screen-for-screen Jmix rewrite
and not a service per React route.

Current baseline:

- `panel`: React 19, TypeScript, Vite 8, React Router, TanStack Query/Table,
  dnd-kit, Tailwind CSS v4 and shadcn/ui (`radix-mira`, Hugeicons);
- Spring services: Spring Boot 4.1, Java 25, Gradle Kotlin multi-module, JPA and
  PostgreSQL;
- schema policy: JPA mappings define the application model and Flyway is the
  sole target schema migration/version/checksum mechanism; Liquibase is
  forbidden;
- authentication: OAuth2/OIDC Authorization Code + PKCE for the panel and
  Bearer JWT for APIs;
- integration transition: RabbitMQ is the implemented F0/F2 baseline and target
  Spring integration moves to Kafka 4.3.1 through F4; an isolated Rabbit
  media-compat path remains only until the verified combined Stage 3–4 Go
  media-service cutover; object storage: MinIO;
- common edge: stateless Spring API gateway; the combined Stage 3–4
  `media-service` is one stateful Go deployable with in-process transformations.

The verified F4K platform foundation uses Spring Cloud 2025.1.2, Spring Cloud
Stream 5.0.2, Kafka 4.3.1, Lombok 1.18.46 and MapStruct 1.6.3. Kafka business
publication and service-local event-sourced cutovers remain staged in F4A/F4T;
F4K by itself does not make either application deployable event-driven.

Development authentication bypasses and browser mocks are development-only.
Production always remains fail-closed.

## Service-Only Delivery Boundary

RWMS work ends at the application-service boundary. We implement and verify
services, contracts, migrations, and local development/test dependencies; we
do not deploy RWMS. Do not modify panel code unless the user explicitly
expands a task beyond service-side scope.

Do not add, retain, operate, or validate VPS/VM provisioning, Kubernetes, Helm,
Kind, Terraform, Ansible, Docker Swarm, Compose `deploy` settings, CI/CD release
pipelines, ingress/TLS, hosting topology, or production operations runbooks.
`compose.yaml` is allowed only as an isolated local development/test dependency
definition for services and must never be treated as a deployment manifest or
stage exit gate. Testcontainers and local profiles remain allowed when a service
test needs them.

## Authority And Active Stage

Use authority in this order:

1. approved current product requirements;
2. [`docs/plans/ACTIVE_STAGE.md`](docs/plans/ACTIVE_STAGE.md);
3. the active staged roadmap
   [`docs/plans/20260712-panel-microservices-decomposition.md`](docs/plans/20260712-panel-microservices-decomposition.md);
4. durable decisions in `WMS_ARCHITECTURE_KNOWLEDGE`;
5. current target code and tests;
6. `wms-panel-old` and `old_db` as read-only legacy evidence.

`ACTIVE_STAGE.md` is the sole operational stage pointer. Do not duplicate its
literal state in this file, the roadmap, or architecture memory. Before editing,
read its allowed scope, forbidden scope, and exit gate. A roadmap entry does not
prove implementation or authorize future-stage work.

Never modify `wms-panel-old` or `old_db`. When no approved decision or evidence
proves behavior, record `UNKNOWN`; do not infer a contract from a browser DTO,
localStorage/IndexedDB envelope, seed ID, or Jmix framework shape.

## Foundation And Stage Order

The mandatory foundation sequence is
`F0 → F1 → F2 → F3 → F1C → F4K → F4MA → F4MT → F4A → F4T → F4R → F4G → W1`:

- F0 establishes migration backups, the historical reviewed SQL release tooling, technical
  contracts, the common starter, and RabbitMQ without changing an application
  deployable;
- F1 changes only `auth-service`;
- F2 changes only `task-board-service`;
- F3 creates only the stateless `api-gateway-service`;
- F1C changes only `auth-service` so W1 can activate canonical warehouse-ID
  validation without another auth-service rebuild;
- F4K changes no application deployable and establishes Java tooling, Kafka,
  Cloud Stream, event-store conventions and migration tests;
- F4MA changes only `auth-service`, replaces its historical custom schema
  runner with Flyway, and proves cumulative baseline version 2 without starting
  event sourcing;
- F4MT changes only `task-board-service`, replaces its historical custom schema
  runner with Flyway, and proves cumulative baseline version 4 without starting
  F4T event sourcing;
- F4A changes only `auth-service` and event-sources its approved non-secret
  authorization aggregates through Flyway migration version 3;
- F4T changes only `task-board-service` and event-sources its approved domain
  aggregates through Flyway migration version 5 while preserving HTTP
  concurrency/idempotency contracts;
- F4R retires RabbitMQ from target Spring/task-board integration after Kafka
  parity is proved. It preserves an isolated media-compat Rabbit runtime for
  the unchanged legacy Go worker until the combined Stage 3–4 media-service
  cutover and deletes no historical DB evidence;
- F4G changes only `api-gateway-service` while completing observability across
  the three existing deployables without giving the gateway domain state;
- W1 implements Stage 1 `warehouse-service`.

The active ten-stage roadmap then proceeds one deployable at a time:

| Stage | Single deployable | Canonical ownership |
|---|---|---|
| 1 | `warehouse-service` | warehouse identity, metadata and timezone |
| 2 | `task-board-service` | production parity for queues, workforce, schedules, tasks, pauses, interruptions and worker inbox |
| 3–4 | `media-service` | stateful Go upload/finalize, metadata, provenance, variants, access, retention and in-process transformations |
| 5 | `asset-service` | cabins, statuses, equipment, balances, contents, holds, leases and write-offs |
| 6 | `maintenance-service` | repair catalog, estimates, repairs, acceptance and rework |
| 7 | `inventory-service` | inventory sessions, findings, completion and publication intents |
| 8 | `logistics-service` | returns, shipments, transfers and accounting corrections |
| 9 | `dossier-service` | append-only cross-domain cabin activity projection and replay |
| 10 | `analytics-service` | read-only dashboard, KPI and operational projections |

Do not scaffold, change contracts for, or implement a future stage early.

## Stage Lifecycle

Every deployable stage is completed in this order:

1. **Evidence** — inspect panel ports/stores, current tests, legacy/database
   evidence, previous migration artifacts, and unresolved decisions.
2. **Contract** — approve ownership, state machine, OpenAPI/events, errors,
   scopes, idempotency, concurrency, and migration mapping.
3. **Database** — create the service-owned PostgreSQL database, versioned
   Flyway baseline/migrations, constraints, outbox, and required inbox tables.
4. **Implementation** — implement only the active service. Panel port/adapter
   changes require explicit user authorization.
5. **Cutover** — switch affected production clients to HTTP/events; mocks remain
   only explicit development fixtures.
6. **Tests** — run domain, authorization, Testcontainers, contract,
   concurrency, failure/retry, migration, panel, and affected Playwright tests.
7. **Memory** — reconcile numbered sections, `09_MIGRATION`, history, decisions,
   and `UNKNOWN`s.
8. **Commit** — independently review and create one scoped human commit.

The next gate starts only after the current exit gate and commit are complete.

## Schema Management With Flyway

- Liquibase is prohibited in target services. Flyway is the sole target schema
  migration, version-history and checksum authority.
- For stateful Spring services, JPA/Hibernate mappings remain the application
  model source, but Hibernate never creates, updates or drops target schemas in
  development, test or production. Every Spring target profile uses
  `hibernate.ddl-auto=validate`. The combined Go media-service uses native Go
  persistence and its own schema verification instead of JPA/Hibernate.
- Each service owns its Flyway locations and `flyway_schema_history`; migrations
  are immutable, ordered and applied before Spring JPA validation or the
  service-native Go schema verification.
- New databases install from the approved cumulative schema migration followed
  by every later versioned migration. Existing databases are baselined
  explicitly at the proven current schema version before later migrations run.
- `baselineOnMigrate` is always false. An operator or controlled deployment step
  must perform the explicit baseline; an application must never silently adopt
  a non-empty unversioned schema.
- F4MA adopts Flyway in `auth-service`: a new database installs cumulative
  `V2__auth_schema.sql`; a restored existing auth database is explicitly
  baselined at version `2`. F4A later owns `V3__auth_event_sourcing.sql`.
- F4MT adopts Flyway in `task-board-service`: a new database installs cumulative
  `V4__task_board_schema.sql`; a restored existing task-board database is
  explicitly baselined at version `4`. F4T later owns
  `V5__task_board_event_sourcing.sql`.
- Every migration gate proves clean install, previous-version upgrade, repeat
  safety, Flyway checksum drift rejection and either Spring JPA validation or
  service-native Go schema verification.
- Destructive changes use expand/contract migrations. Rollback uses the verified
  pre-migration backup or reviewed compensating migration; Flyway repair is not
  a substitute for rollback review.
- Historical `database/releases`, `rwms_schema_history` and
  `databasechangelog*` evidence is retained read-only until a separate reviewed
  cleanup. Migration work must never delete current data implicitly.

Every stateful service owns one PostgreSQL database. Cross-database foreign
keys, joins, shared tables, shared JPA entities, and shared mutable domain models
are forbidden.

## Shared Platform Boundaries

`platform:technical-contracts` may contain only immutable framework-neutral
technical records such as Problem Details, pagination, event envelopes, actor
snapshots, and correlation metadata. It must contain no Spring/JPA dependency,
business DTO, domain enum, repository, entity, or aggregate.

`platform:spring-boot-starter` may provide conditional technical
auto-configuration for Jackson/UTC, correlation IDs, Problem Details,
issuer/audience validation, Kafka/Cloud Stream, observability, and production
JPA safety. RabbitMQ auto-configuration may remain only until F4R cutover.
It must not create a `SecurityFilterChain`, embed secrets/URLs, or encode domain
authorization rules.

Use a version catalog and Gradle convention plugin for annotation processing.
`platform:technical-contracts` must remain free of Lombok, MapStruct, Spring,
JPA and Kafka. Lombok is encouraged for safe boilerplate reduction, but JPA
entities must not use `@Data`, Lombok builders, generated
`equals/hashCode/toString`, or setters for IDs, versions, timestamps and domain
invariants. Records remain records. MapStruct uses Spring component model,
constructor injection and `unmappedTargetPolicy=ERROR`; it maps
projection/entity reads to DTOs and sanitized integration payloads only. It
must not perform request-to-entity mutation, security/secret mapping,
optimistic-version mutation, outbox/checksum construction or domain
transitions. Architecture tests enforce these rules.

F4K adds a side-by-side framework-neutral `DomainEventEnvelopeV2`: nullable
`occurredAt`, mandatory `recordedAt`, and a sanitized opaque actor reference
without display name, login, email or other PII. Existing Rabbit V1
`EventEnvelope` and `ActorSnapshot` fields/JSON remain unchanged; do not
perform a breaking in-place mutation. F4R may isolate the V1 compatibility
contract with the media-compat runtime, but it must remain available to the
unchanged legacy Go worker until the verified combined Stage 3–4 media-service
cutover.

Store canonical transport schemas under `contracts/openapi` and
`contracts/events`. Schemas, not shared Java domain models, are the cross-service
source of truth. Generated clients remain service-local build outputs.

## Event Sourcing, Kafka And Distributed Consistency

- RabbitMQ `rwms.domain.v1` is a completed F0/F2 historical fact, not erased
  from migration evidence. F4 migrates target Spring integration to Kafka; F4R
  removes AMQP from task-board/common Spring runtime only after write freeze,
  outbox drain/retarget, Kafka lag and inbox parity. The isolated
  media-compat Rabbit path remains until the verified combined Stage 3–4
  media-service cutover.
- PostgreSQL service-local event stores are authoritative for non-secret domain
  state. Kafka is transport and is never the canonical archive.
- Stateful event-sourced services own append-only `domain_event`,
  `event_stream_head`, snapshots, projection checkpoints, transactional outbox,
  inbox and consumer aggregate checkpoints. Append, synchronous projection and
  outbox write occur in one PostgreSQL transaction with stream CAS.
- Kafka topics are aggregate-family topics, keyed by `aggregateId`, so every
  aggregate's events preserve order. Exact versioned facts remain in
  `eventType`; separate created/cancelled topics for one aggregate are forbidden.
- Producers publish only through a service-owned transactional outbox using
  Cloud Stream `StreamBridge`; a row is marked published only after broker ack.
- Consumers use Cloud Stream functional consumers and own inbox deduplication
  keyed by `eventId`; effect, inbox and checkpoint commit in one DB transaction.
- Kafka uses `acks=all`, producer idempotence and Zstd. Consumers receive the
  first attempt plus three bounded retries at 1s/2s/4s, then a consumer-owned
  DLT. Validation failures are not retried; transient infrastructure failures
  are. Infinite requeue is forbidden.
- Aggregate version gaps quarantine that aggregate and block later effects
  until reconciliation. Topic auto-creation is local-development only.
- Delivery is at-least-once; duplicate effects must be harmless.
- 2PC is forbidden. The initiating service owns saga state and compensation.
- REST returns immediate operator outcomes; events carry committed facts and
  downstream projection/effect requests.
- Kafka transactions and `ChainedTransactionManager` do not replace the
  PostgreSQL outbox. Kafka retention does not replace indefinite event-store
  replay.

Messaging topology does not transfer domain ownership. `api-gateway-service`
is stateless and does not own a database, Kafka binder, event store,
outbox/inbox, consume domain events, or publish them.

## Service And Data Boundaries

- Mutable commands require optimistic concurrency (`expectedVersion` or ETag)
  and a consistent `409` response.
- Retried create/effect commands require `Idempotency-Key` or a stable domain
  external ID.
- JWT is validated locally by issuer/audience; auth-service is not called on
  each request.
- `auth-service` owns credentials, clients, roles, and warehouse access grants,
  but only opaque warehouse IDs.
- `asset-service` owns canonical cabin status, physical equipment balances,
  holds, and cabin operation leases/fencing tokens.
- `maintenance-service`, `inventory-service`, and `logistics-service` own their
  workflows and store only opaque lease/hold references and snapshots.
- the combined stateful Go `media-service` owns media metadata,
  original-access decisions and in-process object transformations; no separate
  target photo-processing deployable exists.
- `dossier-service` and `analytics-service` are projections, never command
  owners.

## API Gateway And Panel Strangler

These rules apply only when the user explicitly authorizes panel work. The
default task scope remains service-side.

The gateway is a stateless Spring Cloud Gateway Server MVC edge. It owns no DB,
message-bus participation, token storage/exchange, or business aggregation. It
validates external Bearer tokens, while downstream services validate them
again. Internal service-to-service calls use private addresses and
client-credentials, not the gateway.

Preserve the current UI while replacing one adapter at a time:

`browser store → feature port → versioned HTTP client → production cutover`.

- Components never access localStorage/IndexedDB directly.
- Production missing service/config/token is an error; never fall back to a
  browser mock.
- Browser data is fixture/recovery evidence, not production migration input.
- Reuse the dashboard shell, `PageToolbar`, `OperationsListGrid`,
  `PhotoCarousel`, route/back helpers, shadcn primitives, and feature boundaries.
- Preserve shared grid sorting, resizing, visibility/order, row height, and
  desktop-grid/mobile-card behavior.

## Durable Project Memory

Before every task read:

1. `WMS_ARCHITECTURE_KNOWLEDGE/README.md`;
2. `10_AGENT_MEMORY/history.md`, `decisions.md`, and `unknowns.md`;
3. the relevant numbered domain section;
4. `09_MIGRATION`, the active roadmap, and `ACTIVE_STAGE.md`.

Tie claims to paths, tests, commands, schemas, or runtime observations. Label
legacy fact, target fact, approved decision, mock-only behavior, and `UNKNOWN`.
After each task update history, relevant numbered sections, `09_MIGRATION`, and
durable decisions/unknowns without deleting audit history.

## Terra Multi-Agent Workflow

All roles use `GPT-5.6-Terra`; choose reasoning effort by complexity. The
lead/reviewer owns scope, stage enforcement, integration, verification, memory,
and commits. Backend owns Spring/Go, JPA, security, contracts, and backend tests.
Frontend owns panel/adapters/responsive UI. Legacy/domain is read-only evidence.
QA independently reviews behavior, security, concurrency, and test evidence.

Give each subagent bounded, non-overlapping file ownership. Shared-worktree edits
are visible immediately: preserve user/concurrent changes and never revert them.
Subagents do not commit unless explicitly assigned. Reports list changed files,
verification, risks, and memory-ready evidence.

## Verification

Run checks against the final diff and all completed gates. Never claim an
unexecuted check.

- `panel` (only when explicitly authorized): `npm run typecheck`, `npm run
  lint`, `npm run build`, affected tests, and Playwright desktop/tablet/mobile.
- Spring service: its Gradle test task, Testcontainers PostgreSQL Flyway
  clean-install/explicit-baseline-upgrade/repeat/checksum/JPA-validate tests,
  auth and concurrency tests.
- Kafka producer/consumer: contract, aggregate ordering, duplicate, retry,
  DLT/quarantine, ack, outbox, inbox, version-gap and outage recovery tests.
- Event sourcing: deterministic baseline, CAS/concurrency, multi-stream
  atomicity, replay/shadow-projection parity, snapshot and PII/secret exclusion
  tests.
- Go worker: `go test ./...`, reproducible build, duplicate/retry/DLT, MinIO and
  Kafka recovery tests after its owning stage adopts the F4 platform.
- Cross-service exit: OpenAPI/event validation, affected Compose E2E, security,
  failure/compensation, and regression tests for completed gates.

If infrastructure or unrelated work blocks a check, name the precise blocker
and run the narrowest valid replacement. The gate remains blocked until its full
matrix passes.

## Deferred Capabilities And UNKNOWNs

Do not create reservation, company, search, preference, or general notification
services opportunistically. Keep unresolved until their roadmap gates/ADRs:

- warehouse location/bin topology;
- canonical legacy-to-target cabin status mapping;
- formal stock balance/reservation invariant;
- task-board timezone/DST/downtime behavior;
- media retention, original authorization, and orphan cleanup;
- compensation after irreversible shipment/transfer departure;
- company/contract and customer-reservation ownership;
- KPI formulas/reporting periods and historical backfill;
- worker push/offline delivery;
- hosting, deployment topology, and production operations; these are outside
  the RWMS repository scope.

## Git And Safety

- Preserve user and concurrent changes; stage only intended hunks. Never use
  destructive reset/checkout to clean the shared worktree.
- Never commit secrets, migration backups, local passwords/keys, logs, build
  output, browser artifacts, or IDE state.
- Verify Git identity before committing. Commits use `buhanzaz` and the user's
  configured email with short human messages.
- Do not put agent/model/tool names in branches, folders, commits, or PR metadata.
- Do not use a `codex/` branch prefix. Push only when explicitly requested.
