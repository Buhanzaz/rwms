# RWMS Warehouse Service

[Русская версия](README.ru.md)

`warehouse-service` is the sole owner of warehouse identity, mutable metadata,
lifecycle, effective-dated timezone history, operation admission, and durable
proof that a warehouse has been used. Other domains store the warehouse UUID as
an opaque reference; they do not maintain a competing warehouse registry.

## Why it exists

A warehouse name, address, lifecycle, and timezone affect almost every RWMS
domain. Central ownership prevents each service from assigning different
meaning to the same warehouse or rewriting historical business dates when a
timezone changes.

| Concern | Warehouse-service responsibility | Consumer responsibility |
| --- | --- | --- |
| Stable identity | Issue and resolve the warehouse UUID | Store only the UUID and contract-defined snapshots |
| Directory metadata | Own normalized-unique display name, city, address, and ordering | Read through the public or least-privilege private contract |
| Lifecycle | Own `ACTIVE -> DRAINING -> INACTIVE` and exact version | Ask for directional admission and drain local blockers |
| Timezone | Own current and effective-dated history | Resolve the timezone at the immutable operation instant |
| First operation | Record an idempotent operated-boundary mark | Commit local work and a recoverable mark together |
| Integration fact | Persist and publish the versioned warehouse fact | Deduplicate and version-check before updating a local projection |

The service does not own cabins, inventory sessions, repairs, tasks, logistics
documents, users, warehouse grants, or client-side warehouse selection.

## Representative warehouses and support graph

A representative warehouse is the same `Warehouse` aggregate with the independent
`representative` characteristic. The characteristic defaults to `false` and does not imply a
lifecycle state, an empty workforce, restricted inventory direction, or disabled ordinary
delivery. WGS84 `latitude` and `longitude` are an all-or-none pair owned with the warehouse
metadata; `address` may remain absent when the coordinate pair is present. A warehouse without
coordinates remains valid in the directory but its logistics projection reports it as not ready
for routing, so it is not materialized as a new map workspace.

The owner also keeps a directed many-to-many support graph. One active link means that its support
warehouse may provide selected capabilities to one representative served warehouse. The policy
independently controls drivers, vehicles, inventory, direct fulfilment, interwarehouse transfers
and contractor fallback, plus priority, weekdays, allowed dates, excluded dates and an optional
daily interval. Self-links and duplicate support warehouses are rejected. The complete collection
is replaced atomically under the served warehouse's aggregate-version fence; clearing the
representative characteristic is rejected while links still exist.

Logistics reads the same warehouse UUID, owner-held coordinates, representative flag and
date-filtered support links through the private warehouse boundary. It does not create a second
warehouse or map identity. The standalone planner reconciles this directory by warehouse UUID and
version; a changed version or coordinate pair invalidates its mutable route plans before the
updated point is used. See [`Warehouse`](src/main/java/dev/buhanzaz/rwms/warehouse/domain/Warehouse.java),
[`WarehouseSupportLinkService`](src/main/java/dev/buhanzaz/rwms/warehouse/service/WarehouseSupportLinkService.java),
[`LogisticsWarehouseController`](src/main/java/dev/buhanzaz/rwms/warehouse/api/LogisticsWarehouseController.java)
and the planner's
[`directory reconciliation`](../../logistics/backend/app/services/catalog.py).

## Request and lifecycle flow

```text
SYSTEM_ADMIN public command
          |
          v
create/replace/start draining/schedule timezone/complete inactivation
          |
          v
warehouse aggregate + expectedVersion/idempotency validation
          |
          +--> warehouse DB transaction + domain event + outbox
          |
          +--> Kafka warehouse fact

operation owner --private admission--> warehouse-service
operation owner --durable operation mark/readiness confirmation--> warehouse-service
```

Lifecycle is one-way:

- `ACTIVE` accepts incoming and outgoing operations;
- `DRAINING` rejects new incoming operations but permits valid outbound work to
  finish;
- `INACTIVE` is terminal and requires exact-version readiness confirmation from
  asset, inventory, logistics, maintenance, and task-board owners.

A timeout or unknown dependency state is not readiness. Each owner must keep
pending, ambiguous, quarantined, and non-terminal work as a blocker until it can
prove the exact lifecycle version is drained.

## HTTP boundaries

The canonical contract is
[`contracts/openapi/warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml).
The public gateway exposes `/api/warehouse/**` unchanged.

| Boundary | Audience | Purpose |
| --- | --- | --- |
| `/api/warehouse/v1/warehouses/**` | Authenticated `USER`; writes require exact administrator policy | Global directory, create/replace, draining, inactivation, and timezone scheduling |
| `/api/warehouse/v1/warehouses/{id}/support-links` | Authenticated warehouse manager with grants for every endpoint | Read or atomically replace one representative warehouse's complete support graph |
| `/api/warehouse/v1/admin/outbox-events/**` | Reviewed administrator recovery | Requeue one immutable terminal/quarantined outbox fact under a review fence |
| `/api/internal/warehouse/v1/warehouses/{id}/existence` | Exact auth-service credential/scope | Narrow existence validation for warehouse grants |
| `/api/internal/warehouse/v1/warehouses/{id}/identity` | Exact task-board-service credential with only `warehouse.identity.read` | Current metadata, coordinates and timezone for the warehouse-local Driver Up shift |
| `/api/internal/warehouse/v1/warehouses/asset/**` | Exact asset-service credential/scope | Asset-specific existence boundary |
| `/api/internal/warehouse/v1/warehouses/inventory/**` | Exact inventory-service credential/scope | Inventory metadata snapshot |
| `/api/internal/warehouse/v1/warehouses/logistics/**` | Exact logistics-service credential/scope | Logistics identity/directory view |
| `/api/internal/warehouse/v1/warehouses/logistics/{id}/support-links` | Exact logistics-service credential/scope | Active support edges eligible at the supplied instant, including both endpoint coordinates |
| `/api/internal/warehouse/v1/warehouses/{id}/admission` | Contract-defined operation owner | Directional lifecycle admission |
| `/api/internal/warehouse/v1/warehouses/{id}/lifecycle-readiness` | Contract-defined operation owner | Exact-version readiness confirmation |
| `/api/internal/warehouse/v1/lifecycle/readiness-work` | Service credential | Reconciliation work for incomplete confirmations |
| `/api/internal/warehouse/v1/warehouses/{id}/time-zone` | Least-privilege service credential | Timezone at an immutable instant |
| `/api/internal/warehouse/v1/warehouses/{id}/operation-marks` | Contract-defined owner | Idempotent proof of first warehouse operation |

The public directory is intentionally a global authenticated read. Warehouse
grants do not filter it; write and domain access checks remain separate.

The logistics identity and directory responses always include the warehouse
owner's `address` field; it is nullable for warehouses whose address has not
yet been recorded. The directory contains only active warehouses in canonical
warehouse-service order and remains accessible solely to the exact
logistics-service credential and scope.

The task-board identity response is a separate least-privilege projection of
`id`, version, active flag, name, city, nullable address/coordinate pair and canonical IANA
timezone. Task-board uses it to derive the 06:00 warehouse work date and weather coordinates; it
does not copy or mutate the warehouse registry. The route is private, requires SERVICE subject and
client ID `task-board-service` with exactly `warehouse.identity.read`, and is never gateway-routable.

## Identity, concurrency, and time

- Warehouse UUID is the integration identity. Display name is normalized for
  uniqueness but never used as a cross-service key.
- Create uses `Idempotency-Key`; mutable updates use the contract-defined
  expected aggregate version and return `409` on stale state.
- An unused warehouse may correct its timezone immediately. After a durable
  operation mark exists, a change is appended with a future `effectiveFrom` and
  never rewrites prior facts.
- Inactive warehouses remain resolvable for historical references.
- Lifecycle readiness is fenced to the exact warehouse version so a late
  confirmation cannot inactivate a newer state.
- Coordinate, representative and support-collection mutations advance the same aggregate version;
  support-link replacement additionally advances its owned revision and cannot race endpoint
  lifecycle changes.

## Persistence and events

This service owns one PostgreSQL database. Flyway migrations under
[`src/main/resources/db/migration`](src/main/resources/db/migration/) are the
only schema authority; Hibernate uses `ddl-auto=validate` and never mutates the
schema.

V7 adds the backward-compatible non-null `representative=false` column. V8 adds the coordinate pair,
the warehouse-owned support revision, directed support-link rows and their weekday/date value
tables with uniqueness and direction constraints. No representative-warehouse table or
`parentWarehouseId` exists.

The aggregate transition, append-only domain history, and outbox envelope are
committed locally. The Kafka relay claims an ordered row with a lease, verifies
the immutable envelope and checksum, publishes synchronously to
`rwms.warehouse.warehouse.v1`, and then marks the row published. Broker failure
leaves durable retry state; schema/checksum failure is quarantined.

The reviewed recovery API never edits the stored event. It verifies that the
event is the first unpublished fact for the aggregate and that the review
version, actor, reason, envelope, checksum, and schema are valid before the same
relay may retry it.

Outside an explicit `dev` or `test` profile, startup requires the exact
warehouse topic, non-loopback Kafka brokers, disabled topic auto-creation,
synchronous `acks=all` idempotent publishing, a maximum publish wait shorter
than the outbox lease, and both the relay and output-binding beans. If a
production profile is combined with a local profile, production safety wins.

## Security and isolation

- The service is a JWT resource server and validates issuer and audience.
- Public writes require the exact user, scope, and global administrator rules
  implemented by `WarehouseAuthorizer`.
- Private routes require a `SERVICE` principal, an allow-listed `client_id`, and
  the exact least-privilege scope declared by the contract.
- Development auth bypass is available only in the explicit `dev` profile and
  is rejected for production profiles.
- CORS is configured from an explicit panel-origin list; clients reach the
  service only through the public gateway.

## Observability and failure behavior

The service exposes Actuator liveness/readiness, Prometheus metrics, tracing,
ECS structured logs, and `X-Correlation-Id` propagation. Logs and Problem
Details must not expose credentials, internal URLs, payloads, or personal data.

Business failures are explicit: invalid input is `400`, missing/invalid identity
is `401/403`, missing warehouse is `404`, and stale lifecycle/version or
idempotency conflict is `409`. A Kafka failure does not roll back an already
committed warehouse transition; the outbox remains the recovery authority.

Prometheus exposes `rwms.warehouse.outbox.backlog`,
`rwms.warehouse.outbox.oldest.age.seconds`, and
`rwms.warehouse.outbox.terminal`. These read-only database gauges must be used
to assess pending, in-flight, DLT, and quarantined work before any separately
authorized backlog drain.

## Local development

Start the local PostgreSQL and Kafka dependencies:

```bash
docker compose --profile core up -d warehouse-db kafka
```

Then run the service with its dev profile:

```bash
bash ./gradlew :services:warehouse-service:bootRun --args='--spring.profiles.active=dev'
```

Dev defaults use PostgreSQL on `127.0.0.1:5435`, auth on
`http://localhost:9000`, Kafka on `localhost:9092`, and port `8083`. They are not
production configuration.

## Required production configuration

Provide database URL/credentials, HTTPS auth issuer, audience, explicit CORS
origin, Kafka brokers, and an explicit `WAREHOUSE_KAFKA_ENABLED=true`. The base
profile has no Kafka-enable fallback. Keep topic auto-creation disabled and
preserve synchronous `acks=all`, producer idempotence, bounded publish timeouts,
and an outbox lease longer than the maximum publish wait.

Never expose the service's internal routes, database, Actuator management
surface, or Kafka broker directly to interactive clients.

## Verification

From the repository root:

```bash
bash ./gradlew :services:warehouse-service:test
bash ./gradlew :services:warehouse-service:javadoc
bash ./gradlew :platform:architecture-tests:test
```

Lifecycle changes require success, stale-version, wrong-owner, unavailable-
dependency, idempotent retry, and exact readiness-version coverage. Persistence
changes require clean/upgrade Flyway paths and JPA validation. Contract changes
require OpenAPI/event schema and consumer compatibility checks.

## Safe change rules

1. Keep identity, lifecycle, timezone, admission, and operation marks owned here.
2. Do not add another service's business blockers or repositories to this
   database; owners report readiness through the private contract.
3. Preserve global-directory semantics unless an explicit product decision and
   coordinated client change redefine them.
4. Keep historical timezone and inactive identity resolvable.
5. Commit aggregate change and outbox fact atomically; preserve aggregate order,
   lease fencing, reviewed recovery, and sanitized payload policy.
6. Update the OpenAPI/event schemas and every active consumer together when a
   boundary changes.

## Primary implementation references

- [Canonical OpenAPI](../../contracts/openapi/warehouse-service.yaml)
- [Warehouse controller](src/main/java/dev/buhanzaz/rwms/warehouse/api/WarehouseController.java)
- [Lifecycle private boundary](src/main/java/dev/buhanzaz/rwms/warehouse/api/WarehouseLifecycleController.java)
- [Warehouse application service](src/main/java/dev/buhanzaz/rwms/warehouse/service/WarehouseService.java)
- [Timezone history service](src/main/java/dev/buhanzaz/rwms/warehouse/service/WarehouseTimeZoneHistoryService.java)
- [Outbox relay](src/main/java/dev/buhanzaz/rwms/warehouse/eventing/WarehouseKafkaOutboxRelay.java)
- [Production safety validator](src/main/java/dev/buhanzaz/rwms/warehouse/config/WarehouseProductionSafetyValidator.java)
- [Outbox metrics](src/main/java/dev/buhanzaz/rwms/warehouse/eventing/WarehouseEventingMetrics.java)
- [Lifecycle schema](src/main/resources/db/migration/V5__warehouse_lifecycle.sql)
- [Runtime flow map](../../docs/project-knowledge/runtime-flows.md)
