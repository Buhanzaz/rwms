# RWMS Contracts

[Русская версия](README.ru.md)

This directory is the canonical repository boundary for versioned contracts
exchanged between independently deployable RWMS components. It contains
transport schemas and contract documentation, not shared domain models or
persistence entities.

Current architecture and contract navigation are maintained in
[`docs/project-knowledge/contracts.md`](../docs/project-knowledge/contracts.md).
Historical migration plans and legacy knowledge are not contract authority.

## Contract Areas

- [`openapi`](openapi/README.md) — synchronous HTTP APIs.
- [`events`](events/README.md) — asynchronous integration facts and envelopes.
- [`technical-contracts.md`](technical-contracts.md) — framework-neutral shared
  technical records implemented by `platform:technical-contracts`.

Do not place Java, TypeScript, Go, JPA, Hibernate, Flyway or other runtime
implementation classes in this directory.

## Source And Generated Artifacts

Every contract identifies one canonical handwritten source. It is reviewed and
versioned with the producer and all active consumers.

Generated clients, server interfaces, schemas and documentation:

- are reproducible from the canonical source;
- are never edited manually;
- live in service-local build or clearly named generated-source directories;
- never become shared runtime domain or persistence models;
- are committed only when the owning component explicitly requires checked-in
  generated output and verifies drift.

Handwritten adapters may wrap generated transport types. Business rules remain
inside the owning service.

## Compatibility And Versioning

- Breaking changes require a new major version or an explicit coordinated
  producer/consumer replacement approved by the user.
- Additive optional fields and enum values are compatible only when every
  active consumer is proven to tolerate them.
- Field meaning, units, identity, nullability and enum semantics never change
  silently in place.
- Mutable commands carry an expected aggregate version or equivalent fencing
  token and return an explicit stale-write conflict.
- Retried creates/effects define an idempotency key or stable external ID.
- Deprecation names the replacement, affected consumers and removal condition;
  obsolete paths are deleted when no supported consumer remains.
- Contract tests cover the current producer and every active consumer.

Browser DTOs, localStorage keys, IndexedDB records, fixture UUIDs and legacy
database rows are implementation evidence, not automatic backend contracts.

## Service Boundary Rules

- Each stateful service owns its database, Flyway history, aggregates, status
  registry and API/event implementation.
- Services exchange opaque identifiers and immutable snapshots. Cross-database
  foreign keys, joins and shared entity graphs are forbidden.
- There is no shared business-domain or JPA module.
- Cross-service workflows use idempotent commands and durable outbox/inbox
  delivery, not distributed database transactions.
- Contracts and examples never contain secrets, access tokens, private URLs or
  production/customer data.

## Contract Change Workflow

Before changing a contract, identify its owner, producer, every consumer,
authorization, failure behavior, concurrency behavior and data semantics. Make
the schema and implementation changes in one coordinated task and validate
producer/consumer compatibility.

If current code, current contract and requested semantics conflict, stop and
request the missing product decision rather than weakening or bypassing the
contract.
