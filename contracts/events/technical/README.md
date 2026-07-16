# F4K technical event conventions

These schemas define transport and persistence mechanics, not business events.
They do not create Kafka bindings, database tables, shared JPA entities or a
consumer runtime.

- `event-delivery-policy-v1.schema.yaml` fixes the first attempt plus retries
  after 1s, 2s and 4s, followed by the consumer-owned
  `<topic>.<consumer-group>.dlt`. Validation errors are never retried.
- `aggregate-checkpoint-policy-v1.schema.yaml` fixes aggregate ordering and
  requires explicit reconciliation after a version gap. Later effects for that
  aggregate remain blocked until reconciliation.
- `event-store-convention-v1.schema.yaml` lists the seven required
  service-local technical tables, snapshot threshold and transaction/CAS
  invariants. Owning services still implement separate SQL and mappings during
  F4A or F4T.

Kafka is delivery transport. The service-local append-only `domain_event` store
is the replay authority; neither broker retention nor a DLT is an event archive.
