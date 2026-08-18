# Сервис инвентаризации RWMS

[English version](README.md)

## Назначение и владение

`inventory-service` владеет inventory sessions, expected membership, findings и inspection state,
completion/statistics, final planning, а также publication intent, attempts и recovery state. Он не
владеет cabins, warehouse identity, repairs, logistics tasks или media objects; эффекты у этих
владельцев проходят через явные private integrations и durable local state.

Авторитетные HTTP- и event-контракты находятся в
[`contracts/openapi/inventory-service.yaml`](../../contracts/openapi/inventory-service.yaml) и
[`contracts/events/inventory-events.yaml`](../../contracts/events/inventory-events.yaml).
[`docs/project-knowledge/domain-logic.md`](../../docs/project-knowledge/domain-logic.md) — индекс:
любое правило при необходимости проверяется по текущим контрактам и коду сервиса.

## Публичная и внутренняя HTTP-граница

Аутентифицированные пользовательские операции версионированы под `/api/inventory/v1/**`. Они
создают и ведут sessions, записывают findings и inspection, готовят/проверяют final plans,
завершают или отменяют session и показывают либо восстанавливают publication work. Изменяющие
команды используют определённые контрактом expected-version и idempotency fields; вызывающая
сторона должна обработать канонический конфликт `409`, а не отправлять изменившийся повтор.

Интерактивные panel и Android-клиенты обращаются к этому namespace только через публичный маршрут
`/api/inventory/**` в `api-gateway-service`. Им нельзя напрямую вызывать host этого модуля или
любой private dependency route.

У inventory нет публичного client route для asset, warehouse, maintenance или media mutations. Он
вызывает их узкие private boundaries с service credentials после того, как собственные local workflow
records стали durable.

## Безопасность, изоляция складов и fencing

HTTP-слой — stateless OAuth2/JWT resource server. `InventoryAuthorizer` требует USER principal,
read/write scope и уровень доступа к запрошенному складу; global administrators получают только
явно реализованный unrestricted warehouse scope. Dev auth bypass действует только в профиле `dev` и
никогда при активном production profile.

Inventory владеет отдельной PostgreSQL database. Он хранит opaque references и contract-defined
snapshots, но никогда shared JPA model, cross-service foreign key или cross-database join. Warehouse
scope session проверяется на owning boundary, а не делегируется API gateway.

Stable idempotency keys, request fingerprints, expected versions и durable capture/publication
attempts fence retries. Нельзя делать вывод о completed remote effect из timeout: recovery использует
то же stable operation identity и owner proof.

## Внутренняя структура приложения

`InventoryApplicationService` — стабильный compatibility facade над семью collaborators. Он
сохраняет поверхность вызовов controllers, inbox и schedulers, но делегирует каждое решение одному
связному use-case service:

| Компонент | Владеющая ответственность |
| --- | --- |
| `InventorySessionService` | Запуск session и durable recovery освобождения capture |
| `InventoryReadService` | Чтения sessions, findings и statistics |
| `InventoryFindingService` | Membership reconciliation, разрешение номера, создание source и изменение inspection |
| `InventoryFindingValidationService` | Fresh-валидация finding, media и plan для точных владельцев |
| `InventoryReviewService` | Registry/furniture review и их durable recovery |
| `InventoryPlanningService` | Warehouse planning settings и immutable versioned final plans |
| `InventoryCompletionService` | Preview, terminal completion/cancellation и post-commit intents |
| `InventoryStatisticsService` | Расчёт frozen и aggregate statistics |
| `InventoryPublicationService` | Publication, retry, closure и recovery |
| `InventoryProjectionService` | API-проекции над owner-local state |

`InventoryFindingPersistenceService` инкапсулирует только source attachments, media
references/facts и persistence frozen plan. Узкие abstract workflow supports открывают только
repositories и ports, необходимые их единственному concrete use case; Spring beans из них не
создаются. Общий `InventoryTechnicalRuntimeSupport` содержит только JSON/canonical-hash,
actor/authorization, correlation и transaction primitives — без repository, удалённого владельца,
lifecycle или workflow-решения. Dependency graph ацикличен, и ни один extracted collaborator не
владеет состоянием другого домена.

`InventoryAssetInboxStore` — единственный технический владелец строк
`inbox_message` asset consumer. Он содержит только row-level deduplication, retry locks/backoff
и state transitions; проверка формы event и решения о inventory membership остаются в processors.
`InventoryPostgresJsonbCanonicalizer` — единственный PostgreSQL adapter `jsonb::text` и никогда
не запрашивает inventory business tables, поэтому storage asset payload и frozen-plan fingerprints
используют каноническое UTF-8 представление V12.

## Хранение, события и восстановление

Flyway-миграции в `src/main/resources/db/migration/` — единственный источник схемы. JPA использует
`ddl-auto=validate`; Hibernate schema mutation и cross-service foreign keys запрещены.

Сервис сохраняет inventory facts и transactional outbox вместе с local state. Kafka — at-least-once
transport: relay арендует ordered aggregate head, проверяет stored envelope и отмечает его
опубликованным только после acknowledgement. Asset и media inbox processors дедуплицируют event IDs,
проверяют contract shape и локально хранят retry/quarantine/DLT state. Inventory start,
capture-release и publication flows сохраняют durable attempts, поэтому retry не создаёт второй
session и не угадывает uncertain dependency result.

План осмотра может содержать необязательный явный выбор `forceCapitalRepair`: отсутствие означает
`false`, а JSON `null` отклоняется. Inventory хранит его в неизменяемых frozen-plan и final-plan
evidence, включает в hash итогового плана/publication request и сам не пересчитывает maintenance
complexity. План не может одновременно выбрать перемещение на ремонт и принудительный капремонт.
Публичные frozen-plan lines возвращают неизменяемые ID, имя и тип очереди, уже записанные в source
snapshot maintenance, поэтому клиенты восстанавливают произвольные и повторяющиеся catalog stages
без дублирования строк или угадывания владельца; новая таблица или миграция inventory не нужны.
Миграция [`V17__manual_capital_repair_selection.sql`](src/main/resources/db/migration/V17__manual_capital_repair_selection.sql)
заполняет существующие evidence значением `false` и сохраняет invariant плана без работ.

`INVENTORY_KAFKA_ENABLED` управляет Kafka relay и consumer beans.
`INVENTORY_DEPENDENCIES_ENABLED` управляет private client-credential dependency gateway для
warehouse, asset и maintenance.

## Runtime-конфигурация

Порт HTTP по умолчанию — `8089`. Настройте inventory database, `AUTH_ISSUER`, CORS origin, а для
live dependencies — token URI, client ID/secret и private base URLs из
`src/main/resources/application.yaml`. Нельзя коммитить credentials или направлять service calls
через public gateway.

В профиле `prod` или `production` `InventoryProductionSafetyValidator` останавливает запуск, если
dependencies или Kafka отключены, dev auth bypass включён либо private dependency boundary не
production-ready. Disabled boundaries предназначены для изолированной локальной разработки или
тестов.

## Наблюдаемость и эксплуатация

Actuator предоставляет `health`, `info` и Prometheus metrics. Логи используют ECS format, а tracing
sampling задаётся `INVENTORY_TRACING_SAMPLING_PROBABILITY`. Перед retry effect исследуйте delayed
publication или inbox work по local attempt, retry, outbox и correlation records.

## Локальная разработка

Из корня репозитория:

~~~bash
./gradlew :services:inventory-service:bootRun
~~~

Используйте disabled dependencies и Kafka только для изолированной разработки или тестов. Live
integrations используют private URLs и service credentials; interactive clients используют gateway.

## Известные ограничения аудита

Production fail-fast checks применяются, только когда активный Spring profile — `prod` или
`production`. Deployment configuration обязана выбрать один из этих profiles; эта
documentation-only правка не делает non-production profile безопасным live runtime.

## Исполняемый parity маршрутов и безопасности

[`InventoryRouteSecurityParityTest`](src/test/java/dev/buhanzaz/rwms/inventory/config/InventoryRouteSecurityParityTest.java)
разбирает канонический OpenAPI-инвентарь операций, находит все активные mapping из
`@RestController` через merged-аннотации Spring и требует точного равенства множеств method/path без
дубликатов. Нормализуются только имена placeholders и необязательный завершающий slash. Этот же тест
исполняет реальную owner security filter chain с отключённым dev auth bypass; каждая каноническая
Bearer-операция должна отклонить неаутентифицированный запрос до dispatch в controller.

Запуск focused gate из корня репозитория:

~~~bash
bash ./gradlew :services:inventory-service:test --tests 'dev.buhanzaz.rwms.inventory.config.InventoryRouteSecurityParityTest'
~~~

## Правила безопасного изменения

- Меняйте OpenAPI/AsyncAPI contracts и всех затронутых producers/consumers одновременно.
- Оставляйте session, finding, completion и publication orchestration в этом сервисе, а не в UI или
  gateway saga.
- Сохраняйте expected-version fencing, stable idempotency keys, inbox/outbox deduplication и durable
  retry/recovery state.
- Добавляйте неизменяемые service-local Flyway migrations и валидируйте затронутые JPA mappings.
- Тестируйте focused authorization, conflict, dependency timeout/retry, Kafka replay и recovery paths.

## Основные исходные материалы

- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryApplicationService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventorySessionService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryFindingService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryPlanningService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryCompletionService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryPublicationService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryIdempotencyService.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/eventing/InventoryEventStore.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/eventing/InventoryOutboxStore.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/eventing/InventoryAssetInboxStore.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/persistence/InventoryPostgresJsonbCanonicalizer.java`
- `src/main/java/dev/buhanzaz/rwms/inventory/security/InventoryAuthorizer.java`
