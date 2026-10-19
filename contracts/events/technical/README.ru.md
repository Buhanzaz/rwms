# Технические event conventions RWMS

[English version](README.md)

Эти схемы определяют общие transport- и persistence-механизмы действующих event
families. Они не определяют бизнес-события, не создают Kafka bindings или
database tables и не предоставляют общий consumer runtime.

## Схемы

- `domain-event-envelope-v2.schema.yaml` определяет framework-neutral форму
  event identity, producer, aggregate, version, correlation, actor и payload.
- `event-delivery-policy-v1.schema.yaml` определяет одну начальную попытку и
  ограниченные retries через 1s, 2s и 4s, после чего запись переходит в
  consumer-owned sanitized `<topic>.<consumer-group>.dlt`. Validation failures
  не повторяются.
- `aggregate-checkpoint-policy-v1.schema.yaml` определяет порядок агрегата и
  требует явной reconciliation после version gap. Последующие эффекты этого
  агрегата блокируются до успешной сверки.
- `event-store-convention-v1.schema.yaml` определяет обязательные service-local
  technical tables, snapshot threshold и transaction/CAS invariants. Каждый
  владелец по-прежнему реализует собственные SQL, mappings, retention и recovery.

Kafka — транспорт at-least-once доставки. Источником replay служит service-local
append-only `domain_event` store или authoritative current projection; broker
retention и DLT storage не являются event archive.

Изменение технической схемы требует проследить всех активных producers и
consumers и проверить согласованный compatibility path. Технические conventions
никогда не переносят ownership доменного агрегата в общую библиотеку.
