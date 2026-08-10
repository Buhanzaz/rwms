# Contract Map And Evolution Rules

Status: Confirmed repository layout as of 2026-08-07.

## Canonical Locations

- HTTP: [`contracts/openapi/`](../../contracts/openapi/)
- Events: [`contracts/events/`](../../contracts/events/)
- Technical conventions:
  [`contracts/technical-contracts.md`](../../contracts/technical-contracts.md)
- Framework-neutral Java records:
  [`platform/technical-contracts/`](../../platform/technical-contracts/)

The handwritten schema is authoritative. Generated source and client-specific
DTOs are derived boundary artifacts and never shared persistence/domain models.

## HTTP Families

Each current domain service has one OpenAPI family named
`contracts/openapi/<service>.yaml`. `api-gateway-service` routes those APIs and
does not redefine or aggregate their business schema.

Interactive clients use public `/auth/**` and `/api/**` gateway routes. Private
`/api/internal/**` operations are for authenticated service-to-service calls
only.

### Rental clients, interactive search and selection

[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml)
defines logistics-owned rental clients, their orders and delivery facts. Client
creation requires an `Idempotency-Key`; type, name and phone are mandatory,
while IP/legal entities additionally require a contact person. Order create and
update carry address, optional coordinate pair, contact phone, optional comment
and a bounded unique acceptable-date list. Detail exposes logistics document
movement evidence rather than a browser-owned history.

The same contract defines search `resultMode`, characteristic and
type-dimension facets, paged facts-only catalog lookup, and owner-scoped
selection GET/PUT. Selection PUT carries the complete requested identifier set
plus warehouse and `Idempotency-Key`; an empty set is an explicit release.
[`assistant-service.yaml`](../../contracts/openapi/assistant-service.yaml)
exposes durable clarification questions, exact button-answer turns, structured
clarification/selection SSE events and an owner-checked selection proxy. It
does not redefine availability or hold state. Asset private contracts carry
the exact result mode/facets and remain the hold-effect boundary.

Evidence:
[`logistics OpenAPI`](../../contracts/openapi/logistics-service.yaml),
[`assistant OpenAPI`](../../contracts/openapi/assistant-service.yaml),
and
[`asset OpenAPI`](../../contracts/openapi/asset-service.yaml).

### Driver Task Audience And Document Tasks

The logistics HTTP contract carries an optional opaque `driverWorkerId` only
on shipment and return scheduling commands and document responses. Transfer
creation has no driver identity. Driver tasks and board cards still expose
`UNASSIGNED`, `ASSIGNED_DRIVER` and `WAREHOUSE_DRIVERS`: shipment/return may be
unassigned or assigned to exactly one driver, while every transfer is
warehouse-shared and cannot carry an identity snapshot. The warehouse-scoped
`shipment-task-settings` GET/PUT contract owns the 1–100 cabin cap and its
optimistic version. It applies when a new shipment is created: its one local
driver task is sourced from the document and carries immutable cabin members
and client/cabin task text. Retained legacy shipment tasks, returns and
transfers remain document-line sourced. The public logistics move command
contains no audience replacement; shipment/return moves only reorder inside
their existing driver/date/lane queue.

The task-board private registration and movement boundary accepts the same
audience for owner-driven reconciliation, but rejects a worker identity on a
shared audience. Public board-entry, registration and worker-feed responses
always contain the nullable audience property. Board-task V1 facts add optional
`driverAudience` and `plannedDriverWorkerId` only: retained events without
either field remain valid, and the display-name snapshot never crosses the
event boundary.

Evidence:
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml),
[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml),
and
[`task-board-events-v1.schema.json`](../../contracts/events/task-board/task-board-events-v1.schema.json).

### Media Upload Session Recovery

The public create-upload command in
[`media-service.yaml`](../../contracts/openapi/media-service.yaml) treats an
exact idempotent replay as the same logical media creation. While the original
session is open it returns that session. If the session expired before content
was finalized, it may return a replacement session for the same media ID.
Completed content is immutable and cannot be reopened through this recovery
path. Active panel and Android consumers already use the session returned by
each create response, so the change is compatible with their durable retries.

### Media Orientation

`media-service.yaml` has no mutable photo-rotation operation. Android clients
normalize a captured or gallery JPEG into upright pixel data before it enters a
durable upload queue; media-service stores and derives variants from that
supplied orientation without applying EXIF or requested rotation. The
read-only `rotationDegrees` response field remains only so older persisted
assets can still be displayed compatibly; it is not a command for new media.

Evidence: [`media-service.yaml`](../../contracts/openapi/media-service.yaml),
[`ManagerCameraScreen.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/components/ManagerCameraScreen.kt),
[`ManagerPhotos.kt`](../../app/src/main/java/dev/buhanzaz/rwms/manager/ui/components/ManagerPhotos.kt),
[`image_processor.go`](../../services/media-service/internal/media/image_processor.go).

### Media Processing Terminal DLT

The sanitized processing DLT contract in
[`media-processing-dlt-v1.schema.json`](../../contracts/events/media/media-processing-dlt-v1.schema.json)
includes `PROCESSING_ATTEMPT_EXHAUSTED` for an expired, already-fourth fenced
attempt that is terminalized without another processor call. This is a
compatible enum addition for the current repository: no active consumer outside
media-service was found. The payload remains hash-only and may not contain the
source record, object key, owner data or free-form dependency error. Any
out-of-repository strict enum consumer must accept the added value before a
runtime rollout.

### Inventory-Created Assets

The private source-asset operation in
[`asset-service.yaml`](../../contracts/openapi/asset-service.yaml) accepts one
of two explicit representations: canonical catalogue UUIDs, or the supported
manager-client name fields. They cannot be mixed. Name resolution belongs to
asset-service and is completed against active catalogue data before any
permanent source identity, cabin-number claim or rental item is stored. The
stored source fingerprint uses the resolved UUID selection, so an equivalent
retry in the other representation is the same idempotent command.

### Driver-Board Repair Places

The public `DriverBoard` contract in
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml)
separates physical repair-place occupancy (`occupiedRepairPlaceCount`) from
the capacity-aware placement value (`usedRepairPlaceCount`). Its `repairPlaces`
cards are read-only progress facts only for cabins physically in the repair
zone (`OCCUPIED` or `READY_TO_RELEASE`); a `RESERVED` delivery remains in the
driver queue. Each card carries its allocation state, priority, and an optional
earliest unfinished repair stage with its state.

`maintenance-service` is the sole producer of allocation and stage truth via
its private logistics projection in
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml).
The panel must not call that private route or infer status from browser state;
`logistics-service` validates and republishes the needed fields through the
public driver-board response.

### Return Estimate Sources And Legacy Furniture

[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml)
defines `POST /returns/{documentId}/start-estimates`. Its request contains
only immutable inspection-photo references for every return line: it does not
select shortages or furniture. Logistics settles each line and calls the
private maintenance `estimate-source` boundary with the permanent
`returnId:lineId` identity. Maintenance creates exactly one empty `DRAFT`
estimate for that source, and its public `GET /estimates/return-sources` read
returns the separate estimate IDs to panel and Android clients.

`CompleteEstimateRequest.allowUnaccountedFurniture` in
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml)
is an explicit confirmation. It is accepted only when the canonical
cabin contents are empty; otherwise the server returns
`MAINTENANCE_UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED` or fails closed.
The accepted legacy path creates separately approved `UNACCOUNTED` property
decisions with `NOT_REQUIRED` asset effect, so it never decrements a warehouse
additional-equipment balance.

### Property Disposition And Asset Effects

The public write-off/loss decision boundary belongs to
[`maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml).
One list row represents one decision and root asset; repair/rework nodes are
detail history, not duplicate decisions. Warehouse managers and global
administrators may propose a mandatory-reason decision, while only
`WMS_ADMIN` or `SYSTEM_ADMIN` may approve, reject or recover it. A cabin with
non-zero contents must carry an exact versioned per-line plan; an empty cabin
must carry no contents plan. Selected positive quantities become a
logistics-owned furniture movement, and every unselected quantity is part of
the terminal asset effect.

[`asset-service.yaml`](../../contracts/openapi/asset-service.yaml) exposes only
the narrow maintenance prepare/apply effects. It owns balances, terminal
movement and permanent furniture custody; it never accepts the business
decision itself. Inventory shortage publication creates an idempotent
maintenance `LOSS` proposal and does not directly mutate a terminal balance.
Decision, effect and recovery commands are version/idempotency fenced and
return their honest pending, effective or quarantined state.

### Warehouse Lifecycle And Time

[`warehouse-service.yaml`](../../contracts/openapi/warehouse-service.yaml)
defines directional admission, exact-version readiness confirmation, durable
operation marks and an as-of timezone read. Incoming work is admitted only for
`ACTIVE`; existing outgoing work may drain in `DRAINING`; no new owner-local
blocker may commit behind a readiness fence. A timezone correction is immediate
only before the first operation. Once used, a change has an `effectiveFrom` and
historical operations keep the zone effective at their own timestamp.

Actual inter-warehouse movement is represented by
[`logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml).
When an arrived line continues an active repair, logistics reads the exact
post-`TRANSFER_ARRIVE` asset version from its released guard, includes it in the
durable maintenance-attempt fingerprint and sends it as required
`rentalItemVersion` in `CompleteTransferRepairRequest`. Maintenance must use
that original value for replayed lease/status commands; a fresh read cannot
silently replace the version fence.
The separate asset administrative-correction command is administrator-only,
requires `expectedVersion`, reason and HTTPS evidence, rejects active
workflow/reservation/lease blockers, and records immutable correction evidence.

## Event Families

Domain event indexes and JSON/YAML schemas live under `contracts/events/`.
Events describe committed facts. The technical envelope, delivery and
event-store conventions live under `contracts/events/technical/`.

Kafka is at-least-once transport. Producer outbox, consumer inbox,
aggregate-version handling and sanitized DLT behavior remain service-owned
implementations constrained by these contracts.

## Canonical Integrity Gate

The root `verifyCanonicalContracts` task is the deterministic repository gate
for canonical transport sources. It parses every YAML and JSON document under
`contracts/`, resolves only bounded local file references and JSON Pointers,
compiles every declared JSON Schema draft, and rejects missing, escaping or
remote references. Within each owning document or catalog, it also requires
unique non-blank OpenAPI `operationId` values and unique lowercase namespaced
AsyncAPI message names ending in `.vN`.

Schema identity is path-bound. Schemas under `contracts/events/technical/`
retain the established
`https://rwms.example/contracts/events/technical/` namespace; every other
event schema uses `https://rwms.local/contracts/events/`. In both cases the
complete `$id` must equal the schema's relative repository path, so a valid
host with a wrong path is still rejected. This narrow legacy namespace rule
does not authorize another host or a new exception.

Run the gate from the repository root:

```bash
bash ./gradlew verifyCanonicalContracts
```

Evidence:
[`root task`](../../build.gradle.kts),
[`architecture test task`](../../platform/architecture-tests/build.gradle.kts),
[`integrity coordinator`](../../platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/contracts/CanonicalContractIntegrityGate.java),
and
[`negative and checked-in fixtures`](../../platform/architecture-tests/src/test/java/dev/buhanzaz/rwms/contracts/CanonicalContractIntegrityTest.java).

## Implementation Parity Gates

Canonical structure is supplemented by executable implementation inventories.
Inventory-service and logistics-service parse their owning OpenAPI documents,
compare every method/path pair with merged Spring controller mappings, and send
unauthenticated probes through the real owner security filter chain. Inventory
contains 27 bearer operations. Logistics contains 77
operations: 73 bearer operations and exactly four anonymous client-presentation
operations.

The gateway inventory resolves every canonical domain-public operation through
the current functional routers and the real edge security chain. It covers 265
domain-public operations, proves the four logistics presentation operations are
the only anonymous domain routes, and proves canonical internal operations and
reserved internal/private aliases are not public gateway routes. Auth callbacks
and media health remain explicit owner-specific exclusions rather than hidden
route gaps.

Manager and worker Retrofit gates inventory all declared client methods,
eagerly validate their converters and exercise representative encode/decode
fixtures for every consumed JSON root family. Manager has 62 methods (60 fixed
public gateway paths and two media-only guarded `@Url` methods); worker has 11
(nine fixed and two guarded media methods). The worker action serializer emits
all seven required contract properties, including explicit `null` for the
required nullable `workerGroupId` and `evidenceId`, without enabling global
explicit-null serialization.

Evidence:
[`inventory parity`](../../services/inventory-service/src/test/java/dev/buhanzaz/rwms/inventory/config/InventoryRouteSecurityParityTest.java),
[`logistics parity`](../../services/logistics-service/src/test/java/dev/buhanzaz/rwms/logistics/config/LogisticsRouteSecurityParityTest.java),
[`gateway parity`](../../services/api-gateway-service/src/test/java/dev/buhanzaz/rwms/gateway/config/GatewayRouteSecurityParityTest.java),
[`manager boundary`](../../app/src/test/java/dev/buhanzaz/rwms/manager/network/RwmsApiContractBoundaryTest.kt),
[`worker boundary`](../../worker-app/core-network/src/test/java/dev/buhanzaz/rwms/worker/core/network/WorkerGatewayApiContractBoundaryTest.kt),
and
[`worker action serializer`](../../worker-app/core-network/src/main/java/dev/buhanzaz/rwms/worker/core/network/WorkerActionRequestDtoSerializer.kt).

## Safe Change Procedure

1. Identify the owning service and canonical schema.
2. Find every producer and active panel, Android, service or external consumer.
3. Compare the requested meaning with existing identities, statuses,
   nullability, units, money/time rules, authorization, errors and concurrency.
4. Decide whether the change is compatible. New optional syntax is not safe
   until all consumers are proven tolerant.
5. If meaning or ownership must break and the user did not decide it, stop and
   ask a focused question.
6. Change the canonical schema, owner and consumers in one task.
7. Run `verifyCanonicalContracts`, then focused producer/consumer compatibility
   tests including relevant failure behavior.
8. Remove the obsolete version/path once no supported consumer remains.
9. Update this knowledge base and append the durable change to the log.

## Contract Review Checklist

- Owner and audience are explicit.
- Authentication, scopes and warehouse isolation are explicit.
- IDs are opaque and do not imply cross-database relationships.
- Mutable commands define optimistic concurrency and idempotency.
- Errors use the shared Problem Details convention.
- Pagination, filtering, units, timezone and date/time semantics are explicit.
- Event payloads contain facts, not remote commands or secrets.
- Producer and every active consumer agree on versions and enum handling.
- No UI mock, local-storage shape or old database row was promoted into a
  contract without an explicit product decision.
