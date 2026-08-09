# RWMS Technical Event Conventions

[Русская версия](README.ru.md)

These schemas define transport and persistence mechanics shared by current
event families. They do not define business events, create Kafka bindings or
database tables, or provide a shared consumer runtime.

## Schemas

- `domain-event-envelope-v2.schema.yaml` defines the framework-neutral event
  identity, producer, aggregate, version, correlation, actor, and payload shape.
- `event-delivery-policy-v1.schema.yaml` defines one initial attempt followed by
  bounded retries after 1s, 2s, and 4s, then a consumer-owned sanitized
  `<topic>.<consumer-group>.dlt`. Validation failures are never retried.
- `aggregate-checkpoint-policy-v1.schema.yaml` defines aggregate ordering and
  requires explicit reconciliation after a version gap. Later effects for that
  aggregate remain blocked until reconciliation succeeds.
- `event-store-convention-v1.schema.yaml` defines the required service-local
  technical tables, snapshot threshold, and transaction/CAS invariants. Every
  owner still implements its own SQL, mappings, retention, and recovery.

Kafka is at-least-once delivery transport. A service-local append-only
`domain_event` store or authoritative current projection is the replay source;
broker retention and DLT storage are not an event archive.

Changing a technical schema requires tracing every active producer and consumer
and validating the coordinated compatibility path. Technical conventions never
transfer ownership of a domain aggregate into a shared library.
