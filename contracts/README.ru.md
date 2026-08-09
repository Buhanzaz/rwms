# Контракты RWMS

[English version](README.md)

Этот каталог — каноническая граница репозитория для версионированных контрактов
между независимо развёртываемыми компонентами RWMS. Здесь находятся transport
schemas и документация контрактов, а не общие доменные модели или persistence
entities.

Текущая архитектура и навигация по контрактам поддерживаются в
[`docs/project-knowledge/contracts.md`](../docs/project-knowledge/contracts.md).
Исторические migration plans и legacy knowledge не являются authority.

## Области контрактов

- [`openapi`](openapi/README.md) — синхронные HTTP API.
- [`events`](events/README.md) — асинхронные integration facts и envelopes.
- [`technical-contracts.md`](technical-contracts.md) — framework-neutral общие
  технические records, реализованные в `platform:technical-contracts`.

Не размещайте в этом каталоге Java, TypeScript, Go, JPA, Hibernate, Flyway или
другие runtime implementation classes.

## Источник и generated artifacts

Каждый контракт определяет один канонический handwritten source. Он ревьюится
и версионируется вместе с producer и всеми активными consumers.

Generated clients, server interfaces, schemas и документация:

- воспроизводимо создаются из канонического источника;
- никогда не редактируются вручную;
- находятся в service-local build или явно названных generated-source каталогах;
- не становятся общими runtime domain- или persistence-моделями;
- коммитятся только когда владеющий компонент явно требует checked-in output и
  проверяет drift.

Handwritten adapters могут оборачивать generated transport types. Бизнес-
правила остаются внутри сервиса-владельца.

## Совместимость и версионирование

- Breaking changes требуют новой major version или явно согласованной
  одновременной замены producer/consumer, одобренной пользователем.
- Additive optional fields и enum values совместимы только когда доказано, что
  каждый активный consumer их переносит.
- Смысл поля, units, identity, nullability и enum semantics нельзя молча менять.
- Mutable-команды несут expected aggregate version или эквивалентный fencing
  token и возвращают явный stale-write conflict.
- Повторяемые create/effect определяют idempotency key или stable external ID.
- Deprecation называет замену, affected consumers и условие удаления; obsolete
  paths удаляются, когда не остаётся поддерживаемых consumers.
- Contract tests покрывают текущий producer и каждый активный consumer.

Browser DTO, localStorage keys, IndexedDB records, fixture UUID и legacy rows
БД — implementation evidence, а не автоматические backend contracts.

## Правила границы сервиса

- Каждый stateful-сервис владеет своей БД, Flyway history, aggregates, status
  registry и реализацией API/events.
- Сервисы обмениваются opaque identifiers и immutable snapshots. Запрещены
  cross-database foreign keys, joins и shared entity graphs.
- Общего business-domain или JPA-модуля нет.
- Межсервисные workflow используют idempotent commands и durable outbox/inbox,
  а не distributed database transactions.
- Контракты и примеры никогда не содержат secrets, access tokens, private URLs
  или production/customer data.

## Порядок изменения контракта

До изменения определите владельца, producer, каждый consumer, авторизацию,
failure behavior, concurrency и data semantics. Изменяйте схему и реализации в
одной согласованной задаче и проверяйте producer/consumer compatibility.

Если текущий код, контракт и требуемая семантика противоречат друг другу,
запросите отсутствующее продуктовое решение вместо ослабления или обхода
контракта.
