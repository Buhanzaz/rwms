# RWMS Services

[Русская версия](README.ru.md)

This directory contains the current independently owned RWMS services. Product
work is selected by the user's request; there is no active stage sequence or
service-order gate.

The maintained architecture map is
[`docs/project-knowledge/architecture.md`](../docs/project-knowledge/architecture.md).
Historical plans and stage records are evidence only.

## Service Registry

| Service               | Runtime shape              | Primary ownership                                                          |
| --------------------- | -------------------------- | -------------------------------------------------------------------------- |
| `auth-service`        | Stateful Spring            | OAuth2/OIDC, user/worker credentials, roles, clients and warehouse grants  |
| `api-gateway-service` | Stateless Spring           | Public edge routing and transport security only                            |
| `warehouse-service`   | Stateful Spring            | Warehouse identity, metadata and timezone                                  |
| `asset-service`       | Stateful Spring            | Cabins, equipment, stock, contents, holds and leases                       |
| `task-board-service`  | Stateful Spring            | Queues, workforce, assignments and task board                              |
| `maintenance-service` | Stateful Spring            | Catalog, estimates, repairs, acceptance and write-off decisions            |
| `inventory-service`   | Stateful Spring            | Inventory sessions, findings, completion and publication                   |
| `logistics-service`   | Stateful Spring            | Rental inquiries, returns, shipments, transfers, drivers and orchestration |
| `dossier-service`     | Stateful Spring read model | Cross-domain cabin activity projection                                     |
| `analytics-service`   | Stateful Spring read model | KPI and dashboard projections                                              |
| `assistant-service`   | Stateful Spring            | Assistant conversations, messages and tool-call history                    |
| `media-service`       | Stateful Go                | Media metadata, upload/finalize, private objects and transformations       |

The root Gradle build lists Spring modules in `settings.gradle.kts`.
`media-service` is Go and is intentionally outside that build.

## Stateful Service Shape

Every stateful business service owns:

- one dedicated PostgreSQL database and its data lifecycle;
- service-local immutable Flyway migrations and `flyway_schema_history`;
- aggregates, statuses, application ports and authorization rules;
- its OpenAPI family and produced/consumed event schemas;
- transactional outbox/inbox and recovery where it publishes or consumes;
- health, readiness, structured logs, metrics and tracing;
- focused unit, authorization, contract, persistence, concurrency and failure
  tests.

Spring services use JPA as the application mapping and
`hibernate.ddl-auto=validate`. The Go media service owns native persistence and
schema validation. Liquibase is not an active schema authority.

## Boundary Rules

- No cross-service database foreign keys, joins, shared tables, repository
  imports or direct database access.
- No shared JPA entities, aggregates, repositories or domain-enum modules.
- Services exchange opaque IDs, immutable snapshots, versioned APIs and
  versioned events.
- Mutable commands use optimistic versions or fencing; retried effects are
  idempotent.
- Cross-service workflows persist saga/reconciliation state in the initiating
  owner. UI rollback is not a consistency mechanism.
- Public traffic goes through `api-gateway-service`; downstream services still
  validate JWTs. Internal service traffic uses private routes and credentials.
- The gateway owns no database, messaging, workflow, cache or business
  aggregation.

## Completion Gate

A changed service is ready for handoff only after its contract and consumers
are aligned, focused success/failure tests pass, schema/JPA validation passes
when applicable, architecture boundaries are reviewed and durable changes are
recorded in `docs/project-knowledge/`.

Secrets, credentials, backups, logs and generated build output must never be
committed.
