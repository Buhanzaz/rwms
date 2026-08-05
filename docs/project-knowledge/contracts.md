# Contract Map And Evolution Rules

Status: Confirmed repository layout as of 2026-08-05.

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
