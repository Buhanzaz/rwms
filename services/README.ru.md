# Сервисы RWMS

[English version](README.md)

В этом каталоге находятся независимо владеющие данными сервисы действующего
продукта RWMS. Объём работы выбирает текущая задача пользователя; активной
последовательности стадий или обязательного порядка сервисов нет.

Поддерживаемая карта архитектуры находится в
[`docs/project-knowledge/architecture.md`](../docs/project-knowledge/architecture.md).
Исторические планы и stage-записи служат только свидетельствами.

## Реестр сервисов

| Сервис | Runtime-форма | Основная ответственность |
| --- | --- | --- |
| `auth-service` | Stateful Spring | OAuth2/OIDC, учётные данные пользователей и работников, роли, клиенты и складские grants |
| `api-gateway-service` | Stateless Spring | Только публичная маршрутизация и transport security |
| `warehouse-service` | Stateful Spring | Идентичность, метаданные и timezone склада |
| `asset-service` | Stateful Spring | Кабины, оборудование, остатки, содержимое, holds и leases |
| `task-board-service` | Stateful Spring | Очереди, workforce, назначения и task board |
| `maintenance-service` | Stateful Spring | Каталог, сметы, ремонт, приёмка и решения о списании |
| `inventory-service` | Stateful Spring | Сессии инвентаризации, находки, завершение и публикация |
| `logistics-service` | Stateful Spring | Запросы аренды, возвраты, отгрузки, перемещения, водители и оркестрация |
| `dossier-service` | Stateful Spring read model | Междоменная проекция активности кабины |
| `analytics-service` | Stateful Spring read model | KPI и dashboard-проекции |
| `assistant-service` | Stateful Spring | Диалоги ассистента, сообщения и история tool calls |
| `media-service` | Stateful Go | Метаданные медиа, upload/finalize, приватные объекты и преобразования |

Корневая Gradle-сборка перечисляет Spring-модули в `settings.gradle.kts`.
`media-service` написан на Go и намеренно находится вне этой сборки.

## Форма stateful-сервиса

Каждый stateful business service владеет:

- одной выделенной PostgreSQL БД и её жизненным циклом;
- локальными неизменяемыми Flyway-миграциями и `flyway_schema_history`;
- агрегатами, статусами, application ports и правилами авторизации;
- своим семейством OpenAPI и produced/consumed event schemas;
- transactional outbox/inbox и recovery там, где публикует или получает факты;
- health, readiness, структурированными логами, метриками и tracing;
- focused unit-, authorization-, contract-, persistence-, concurrency- и
  failure-тестами.

Spring-сервисы используют JPA как application mapping и
`hibernate.ddl-auto=validate`. Go media-service владеет нативным persistence и
валидацией схемы. Liquibase не является активным владельцем схемы.

## Правила границ

- Запрещены межсервисные database foreign keys, joins, общие таблицы, импорты
  repository и прямой доступ к чужой БД.
- Запрещены общие JPA entities, aggregates, repositories и модули доменных
  enum.
- Сервисы обмениваются opaque ID, immutable snapshots, versioned API и событиями.
- Mutable-команды используют optimistic version или fencing, повторяемые
  эффекты — idempotency.
- Межсервисные workflow хранят saga/reconciliation state у инициирующего
  владельца. UI rollback не является механизмом согласованности.
- Публичный трафик проходит через `api-gateway-service`; downstream-сервисы всё
  равно валидируют JWT. Внутренний трафик использует приватные routes и
  service credentials.
- Gateway не владеет БД, messaging, workflow, cache или business aggregation.

## Критерий готовности

Изменённый сервис готов к передаче только после согласования контракта и
consumers, прохождения focused success/failure tests, schema/JPA validation при
необходимости, проверки архитектурных границ и записи устойчивых изменений в
`docs/project-knowledge/`.

Нельзя коммитить secrets, credentials, backups, logs и generated build output.
