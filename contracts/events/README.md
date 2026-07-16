# Integration Event Contracts

This directory will contain canonical schemas for asynchronous integration
events. Events communicate facts already committed by one owning service; they
are not remote commands disguised as events.

## Standard Event Envelope

Every event contract must define an envelope equivalent to:

| Field | Meaning |
|---|---|
| `eventId` | Globally unique immutable delivery identity. |
| `eventType` | Stable namespaced event name. |
| `eventVersion` | Major contract version for the payload. |
| `occurredAt` | Proven business instant, nullable for deterministic migration baselines. |
| `recordedAt` | UTC instant at which the owning service recorded the fact. |
| `producer` | Owning service and deployable version. |
| `aggregateType` | Producer-owned aggregate kind. |
| `aggregateId` | Opaque producer-owned identifier. |
| `aggregateVersion` | Version after the committed mutation. |
| `correlation` | `correlationId` plus optional `causationId`. |
| `actorRef` | Sanitized opaque actor reference when policy permits it. |
| `payload` | Versioned event-specific data. |

Trace metadata may be transported separately by infrastructure. Never place
passwords, OAuth tokens, client secrets, private keys, signed object URLs, or
unnecessary personal data in an event.

## Delivery And Persistence

- A producer inserts the domain change and `outbox_event` record in one local
  database transaction.
- A relay publishes from the outbox with at-least-once delivery; publishing
  twice must be harmless.
- A consumer records `eventId` in a service-owned `inbox_message` before or with
  its local projection/state change.
- Consumers must be idempotent and tolerate reordered delivery where the
  contract allows it, using aggregate versions when ordering is required.
- RabbitMQ `rwms.domain.v1` is retained as historical F0/F2 and media-compat
  evidence. Target Spring integration migrates service by service to Kafka.
- Kafka facts use aggregate-family topics keyed by aggregate ID. Exact facts
  remain in `eventType`; the service-local PostgreSQL event store, not Kafka,
  owns indefinite replay.
- Producers use transactional outboxes and broker acknowledgements. Consumers
  use inbox deduplication, first attempt plus three bounded retries, then a
  consumer-owned sanitized DLT; infinite requeue is forbidden.
- Long-term retention, historical replay, partition strategy, and delivery SLA
  beyond at-least-once remain operational decision gates.

No service reads another service's outbox or inbox tables directly.

## Compatibility

- Event names and payload meaning are immutable within a major version.
- Envelope V2 is strict. Additive payload fields require an explicitly evolved
  schema accepted by both producer and consumer.
- Removed/renamed fields or changed semantics require a new major event version
  and parallel publication during the migration window.
- Producer and consumer contract tests must exercise duplicate delivery,
  retry, replay, and unsupported-version behavior.
- Historical gaps are represented honestly; consumers must not synthesize
  actors, dates, or events that cannot be proven.

## Planned Event Families

F4A defines the strict V2 auth schemas in `auth/auth-events-v1.schema.json`.
Its migration baselines remain service-local and are never Kafka history.

Stage-owned files are introduced only with the service:

- `task-board-events.yaml`
- `media-events.yaml`
- `asset-events.yaml`
- `maintenance-events.yaml`
- `inventory-events.yaml`
- `logistics-events.yaml`
- `dossier-consumers.yaml`
- `analytics-consumers.yaml`

Warehouse lifecycle events are defined with `warehouse-service`; the exact file
split is decided in Stage 1. The former Stage 4 worker is superseded by the
combined stateful Go `media-service`: it owns PostgreSQL media metadata and
status, signed MinIO URLs, its outbox/inbox and in-process transformations.
