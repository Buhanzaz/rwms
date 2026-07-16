# RWMS Services

This directory contains independently owned services. The only operational
stage pointer is
[`docs/plans/ACTIVE_STAGE.md`](../docs/plans/ACTIVE_STAGE.md). The approved
service order and exit gates are documented in the
[`microservice decomposition roadmap`](../docs/plans/20260712-panel-microservices-decomposition.md),
but roadmap entries do not authorize future-stage implementation.

## Foundation Sequence

Before the ten domain stages, foundation proceeds strictly as
`F0 → F1 → F2 → F3 → F1C → F4K → F4MA → F4MT → F4A → F4T → F4R → F4G → W1`.
`ACTIVE_STAGE.md` remains the sole operational pointer.

- F0 establishes recoverable database evidence, reviewed SQL tooling, technical
  contracts, the common Spring starter, and RabbitMQ without changing an
  application deployable;
- F1 changes only `auth-service` and adds configuration-driven OAuth service
  clients while migrating its schema authority;
- F2 changes only `task-board-service`, migrating its schema authority and
  adding its service-owned messaging foundation without implementing Stage 2
  production parity early;
- F3 creates only the stateless `api-gateway-service`;
- W1 implements and cuts over Stage 1 `warehouse-service`.

RWMS does not maintain deployment assets. Kubernetes/Helm/Kind/VPS/hosting and
release-pipeline work is out of scope. `compose.yaml` is retained only for
local development and service-test dependencies, never as a deployment target.

Current Spring modules in the unified Gradle build:

- `auth-service` — OAuth2/OIDC, USER and WORKER credentials, global roles, and
  warehouse-access grants;
- `task-board-service` — queue, workforce, and task core, with production parity
  recorded by the user-confirmed Stage 2 pointer;
- `api-gateway-service` — stateless Spring edge;
- `warehouse-service` — canonical warehouse registry from user-confirmed Stage
  1;
- `asset-service` — active Stage 5 implementation; it is not an exited stage.

`media-service` is the approved combined Stage 3–4 Go boundary and therefore
does not belong in the Gradle build. Its repository directory currently proves
contracts, schema and processing foundation only; the active pointer's user
confirmation does not reconstruct a runnable HTTP/PostgreSQL/Kafka exit.

Client secrets, passwords, signing keys, tokens, database backups, and
environment credentials must never be committed.

## Domain Stage Registry

Only the deployable named by `ACTIVE_STAGE.md` may be created or hardened.

| Stage | Service | Primary ownership |
|---:|---|---|
| 1 | `warehouse-service` | Canonical warehouse registry and timezone |
| 2 | `task-board-service` | Production schedules, interruptions, notifications and external tasks |
| 3–4 | `media-service` | Go upload API, metadata, signed MinIO URLs and in-process transformations |
| 5 | `asset-service` | Cabins, equipment, stock, contents, holds and write-offs |
| 6 | `maintenance-service` | Catalog, estimates, repairs, acceptance and rework |
| 7 | `inventory-service` | Inventory sessions, findings and publication intents |
| 8 | `logistics-service` | Returns, shipments and inter-warehouse transfers |
| 9 | `dossier-service` | Event-driven cabin dossier projection |
| 10 | `analytics-service` | Dashboard and KPI projections |

Do not create service directories, contracts, or Spring/Go stubs before their
gate starts. Reservation, company, search, preference, and general notification
services remain deferred capabilities.

## Mandatory Stateful Service Shape

Every stateful business service owns:

- its deployable runtime and build module;
- one dedicated PostgreSQL database;
- versioned service-owned Flyway migrations and `flyway_schema_history`;
- its aggregates, application ports, statuses, and authorization rules;
- its OpenAPI and produced/consumed event contracts;
- a transactional outbox when it publishes and an inbox when it consumes;
- health/readiness, structured logging, metrics, tracing, and schema
  verification;
- unit, authorization, contract, PostgreSQL, concurrency, and failure tests
  appropriate to its stage.

Every stateful Spring service additionally uses JPA mappings as its application
model source and Hibernate `ddl-auto=validate` in every target profile. The
combined Stage 3–4 Go `media-service` instead owns native Go persistence and
schema verification while retaining the same PostgreSQL/Flyway authority.
Liquibase is prohibited in every target service.
Existing historical `rwms_schema_history` and `databasechangelog*` evidence
remains untouched until separately approved cleanup.

## Stateless Exceptions

There is exactly one approved database-less service shape:

- `api-gateway-service` is the stateless Spring edge. It owns no database,
  RabbitMQ participation, token exchange, or business aggregation.

The combined Stage 3–4 Go `media-service` is stateful. It owns media metadata,
its PostgreSQL/Flyway schema, signed MinIO URLs, outbox/inbox records and the
in-process image/video transformation pipeline.

## Kafka, Media Compatibility And Consistency

Kafka is the target Spring at-least-once transport. Stateful producers use a
service-local PostgreSQL transactional outbox; consumers own inbox
deduplication and aggregate checkpoints. For event-sourced services, the
service-local event store, not Kafka retention, owns replay.

RabbitMQ is retired from target Spring integration at F4R. The explicit Compose
`media-compat-rabbit` profile remains only as a compatibility definition for
the unchanged legacy Go photo worker until the combined Stage 3–4 media-service
cutover is technically verified. The active-stage user confirmation is not
reconstructed runtime evidence. Historical F0/F2 tables, contracts and
migration evidence remain read-only facts. Infinite requeue, 2PC and direct
cross-service outbox reads are forbidden.

## Boundary Rules

- No cross-service database foreign keys, joins, repository imports, shared
  tables, or direct database access.
- No shared JPA entities, Hibernate mappings, aggregates, repositories, or
  domain-enum modules.
- Services exchange opaque IDs, immutable snapshots, versioned APIs, and
  versioned events.
- Each status belongs to one owning service. Consumers keep snapshots or
  mappings and tolerate compatible additions.
- Mutable commands use optimistic versions/fencing; retried effects are
  idempotent.
- Multi-service workflows persist saga/reconciliation state; UI rollback is not
  a consistency mechanism.
- External API/OIDC traffic uses the stateless gateway. Downstream services
  validate JWT again; internal client-credentials traffic bypasses the gateway.
- Development bypasses and browser stores remain development-only and
  fail-closed in production.

## Stage Entry And Exit

A deployable gate starts only when ownership, identities, statuses, APIs,
events, authorization, migration mapping, dependencies, and unresolved product
decisions are documented.

A stateful service gate finishes only after:

1. reviewed SQL proves clean install, previous-version upgrade, repeat safety,
   checksum drift rejection, `verify.sql`, and JPA `validate` on PostgreSQL;
2. contract and compatibility tests pass for every active producer/consumer;
3. outbox/inbox, idempotency, concurrency, compensation, and recovery paths are
   verified where applicable;
4. the panel is cut over through ports/adapters without production browser-store
   fallback;
5. desktop/mobile regression checks and service tests pass;
6. architecture memory and migration documentation are reconciled;
7. independent review completes and a scoped human commit exists;
8. no secrets, generated build output, runtime logs, migration backups, or local
   artifacts enter the commit.

## Stage 1 implementation record: `warehouse-service`

`warehouse-service` is the completed-scope Stage 1 implementation recorded in
this registry; `ACTIVE_STAGE.md` alone identifies the current active gate.
It runs locally on port `8083` against the dedicated `warehouse-db` Compose
dependency, validates Flyway V1 and JPA mappings, and publishes sanitized
warehouse lifecycle facts through a transactional Kafka outbox. It owns no
topology/location model, event store or consumer inbox. Deployment manifests
and application containers remain out of scope under the superseded F4I gate.
