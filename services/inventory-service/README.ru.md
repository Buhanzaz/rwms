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

`POST /api/inventory/v1/sessions/{inventoryId}/refresh` — MANAGE-команда восстановления активной
сессии, в которой устарел живой состав бытовок или производная сверка. Она проверяет переданную
ревизию сессии до свежего read-only asset capture и повторно под локальной блокировкой применения.
Удалённое чтение capture завершается до idempotent local transaction, которая проводит приходы,
уходы и текущие snapshots через штатный membership journal. За последующим уходом из реестра
следует только автоматическая популяция `EXPECTED`. Явно найденный `ADDED_NEW`, `ADDED_USED` или
`UNEXPECTED_EXISTING` finding остаётся в таблице инвентаризации и составе итогового плана, даже если
следующий capture его не вернул: фильтр eligibility не может стереть физическое наблюдение оператора.
Сохранённые findings, inspection evidence, ссылки на media и история перемещений остаются без
изменений; перезапускается только производная сверка мебели, а существующий итоговый план помечается устаревшим. При построении
сверки читается активное клиентское наблюдение `quantity`, а `observedQuantity` сохраняется для
совместимости с уже записанными фактами сверки. Публичный refresh-контракт не добавляет поле схемы.

Media finding — неизменяемое evidence конкретной ревизии. Workflow, который повышает ревизию
finding без редактирования фотографий — сверка membership/current snapshot,
привязка созданного source asset, разрешение конфликта, подтверждение мебели или закрытие owner proof — в той же
local transaction переносит в новую ревизию точный набор media из непосредственно предыдущей ревизии.
Сохранение осмотра остаётся отдельным случаем: оно записывает ровно переданный набор, поэтому намеренно
пустой набор остаётся пустым. Миграция
[`V18__carry_forward_inventory_finding_media.sql`](src/main/resources/db/migration/V18__carry_forward_inventory_finding_media.sql)
исправляет прежний сдвиг ревизий только когда ненулевое cover photo доказывает, что у текущего
finding должны быть media, у текущей ревизии их нет, а предыдущий точный набор содержит это cover.
Она копирует целиком самый новый такой набор без union, удаления, перезаписи media objects или
изменения версии finding.

Интерактивные panel и Android-клиенты обращаются к этому namespace только через публичный маршрут
`/api/inventory/**` в `api-gateway-service`. Им нельзя напрямую вызывать host этого модуля или
любой private dependency route.

У inventory нет публичного client route для asset, warehouse, maintenance или media mutations. Он
вызывает их узкие private boundaries с service credentials после того, как собственные local workflow
records стали durable.

Подготовка итогового плана использует read-only preflight публикации maintenance. Inventory
повторяет этот запрос не более одного раза, с теми же body, idempotency key и service token, только
после transport failure либо HTTP `502`, `503` или `504`. Validation, conflict, authentication,
прочие server и malformed-response failures остаются fail-closed и не повторяются.

## Авторитетный итог завершённой инвентаризации и восстановление из истории

Каждый finding завершённого итогового плана имеет durable publication intent, включая findings без
ремонтных работ. После успешной сверки мебели recovery scheduler сначала применяет точный итог
через asset-service: отсутствие работ означает `FREE`, обычные работы — `REPAIR`, а явный выбор
капремонта — `CAPITAL_REPAIR`. Asset-service единолично атомарно освобождает или замещает active
order-unit reservations, operation leases, presentation holds и transfer state. Их история
сохраняется, а терминальные бытовки `LOST` и `WRITTEN_OFF` отклоняются.
Каждый publication intent итогового плана также замораживает точное наблюдение паспорта finding при
завершении. Revision исходного finding и asset должны совпадать с immutable entry итогового плана.
`ABSENT` отправляется как явная команда сохранить текущий паспорт; `PRESENT` нормализуется в шесть
asset-owned facets: тип аренды, габариты, отделку, категорию, разделённые по запятым уникальные
характеристики и nullable-линолеум. Отсутствующее поле характеристик становится точным пустым
набором, а отсутствующий или не-boolean линолеум — `null`. Нормализованное наблюдение и его
канонический SHA-256 входят в asset outcome и поэтому в durable fingerprint запроса публикации.
См.
[`InventoryPublicationIntent`](src/main/java/dev/buhanzaz/rwms/inventory/domain/InventoryPublicationIntent.java)
и
[`InventoryPublicationService`](src/main/java/dev/buhanzaz/rwms/inventory/service/InventoryPublicationService.java).
Только после сохранения этого результата inventory отправляет в media-service точные `IMAGE`
references ревизии finding
из итогового плана, используя тот же idempotency key попытки публикации и узкий токен
`media.inventory`. Media-service делает папку завершённой инвентаризации текущим набором фото
бытовки; прежние папки приёмки или инвентаризаций остаются отдельным историческим evidence и не
смешиваются с текущим набором. Для finding без изображений этот эффект пропускается. Затем
inventory передаёт весь неизменяемый итоговый план в logistics-service с одним стабильным для
плана key и точным токеном `logistics.inventory`. Logistics замещает active rental, shipment,
transfer и driver-task state перечисленных бытовок, сохраняя audit rows. В конце finding с работами
передаётся в maintenance вместе с эффективной версией asset и временем завершения: maintenance
авторитетно замещает прежние active ремонтные работы перед созданием требуемого ремонта или
капремонта. Finding без работ идёт в отдельную no-work границу maintenance, которая замещает active
estimates, repairs, leases и tasks, а не только меняет статус бытовки. No-work payload явно
проецируется из результата asset и содержит только поля maintenance-контракта; asset-only поля
наблюдения паспорта и его hash через эту границу не передаются. Отказ любого обязательного
эффекта не позволяет отметить публикацию успешной, поэтому recovery повторно подтверждает asset,
media, общий эффект logistics и maintenance именно в таком порядке. Этот поток никогда не удаляет
фотографии, evidence finding, доменную историю и объекты media-service/MinIO.

`POST /api/inventory/v1/sessions/{inventoryId}/outcome/recalculate` — MANAGE-команда восстановления
для строки истории завершённых инвентаризаций. Она проверяет точные revision session, version
итогового плана и SHA-256 и возвращает `202` после восстановления durable работы. Если устаревшая
автоматическая projection состава исключила осмотренное явное наблюдение, команда восстанавливает
finding из подтверждённого человеком inspection baseline, добавляет его в строго следующую
completed-версию плана и заменяет замороженную статистику. Существующие entries, выборы менеджера,
даты и порядок не меняются; только восстановленные работы capacity-schedule-ятся после них.
Completed inventory авторитетна, поэтому correction не требует maintenance preflight и не делает
remote I/O или downstream mutation. Media owner authorization завершённой сессии остаётся закрытой, а finding-owned факт
восстановления продвигает checkpoints потребителей. Затем команда создаёт недостающие intents для
статуса/бытовок без работ и повторно ставит в очередь каждую публикацию исправленного плана,
включая ранее помеченные successful: старые версии runtime
не могут доказать выполнение более новых logistics, media и no-work эффектов. Она также делает
незавершённую сверку мебели немедленно доступной для повтора. Attempts и прежние результаты
maintenance остаются append-only audit evidence; schedulers применяют очередь со стабильной
idempotency. Поэтому compatibility-поле `preservedSucceededPublicationCount` для текущей
авторитетной команды равно нулю. Миграция
[`V19__authoritative_inventory_outcome_recovery.sql`](src/main/resources/db/migration/V19__authoritative_inventory_outcome_recovery.sql)
добавляет желаемый статус и сохранённый asset result без удаления прежней истории публикации.
Миграция
[`V23__freeze_inventory_outcome_passport_observation.sql`](src/main/resources/db/migration/V23__freeze_inventory_outcome_passport_observation.sql)
backfill-ит замороженное наблюдение только при совпадении source revision публикации, entry
итогового плана и asset identity; несовпавшие legacy rows получают явный `ABSENT`. Recovery из
истории сохраняет эту frozen column вместо повторного чтения finding, увеличивает durable
generation повторного применения и тем самым получает новый owner-effect idempotency key. Старые
успешные receipts по-прежнему могут replay-ить неизменённый response, а новая generation отправляет
запрос с паспортом. Миграция
[`V24__restore_explicit_inventory_observations.sql`](src/main/resources/db/migration/V24__restore_explicit_inventory_observations.sql)
разрешает audit event восстановления без перезаписи существующих findings, plans или publication rows.

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

`InventoryApplicationService` — стабильный compatibility facade над use-case collaborators. Он
сохраняет поверхность вызовов controllers, inbox и schedulers, но делегирует каждое решение одному
связному use-case service:

| Компонент | Владеющая ответственность |
| --- | --- |
| `InventorySessionService` | Запуск session, ручной refresh состава и durable recovery освобождения capture |
| `InventoryReadService` | Чтения sessions, findings и statistics |
| `InventoryFindingService` | Membership reconciliation, разрешение номера, создание source и изменение inspection |
| `InventoryFindingValidationService` | Fresh-валидация finding, media и plan для точных владельцев |
| `InventoryReviewService` | Registry/furniture review и их durable recovery |
| `InventoryPlanningService` | Warehouse planning settings и immutable versioned final plans |
| `InventoryCompletionService` | Preview, terminal completion/cancellation и post-commit intents |
| `InventoryStatisticsService` | Расчёт frozen и aggregate statistics |
| `InventoryPublicationService` | Publication, retry, closure и recovery |
| `CompletedInventoryPlanCorrectionService` | Восстановление исключённых явных наблюдений в completed plan и создание строгой следующей версии |
| `InventoryOutcomeRecoveryService` | Восстановление авторитетной asset/maintenance работы из завершённой истории |
| `InventoryProjectionService` | API-проекции над owner-local state |

`InventoryFindingPersistenceService` инкапсулирует только source attachments, media
references/facts, точный carry-forward для non-media ревизий и persistence frozen plan. Узкие
abstract workflow supports открывают только repositories и ports, необходимые их единственному
concrete use case; Spring beans из них не создаются. Общий `InventoryTechnicalRuntimeSupport`
содержит только JSON/canonical-hash,
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
`production`. Deployment configuration обязана выбрать один из этих profiles; dependency retry
policy не делает non-production profile безопасным live runtime.

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
