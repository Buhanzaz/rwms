# RWMS Platform Modules

[Русская версия](README.ru.md)

The platform directory contains shared technical modules and executable architecture policy for RWMS. These modules are intentionally narrow: domain ownership stays with the owning service and canonical HTTP/event meaning stays under contracts.

## Modules and ownership

| Module | Responsibility | Deliberate non-responsibility |
| --- | --- | --- |
| [technical-contracts](technical-contracts/README.md) | Framework-neutral cross-cutting Java wire records | Shared business entities, Spring/JPA/Kafka dependencies and domain commands |
| [spring-boot-starter](spring-boot-starter/README.md) | Optional technical Spring auto-configuration and Kafka publishing minimums | Service security chains, repositories, business topics, inbox/outbox tables and workflow |
| [architecture-tests](architecture-tests/README.md) | Executable dependency, mapper and source-boundary policies | Runtime business authorization or service orchestration |

The repository root [settings.gradle.kts](../settings.gradle.kts) includes these modules and the services that consume them. A platform module may support a boundary but does not become its business owner.

## Dependency and contract rules

- Handwritten OpenAPI and AsyncAPI/JSON schemas under [contracts](../contracts/) are canonical.
- Technical contracts must remain immutable, framework-neutral and free of service domain models.
- A stateful service owns one database; cross-service repositories, JPA entities, joins and shared mutable models are forbidden.
- Kafka is at-least-once transport. Service-owned outbox/inbox, deduplication, aggregate ordering, DLT and replay behavior remain local to the owning service.
- The gateway is transport-only and does not replace a service-owned command, saga or projection.

## Using and changing platform modules

Add a platform dependency only for a technical concern that is genuinely common. Keep generated clients and transport DTOs at service boundaries; do not use them as persistence entities or a way to cross a domain boundary.

When a change changes a technical wire shape, trace every producer and consumer, update canonical schemas and compatibility tests together, and retain service-local mapping. Do not silently select an obsolete compatibility record for a new event family.

Architecture tests currently import the services named in their test configuration. Their documented coverage is intentionally explicit; adding a service to the root build does not automatically add it to an ArchUnit rule.

The current Java graph covers auth, task-board, warehouse, asset, maintenance,
inventory, logistics, dossier, assistant, analytics and api-gateway. Its central
rules enforce constructor injection and approved MapStruct boundaries, preserve
service-owned models, keep the gateway stateless and prevent analytics/dossier
read models from exposing mutating HTTP mappings.

## Verification

Run the narrow module checks for a platform change:

    bash ./gradlew :platform:technical-contracts:test
    bash ./gradlew :platform:spring-boot-starter:test
    bash ./gradlew :platform:architecture-tests:test

A contract-boundary change additionally requires canonical schema validation and focused producer/consumer checks in the owning services.

## References

- [Repository architecture knowledge](../docs/project-knowledge/architecture.md)
- [Contract evolution rules](../docs/project-knowledge/contracts.md)
- [Documentation standard](../docs/project-knowledge/documentation-standard.md)
- [Root Gradle settings](../settings.gradle.kts)
