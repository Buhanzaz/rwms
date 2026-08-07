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
7. Run schema validation and focused producer/consumer compatibility tests,
   including relevant failure behavior.
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
