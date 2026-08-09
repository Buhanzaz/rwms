# Сервис активов RWMS

[English version](README.md)

## Назначение и владение

`asset-service` владеет кабинами и rental items, их оборудованием/содержимым и статусом, каталогом
и остатками оборудования, operation leases, presentation holds и asset-side inventory source
records. Это источник истины для этих переходов; maintenance, logistics и inventory используют
узкие контракты, а не записывают asset database и не восстанавливают изменяемое состояние из Kafka.

Авторитетные HTTP- и event-контракты находятся в
[`contracts/openapi/asset-service.yaml`](../../contracts/openapi/asset-service.yaml) и
[`contracts/events/asset-events.yaml`](../../contracts/events/asset-events.yaml).
[`docs/project-knowledge/domain-logic.md`](../../docs/project-knowledge/domain-logic.md) — индекс:
любое правило при необходимости проверяется по текущим контрактам и коду сервиса.

## Публичная и внутренняя HTTP-граница

Публичные пользовательские операции версионированы под `/api/asset/v1/**`. Они покрывают rental
items, настройки кабин и classifiers, equipment, HTML import, administrative corrections, event
streaming и operator-reviewed outbox recovery. Изменяющие команды используют определённые
контрактом expected-version и idempotency fields там, где это предусмотрено; вызывающая сторона
должна обработать канонический конфликт `409`, а не отправлять изменившийся повтор.

Интерактивные panel и Android-клиенты обращаются к этому namespace только через публичный маршрут
`/api/asset/**` в `api-gateway-service`. Им нельзя напрямую вызывать host этого модуля или маршрут
`/api/internal/**`.

Внутренние границы намеренно ограничены:

- `/api/internal/asset/v1/maintenance/**` — maintenance leases, snapshots, fenced effects и
  furniture custody;
- `/api/internal/asset/v1/logistics/**` — logistics leases, holds, reservations, movement plans и
  asset effects; и
- `/api/internal/asset/v1/inventory/**` — inventory capture, validation, source-asset и
  furniture-reconciliation work.

Внутренние вызывающие стороны используют service credentials, а не проброшенный пользовательский
token. Security chain требует точные identity сервиса и single-purpose scope для каждого namespace.

В logistics namespace маршрут `/cabin-facets` возвращает availability-backed
значения type, finish, dimension, category и characteristics, а также точные
type-to-dimension relations. `/cabin-catalog` — отдельный bounded read-only
facts lookup по warehouse и обязательному query. Он ищет number, type, finish,
dimension, category, characteristics и linoleum среди всех current statuses;
он не проверяет availability и не создаёт, не продлевает и не освобождает hold.

## Внутренняя структура приложения

`AssetService` — стабильный controller-facing фасад с пятью точными application
collaborators. Он сохраняет публичные transaction boundaries, разделяя каждый
независимо изменяющийся workflow:

| Семейство collaborators | Владеющая ответственность |
| --- | --- |
| `AssetRentalItemService` и `AssetRentalProjectionService` | Команды rental item, канонические status mutations, notes и read/event projection |
| `AssetLogisticsService` | Logistics leases, rental effects, reservations, shipment holds и task-bound equipment moves |
| `AssetEquipmentService` | Equipment availability, stock commands, general holds и physical transfers |
| `AssetMaintenanceService` | Maintenance leases, fenced rental effects, characteristics и вход в furniture custody |
| `AssetClassifierService` | Команды classifier aggregate и event facts |
| Сервисы lease, catalog, ledger и hold | Точные locking, fencing, catalog binding, physical balances и expiry allocations |
| `AssetJsonCodec` и `AssetBalanceRow` | Только canonical JSON/idempotency decoding и immutable carrier физического остатка |
| `InventoryAssetService` | Стабильный private inventory facade над capture, projection, furniture reconciliation и source creation |
| Сервисы inventory capture/projection/furniture/source | Lifecycle замороженного capture, current validation reads, проверенные абсолютные furniture counts и permanent source identity |
| `InventoryAssetSnapshotTransaction` и `InventoryAssetCodec` | Только repeatable-read snapshot boundary и canonical inventory JSON/hash mechanics |
| `RentalItemHtmlImportService` | Стабильный HTML-import фасад над read projection, plan decisions, commit recovery и media recovery |
| Сервисы HTML-import projection, plan, commit и media | Raw intake/read mapping, durable row decisions, materialization и retry/replace/skip workflows |
| `RentalItemHtmlImportCodec` | Только canonical persisted JSON, bounded hashes, stable command keys и redaction media keys |
| `PropertyDispositionService` | Стабильный private facade над eligibility snapshots, prepared fences и approved terminal effects |
| Сервисы disposition snapshot, preparation и application | Три внешне видимые asset-side фазы одобренного maintenance decision |
| Сервисы disposition ledger, eligibility, decision-store и codec | Physical balances/holds/movements, lease/reservation proofs, durable replay/audit и canonical JSON |

У фасада нет repositories или transport client. Collaborators не ссылаются на
него обратно, не используют inherited dependency surface или общий universal
context; dependency graph ацикличен, а direct surface не превышает 15.

Inventory facade следует тому же направлению зависимостей. Команды capture и
source могут использовать read projection, а furniture reconciliation является
независимым владельцем locks/facts. Только ветки projection и furniture
используют изолированную repeatable-read snapshot transaction; ни одна не
ссылается обратно на facade.

У HTML-import фасада четыре точных collaborator. Projection может использовать
plan mapping, commit — projection и plan, а media recovery — commit и
projection; все три зависят только вниз от JSON codec. Ни один выделенный
владелец не ссылается обратно на facade и не использует inherited dependency
context.

В property disposition decision-ID lock и durable replay остаются в decision
store, а только ledger владеет physical balance, hold и movement locks.
Preparation выполняет существующую warehouse check до своей local transaction;
application и snapshot используют общие eligibility и ledger leaves, не
вызывая друг друга или facade.

## Безопасность, изоляция складов и fencing

HTTP-слой — stateless OAuth2/JWT resource server. `AssetAuthorizer` проверяет пользовательские
read/write scope, уровень доступа к складу и более узкие administrator-only операции. Dev auth
bypass действует только в профиле `dev` и отключён при активном production profile.

Данные asset остаются в собственной PostgreSQL database; недопустимы cross-service JPA entity,
shared table и cross-database join. Warehouse registry читается через private client boundary.
Входящее physical custody требует warehouse admission, а readiness draining-склада сверяется по
local asset facts.

Lease, hold, reservation и effect endpoints передают stable operation identities и
contract-defined fencing values, нужные для owner-side обработки retry и uncertain response. Нельзя
превращать client retry в un-fenced direct update.

`POST /api/internal/asset/v1/logistics/cabin-searches` требует
`Idempotency-Key`. Существующий `AssetIdempotencyStore` atomically связывает
его с точным subject `service:logistics-service`, scope
`logistics.cabin-search` и SHA-256 fingerprint полного request. Исходный
status-200 body замораживается в той же transaction, что и presentation holds.
Идентичный retry возвращает этот body с `Idempotency-Replayed: true`; тот же key
с другим request возвращает `409`, а advisory lock сериализует concurrent
retries. Используется существующая asset idempotency schema, новая asset Flyway
migration не добавляется.

Cabin search фильтрует type, finish, dimension и category точно,
characteristics — по normalized case-insensitive text, а linoleum — по boolean
value. Missing или null `resultMode` означает replacement-safe `REPLACE`;
`APPEND` действует только при явном значении. Одна transaction выполняет expiry
просроченных holds, lock inquiry scope, расчёт точного результата, mutation
holds и freeze idempotent response. REPLACE освобождает каждый старый inquiry
hold, не выбранный результатом, включая пустой результат. APPEND исключает уже
held cabins из новых совпадений, сохраняет и продлевает existing inquiry holds
и добавляет только возвращённые новые cabins. TTL expiry остаётся во владении
asset.

## Хранение, события и восстановление

Flyway-миграции в `src/main/resources/db/migration/` — единственный источник схемы. JPA использует
`ddl-auto=validate`; Hibernate schema mutation и cross-service foreign keys запрещены.

Переход asset записывает local fact и transactional outbox в owning database transaction. Kafka —
at-least-once transport: outbox store арендует один ordered aggregate head, relay проверяет и
подтверждает stored envelope, а terminal failures проходят sanitized DLT path. Сервис хранит local
inbox/replay и invalidation handling, а не считает Kafka источником изменяемого asset state. Terminal
outbox recovery — это administrator-reviewed, checksum-validated requeue существующего fact, а не
reconstruction события.

`ASSET_KAFKA_ENABLED` управляет Kafka relay и consumer beans. Он может быть `false` только в явном
профиле `dev` или `test`; любой другой профиль отклоняет startup при выключенной доставке. Startup
fence также требует точный asset topic allow-list, не-loopback brokers, выключенный topic
auto-creation, синхронную идемпотентную публикацию с `acks=all`, publish wait короче outbox lease, а
также outbox, sanitized-DLT и output-binding beans. Production profile имеет приоритет при
объединении с local profile. `ASSET_WAREHOUSE_REGISTRY_ENABLED` и `ASSET_MEDIA_IMPORT_ENABLED`
управляют private warehouse и media boundaries, нужными для live asset work.

## Runtime-конфигурация

Порт HTTP по умолчанию — `8086`. Настройте asset database, `AUTH_ISSUER`, CORS origin, а для live
private integrations — token URI, client ID/secret и internal base URLs из
`src/main/resources/application.yaml`. Нельзя коммитить credentials или использовать public gateway
для service-to-service call.

Production validator отклоняет отключённый warehouse-registry или media-import client. У base
profile нет Kafka-enable fallback, поэтому managed environments должны явно установить
`ASSET_KAFKA_ENABLED=true`. Локальная изолированная разработка может использовать disabled
dependency boundaries только с явным профилем `dev` или `test`.

## Наблюдаемость и эксплуатация

Actuator предоставляет `health`, `info` и Prometheus metrics. Логи используют ECS format, а tracing
sampling задаётся `ASSET_TRACING_SAMPLING_PROBABILITY`. Перед retry или correction domain data
исследуйте delayed work по local outbox, inbox, recovery state и correlation ID.

Существующие gauges `rwms.asset.outbox.backlog`,
`rwms.asset.outbox.oldest.age.seconds` и `rwms.asset.outbox.terminal` показывают
pending/in-flight age и reviewed DLT/quarantine work. Это read-only observations:
они не дренируют и не переписывают outbox.

## Локальная разработка

Из корня репозитория:

~~~bash
bash ./gradlew :services:asset-service:bootRun --args='--spring.profiles.active=dev'
~~~

Используйте disabled dependency boundaries только для изолированной разработки или тестов. Live
integrations используют private URLs и service credentials; browser callers используют gateway.

## Production Kafka safety

Non-local startup работает fail-closed: отсутствующее enablement, неодобренный destination, blank
или loopback brokers, topic auto-creation, asynchronous или non-idempotent producer settings,
небезопасный publish timeout либо отсутствующий relay/binding bean прерывают startup до того, как
приложение считается ready. Backlog draining остаётся отдельно разрешаемым runtime action и не
выполняется startup validator.

## Правила безопасного изменения

- Меняйте OpenAPI/AsyncAPI contracts и всех затронутых producers/consumers одновременно.
- Оставляйте cabin, equipment, hold и lease transitions в этом сервисе, вместе с contract fencing и
  stable idempotency identities.
- Добавляйте неизменяемые service-local Flyway migrations и валидируйте затронутые JPA mappings.
- Сохраняйте outbox/inbox deduplication, aggregate ordering и administrator-reviewed recovery.
- Тестируйте focused public/private authorization, conflict, timeout/retry и replay paths.

## Основные исходные материалы

- `src/main/java/dev/buhanzaz/rwms/asset/service/AssetService.java`
- `src/main/java/dev/buhanzaz/rwms/asset/service/PresentationHoldService.java`
- `src/main/java/dev/buhanzaz/rwms/asset/service/AssetIdempotencyStore.java`
- `src/main/java/dev/buhanzaz/rwms/asset/service/InventoryAssetService.java`
- `src/main/java/dev/buhanzaz/rwms/asset/service/RentalItemHtmlImportService.java`
- `src/main/java/dev/buhanzaz/rwms/asset/disposition/PropertyDispositionService.java`
- `src/main/java/dev/buhanzaz/rwms/asset/eventing/AssetEventStore.java`
- `src/main/java/dev/buhanzaz/rwms/asset/eventing/AssetKafkaOutboxStore.java`
- `src/main/java/dev/buhanzaz/rwms/asset/eventing/AssetEventingMetrics.java`
- `src/main/java/dev/buhanzaz/rwms/asset/config/AssetProductionSafetyValidator.java`
- `src/main/java/dev/buhanzaz/rwms/asset/security/AssetAuthorizer.java`
