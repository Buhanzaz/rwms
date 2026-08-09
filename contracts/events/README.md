# Integration Event Contracts

[Русская версия](README.ru.md)

This directory contains canonical schemas for asynchronous RWMS integration
events. Events communicate facts already committed by one owning service; they
are not remote commands disguised as events.

## Standard Event Envelope

Every event family uses the repository's canonical technical envelope and
defines the following meaning:

| Field              | Meaning                                                  |
| ------------------ | -------------------------------------------------------- |
| `eventId`          | Globally unique immutable delivery identity.             |
| `eventType`        | Stable namespaced fact name.                             |
| `eventVersion`     | Major contract version for the payload.                  |
| `occurredAt`       | Proven business instant when available.                  |
| `recordedAt`       | UTC instant at which the owner recorded the fact.        |
| `producer`         | Owning service and deployable version.                   |
| `aggregateType`    | Producer-owned aggregate kind.                           |
| `aggregateId`      | Opaque producer-owned identifier.                        |
| `aggregateVersion` | Version after the committed mutation.                    |
| `correlation`      | Correlation ID and optional causation ID.                |
| `actorRef`         | Sanitized opaque actor reference when policy permits it. |
| `payload`          | Versioned event-specific data.                           |

Trace metadata may travel through infrastructure separately. Events never
contain passwords, OAuth tokens, client secrets, private keys, signed object
URLs or unnecessary personal data.

## Delivery And Persistence

- A producer commits the domain change and outbox record in one local database
  transaction.
- Kafka delivery is at least once; duplicate publication must be harmless.
- A consumer owns inbox deduplication and records `eventId` with its local
  projection/state change.
- Aggregate versions protect ordered projections and expose gaps or
  regressions instead of silently overwriting them.
- Retries are bounded and exhausted records go to a consumer-owned sanitized
  DLT. Infinite requeue is forbidden.
- A service-local PostgreSQL event store or current projection is the replay
  authority. Kafka retention is not a database.
- No service reads another service's outbox, inbox or event-store tables.

RabbitMQ artifacts and migration baselines are historical compatibility
evidence only unless a current runtime source explicitly proves an active
dependency.

## Compatibility

- Event names and payload meaning are immutable within a major version.
- Additive fields require an evolved schema accepted by producer and consumers.
- Removed or renamed fields and changed semantics require a new major version
  or an explicitly coordinated replacement.
- Producer and consumer tests cover duplicate delivery, ordering/gaps,
  retry/DLT behavior and unsupported versions when relevant.
- Consumers never synthesize unproven actors, dates or facts.

## Current Families

- `auth/`, `warehouse-events.yaml`, `task-board-events.yaml`
- `media-events.yaml` and schemas under `media/`
- `asset-events.yaml`, `maintenance-events.yaml`, `inventory-events.yaml`
- `logistics-events.yaml` and schemas under `logistics/`
- `dossier-consumers.yaml` and schemas under `dossier/`
- `analytics-consumers.yaml` and schemas under `analytics/`
- technical envelope and delivery schemas under `technical/`

Before changing a family, trace its producer and every active consumer. Update
the schema, producer, consumers and compatibility tests together.
