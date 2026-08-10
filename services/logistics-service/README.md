# RWMS Logistics Service

[Русская версия](README.ru.md)

## Purpose and ownership

`logistics-service` owns rental inquiries, client presentations, rental orders, returns, shipments,
transfers, driver work and logistics orchestration. It owns document/workflow state, not the cabin,
equipment, repair or warehouse aggregates. Effects on those owners use narrow private APIs and
durable logistics recovery work.

The authoritative HTTP and event contracts are
[`contracts/openapi/logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml) and
[`contracts/events/logistics-events.yaml`](../../contracts/events/logistics-events.yaml). Start
with [`docs/project-knowledge/domain-logic.md`](../../docs/project-knowledge/domain-logic.md),
then verify ownership and invariants against current code and contracts.

## Public and private HTTP boundary

Authenticated operations are versioned below `/api/logistics/v1/**`. They expose returns,
shipments, transfers, equipment movements, driver board/tasks, orders and rental inquiries.
Commands use the contract-defined `Idempotency-Key` and expected-version field or parameter; callers
must handle a canonical conflict rather than send a changed retry.

Shipment and return commands carry an optional opaque task-board `driverWorkerId` together with
the historical display snapshot; logistics never derives identity from the name. Transfer commands
carry no driver identity. Once scheduled, each newly created shipment stores one durable
document-owned driver task with an immutable client snapshot and ordered cabin members. Its neutral
technical priority remains task-board implementation detail; the title, count summary and task text
carry the shipment intent, client and full cabin-number list. Completion evidence applies its cover
checkpoint idempotently to every member and the group completes only after all covers succeed.
Historical document-line shipment tasks are not regrouped. Returns retain one task per line and are
assigned when an ID is present and otherwise stay unassigned; transfers and other movement work use
identity-free `WAREHOUSE_DRIVERS`. The public board exposes the audience used for date/driver
grouping. Public moves can only reorder shipment and return work inside its current driver, date and
lane. Movement work retains its existing date/lane planning policy, while no public move can change
audience.

`GET` and `PUT /api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings` own the
warehouse-scoped maximum cabin count for one newly created shipment task. The lazily materialized
default is one cabin; GET requires read/VIEW scope and PUT requires write/MANAGE scope with an
`expectedVersion`. Both generic and saved-order shipment commands reject a unique selected set above
the current cap before creating a document or driver task; logistics never auto-splits that request.

`POST /api/logistics/v1/rental-inquiries/{inquiryId}/cabin-searches` is a write-authorized command
and requires `Idempotency-Key`. A short PREPARE transaction locks the inquiry, rechecks current
ownership/state and warehouse-edit authority, and stores a subject/operation/key request digest,
domain-separated downstream UUID, immutable actor snapshot, hold expiry and the exact asset JSON
text plus its digest. Warehouse and asset HTTP calls then run without a local transaction. A short
COMPLETE transaction repeats the current authorization checks, selects the inquiry warehouse only
after asset success and freezes the response; an identical completed retry returns it with
`Idempotency-Replayed: true` and no remote call. A changed request or another live key is `409`.
Only inactive warehouse and sanitized asset `400`/`409` domain rejection codes become `REJECTED`;
OAuth/configuration/transport/timeout, asset `401`/`403`/`404` and `5xx`, and a lost response remain
`PREPARED` for an exact retry. `EXPIRED` releases the one-PREPARED-per-inquiry slot only after the
stored hold expiry, while that expired public key remains terminal.

Cabin search defaults an omitted `resultMode` to `REPLACE`: asset-service atomically releases the
inquiry's previous chat holds before installing the new result. `APPEND` is accepted only when the
caller explicitly requests it. Facets include the available characteristic values and exact
type-to-dimension relations. `GET .../cabin-catalog` is a bounded facts-only lookup and has no hold
side effect. `GET .../cabin-selection` reads the authoritative asset-owned hold set; idempotent
`PUT .../cabin-selection` replaces the exact full cabin-ID list using `chatSelectionHoldMinutes`,
and an empty list releases every inquiry hold immediately. A short PREPARE transaction stores the
owner/key request digest, exact replace-or-release JSON, non-null command deadline and nullable
public hold expiry before the asset call; for replace the deadlines are equal, while release keeps
the public expiry null. A short COMPLETE transaction rechecks current inquiry ownership/state and
warehouse authority before freezing the validated response. An identical key retry reuses the exact
bytes and deadlines, while changed reuse or another live key is `409`; a transport failure or lost
response remains `PREPARED` for that exact retry only until its persisted command deadline, after
which the slot becomes recoverable. The remote calls run outside local transactions, and the receipt
is effect/replay evidence rather than a duplicate of the authoritative asset-owned selection.

`POST /api/logistics/v1/clients` creates a logistics rental client of type `INDIVIDUAL` or
`LEGAL_ENTITY`. Name/FIO and main phone are required; legal entities also require a contact person.
Email, comment and source are optional, while the
responsible-manager identity and display-name snapshot come only from the authenticated write
actor. Historical clients keep the truthful creator-subject UUID as manager identity and may have
no display-name snapshot. Client search/detail and the paged `/clients/{clientId}/orders` route use
the ordinary visible-order rules and do not disclose inaccessible identities.

Rental-order drafts may be created for a preselected client and edited with idempotency and an
expected version. Delivery address, coordinate pair, contact phone, optional comment and up to 31
unique concrete acceptable dates are stored on the order. Address, coordinates, phone and a
non-empty date list are required before save; a new shipment date must belong to that list when it
is configured. A request may use `+` international or Russian `8` trunk human phone formatting;
the domain validates its digits and persists/exposes only canonical E.164. Order detail includes
logistics-owned shipment/return timeline facts and cabin
lines. Maintenance estimates and repair history remain dossier/maintenance reads and are neither
copied nor persisted here.

Interactive panel and Android clients reach this namespace only through the public
`api-gateway-service` `/api/logistics/**` route. They must not call this module host or an
`/api/internal/**` route directly.

`/api/internal/logistics/v1/maintenance/**` is the narrow private boundary for maintenance-owned
repair work requiring logistics driver/equipment orchestration. It uses service credentials and is
not a client route.

`/api/logistics/public/v1/client-presentations/**` is intentionally anonymous, but a signed
presentation token, its revision and current viewability constrain access. Media access also verifies
the requested item/generation/variant belongs to that presentation; this is not a general media proxy.

## Internal application structure

`HttpLogisticsDependencyGateway` is the stable implementation of the private
dependency port. Its unchanged constructor composes six owner clients and the
facade delegates every interface operation:

| Owner client | Private boundary |
| --- | --- |
| `LogisticsWarehouseDependencyClient` | Warehouse identity, admission, timezone and lifecycle |
| `LogisticsAssetOperationsDependencyClient` | Rental snapshots, leases, fenced effects, equipment holds and movements |
| `LogisticsAssetOrderPresentationDependencyClient` | Order units, reservations, cabin availability/search and presentation holds |
| `LogisticsMaintenanceDependencyClient` | Transfer repair, estimate source, capital repair and repair-place calls |
| `LogisticsMediaDependencyClient` | Media validation, owner proof, evidence, snapshots and binary presentation media |
| `LogisticsTaskBoardDependencyClient` | Movement tasks, driver queue/task, board and completion calls |
| `LogisticsOAuthHttpTransport` | Exact-scope client credentials, HTTP exchange and established dependency error mapping only |
| `RentalInquiryCabinSearchService` | Non-transactional warehouse/asset call sequence over one frozen downstream command |
| `RentalInquiryCabinSearchStore` | Locked PREPARE/COMPLETE/REJECTED/EXPIRED receipt transactions and frozen-response replay |
| `RentalInquiryCabinSelectionService` | Owner-scoped authoritative hold reads and exact full-selection replace/release calls outside local transactions |
| `RentalInquiryCabinSelectionStore` | Locked PREPARE/COMPLETE/REJECTED/EXPIRED selection receipt transactions, exact-byte retry and frozen-response replay |
| `RentalInquiryCabinCatalogService` | Bounded facts-only cabin lookup with inquiry, warehouse and owner authorization |
| `LogisticsDocumentService` | Stable return/shipment/transfer and rental-order hook facade over seven exact owners |
| Return, shipment and transfer document coordinators | Independent document state machines with their existing transaction and recovery order |
| `DocumentDriverTaskPlanner` | One idempotent document-owned task with ordered cabin members for each new scheduled shipment; legacy line tasks plus return/transfer tasks retain per-line planning and pre-start replan/cancel guards |
| `ShipmentTaskSettingsService` | Warehouse-scoped, version-fenced cap for cabins in a new shipment task; materializes default one atomically and rejects over-limit creates before document persistence |
| Rental-order shipment/completion and reconciliation coordinators | Document hooks for rental shipment, terminal return and reconciliation request commands |
| Document admission, idempotency, attempts, reads and binding policies | Narrow warehouse, replay, external-attempt, projection and active-order leaves |
| `RentalOrderService` | Stable order facade over reads, creation, lifecycle, reservations, terms and shipment hand-off |
| Rental-order command store, editability and problem/outcome leaves | Row/receipt replay, saved-draft synchronization and canonical local problem mapping; `LogisticsTransactionLock` owns the narrow transaction advisory-lock access |

Owner clients depend only on the shared transport and their configured private
base URL; they do not depend on peers or refer back to the facade. Domain and
saga decisions remain in logistics application services and durable stores,
not in the transport layer.

The document facade retains every controller/order-facing method and outer
transaction annotation. Return, shipment and transfer coordinators do not call
one another; rental-order shipment/completion and reconciliation are separate
owners. Shared leaves contain only warehouse admission, idempotency records,
external-attempt writes, read projection or active-order binding, and none
refers back to the facade.

The rental-order facade retains its complete controller/presentation API and
outer transaction annotations. Read projection, creation, client/warehouse
lifecycle, reservations/equipment, rental terms and shipment hand-off have
separate owners. The command store is the sole order-row/receipt/advisory-lock
owner, while editability alone coordinates the saved document-draft lock; no
owner calls back into the facade.

## Warehouse isolation, fencing and orchestration

A user action requires the appropriate warehouse grant. A new physical logistics operation first
obtains warehouse admission and local date information through the private warehouse boundary, then
commits only local logistics state. Asset, equipment-hold and task-board effects use stable operation
IDs, expected versions and owner-side fencing, so an uncertain remote result is replayed instead of
guessed.

Warehouse admission is fail closed. Disabled or unavailable dependencies return the existing
`503 LOGISTICS_DEPENDENCY_UNAVAILABLE` response before a document, domain event, outbox event or
operation mark is written. Warehouse-service rejects an incoming operation for `DRAINING` or
`INACTIVE`, and logistics leaves no reserved intent or domain write after that rejection.

Every fresh remote-admission ticket carries the exact direction and non-negative warehouse lifecycle
version returned for each sorted requirement. The owning document, equipment or driver transaction
stores that vector on its permanent warehouse operation marks together with the new domain work; a
rollback leaves neither work nor admission evidence. If a timezone response is lost after admission,
logistics re-reads the same admitted-intent versions instead of asking warehouse-service to admit
another version.

Before any remote dependency call, a public create retry may receive an
`EVIDENCED_REPLAY_CANDIDATE` ticket only when local SQL finds a live durable domain identity and a
complete, exact set of evidenced operation marks. A document candidate requires a live, unexpired
subject/operation/key receipt that still references its document; equipment and driver candidates
require the matching actor/key domain row. SQL reconstructs the stored warehouse/direction vector
from that domain row, then requires permanent marks with the same warehouses and directions,
non-negative versions, and no missing or additional warehouses. This candidate does not prove that
the incoming payload is identical. The owning create path remains the checksum authority: changed
warehouse/direction or any other payload change returns the existing `409` without a dependency call
or mutation, while an exact match returns the stored operation before ticket consumption. A replay
candidate is rejected if it reaches any new-create consumption path. Driver replay checksums use the
persisted scheduled date before any current warehouse-local date gate; only a fresh driver task
derives an `AUTO` date from its remote ticket.

Historical marks remain deliberately unproven because migration `V39` does not backfill evidence.
Legacy null evidence, an expired document receipt, a missing domain row or mark, a subset, a
superset, and a direction/version mismatch never authorize dependency-free replay. Test-only and
parent-owned continuation marks also store null evidence. When candidate evidence is absent, a retry
follows the fresh remote-admission path and returns `503 LOGISTICS_DEPENDENCY_UNAVAILABLE` if that
dependency is not ready; an exact owner-verified candidate replay makes no warehouse
admission/timezone call and creates no short admission intent.

No user JWT crosses `LogisticsDependencyGateway`. The gateway obtains client-credential tokens for
asset, warehouse, task-board, maintenance and media. Remote effects become durable attempts and are
relayed after local commit; do not make cross-service calls inside an owning database transaction.

## Persistence, events and recovery

Flyway migrations under `src/main/resources/db/migration/` own the logistics schema. JPA uses
`ddl-auto=validate`; service databases remain isolated and cross-service foreign keys/JPA entities
are forbidden.

Migration
[`V42__clients_order_delivery_and_acceptable_dates.sql`](src/main/resources/db/migration/V42__clients_order_delivery_and_acceptable_dates.sql)
adds contact/manager/comment/source client fields, order delivery facts, the ordered unique
acceptable-date collection and durable exact-command receipts for inquiry cabin selection. It
backfills only the truthful responsible manager UUID from
`created_by_subject_id`; it does not invent a historical display name. Legacy phone/contact rows
remain readable while new writes are constrained, and V39-V41 remain immutable.

Migration
[`V43__remove_sole_proprietor_client_type.sql`](src/main/resources/db/migration/V43__remove_sole_proprietor_client_type.sql)
reclassifies historical `SOLE_PROPRIETOR` rows to `LEGAL_ENTITY` before restricting client types to
`INDIVIDUAL` and `LEGAL_ENTITY`. It stops before the update when that reclassification would collide
with an existing legal entity by normalized phone, preserving every record for manual resolution.

Migration
[`V44__driver_task_audience_and_document_driver.sql`](src/main/resources/db/migration/V44__driver_task_audience_and_document_driver.sql)
adds the document's opaque driver ID, the three audience fields on durable
driver tasks, and the document-line source plus shipment/return/transfer kinds.
Existing driver tasks retain the former warehouse-shared behavior. The schema
uses IDs and snapshots only; there is no cross-service foreign key.

Migration
[`V45__correct_driver_task_audience.sql`](src/main/resources/db/migration/V45__correct_driver_task_audience.sql)
removes transfer driver snapshots/IDs and all shared-task responsibility hints. It maps shipment and
return work to `ASSIGNED_DRIVER` when an ID exists and `UNASSIGNED` otherwise, maps every movement
kind to identity-free `WAREHOUSE_DRIVERS`, and adds constraints that preserve those rules.

Migration
[`V46__shipment_task_grouping.sql`](src/main/resources/db/migration/V46__shipment_task_grouping.sql)
adds warehouse-local shipment-task settings, the `LOGISTICS_DOCUMENT` driver-task source and ordered
shipment-member cover checkpoints. It expands only: no historical
`LOGISTICS_DOCUMENT_LINE` task is regrouped or rewritten.

Logistics commits facts, projection checkpoints and a transactional outbox together. Kafka delivery
is at-least-once: aggregate IDs are record keys, event IDs are dedupe identities, and consumers retain
local replay/version-gap handling. Durable stores and relays recover external attempts, owner proofs,
warehouse operation marks, driver task work and sanitized failure paths.

The rental-inquiry booked producer stores the strict `DomainEventEnvelopeV2` in the booking
transaction: aggregate identity/version come from the post-flush inquiry, correlation is the
conversation with the booking as causation, actorRef is the manager USER reference, and payload is
exactly `conversationId` plus `orderId`. Kafka still uses the exact conversation UUID as record key;
the generated eventId and persisted JSON do not change across relay retries. Migration
[`V41__rental_inquiry_search_receipts_and_booked_envelope_v2.sql`](src/main/resources/db/migration/V41__rental_inquiry_search_receipts_and_booked_envelope_v2.sql)
canonicalizes both pending and already published legacy rows without changing event IDs, keys or
delivery statuses; published rows never become relayable again.

Provider-specific persistence is limited to the
[`RentalInquiryBookedOutboxStore`](src/main/java/dev/buhanzaz/rwms/logistics/inquiry/eventing/RentalInquiryBookedOutboxStore.java)
and the narrow warehouse
[`admission`](src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseAdmissionPersistence.java),
[`single-statement blocker`](src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseLifecycleBlockerReader.java)
and [`operation-mark`](src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseOperationMarkPersistence.java)
adapters. They preserve PostgreSQL transaction time, conflict-safe insert, the one-statement blocker
snapshot, `FOR UPDATE SKIP LOCKED`, and conditional fencing writes; lifecycle stores retain all
business decisions. [`LogisticsTransactionLock`](src/main/java/dev/buhanzaz/rwms/logistics/repository/LogisticsTransactionLock.java)
is the sole application caller of the approved transaction advisory-lock query.

[`LogisticsRecoveryObservationStore`](src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsRecoveryObservationStore.java)
is a separate read-only technical SQL adapter for recovery metrics. It reads only scalar counts and
oldest timestamps from logistics-owned outbox, DLT, warehouse-mark and inbound-gap tables; it never
claims, retries, publishes, resolves or returns an identifier, payload, topic or error text.

[`LogisticsExternalAttemptClaimService`](src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsExternalAttemptClaimService.java)
leases one due external attempt at a time through a stable, bounded pessimistic skip-locked page.
PostgreSQL transaction time defines due and expiry checks; the claim transaction commits before any
remote call. The immutable, payload-free claim carries the attempt/operation IDs, lease token and
fence, claimed row version and request digest. Each workflow store locks and verifies that exact
capability before recording completion or failure, so expired or duplicate workers cannot overwrite a
newer result. [`V40__bounded_logistics_external_attempt_claims.sql`](src/main/resources/db/migration/V40__bounded_logistics_external_attempt_claims.sql)
stores the fence, token and expiry and adds due and expired-lease indexes.

The five owner relays use one lightweight trigger scheduler and submit only currently permitted work
to a separate bounded remote-call executor. `LOGISTICS_EXTERNAL_ATTEMPT_LEASE_DURATION`,
`LOGISTICS_EXTERNAL_ATTEMPT_MAXIMUM_PAGE_SIZE`, worker pool/queue/shutdown variables and the five
`*_WORKER_BUDGET` variables in `application.yaml` bound recovery capacity; each owner budget must
remain lower than the worker pool to preserve another owner's remote-call slot. Fixed-name gauges
expose backlog/oldest/terminal state for the main outbox, sanitized DLT and warehouse marks;
backlog/oldest for the rental-inquiry outbox; open/oldest inbound gaps and blocked checkpoints; and
active/oldest/max-retry/reconciliation-required external attempts plus executor active/queue state.
Empty or future-age state is zero and a database access failure is `NaN`. Claim counters and latency
timers use only the closed `owner` and `state` labels; no metric contains attempt IDs, topics,
payloads or free-form exceptions. The rental-inquiry outbox currently has only `PENDING` and
`PUBLISHED`: it still lacks a terminal/reviewed recovery state, which remains follow-up work rather
than a fabricated terminal gauge.

The base configuration requires an explicit `LOGISTICS_KAFKA_ENABLED` value; only the `dev` profile
keeps the explicit optional `false` default. Canonical primary outputs are ordered exactly as return,
shipment, transfer and `rwms.logistics.rental-inquiry.events.v1`; sanitized DLT bindings remain a
separate exact set. Outside explicit `dev`/`test`, startup requires Kafka enabled, explicit
non-loopback brokers, topic auto-creation disabled, synchronous `acks=all`, producer idempotence,
positive request/delivery/max-block timeouts with delivery not shorter than request, combined
max-block plus delivery shorter than the outbox lease, the rental-inquiry outbox enabled, and all
main-outbox, sanitized-DLT, rental-inquiry and output-binding beans. A `prod` or `production` profile
wins over a simultaneous local profile and additionally requires valid private dependency URLs and
client credentials, a ready dependency gateway, and `LOGISTICS_DEV_AUTH_BYPASS=false`.
`LOGISTICS_DEPENDENCIES_ENABLED` disabled mode remains limited to isolated local/test work. Fresh and
unproven warehouse-bound creates then fail with `503`; only an exact replay backed by durable
evidence can succeed without the dependency. Direct fixtures under `test` may receive a test-only
ticket, but its null-evidence marks never authorize public replay.

## Runtime configuration

The default HTTP port is `8090`. Configure the logistics database, `AUTH_ISSUER`, CORS origin,
client-presentation token secret, and, for real integrations, token URI, client ID/secret and private
base URLs. See `src/main/resources/application.yaml` for exact variable names; never commit live
credentials or presentation secrets. Non-local Kafka settings and, in production,
`LOGISTICS_DEPENDENCIES_ENABLED` plus `LOGISTICS_DEV_AUTH_BYPASS` are validated together by the
startup guard without including configured secrets in failures.

The presentation-token secret must be at least 32 characters, and production rejects the known local
default. The general API is stateless OAuth2/JWT; dev auth bypass is limited to the `dev` profile.

## Observability and operations

Actuator exposes `health`, `info` and `prometheus`. Logs use ECS and tracing sampling is set by
`LOGISTICS_TRACING_SAMPLING_PROBABILITY`. The recovery gauges are registered without dynamic labels;
the existing common `application=logistics-service` tag is added by runtime configuration. For
delayed work, inspect the document, external-attempt/recovery record, outbox status and correlation
ID before manually retrying an effect. Do not repair another service's state directly from
logistics.

## Local development

From the repository root:

```bash
./gradlew :services:logistics-service:bootRun
```

Use disabled dependencies only for isolated development/test scenarios; fresh and unproven
warehouse-bound public creates deliberately remain unavailable in that mode. Live integrations use
private URLs and service credentials, never the public gateway or a browser token.

## Executable route and security parity

[`LogisticsRouteSecurityParityTest`](src/test/java/dev/buhanzaz/rwms/logistics/config/LogisticsRouteSecurityParityTest.java)
parses the canonical OpenAPI operation inventory, discovers every active `@RestController` mapping
through Spring's merged annotations, and requires exact method/path set equality without duplicates.
Only placeholder names and an optional trailing slash are normalized. The same test executes the
real owner security filter chain with dev auth bypass disabled: Bearer operations must reject an
unauthenticated request, while only the four contract operations below
`/api/logistics/public/v1/client-presentations/**` may pass anonymously.

Run the focused gate from the repository root:

```bash
bash ./gradlew :services:logistics-service:test --tests 'dev.buhanzaz.rwms.logistics.config.LogisticsRouteSecurityParityTest'
```

## Safe change rules

- Change the OpenAPI/AsyncAPI boundary and every affected producer/consumer together.
- Keep return, shipment, transfer, order and driver workflow state in logistics, not a UI or gateway saga.
- Preserve expected-version fencing, stable idempotency keys, outbox/inbox dedupe and durable recovery.
- Add immutable service-local Flyway migrations and verify affected JPA mappings.
- Test success, conflict, timeout/retry and replay paths for the changed owner boundary.

## Primary implementation references

- `src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsDocumentService.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderService.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsWarehouseLifecycle.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/repository/LogisticsTransactionLock.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseAdmissionPersistence.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseLifecycleBlockerReader.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseOperationMarkPersistence.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsEventStore.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/integration/HttpLogisticsDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/ClientPresentationService.java`
