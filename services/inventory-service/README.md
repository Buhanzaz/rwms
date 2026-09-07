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

`GET`/`PUT /api/inventory/v1/planning-settings/{warehouseId}` are holidays-only: the read carries
exactly `warehouseId`, `settingsRevision`, `updatedAt` and `holidays`, while PUT carries
`expectedSettingsRevision` and `holidays`. Ordinary calls use the existing USER `rwms.write` plus
warehouse-MANAGE policy; the isolated admin-web alternative is the exact `rwms-admin-web`
`admin.manage` token for a `SYSTEM_ADMIN` or `WMS_ADMIN`. Historical capacity/weekdays columns are
inert transitional storage and are neither public settings nor a planning input.

For every new final plan, inventory reads task-board's private effective object work calendar with
its sole `task-board.inventory-calendar.read` service credential. It merges task-board `daysOff`
with inventory holidays, schedules AUTO work on the earliest common date without a per-day cabin
limit, and validates MANUAL dates against the same calendar and ordering only. The consumed
schedule/timezone snapshot and fingerprint fence the plan; a changed calendar yields a stale-plan
conflict. V28 marks already active draft heads `STALE` safely and leaves completed historical plans
unchanged.

`POST /api/inventory/v1/sessions/{inventoryId}/refresh` is the MANAGE-scoped recovery command for
an active session whose live cabin membership or derived review became stale. It checks the supplied
session revision before a fresh read-only asset capture and again under the local apply lock. Remote
capture reads finish before the idempotent local transaction reconciles arrivals, departures and
current snapshots through the normal membership journal. Only uninspected automatic `EXPECTED`
population is deactivated by a later registry departure. An inspected finding or explicitly observed `ADDED_NEW`, `ADDED_USED` or
`UNEXPECTED_EXISTING` finding remains in the inventory table and final-plan population when a later
capture omits it; capture eligibility cannot erase the operator's physical warehouse observation.
Saved findings, inspection evidence, media references and movement history remain intact; only the
derived furniture review is restarted and an existing final plan is marked stale. Furniture-review
seeding reads the active client
`quantity` observation and retains `observedQuantity` as compatibility for previously stored review
facts. The public refresh contract adds no schema field.

An inspected departure retains its original passport, photos and frozen work as historical evidence.
Ordered asset-event warehouse/status facts drive the movement journal independently of a newer HTTP
snapshot, so a departure followed by a return is not collapsed into one current-state read. The
`asset.rental-item.inventory-visibility-changed.v1` marker is durably acknowledged by the
rental-item inbox but never creates a membership or warehouse-movement mutation: temporary
inventory isolation is not a physical departure. Its `inventoryId` remains source-scoped evidence.
`PRESERVE` final-plan disposition publishes only inventory passport/photo observations and never
changes live status, warehouse, contents, leases, logistics or maintenance work. The finding exposes
`preserveOperationalState=true`; its clients display the current owner status, not its obsolete work.

Normal rental returns are checked automatically only after logistics acceptance without an estimate,
or after the linked maintenance estimate is actually `COMPLETED`. Creating its draft is insufficient.
Inventory consumes the terminal event and verifies private owner proof before importing into the
warehouse session active at completion. An immutable return-line receipt deduplicates the import.
`inspectionSource=LOGISTICS_RETURN` carries external proof, not a fabricated inventory passport
baseline; it is always `PRESERVE`. Older inventory evidence remains historical but is not copied into
the imported revision or republished over return photos. No repair command is issued by this import.
The warehouse-scoped `GET /api/inventory/v1/return-estimates/{estimateId}/inspection` reports
`CONFIRMED` only from that receipt, including after the session closes; otherwise it reports pending
applicability or `NOT_REQUIRED`. Clients use this read to confirm addition after completing an estimate.

Finding media is immutable revision evidence. A workflow that advances a finding without editing
its photos—live membership/snapshot reconciliation, source-asset attachment, conflict resolution,
furniture confirmation or owner-proof closure—carries the exact immediately preceding media set
into the new revision in the same local transaction. An inspection save remains different: it
records exactly the submitted set, so an intentional empty set stays empty. Migration
[`V18__carry_forward_inventory_finding_media.sql`](src/main/resources/db/migration/V18__carry_forward_inventory_finding_media.sql)
repairs earlier revision drift only when a non-null cover photo proves that the current finding must
have media, the current revision has none, and an earlier exact set contains that cover. It copies
the newest such whole set without a union, deletion, media-object rewrite or finding-version change.

Interactive panel and Android clients reach this namespace only through the public
`api-gateway-service` `/api/inventory/**` route. They must not call this module host or any private
dependency route directly.

Inventory has no public client route for asset, warehouse, maintenance or media mutations. It calls
their narrow private boundaries with service credentials after its own local workflow records have
been made durable.

Final-plan preparation uses maintenance's read-only publication preflight. Inventory repeats that
request at most once, with the same body, idempotency key and service token, only after a transport
failure or HTTP `502`, `503` or `504`. Validation, conflict, authentication, other server and
malformed-response failures remain fail-closed and are not retried.

## Authoritative completed outcome and history recovery

New inventory cabins are isolated proposals until completion. The permanent
`inventoryId:findingId` source registration reserves a stable asset UUID but creates no warehouse
cabin, equipment balance or rental availability. Inspection status belongs to the session. Before
completion review, every `ADDED_NEW`/`ADDED_USED` proposal must be inspected; it cannot be inferred
missing and automatically written off. Completion freezes each local added finding's exact asset
outcome into the durable furniture reconciliation request, including when there is no furniture.
Asset-service materializes the final status, passport and reviewed contents in one transaction;
ordinary per-finding publication then delivers the remaining outcomes. Cancellation never
materializes the proposal. Existing cabins created by earlier versions are not deleted or hidden.

Every non-write-off completed final-plan finding has one durable publication intent, including findings without
maintenance work. After furniture reconciliation succeeds, the recovery scheduler first applies
the exact completed local finding through asset-service: no work means `FREE`, ordinary work means
`REPAIR`, and an explicit capital choice means `CAPITAL_REPAIR`. Asset-service owns the atomic
release or supersession of active order-unit reservations, operation leases, presentation holds and
transfer state. It preserves their history and rejects terminal `LOST` or `WRITTEN_OFF` cabins.
`PRESERVE` instead sends `preserveOperationalState=true` with a null desired status, skips logistics
and maintenance, and may update only the recorded passport/photos. New status-applying intents freeze
the observed asset version; a later rental/transfer cannot be overwritten by delayed publication.
Fresh departure truth also rejects a previously prepared non-preserving plan before completion.
Each final-plan publication intent also freezes the exact finding passport observation at
completion. The source finding revision and asset must match the immutable final-plan entry.
`ABSENT` is sent as an explicit preserve-current instruction; `PRESENT` is normalized into the six
asset-owned facets: rental type, dimensions, finishing, category, comma-split distinct
characteristics and nullable linoleum. A missing characteristics field becomes the exact empty
set, and missing or non-boolean linoleum becomes `null`. The normalized observation and its
canonical SHA-256 are part of the asset outcome and therefore of the durable publication request
fingerprint. See
[`InventoryPublicationIntent`](src/main/java/dev/buhanzaz/rwms/inventory/domain/InventoryPublicationIntent.java)
and
[`InventoryPublicationService`](src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryPublicationService.java).
Only after that result is stored does inventory send the exact `IMAGE` references from the
final-plan finding revision to media-service with the same publication-attempt idempotency key and
the narrow `media.inventory` token. Media-service makes that completed-inventory folder the cabin's
current photo set; earlier acceptance or inventory folders remain separate historical evidence
instead of being mixed into the current set. A finding without images skips this effect. Inventory
then persists one generation-scoped
[`InventoryPlanLogisticsEffect`](src/main/java/dev/buhanzaz/rwms/inventory/domain/InventoryPlanLogisticsEffect.java)
for the whole immutable final plan. Its request bytes, SHA-256 and idempotency key are frozen before
remote I/O. A short independently committed claim sends that request to logistics-service exactly
once per successful plan generation with the `logistics.inventory` token; finding retries verify the
shared receipt instead of rebuilding and resending the complete plan. Logistics supersedes active
rental, shipment, transfer and driver-task state for listed cabins while retaining its audit rows.
Finally, a work finding goes to
maintenance with the effective asset version and completion timestamp; maintenance authoritatively
supersedes older active repair work before creating the required repair or capital repair. A
no-work finding goes to the maintenance no-work boundary, which supersedes active estimates,
repairs, leases and tasks instead of merely changing the cabin status. The no-work payload is
projected explicitly from the asset result and contains only the fields in the maintenance
contract; asset-only passport observation and hash fields never cross that boundary. A rejected
required effect prevents publication success, so recovery reasserts asset, media, the plan-wide
logistics effect and maintenance in that order. A bounded RWMS Problem Details code from a semantic
downstream `400`, `404`, `409` or `422` is retained on the blocked publication. `408`, `425` and
`429`, transport failures and malformed responses remain retryable dependency failures. Automatic
publication recovery selects one due batch of at most 20 rows, persists exponential backoff and
stops after eight delivery attempts in one outcome-reapplication generation. Photos, finding
evidence, domain history and
media-service/MinIO objects are never inputs to deletion in this flow.

`POST /api/inventory/v1/sessions/{inventoryId}/outcome/recalculate` is the MANAGE-scoped recovery
command for a completed history row. It fences the exact session revision, final-plan version and
SHA-256 and returns `202` after rebuilding durable work. When an obsolete automatic-membership
projection omitted an inspected explicit observation, the command restores that finding from its
human-confirmed inspection baseline, appends it to a strictly newer completed plan version and
replaces the frozen statistics. Existing plan entries, manager choices, dates and order remain
unchanged; only restored work is capacity-scheduled after them. Completed inventory is
authoritative, so this correction needs no maintenance preflight and performs no remote I/O or
downstream mutation. Completed-session media
owner authorization remains closed while the finding-owned restoration fact advances consumer
checkpoints. The command then creates missing no-work/status intents and requeues every publication
in the corrected plan, including rows previously marked
successful, because older runtime versions cannot prove that newer logistics, media and no-work
effects ran. The same command also makes an unresolved furniture reconciliation immediately
eligible. Attempts and prior maintenance results remain append-only audit evidence; schedulers
apply the queued effects with stable idempotency. Publication intents are written as one local batch
and the response contains only the session/plan fences, furniture state and created/requeued counts.
The panel invalidates and rereads the authoritative publication projection instead of receiving and
caching every intent in the `202` response.
Migration
[`V19__authoritative_inventory_outcome_recovery.sql`](src/main/resources/db/migration/V19__authoritative_inventory_outcome_recovery.sql)
adds the desired status and stored asset result without deleting existing publication history.
Migration
[`V23__freeze_inventory_outcome_passport_observation.sql`](src/main/resources/db/migration/V23__freeze_inventory_outcome_passport_observation.sql)
backfills the frozen observation only when the publication source revision, final-plan entry and
asset identity still match; unmatched legacy rows receive explicit `ABSENT`. History recovery
preserves this frozen column instead of rereading the finding, increments the durable reapplication
generation, and therefore derives a new owner-effect idempotency key. Old successful receipts can
still replay their unchanged response, while the new generation sends the passport-bearing request.
Migration
[`V24__restore_explicit_inventory_observations.sql`](src/main/resources/db/migration/V24__restore_explicit_inventory_observations.sql)
admits the restoration audit event without rewriting any existing finding, plan or publication row.
Migration
[`V25__plan_wide_logistics_effect.sql`](src/main/resources/db/migration/V25__plan_wide_logistics_effect.sql)
adds the durable plan-generation logistics request and its lease/retry/result state. Existing
completed plans are scheduled lazily on their first retry, so no remote call is performed by the
migration.
Migration
[`V27__bound_inventory_publication_recovery.sql`](src/main/resources/db/migration/V27__bound_inventory_publication_recovery.sql)
adds the generation-local attempt budget and due time. Explicit history recalculation resets only
that generation budget; the lifetime attempt counter and append-only attempt/result rows remain
unchanged.

Before furniture review, inventory now owns a durable cabin-disposition review with ordered
`RETURNS`, `SHIPMENTS` and `COMPLETED` phases. Every found cabin whose inspection snapshot was
`RENTED` requires an exact historical return. Among missing cabins, the request lists only actual
shipments; every omitted candidate becomes `WRITE_OFF` automatically. Shipment furniture records
catalog identity/version and any positive quantity without checking warehouse stock. These choices
are copied into every immutable final-plan generation. `LOCAL` follows the normal free/repair
publication path, `SHIPMENT` publishes `RENTED` with frozen contents, and `WRITE_OFF` never mutates
asset state or enters furniture, photos or repair publication.

Migration
[`V26__inventory_cabin_dispositions.sql`](src/main/resources/db/migration/V26__inventory_cabin_dispositions.sql)
adds the phase aggregate, exact candidate rows, final-plan disposition evidence and durable cabin
write-off intent. Completion persists the plan-wide logistics effect before the write-off intent in
the same transaction. Write-off recovery selects only the exact logistics generation in
`SUCCEEDED` or `BLOCKED`: success permits the stable maintenance command, while an upstream block
terminates the dependent intent explicitly. Logistics and write-off automatic delivery are both
bounded to eight attempts; the MANAGE recovery command can create a new immutable plan generation
and requeue unresolved work.

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

`InventoryApplicationService` is a stable use-case compatibility facade. It retains the
controller, inbox and scheduler call surface but delegates every decision to one cohesive use-case
service:

| Collaborator | Owned responsibility |
| --- | --- |
| `InventorySessionService` | Session start, manual membership refresh and durable capture-release recovery |
| `InventoryReadService` | Session, finding and statistics reads |
| `InventoryFindingService` | Membership reconciliation, number resolution, source creation and inspection mutation |
| `InventoryFindingValidationService` | Fresh finding, media and plan validation shared by exact owners |
| `InventoryReviewService` | Registry and furniture review, including their durable recovery |
| `InventoryPlanningService` | Warehouse planning settings and immutable versioned final plans |
| `InventoryCompletionService` | Preview, terminal completion/cancellation and post-commit intents |
| `InventoryStatisticsService` | Frozen and aggregate statistics calculations |
| `InventoryPublicationService` | Publication, retry, closure and recovery |
| `CompletedInventoryPlanCorrectionService` | Completed-plan restoration of omitted explicit observations and strict next-version creation |
| `InventoryOutcomeRecoveryService` | Completed-history rebuilding of authoritative asset and maintenance work |
| `InventoryProjectionService` | API projections over owner-local state |

`InventoryFindingPersistenceService` encapsulates only source attachments, media references/facts,
exact non-media revision carry-forward and frozen-plan persistence. Narrow abstract workflow
supports expose only the repositories and ports required by their one concrete use case; they are
not Spring beans. The shared
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

Inventory outbox and sanitized DLT publication have a finite per-record budget
(`INVENTORY_KAFKA_OUTBOX_MAX_ATTEMPTS`, default `4`). Broker rejection, publication
exceptions and expired claims consume that budget. Exhaustion retains the exact
body in outbox `QUARANTINED` or DLT `FAILED`; an unpublished aggregate head keeps
later versions blocked. Administrators recover intact records through
`POST /api/inventory/v1/operations/outbox/{eventId}/requeue` or
`POST /api/inventory/v1/operations/dead-letters/{dltId}/requeue`, supplying
`expectedReviewVersion` and a non-blank reason. The authenticated reviewer and
previous failure are recorded in an immutable audit. Exact replay returns the
original `PENDING` receipt even after publication; changed/stale reviews conflict,
and review cannot override corrupt or unsafe stored data.
Terminal backlogs are exposed separately as `rwms.inventory.outbox.terminal.current`
and `rwms.inventory.dlt.terminal.current`.

An inspection plan may carry the optional explicit `forceCapitalRepair` choice; omission means
`false` and JSON `null` is rejected. Inventory persists it in immutable frozen-plan and final-plan
evidence, includes it in the final-plan hash/publication request, and never recalculates maintenance
complexity itself. A plan cannot select both repair movement and forced capital repair. Public
frozen-plan lines expose the immutable queue ID, name and type already recorded in the maintenance
source snapshot, so clients can reconstruct custom and repeated-catalog stages without duplicating
or guessing line ownership; this adds no inventory table or migration. Migration
[`V17__manual_capital_repair_selection.sql`](src/main/resources/db/migration/V17__manual_capital_repair_selection.sql)
backfills existing evidence as `false` and preserves the no-work invariant.

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
Deployment configuration must select one of those profiles; the dependency retry policy does not
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
