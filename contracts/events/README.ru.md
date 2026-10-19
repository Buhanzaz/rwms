# Контракты integration events

[English version](README.md)

Этот каталог содержит канонические схемы асинхронных integration events RWMS.
События передают факты, уже зафиксированные одним сервисом-владельцем; это не
удалённые команды, замаскированные под события.

## Стандартный event envelope

Каждое семейство использует канонический технический envelope репозитория:

| Поле | Смысл |
| --- | --- |
| `envelopeVersion` | Обязательный маркер технического envelope, строго `2` |
| `eventId` | Глобально уникальная immutable identity доставки |
| `eventType` | Стабильное namespaced имя факта |
| `eventVersion` | Major contract version payload |
| `occurredAt` | Доказанный business instant, когда он известен |
| `recordedAt` | UTC instant записи факта владельцем |
| `producer` | Точная identity сервиса-владельца, например `asset-service`, без версии сборки |
| `aggregateType` | Тип агрегата producer |
| `aggregateId` | Opaque identifier агрегата producer |
| `aggregateVersion` | Версия после committed mutation |
| `correlation` | Correlation ID и optional causation ID |
| `actorRef` | Sanitized opaque actor reference, когда разрешает policy |
| `payload` | Версионированные event-specific данные |

Trace metadata может передаваться инфраструктурой отдельно. События никогда не
содержат passwords, OAuth tokens, client secrets, private keys, signed object
URLs или лишние персональные данные.

## Доставка и persistence

- Producer фиксирует доменное изменение и outbox в одной локальной транзакции.
- Kafka доставляет at least once; повторная публикация должна быть безопасной.
- Consumer владеет inbox deduplication и записывает `eventId` вместе со своим
  projection/state change.
- Aggregate versions защищают упорядоченные проекции и выявляют gaps или
  regressions вместо молчаливой перезаписи.
- Retries ограничены; исчерпанные записи переходят в consumer-owned sanitized
  DLT. Бесконечный requeue запрещён.
- Service-local PostgreSQL event store или текущая projection — authority для
  replay. Kafka retention не является базой данных.
- Сервис не читает outbox, inbox или event-store таблицы другого сервиса.

RabbitMQ artifacts и migration baselines являются только историческими
свидетельствами совместимости, если текущий runtime source явно не доказывает
активную зависимость.

## Совместимость

- Имена событий и смысл payload неизменяемы внутри major version.
- Additive fields требуют evolved schema, принятой producer и consumers.
- Удалённые или переименованные поля и изменённая семантика требуют новой major
  version либо явно согласованной замены.
- Producer и consumer tests покрывают duplicate delivery, ordering/gaps,
  retry/DLT behavior и unsupported versions, где это применимо.
- Consumers не создают недоказанных actors, dates или facts.

## Текущие семейства

- `auth/`, `warehouse-events.yaml`, `task-board-events.yaml`;
- `media-events.yaml` и схемы в `media/`;
- `asset-events.yaml`, `maintenance-events.yaml`, `inventory-events.yaml`;
- `logistics-events.yaml` и схемы в `logistics/`;
- `dossier-consumers.yaml` и схемы в `dossier/`;
- `analytics-consumers.yaml` и схемы в `analytics/`;
- технические envelope и delivery schemas в `technical/`.

До изменения семейства проследите producer и всех активных consumers. Схему,
producer, consumers и compatibility tests нужно обновлять вместе.
