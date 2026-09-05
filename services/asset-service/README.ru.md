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
- `/api/internal/asset/v1/inventory/**` — inventory capture, validation, source-asset,
  furniture-reconciliation и completed-outcome work.

Внутренние вызывающие стороны используют service credentials, а не проброшенный пользовательский
token. Security chain требует точные identity сервиса и single-purpose scope для каждого namespace.

Для импортированной исторической отгрузки аренды maintenance может вызвать только fenced action
`CLOSE_FOR_HISTORICAL_SHIPMENT` под существующим lease `MAINTENANCE_REPAIR`. Политика asset
допускает его только из `REPAIR`, `CAPITAL_REPAIR` или `WAITING_REPAIR_CHECK` и переводит бытовку в
`FREE`; `IN_TRANSFER`, terminal и несвязанные статусы остаются конфликтом. Затем logistics получает
и применяет собственный обычный shipment lease, а не записывает состояние бытовки напрямую.

В logistics namespace маршрут `/cabin-facets` возвращает availability-backed
значения type, finish, dimension, category и characteristics, а также точные
type-to-dimension relations. `/cabin-catalog` — отдельный bounded read-only
facts lookup по warehouse и обязательному query. Он ищет number, type, finish,
dimension, category, characteristics и linoleum среди всех current statuses;
он не проверяет availability и не создаёт, не продлевает и не освобождает hold.
`/cabin-pricing-catalog` возвращает актуальные глобальные UUID типов TYPE и
категорий CATEGORY, включая отключённые и ещё не используемые значения;
удалённые значения исчезают из результата. `/cabin-pricing-references` возвращает
точные UUID типа/категории и версию для 1–100 разных бытовок одного склада.
Отсутствующая бытовка или другой склад отклоняют весь запрос с 404. Эти чтения
не раскрывают паспорт, не меняют резервы и не владеют тарифами аренды логистики.
`/equipment-pricing-catalog` возвращает актуальные UUID и названия FURNITURE, включая отключённые
позиции и позиции без остатков, но исключая другие категории оборудования. Требуются точные
credentials logistics-service; месячная цена одной единицы остаётся в ведении логистики.
`/customer-cabin-catalog` — customer-booking read для одного обязательного
`holdScopeId`. Он возвращает только бытовки `FREE` без active order reservation
или operation lease, исключает live holds других scopes и сохраняет видимыми
собственные live holds запрашивающего inquiry. Фильтры type, finish, dimensions,
category и linoleum используют normalized exact matching; все повторяющиеся
characteristics должны присутствовать. Результаты сохраняют asset-owned stable
order и разбиваются на страницы только после проверок availability и фильтров.
Просроченные holds переводятся в expired во время этого чтения, но live hold не
создаётся, не продлевается и не освобождается. Команды presentation и order
reservation от клиента сохраняют `CUSTOMER` как audit role; Flyway V41 расширяет
только соответствующие role constraints.
Закрытые команды освобождения бытовок заказа и замены мебели пустым составом также принимают
audit role `LOGISTICS_SERVICE` для автоматического освобождения. Это не публичная JWT-роль и
не разрешение создавать резервы бытовок/мебели; V49 расширяет только constraint автора освобождения.
`/rental-items/{id}/photo-presentation-snapshot` — отдельное least-privilege чтение для одного
logistics-owned публичного photo snapshot. Оно возвращает identity/version/warehouse fencing,
номер, габариты, отделку, категорию, упорядоченные названия характеристик и nullable-линолеум;
status, rental type, passport JSON, комментарии, tags и equipment исключены.

## Глобальные цвета статусов бытовок

`GET/PUT /api/asset/v1/cabin-settings/status-colors` хранит одну палитру для всех
складов; начальные цвета создаёт Flyway V48. Чтение требует интерактивного USER
с `rwms.read`, замена — `rwms.write` и роль SYSTEM_ADMIN или WMS_ADMIN. Для
каждого канонического статуса обязателен hex-цвет. Полная замена защищена
`expectedVersion` и JPA optimistic locking; устаревшие и конкурирующие изменения
возвращают 409. Цвета не меняют переходы статусов, доступность и факты событий.

## Создание бытовки с обязательными фотографиями

`POST /api/asset/v1/rental-item-creation-intents` — команда интерактивного создания бытовки, когда
вызывающая сторона выбрала обязательные исходные фотографии. В одной asset-транзакции она создаёт
обычный rental item в статусе `FREE`, durable pending intent и принадлежащую asset-сервису
operation lease `CABIN_CREATION`. Эта lease исключает новую бытовку из availability до
терминального состояния intent; существующий endpoint создания rental item без photo intent
остаётся доступным и не меняется. Повторы создания используют пользовательский
`Idempotency-Key` и обязаны передавать тот же payload.

Запрос содержит упорядоченный manifest из 1–20 фотографий: индекс, SHA-256, content type и длину.
Intent хранит этот manifest, стабильный upload-command UUID для каждой позиции, UUID gallery
folder и канонический hash manifest; исходные имена файлов и media bytes в нём не сохраняются.
Pending intents можно получить списком по авторизованному складу или прочитать по ID, поэтому
прерванный клиент восстанавливает точный состав оставшейся загрузки.

Completion использует `expectedVersion` и стабильный idempotency key. До освобождения creation
lease asset-service читает
`POST /api/internal/media/v1/assets/cabin-creation-snapshots` под своей service identity и точным
scope `media.asset`. Media-вызов выполняется вне asset database transaction. Финальная
asset-транзакция повторно проверяет intent и принимает только ту же бытовку, склад и active folder
с точным запланированным количеством и manifest текущих `READY` gallery photos и текущей
обложкой. Ошибка работает fail-closed и оставляет lease активной. Явный abandon также использует
`expectedVersion`: он переводит незавершённую бытовку из `FREE` в существующий неарендный статус
`WAREHOUSE`, выпускает обычные rental-item/lease events, отмечает intent как abandoned и только
после этого освобождает creation lease этого intent. Flyway V45 владеет таблицами intent и
упорядоченного manifest, constraints и lookup indexes.

## Мебель заказа и замена бытовок

`equipment_catalog_item.maximum_per_cabin` — nullable asset-owned лимит одной позиции оборудования
в одной бытовке, добавленный Flyway V36; `null` сохраняет прежнее поведение без лимита. Команды
оборудования заказа
получают требования, сгруппированные по активным бытовкам заказа, проверяют каждый заданный лимит,
агрегируют тот же order-wide reservation и сериализуют состав бытовок/мебели общим advisory lock
заказа. Общая availability вычитает только часть reservations, ещё не обеспеченную физической
мебелью в активных бытовках соответствующего заказа, поэтому booked contents не учитываются дважды.

Конвертация представления может передавать авторитетный полный post-conversion состав бытовок и их
требования. Она материализует reservations выбранных бытовок и заменяет order-wide equipment
reservation в одной transaction; конфликт остатка или лимита оставляет presentation holds
активными. Snapshots `AvailableCabin` содержат физические `contents`, поэтому consumer
представления может прибавить к общей availability только содержимое действительно выбранных held
бытовок, но не всех альтернатив.
`ReplacePresentationHoldsResponse.cabins` фиксирует эти snapshots после canonical locks и в порядке
запрошенных бытовок. Пока presentation hold активен, direct transfer и команды acquire/execute
перемещения оборудования отклоняют эту бытовку как source или известный target, поэтому
опубликованные contents не меняются до conversion или release.

Стандартный equipment movement plan заказа сначала использует физический избыток другой активной
бытовки того же заказа на складе с location `CABIN_NON_RENTED`, а затем legacy allocatable sources.
При acquire такой линии вместе передаются `orderId`, `targetRentalItemId` и авторитетная коллекция
`units`. Существующий allocation hold хранит этот nullable order/target/unit context и optional
provenance освобождённой source reservation для атомарной замены; поля добавлены Flyway V37. Общий
порядок order/equipment/balance locks ограничивает hold одновременно избытком источника и дефицитом
цели с учётом прежних active source и inbound holds. Содержимое `CABIN_RENTED` может обеспечивать
агрегированный reserve, но никогда не предлагается как складской источник перемещения.

`POST /api/internal/asset/v1/logistics/orders/{orderId}/units/replace` атомарно заменяет одну или
несколько упорядоченных пар old/new внутри того же заказа. Команда проверяет полный post-swap
состав, требует, чтобы каждая старая бытовка всё ещё имела статус `BOOKED`, конвертирует выбранные
presentation holds и освобождает все невыбранные альтернативы того же scope, глобально блокирует
все старые источники мебели и заранее создаёт существующие reservations типа
`LOGISTICS_EQUIPMENT_MOVEMENT` до освобождения любой старой reservation заказа. Каждая такая линия
durable хранит post-swap состав и освобождённую старую reservation; обычный acquire может только
повторно получить её, а execute заново проверяет released source, active target, склад и order-wide
reserve мебели. Ошибка в любой паре откатывает весь batch и все переходы presentation holds. Старая
бытовка остаётся непредлагаемой в статусе `BOOKED` без активной reservation заказа. Live hold
перемещения мебели блокирует maintenance acquisition до выполнения существующего movement
(истёкший hold не считается live); order-wide furniture reservation при замене не освобождается.

## Авторитет завершённой инвентаризации

`PUT /api/internal/asset/v1/inventory/outcomes/{inventoryId}/findings/{findingId}` принимает только
точный credential `inventory-service` и immutable evidence завершённого плана. Для найденной
нетерминальной бытовки последняя завершённая инвентаризация является истиной: первый или более новый
source `FREE`, `REPAIR`, `CAPITAL_REPAIR` или `RENTED` освобождает active operation leases, order-unit
reservations и presentation holds, очищает transfer state и затем становится текущим статусом
бытовки. Rows и обычные lease/asset events сохраняются; media metadata и object storage не входят в
эту команду.

`RENTED` допустим только с обязательным (в том числе пустым) `shipmentContents` отгрузки. Каждая
уникальная строка обязана ссылаться на active catalog row `FURNITURE` точной версии. Под cabin locks
asset полностью заменяет все equipment buckets бытовки этими количествами в `CABIN_RENTED`;
пропущенное прежнее наполнение становится нулевым. Доступность stock не читается, а `STOCK` не
меняется. Flyway V40 расширяет hash permanent outcome watermark, поэтому exact replay и строго
более новая correction плана fence-ят payload мебели вместе со статусом и evidence паспорта.

Та же команда передаёт замороженное наблюдение паспорта finding. `ABSENT` сохраняет текущий
паспорт. `PRESENT` авторитетно заменяет тип бытовки, совместимые габариты, отделку, категорию,
характеристики и nullable-линолеум, даже если запрошенный статус уже совпадает. Имена должны точно
разрешаться в active catalog rows asset-service; новые значения каталога не создаются. Каждая
строка характеристик и каждый элемент массива делятся по запятым, обрезаются и дедуплицируются;
отсутствующее поле характеристик очищает набор, а отсутствующий или не-boolean линолеум очищает
nullable-значение. Catalog resolution выполняется до освобождения любых bindings, поэтому
неизвестное, inactive или несовместимое значение откатывает всю команду. Relation rows
характеристик, версия rental item, обычные passport/status events, watermark и receipt фиксируются
в одной asset-owned transaction. См. канонический
[`InventoryOutcomeRequest`](../../contracts/openapi/asset-service.yaml) и
[`InventoryAssetOutcomeService`](src/main/java/dev/buhanzaz/rwms/asset/service/InventoryAssetOutcomeService.java).

Flyway V38 добавляет permanent successful idempotency receipt и per-cabin completed-at watermark.
Тот же key и request возвращают замороженный результат. Новый key для того же latest final-plan
finding повторно применяет статус, reservations, holds и transfer state. При том же completion time
сервис также принимает строго большую версию final plan, только если inventory и finding IDs
совпадают с текущим watermark; меньшая версия или drift hash при той же версии возвращают `409`.
Повторное применение или исправление плана освобождает любую active operation lease для `FREE`. Для
`REPAIR` и `CAPITAL_REPAIR` сохраняется только `MAINTENANCE_REPAIR`, которая уже может принадлежать
ремонту, созданному из этого finding; устаревшие logistics или rental leases освобождаются. Строго
более старая завершённая инвентаризация, другой equal-time source, `LOST`, `WRITTEN_OFF` и неверный
warehouse всегда отклоняются без частичного статуса или receipt.

Flyway
[`V39__inventory_outcome_passport_watermark.sql`](src/main/resources/db/migration/V39__inventory_outcome_passport_watermark.sql)
добавляет nullable hash наблюдения паспорта в watermark, не меняя V38 receipt или response shape.
`NULL` обозначает status-only watermark до V39 и разрешает единственное принятие только при полном
совпадении исходных source/status полей. Успешное повторное применение сохраняет hash; последующий
equal-time payload drift становится конфликтом.

Furniture reconciliation сохраняет `expectedSnapshotSha256` как обязательное immutable source
evidence в permanent request identity, но не сравнивает его с новым full snapshot: non-terminal
status/version бытовки и physical quantities могут штатно измениться до reconciliation. Под
текущими locks она всё ещё требует, чтобы каждая выбранная бытовка существовала на указанном
warehouse и была non-terminal, cabin scope совпадал точно, а active FURNITURE catalog set/version
оставался неизменным. Она завершает конфликтующие cabin leases, order и presentation bindings,
order-wide furniture reservations и live allocation holds, сохраняет их audit rows/events и затем
перезаписывает текущие quantities проверенными абсолютными counts. Повтор release maintenance или
logistics lease может прочитать тот же terminal lease `RELEASED` или `EXPIRED` того же owner с
исходным fencing token; неверный owner или token остаётся fenced. Logistics release сначала
материализует естественный expiry под тем же rental-item/lease lock, поэтому истёкшая, но ещё не
наблюдавшаяся row возвращается как `EXPIRED`, а не как ложный stale-lease conflict.

## Внутренняя структура приложения

`AssetService` — стабильный controller-facing фасад с пятью точными application
collaborators. Он сохраняет публичные transaction boundaries, разделяя каждый
независимо изменяющийся workflow:

| Семейство collaborators | Владеющая ответственность |
| --- | --- |
| `AssetRentalItemService` и `AssetRentalProjectionService` | Команды rental item, канонические status mutations, notes и read/event projection |
| `AssetLogisticsService` | Узкие rental/photo-presentation reads, logistics leases, rental effects, reservations, shipment holds и task-bound equipment moves |
| `AssetEquipmentService` | Equipment availability, stock commands, general holds и physical transfers |
| `AssetMaintenanceService` | Maintenance leases, fenced rental effects, characteristics и вход в furniture custody |
| `AssetClassifierService` | Команды classifier aggregate и event facts |
| Сервисы lease, catalog, ledger и hold | Точные locking, fencing, catalog binding, physical balances и expiry allocations |
| `AssetJsonCodec` и `AssetBalanceRow` | Только canonical JSON/idempotency decoding и immutable carrier физического остатка |
| `InventoryAssetService` | Стабильный private inventory facade над capture, projection, furniture reconciliation, completed outcomes и source creation |
| Сервисы inventory capture/projection/furniture/outcome/source | Lifecycle замороженного capture, current validation reads, проверенные абсолютные furniture counts, completed-at outcome ordering и permanent source identity |
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

Отстающий статус `RENTED` допускается к disposition только после освобождения logistics всех active
leases и при отсутствии reservation, foreign hold или disposition fence. Preparation и application
повторяют эти guards под lock. Освобождение logistics lease не переписывает статус или версию
rental item; только финальный одобренный maintenance APPLY может перевести его в `WRITTEN_OFF`.

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

Test task модуля ограничивает кеш Spring test context одним контекстом. Интеграционные suites asset
используют много разных Testcontainers application contexts, а общий Gradle test worker ограничен
512 МиБ; немедленное вытеснение не даёт широкому запуску удерживать несвязанные контексты до
`OutOfMemoryError`. Полный gate запускается последовательно:
`bash ./gradlew :services:asset-service:test --rerun-tasks --max-workers=1 --no-parallel`.

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
