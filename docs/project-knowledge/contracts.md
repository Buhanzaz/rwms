# Contract Map And Evolution Rules

Status: Confirmed repository layout as of 2026-08-04.

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
