# RWMS Inventory Service

[Русская версия](README.ru.md)

## Purpose and ownership

`inventory-service` owns inventory sessions, expected membership, findings and inspection state,
completion/statistics, final planning, and publication intent, attempts and recovery state. It does
not own cabins, warehouse identity, repairs, logistics tasks or media objects; effects on those
owners use explicit private integrations and durable local state.

The authoritative HTTP and event contracts are
[`contracts/openapi/inventory-service.yaml`](../../contracts/openapi/inventory-service.yaml) and
[`contracts/events/inventory-events.yaml`](../../contracts/events/inventory-events.yaml). Start
with [`docs/project-knowledge/domain-logic.md`](../../docs/project-knowledge/domain-logic.md) as
an index, then verify any rule against current contracts and service code.

## Public and private HTTP boundary

Authenticated user operations are versioned below `/api/inventory/v1/**`. They create and manage
sessions, record findings and inspection, prepare/review final plans, complete or cancel a session,
and observe or recover publication work. Mutable commands use the contract-defined expected-version
and idempotency fields; callers must handle a canonical `409` conflict rather than send a changed
retry.

Interactive panel and Android clients reach this namespace only through the public
`api-gateway-service` `/api/inventory/**` route. They must not call this module host or any private
dependency route directly.

Inventory has no public client route for asset, warehouse, maintenance or media mutations. It calls
their narrow private boundaries with service credentials after its own local workflow records have
been made durable.

## Security, warehouse isolation and fencing

The HTTP layer is a stateless OAuth2/JWT resource server. `InventoryAuthorizer` requires a USER
principal, read/write scope and the requested warehouse access level; global administrators receive
only the explicitly implemented unrestricted warehouse scope. Dev auth bypass is active only in the
`dev` profile and never when a production profile is active.

Inventory owns a separate PostgreSQL database. It stores opaque references and contract-defined
snapshots, never a shared JPA model, cross-service foreign key or cross-database join. A session's
warehouse scope is checked at the owning boundary rather than delegated to the API gateway.

Stable idempotency keys, request fingerprints, expected versions and durable capture/publication
attempts fence retries. Do not infer a completed remote effect from a timeout; recovery consumes the
same stable operation identity and owner proof.

## Internal application structure

`InventoryApplicationService` is a stable seven-collaborator compatibility facade. It retains the
controller, inbox and scheduler call surface but delegates every decision to one cohesive use-case
service:

| Collaborator | Owned responsibility |
| --- | --- |
| `InventorySessionService` | Session start and durable capture-release recovery |
| `InventoryReadService` | Session, finding and statistics reads |
| `InventoryFindingService` | Membership reconciliation, number resolution, source creation and inspection mutation |
| `InventoryFindingValidationService` | Fresh finding, media and plan validation shared by exact owners |
| `InventoryReviewService` | Registry and furniture review, including their durable recovery |
| `InventoryPlanningService` | Warehouse planning settings and immutable versioned final plans |
| `InventoryCompletionService` | Preview, terminal completion/cancellation and post-commit intents |
| `InventoryStatisticsService` | Frozen and aggregate statistics calculations |
| `InventoryPublicationService` | Publication, retry, closure and recovery |
| `InventoryProjectionService` | API projections over owner-local state |

`InventoryFindingPersistenceService` encapsulates only source attachments, media references/facts
and frozen-plan persistence. Narrow abstract workflow supports expose only the repositories and
ports required by their one concrete use case; they are not Spring beans. The shared
`InventoryTechnicalRuntimeSupport` contains JSON/canonical-hash, actor/authorization, correlation
and transaction primitives only—no repository, remote owner, lifecycle or workflow decision. The
dependency graph is acyclic, and no extracted collaborator owns another domain's state.

`InventoryAssetInboxStore` is the sole technical owner of the asset consumer's
`inbox_message` rows. It contains only row-level deduplication, retry locks/backoff and state
transitions; event shape validation and inventory-membership decisions remain in the processors.
`InventoryPostgresJsonbCanonicalizer` is the sole PostgreSQL `jsonb::text` adapter and never
queries inventory business tables, so asset payload storage and frozen-plan fingerprints use the
V12 canonical UTF-8 representation.

## Persistence, events and recovery

Flyway migrations under `src/main/resources/db/migration/` are the only schema authority. JPA uses
`ddl-auto=validate`; Hibernate schema mutation and cross-service foreign keys are prohibited.

The service persists inventory facts and a transactional outbox with its local state. Kafka is
at-least-once transport: the relay leases an ordered aggregate head, validates the stored envelope
and marks it published only after acknowledgement. Asset and media inbox processors deduplicate
event IDs, validate contract shape and retain retry/quarantine/DLT state locally. Inventory start,
capture-release and publication flows retain durable attempts so retries do not create a second
session or guess an uncertain dependency result.

`INVENTORY_KAFKA_ENABLED` controls Kafka relay and consumer beans. `INVENTORY_DEPENDENCIES_ENABLED`
controls the private client-credential dependency gateway for warehouse, asset and maintenance.

## Runtime configuration

The default HTTP port is `8089`. Configure the inventory database, `AUTH_ISSUER`, CORS origin and,
for live dependencies, token URI, client ID/secret and private base URLs in
`src/main/resources/application.yaml`. Never commit credentials or route service calls through the
public gateway.

In a `prod` or `production` profile, `InventoryProductionSafetyValidator` fails startup unless
dependencies and Kafka are enabled, dev auth bypass is off, and the private dependency boundary is
production-ready. Disabled boundaries are for isolated local development or tests.

## Observability and operations

Actuator exposes `health`, `info` and Prometheus metrics. Logs use ECS format and tracing sampling
is configured by `INVENTORY_TRACING_SAMPLING_PROBABILITY`. Diagnose delayed publication or inbox
work through its local attempt, retry, outbox and correlation records before retrying an effect.

## Local development

From the repository root:

~~~bash
./gradlew :services:inventory-service:bootRun
~~~

Use disabled dependencies and Kafka only for isolated development or tests. Live integrations use
private URLs and service credentials; interactive clients use the gateway.

## Known audit limitations

The production fail-fast checks apply only when the active Spring profile is `prod` or `production`.
Deployment configuration must select one of those profiles; this documentation-only update does not
turn a non-production profile into a safe live runtime.

## Executable route and security parity

[`InventoryRouteSecurityParityTest`](src/test/java/dev/buhanzaz/rwms/inventory/config/InventoryRouteSecurityParityTest.java)
parses the canonical OpenAPI operation inventory, discovers every active `@RestController` mapping
through Spring's merged annotations, and requires exact method/path set equality without duplicates.
Only placeholder names and an optional trailing slash are normalized. The same test executes the
real owner security filter chain with dev auth bypass disabled; every canonical Bearer operation must
reject an unauthenticated request before controller dispatch.

Run the focused gate from the repository root:

~~~bash
bash ./gradlew :services:inventory-service:test --tests 'dev.buhanzaz.rwms.inventory.config.InventoryRouteSecurityParityTest'
~~~

## Safe change rules

- Change OpenAPI/AsyncAPI contracts and every affected producer/consumer together.
- Keep session, finding, completion and publication orchestration in this service rather than a UI
  or gateway saga.
- Preserve expected-version fencing, stable idempotency keys, inbox/outbox deduplication and
  durable retry/recovery state.
- Add immutable service-local Flyway migrations and validate affected JPA mappings.
- Test focused authorization, conflict, dependency timeout/retry, Kafka replay and recovery paths.

## Primary implementation references

- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryApplicationService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventorySessionService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryFindingService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryPlanningService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryCompletionService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryPublicationService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryIdempotencyService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/eventing/InventoryEventStore.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/eventing/InventoryOutboxStore.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/eventing/InventoryAssetInboxStore.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/persistence/InventoryPostgresJsonbCanonicalizer.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/security/InventoryAuthorizer.java`
