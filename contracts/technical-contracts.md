# Technical Contract Records

`platform:technical-contracts` is the only shared Java transport library. It is
framework-neutral and contains immutable records for cross-cutting wire shapes,
not domain ownership.

## Included records

| Record | Stable fields |
|---|---|
| `ApiProblem` | `type`, `title`, `status`, `detail`, `instance`, `code`, `violations`, `correlation` |
| `FieldViolation` | `field`, `code`, `message` |
| `PageResponse<T>` | `items`, `page`, `size`, `totalElements`, `totalPages` |
| `EventEnvelope<T>` | `eventId`, `eventType`, `eventVersion`, `occurredAt`, `producer`, `aggregateType`, `aggregateId`, `aggregateVersion`, `correlation`, `actor`, `payload` |
| `ActorSnapshot` | `actorId`, `actorType`, `displayName` |
| `CorrelationContext` | `correlationId`, `causationId` |

Collections are defensively copied. Compact constructors enforce only wire
structure such as required identity/text, positive versions, valid HTTP status,
and non-negative pagination totals. Domain validity remains the responsibility
of the owning service.

## Forbidden contents

- JPA/Spring annotations, entities, repositories, aggregates, or services;
- business commands/responses, domain enums, status registries, or workflow
  rules;
- OAuth secrets, URLs, persistence configuration, generated clients, or broker
  clients;
- types that allow one service to mutate another service's state in memory.

The handwritten OpenAPI/event schema remains canonical for every domain
contract. Service adapters map generated or handwritten transport types to
service-local domain models.
