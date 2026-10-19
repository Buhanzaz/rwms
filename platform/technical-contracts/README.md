# RWMS Technical Contracts

[Русская версия](README.ru.md)

technical-contracts contains immutable framework-neutral Java records for small
cross-cutting wire shapes. It is not a shared domain model: a service keeps its
own aggregate, persistence entity, command, status and transport adapter.

## Boundary

The module has no Spring, JPA, Kafka, broker-client or business-domain
dependency. It may represent technical identity, correlation, paging and a
validated event envelope, but it must never give one service an in-memory way
to mutate another service's state.

Handwritten schemas under [contracts](../../contracts/) remain canonical for
every domain API and integration event. Services map a schema to service-local
types at their boundary; generated clients and these records are never JPA
entities.

## Event records

DomainEventEnvelopeV2 is the strict technical envelope for new canonical event
families. It validates the V2 marker, event naming/version relation, aggregate
identity/version, correlation, opaque actor reference and object payload shape.
Payload meaning, compatibility and producer ownership remain in the event
schema, not in this module.

The module also still contains EventEnvelope and ActorSnapshot. They are not a
substitute for the V2 schema boundary: EventEnvelope lacks the V2 envelope
marker and recorded time, and ActorSnapshot carries a display name. Do not
select either shape for a new integration event without an explicit
compatibility decision and an updated canonical contract.

## Safe use

- Keep records immutable and validation limited to technical wire safety.
- Do not add a domain enum, aggregate, repository, command, secret, URL or
  broker implementation.
- Evolve a domain event through its schema, producer and every active consumer,
  not by changing a shared Java type alone.
- Keep actor references opaque and avoid personal data in technical metadata.

## Verification

Run the module checks from the repository root:

    bash ./gradlew :platform:technical-contracts:test
    bash ./gradlew :platform:technical-contracts:compileJava

Primary references: [DomainEventEnvelopeV2](src/main/java/dev/buhanzaz/rwms/platform/contracts/DomainEventEnvelopeV2.java),
[contracts technical notes](../../contracts/technical-contracts.md), and
[event contract rules](../../contracts/events/README.md).
