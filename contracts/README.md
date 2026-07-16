# RWMS Contracts

This directory is the repository boundary for versioned contracts exchanged
between independently deployable RWMS components. It contains contract sources
and contract documentation, not shared domain models or persistence entities.

The active decomposition and stage gates are defined in
[`docs/plans/20260712-panel-microservices-decomposition.md`](../docs/plans/20260712-panel-microservices-decomposition.md).
Product decisions and migration evidence remain in
[`WMS_ARCHITECTURE_KNOWLEDGE`](../WMS_ARCHITECTURE_KNOWLEDGE/README.md).

## Contract Areas

- [`openapi`](openapi/README.md) — synchronous HTTP APIs.
- [`events`](events/README.md) — asynchronous integration events and their
  envelopes.

Do not place Java, TypeScript, Go, JPA, Hibernate, migration-framework, or other
runtime implementation classes here.

## Source And Generated Artifacts

Every contract must identify one canonical handwritten source. The canonical
source is reviewed and versioned before producer or consumer implementation.

Generated clients, server interfaces, schemas, and documentation:

- must be reproducible from the canonical source;
- must not be edited manually;
- must be generated into service-local build directories or clearly named
  generated source directories;
- must not become a shared runtime domain module;
- are committed only when the owning stage explicitly chooses checked-in
  generated artifacts and verifies them for drift in CI.

Handwritten transport adapters may wrap generated code, but business rules stay
inside the owning service. A generated DTO must not be reused as a persistence
entity or aggregate.

## Compatibility And Versioning

- Contracts use explicit semantic versions. Breaking changes require a new
  major version and a documented consumer migration window.
- Additive optional fields and new enum values are compatible only when every
  consumer is proven to tolerate them.
- Existing field meaning, units, identity semantics, nullability, and enum
  meaning must not change in place.
- Mutable commands carry an expected aggregate version or equivalent fencing
  token and return an explicit conflict on stale writes.
- Deprecation must name the replacement, affected consumers, and removal stage.
- Contract tests cover both the current producer and every active consumer.

Browser mock DTOs, localStorage keys, IndexedDB records, fixture UUIDs, and UI
state are implementation scaffolding. They are evidence for workflows, not
automatic backend contracts.

## Service Boundary Rules

- Each stateful service owns its database, reviewed SQL baseline/releases,
  `rwms_schema_history`, ports, aggregate rules, status registry, and API/event
  implementation.
- Services exchange opaque identifiers and immutable snapshots. They never use
  cross-database foreign keys, joins, or shared entity graphs.
- There is no shared JPA/domain module. Shared libraries may contain only
  narrowly scoped technical utilities that do not encode domain ownership.
- Cross-service workflows use idempotent commands and durable outbox/inbox
  delivery rather than distributed database transactions.
- Secrets, passwords, signing keys, client secrets, connection strings with
  credentials, access tokens, and production endpoints must never be stored in
  contracts or examples.

## Technical Java Contracts

The separate `platform:technical-contracts` library implements only the small
framework-neutral records described in
[`technical-contracts.md`](technical-contracts.md). Canonical OpenAPI and event
schemas remain in this directory; the Java library is not a replacement for
schema ownership and must never contain business or persistence types.

## Current Phase

The only current-stage pointer is
[`docs/plans/ACTIVE_STAGE.md`](../docs/plans/ACTIVE_STAGE.md). Never copy its
literal state here. No contract is approved merely because a future path is
listed in the roadmap.

A stage creates its domain contract only after service ownership, status
semantics, authorization, failure behavior, and migration evidence are reviewed.
