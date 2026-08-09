# RWMS Maintenance Service

[Русская версия](README.ru.md)

## Purpose and ownership

`maintenance-service` owns maintenance catalog versions, estimates, repairs, repair places and
capacities, acceptance/write-off decisions, and its side of inventory-publication reconciliation.
It is the authority for those transitions; other services do not write its PostgreSQL tables or
reconstruct repair state from Kafka.

The authoritative HTTP and event contracts are
[`contracts/openapi/maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml)
and [`contracts/events/maintenance-events.yaml`](../../contracts/events/maintenance-events.yaml).
Read [`docs/project-knowledge/domain-logic.md`](../../docs/project-knowledge/domain-logic.md)
as an index, then verify any rule against current contracts and service code.

## Public and private HTTP boundary

Public user operations are versioned below `/api/maintenance/v1/**`; they cover catalog, estimate,
repair, repair-place/settings and property-disposition work. Mutable commands use the
contract-defined idempotency key and expected-version fields. Clients must handle the canonical
`409` conflict rather than send a changed retry.

Interactive panel and Android clients reach this namespace only through the public
`api-gateway-service` `/api/maintenance/**` route. They must not call this module host or an
`/api/internal/**` route directly.

Private routes are narrow by design:

- `/api/internal/maintenance/v1/inventory/**` supports inventory-owned planning, source and
  publication-reconciliation work.
- `/api/internal/maintenance/v1/logistics/**` supports logistics return-estimate and
  repair-place orchestration.

Private callers use service credentials, not a forwarded user token. The authorizer checks exact
service identity, audience and single-purpose scope for inventory and logistics. The HTTP security
chain is stateless OAuth2/JWT; dev auth bypass is limited to the `dev` profile and rejected by the
production validator.

## Warehouse isolation and transaction rule

User operations are authorized for the requested warehouse and access level. Maintenance keeps its
catalog, repair and disposition state in its own database; it never joins another service database.

Remote truth is accessed through `MaintenanceDependencyGateway` with client credentials for asset,
task-board, media, logistics and warehouse-service. Do not make a remote call while a local write
transaction holds maintenance locks. The application flow commits local preparation before remote
preflight/effects, then continues local fenced state through durable recovery records.

## Internal application structure

`MaintenanceApplicationService` is a stable six-collaborator compatibility facade. It preserves
the controller/private-boundary API and transaction annotations while delegating to cohesive
application owners:

| Collaborator family | Owned responsibility |
| --- | --- |
| `MaintenanceCatalogUseCases` plus catalog model/support types | Catalog-version reads, draft mutation, forking, validation and activation |
| `MaintenanceEstimateUseCases` plus estimate/furniture/revision supports | Estimate lifecycle, lines, plans, furniture admission and immutable revisions |
| `MaintenanceRepairUseCases` plus repair lifecycle/model/media/task-board supports | Repair creation, queueing, execution, acceptance and rework preparation |
| `MaintenanceTransferUseCases` and `MaintenanceTransferSupport` | Transfer departure/arrival maintenance continuation |
| `MaintenanceInboundUseCases` and `MaintenanceInboundFactProjectionUseCases` | Owner-fact ingestion and projection updates |
| `MaintenanceReconciliationUseCases` | Claim dispatch, media-owner proof and failure recording only |
| Asset, task and repair-lifecycle reconciliation use cases | The three independent remote-effect/recovery branches |
| `InventoryMaintenanceService` | Stable private inventory-boundary facade over freeze, upsert and repair-snapshot projection |
| Inventory maintenance freeze/upsert/validation/transaction collaborators | Frozen-plan admission, catalog/routing/media validation and isolated local transactions |
| `InventoryPublicationReconciliationService` | Stable completed-inventory facade over preflight projection and durable apply |
| Inventory publication source/pre-start/target/materialization collaborators | Source replay, replacement compensation, target selection and estimate/repair materialization |
| `PropertyDispositionApplicationService` | Stable decision facade over creation, furniture materialization, review/recovery, reads and processing callbacks |
| Property-disposition boundary, persistence, actor, repair-chain and finalization collaborators | Transaction/lock/hash mechanics, event parity, repair-chain validation and terminal repair effects |
| `HttpMaintenanceDependencyGateway` | Stable private transport facade over warehouse, asset, logistics, task-board and media owners |
| `MaintenanceHttpTransport` and owner HTTP clients | Exact-scope OAuth/HTTP/error policy plus owner-local endpoints, DTO validation and fence checks |

`MaintenanceCommandSupport` centralizes only the original local transaction, lock, hash and JSON
command mechanics; event-payload/model supports remain narrow mappers. The use-case dependency
graph is acyclic, no collaborator refers back to the facade, and every constructor/direct
dependency surface is at most 15. Package-local workflow records avoid coupling collaborators to
the facade's public nested result type; the facade alone adapts that carrier back to its unchanged
public response.

The inventory maintenance boundary follows the same rule. Its facade has only
three collaborators; plan validation and allocation form a dedicated boundary,
freeze and upsert own their separate commands, and snapshot projection is
read-only. Routing and warehouse preflight calls run only after the short local
transaction has rolled back and released its locks.

Completed-inventory publication uses the same remote-outside-lock pattern. Its
pre-start replacement saga owns compensation and successor state, while source,
target, estimate, repair and plan components remain one-way dependencies of the
apply owner. Preflight is an independent read projection and cannot initiate a
publication effect.

Property disposition has five one-way application branches. Creation,
furniture materialization and review/recovery use the same narrow command,
decision-persistence, actor and repair-chain leaves; reads remain projection
only, and processing callbacks own mixed decision/repair stream ordering. No
branch calls back into the facade or performs remote admission while holding
the final local decision lock.

The private HTTP gateway follows a separate transport-only DAG. Five owner
clients keep their endpoint and wire-validation vocabulary, while one shared
transport owns only exact-scope client credentials, HTTP exchange and the
established error policy. Owner clients do not call peers or application use
cases, and none refers back to the gateway facade.

## Persistence, events and recovery

Flyway migrations under `src/main/resources/db/migration/` are the only schema authority. JPA uses
`ddl-auto=validate`; do not enable Hibernate schema mutation or add cross-service foreign keys.

A committed aggregate fact is written to the local event stream, snapshot/checkpoint and
transactional outbox in the same PostgreSQL transaction. Kafka delivery is at-least-once: the relay
revalidates an envelope and marks an outbox row only after broker acknowledgement; consumers
deduplicate and retain version-gap/recovery state; sanitized terminal failures use the service-owned
DLT path rather than copying raw message bodies into logs.

`MAINTENANCE_KAFKA_ENABLED` controls Kafka-specific relay and consumer beans. It may be `false`
only in an explicit `dev` or `test` profile; every other profile fails startup if delivery is
disabled. The canonical ordered owner outputs are catalog-version, estimate, repair,
property-disposition and sanitized DLT. They match the channel addresses in the
[`maintenance-events` contract](../../contracts/events/maintenance-events.yaml) and are shared by
startup validation and output binding initialization.

## Runtime configuration

The default HTTP port is `8087`. Configure the maintenance database, `AUTH_ISSUER`, CORS origin,
and, for real dependencies, the token URI, client ID/secret and private base URLs listed in
`src/main/resources/application.yaml`. Never commit live credentials.

The no-op dependency boundary is limited to disabled `dev`/`test` configurations. A production
runtime must enable real dependencies; the production validator checks dependency configuration and
rejects dev auth bypass. The service does not put business aggregation in the API gateway.

## Observability and operations

Actuator exposes `health`, `info` and `prometheus`. Console logs use ECS format and tracing
sampling is configured by `MAINTENANCE_TRACING_SAMPLING_PROBABILITY`. Investigate delayed work via
local outbox/inbox/recovery records and the correlation ID before changing domain data or replaying
an effect.

Prometheus exposes the low-cardinality `rwms.maintenance.outbox.backlog`,
`rwms.maintenance.outbox.oldest.age.seconds` and `rwms.maintenance.outbox.terminal` gauges. They
read pending/in-flight age and DLT/quarantine work without tags derived from events and without
draining or rewriting rows. The terminal count can scan retained terminal rows because the current
schema has no terminal-status partial index; an index remains a separately reviewed Flyway
follow-up.

## Local development

From the repository root:

```bash
bash ./gradlew :services:maintenance-service:bootRun --args='--spring.profiles.active=dev'
```

Use an isolated dependency configuration only for development or tests. Service integrations use
private URLs and client credentials, never the public gateway or a browser bearer token.

## Production Kafka safety

The base profile has no Kafka-enable fallback. Outside explicit `dev`/`test`, startup requires the
exact five-topic order, explicit non-loopback `host:port` brokers, disabled topic auto-creation,
synchronous `acks=all` idempotent publishing, positive bounded timeouts whose maximum publish wait
is shorter than the outbox lease, and the outbox relay, sanitized-DLT relay and output-binding
beans. A production profile takes precedence when combined with a local profile. The validator
does not drain a backlog; recovery remains an explicit reviewed operation.

## Safe change rules

- Change the OpenAPI or AsyncAPI contract and all affected producers/consumers together.
- Keep aggregate transitions, expected-version fencing, idempotency and compensation in this service.
- Add immutable, service-local Flyway migrations and validate affected JPA mappings.
- Preserve outbox/inbox deduplication, aggregate ordering and operator-reviewed recovery.
- Test the focused public/private contract plus persistence and failure/retry path.

## Primary implementation references

- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceCatalogUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceEstimateUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceRepairUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceReconciliationUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryMaintenanceService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryPublicationReconciliationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionApplicationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/MaintenanceEventStore.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceKafkaOutboxRelay.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceTransportTopics.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceEventingMetrics.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/config/MaintenanceProductionSafetyValidator.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/integration/MaintenanceDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/integration/HttpMaintenanceDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/security/MaintenanceAuthorizer.java`
