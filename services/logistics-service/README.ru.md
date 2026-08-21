# Сервис логистики RWMS

[English version](README.md)

## Назначение и владение

`logistics-service` владеет заявками на аренду, клиентскими презентациями, заказами аренды,
возвратами, отгрузками, перемещениями, работой водителей и логистической оркестрацией. Он владеет
document/workflow state, но не агрегатами кабин, оборудования, ремонта или склада. Effects для этих
владельцев используют узкие private APIs и durable logistics recovery work.

Авторитетные HTTP- и event-контракты находятся в
[`contracts/openapi/logistics-service.yaml`](../../contracts/openapi/logistics-service.yaml) и
[`contracts/events/logistics-events.yaml`](../../contracts/events/logistics-events.yaml).
Начните с [`docs/project-knowledge/domain-logic.md`](../../docs/project-knowledge/domain-logic.md),
затем проверьте ownership и invariants по текущему коду и контрактам.

## Публичная и внутренняя HTTP-граница

Аутентифицированные операции версионированы под `/api/logistics/v1/**`. Здесь находятся возвраты,
отгрузки, перемещения, equipment movements, driver board/tasks, orders и rental inquiries. Команды
используют определённые контрактом `Idempotency-Key` и expected-version field/parameter; callers
должны обработать канонический конфликт, а не отправлять изменившийся retry.

Команды shipment и return несут необязательный opaque `driverWorkerId` task-board вместе с
историческим display snapshot; logistics никогда не выводит identity из имени. Команды transfer не
содержат identity водителя и остаются общей работой `WAREHOUSE_DRIVERS`: назначение конкретного
водителя относится к клиентским ходкам shipment/return, но не к складским transfer. Каждая новая
запланированная shipment, return или transfer сохраняет
одно durable задание водителя `LOGISTICS_DOCUMENT` с неизменяемым снимком клиента и упорядоченными,
неизменяемыми участниками-бытовками. Сохранённый `tripNumber` стабилен в рамках заказа аренды.
Исторические задания по строкам документа до старта отменяются перед созданием одной группы;
начатый исторический участник блокирует перегруппировку, новые задания по строкам не создаются.

Public board и detail задания показывают всю ходку: операцию, клиента, адрес и координаты, основной
и дополнительные контакты клиента/заказа, комментарий, желаемые даты, фактически назначенную дату,
бытовки и желаемую/фактическую мебель каждой бытовки со статусами movement task и readiness. После
обычной manager-проверки warehouse access с `rwms.read` detail задания `ASSIGNED_DRIVER` может
читать только `WORKER` со scope `driver.tasks`, у которого `worker_id` равен сохранённому
`plannedDriverWorkerId`; session `sub` не является identity рабочего, а `worker.tasks`,
`UNASSIGNED` и `WAREHOUSE_DRIVERS` такого доступа не дают. Board move действует на
сгруппированное задание, но не на отдельного участника.
Во время version-fenced вызова task-board намеренно удерживаются блокировки локального задания и
документа, чтобы локально начатая ходка не пересеклась с устаревшим remote-состоянием `WAITING`;
граница зависимости ограничена настроенными connect/read timeout (`2s`/`5s` по умолчанию).
После принятого task-board перемещения до старта logistics обновляет дату owning document в recovery
boundary, сохраняет его дату и пожелания клиента; shipment, return и transfer используют
одинаковое групповое поведение.

`GET` и `PUT /api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings` владеют
warehouse-scoped максимумом бытовок в одной новой сгруппированной ходке. Лениво создаваемое значение по
умолчанию — одна бытовка; GET требует read/VIEW scope, PUT — write/MANAGE scope и
`expectedVersion`. Планирование shipment, return и transfer отклоняет уникальный выбранный набор
выше текущего лимита до создания driver task; logistics не вводит второй лимит и не делит такой
запрос автоматически.

`POST /api/logistics/v1/rental-inquiries/{inquiryId}/cabin-searches` является write-authorized
командой и требует `Idempotency-Key`. Короткая PREPARE transaction блокирует inquiry, повторно
проверяет текущие ownership/state и warehouse-edit authority, затем сохраняет digest запроса по
subject/operation/key, domain-separated downstream UUID, неизменяемый actor snapshot, hold expiry и
точный текст asset JSON вместе с его digest. Warehouse- и asset-HTTP-вызовы после этого выполняются
без local transaction. Короткая COMPLETE transaction повторяет текущие authorization checks,
выбирает склад inquiry только после asset success и замораживает response; идентичный завершённый
retry возвращает его с `Idempotency-Replayed: true` без remote call. Изменившийся запрос или другой
живой key дают `409`. Только inactive warehouse и sanitized коды domain rejection от asset `400`/
`409` переходят в `REJECTED`; OAuth/configuration/transport/timeout, asset `401`/`403`/`404` и `5xx`,
а также lost response остаются `PREPARED` для точного retry. `EXPIRED` освобождает единственный
PREPARED slot inquiry только после сохранённого hold expiry, а сам истёкший public key остаётся
terminal.

При отсутствии `resultMode` поиск бытовок использует `REPLACE`: asset-service атомарно освобождает
предыдущие chat holds заявки перед установкой нового результата. `APPEND` принимается только при
явном запросе caller. Facets содержат доступные значения characteristics и точные связи
тип-размер. `GET .../cabin-catalog` — ограниченный facts-only lookup без побочного эффекта hold.
`GET .../cabin-selection` читает авторитетный asset-owned набор holds; идемпотентный
`PUT .../cabin-selection` заменяет точный полный список cabin IDs со сроком
`chatSelectionHoldMinutes`, а пустой список немедленно освобождает все holds inquiry. Короткая
PREPARE transaction сохраняет digest запроса owner/key, точный replace-or-release JSON, non-null
command deadline и nullable public hold expiry до asset call; для replace deadlines совпадают, а у
release public expiry остаётся null. Короткая COMPLETE transaction повторно проверяет текущие
ownership/state inquiry и warehouse authority перед заморозкой validated response. Повтор с тем же
key и запросом использует те же bytes и deadlines, а изменившийся повтор или другой live key дают
`409`; transport failure или lost response остаются `PREPARED` для этого точного retry только до
сохранённого command deadline, после которого slot снова доступен. Remote calls выполняются вне
local transactions, а receipt является evidence эффекта/replay, а не дубликатом авторитетной
asset-owned selection.

`POST /api/logistics/v1/clients` создаёт rental client логистики типа `INDIVIDUAL` или
`LEGAL_ENTITY`. Name/FIO и основной телефон обязательны; для юридических лиц обязательно также
контактное лицо. Любое количество проверенных дополнительных контактов имя/телефон может оставаться
client-owned; order-owned дополнительные контакты хранятся отдельно. Email, комментарий и источник
необязательны, а identity и
display-name snapshot ответственного менеджера берутся только из authenticated write actor.
Исторические клиенты сохраняют правдивый UUID subject создателя как manager identity и могут не
иметь display-name snapshot. Поиск/detail клиента и paged route `/clients/{clientId}/orders`
используют обычные visible-order rules и не раскрывают недоступные identities.

Draft заказа аренды можно создать с заранее выбранным клиентом и редактировать с idempotency и
expected version. Команды create и обычного update принимают только клиента, основной телефон и
комментарий; delivery address, пару координат и order-owned дополнительные контакты клиент указывает
в normal presentation confirmation. Желаемые окна остаются order-owned read projection: перенесённые
legacy строки правдиво читаются, а актуальное normal confirmation заменяет их одной–пятью разными
выбранными клиентом календарными датами (`startDate=endDate`) в хронологическом порядке. Draft можно
сохранить без delivery facts, но создание отгрузки требует адрес, основной телефон и хотя бы одну
желаемую дату. Фактическое расписание документа —
его `scheduledDate`; публичные команды и проекции не содержат времени суток. Человекочитаемый телефон
нормализуется в canonical E.164.

`CreateRentalInquiryRequest` может указывать существующий draft или сохранённый, но ещё редактируемый
заказ. Assistant inquiry сохраняет conversation ID; manual inquiry использует ту же сущность без
скрытого чата, а `GET /api/logistics/v1/rental-inquiries?rentalOrderId=...` повторно находит оба типа
с авторизацией заказа и склада. Повторные normal presentation добавляют бытовки в тот же заказ и
соблюдают его зафиксированный склад. Presentation reads объединяют live общий asset-остаток,
атомарно снятое содержимое выбранных held cabins и неназначенный физический излишек уже внутри этого
заказа; строки с нулевой общей доступностью сохраняют максимум на бытовку. Confirmation передаёт
мебель по бытовкам и атомарно конвертирует holds вместе с авторитетным полным составом мебели заказа.
Поэтому каждое публичное `NORMAL` presentation требует одну–пять независимо выбранных дат клиента,
обязательный delivery address, необязательную полную пару latitude/longitude, nullable дополнительные
контакты с нормализацией в пустой список и положительный начальный `rentalMonths`; значения не
предзаполняются из связанного заказа. Durable booking receipt хронологически нормализует эти факты,
а local transition после conversion сохраняет упорядоченные даты заказа и создаёт срок только для
каждой newly converted бытовки. В retry проверяются все delivery facts и длительность, поэтому
несовпадающий replay конфликтует и не может переписать срок уже выбранной бытовки. `REPLACEMENT` presentation показывает
текущие facts заказа только для чтения и отклоняет все normal-only поля. Assignment отгрузки рассчитывает дату
возврата каждой бытовки от фактической даты отгрузки и выбранного клиентом срока; единственная
публичная мутация срока — существующая команда продления выбранных уже отгруженных бытовок.
`OrderPermissions.canExtendRentalTerms` — server-derived affordance этой команды, включая
`FULFILLED` заказ, где обычное редактирование недоступно.
В публичной границе заказа нет прямых команд выбора склада или добавления бытовки: бытовки
добавляются, а первый склад фиксируется только через существующий inquiry/presentation flow.
`GET /api/logistics/v1/orders/{orderId}/available-units` остаётся поиском для замены; удаление,
замена бытовки и редактирование желаемой мебели сохраняют отдельные команды.

Replacement повторно использует те же presentation/booking или прямую команду заказа. Warehouse
manager может заменить только запрошенные бытовки до старта с точным количеством client selection;
direct replace также сохраняет непустую причину. Упорядоченный batch атомарно меняет asset
reservations, затем обновляет тот же заказ, document members и требования мебели. Checkpoints
существующих movement tasks отменяют незавершённое наполнение старой бытовки до swap, повторяют
точные old-to-new furniture holds при необходимости физического переноса и оставляют readiness false
до завершения обычного movement task. Обычное редактирование прекращается после окончательной даты
ходки или furniture task; replacement остаётся доступен для конкретной бытовки до старта её
ходки.

Интерактивные panel и Android-клиенты обращаются к этому namespace только через публичный маршрут
`/api/logistics/**` в `api-gateway-service`. Им нельзя напрямую вызывать host этого модуля или
маршрут `/api/internal/**`.

`/api/internal/logistics/v1/maintenance/**` — узкая private boundary для maintenance-owned repair
work, которому нужна logistics driver/equipment orchestration. Она использует service credentials и
не является client route. Её driver-task intake принимает обычную входящую работу
`DELIVER_TO_REPAIR` и отдельное исходящее задание `CAPITAL_TO_PRODUCTION`, источником которого
является внешний капремонт; обе работы принадлежат logistics и не обходят упорядоченную очередь
водителей.
Когда обычный возврат завершает физическую приёмку, logistics один раз записывает immutable
`returnArrivedAt` документа. Maintenance-only чтение прибытия использует ограниченную JPA-проекцию
этого owner field; Flyway V52 заполняет существующие строки из их точного события
`RETURN_INSPECTION_REQUIRED`. Созданные инвентаризацией исторические возвраты обходят приёмку,
оставляют поле null и не могут стать искусственным источником сметы.
Если свежий private maintenance-запрос `FIXED_DATE` приходит в logistics после указанного
warehouse-local дня, scheduler сохраняет fixed-date/source intent, но записывает текущий local day
как эффективный `scheduledDate`. Это восстановление действует только для service-owned maintenance
intake: public create по-прежнему отклоняет прошедшую дату. Checksum сохраняет исходно запрошенный
день, поэтому только точный запрос повторно возвращает сохранённую эффективную дату без дубликата.

`PUT /api/internal/logistics/v1/inventory/outcomes/{inventoryId}` применяет последнюю завершённую
инвентаризацию как авторитетную logistics-истину по точным canonical `assetId`. Маршрут принимает
только exact SERVICE token `inventory-service` с audience `rwms-services` и единственным scope
`logistics.inventory`, а также UUID `Idempotency-Key`. Весь batch отклоняется с `409` до mutation,
если он устарел, имеет неоднозначный equal-time source, пересекает ownership другого склада или
выбирает лишь часть nonterminal document. Равное время завершения принимается только для exact
source reassertion либо когда те же склад и инвентаризация публикуют строго большую версию финального
плана; меньшая версия и изменённый hash при текущей версии остаются конфликтами. Иначе выбранные
document lines и rental terms остаются в истории, получают inventory-superseded marker и
исключаются из active rental/shipment reads. Полностью выбранные nonterminal documents становятся
`CANCELLED`; документы `ACCEPTED`,
`ESTIMATE_REQUESTED`, `SHIPPED`, `COMPLETED` и уже `CANCELLED` сохраняют terminal state. Заказы без
active terms переходят из `DRAFT`/`SAVED` в `CANCELLED` или из `FULFILLED` в `CLOSED`; уже terminal
orders сохраняют status. Незавершённые logistics-owned driver/document tasks отменяются через
source-owned general cancellation task-board даже после старта, а завершённая работа сохраняется.
Сам batch не создаёт работу ремонта или капремонта: maintenance запускается после него через
существующие integrations, а same-source reassertion защищает `INVENTORY` movement, чей `sourceId`
является finding текущего batch, продолжая отменять более старый inventory-source movement.

У каждого outcome есть одно строгое disposition. `LOCAL` с замороженным evidence бывшей аренды
создаёт или переиспользует терминальный публичный `RETURN` (`ACCEPTED`/`ARRIVED`) без intake, сметы
или задания. `SHIPMENT` создаёт или переиспользует терминальный публичный `SHIPMENT`
(`SHIPPED`/`DEPARTED`) без водителя, hold, задания или распределения складского остатка; его строка
показывает точный nullable snapshot `inventoryShipmentFurniture`. `WRITE_OFF` создаёт только
durable marker и освобождает predecessor logistics state: без документа, `FREE` или terminal asset
outcome, поскольку финальным disposition владеет maintenance. V51 хранит эти точные
source/disposition facts, а preparation сохраняет batch с ограниченным числом flush вместо flush
на каждый outcome. Отсортированный набор per-asset advisory locks захватывается за один database
round trip, а оставшиеся active rental terms проверяются один раз для всего набора заказов, поэтому
число запросов не растёт на один lock или active-term query для каждого outcome.
Inventory-displaced logistics guard остаётся в reconciliation, пока asset-service не подтвердит его
exact typed document-line lease как `RELEASED` или `EXPIRED`; release выполняется вне database
transaction со стабильным dependency idempotency key, а несовпадение owner/fence работает fail closed.
Equipment work в `EXECUTING` или `RECONCILIATION_REQUIRED` работает fail closed; более ранняя работа
переходит в существующий durable cancellation path. Постоянный receipt, per-asset source watermark и
task-action checkpoints сохраняют каждую строку и возобновляют uncertain remote result; inventory
outcome path ничего не удаляет из logistics history.

`/api/logistics/public/v1/client-presentations/**` намеренно anonymous, но доступ ограничивают
signed presentation token, его revision и current viewability. Media access также проверяет, что
запрошенный item/generation/variant принадлежит этой presentation; это не общий media proxy.

## Внутренняя структура приложения

`HttpLogisticsDependencyGateway` — стабильная реализация private dependency
port. Его неизменённый constructor собирает шесть owner clients, а facade
делегирует каждую операцию интерфейса:

| Owner client | Private boundary |
| --- | --- |
| `LogisticsWarehouseDependencyClient` | Warehouse identity, admission, timezone и lifecycle |
| `LogisticsAssetOperationsDependencyClient` | Rental snapshots, leases, fenced effects, equipment holds и movements |
| `LogisticsAssetOrderPresentationDependencyClient` | Order units, reservations, cabin availability/search и presentation holds |
| `LogisticsMaintenanceDependencyClient` | Transfer repair, estimate source, capital repair и repair-place calls |
| `LogisticsMediaDependencyClient` | Media validation, owner proof, evidence, snapshots и binary presentation media |
| `LogisticsTaskBoardDependencyClient` | Movement tasks, driver queue/task, board и completion calls |
| `LogisticsOAuthHttpTransport` | Только exact-scope client credentials, HTTP exchange и существующий dependency error mapping |
| `RentalInquiryCabinSearchService` | Non-transactional последовательность warehouse/asset calls над одной frozen downstream command |
| `RentalInquiryCabinSearchStore` | Locked PREPARE/COMPLETE/REJECTED/EXPIRED receipt transactions и frozen-response replay |
| `RentalInquiryCabinSelectionService` | Owner-scoped чтение authoritative holds и exact full-selection replace/release calls вне local transactions |
| `RentalInquiryCabinSelectionStore` | Locked PREPARE/COMPLETE/REJECTED/EXPIRED transactions selection receipt, exact-byte retry и frozen-response replay |
| `RentalInquiryCabinCatalogService` | Ограниченный facts-only cabin lookup с authorization inquiry, warehouse и owner |
| `LogisticsDocumentService` | Стабильный facade return/shipment/transfer и rental-order hooks над семью точными owners |
| Coordinators документов return, shipment и transfer | Независимые document state machines с исходным порядком transaction и recovery |
| `DocumentDriverTaskPlanner` | Одно idempotent document-owned задание с упорядоченными участниками-бытовками на каждую новую запланированную shipment, return или transfer; ожидающие legacy line tasks сходятся в группу, а начатые блокируют replanning |
| `DriverTripProjectionService` | Structured task/board facts ходки с одним asset read на отдельный заказ и явным unavailable readiness при dependency failure |
| `ShipmentTaskSettingsService` | Warehouse-scoped version-fenced лимит, повторно используемый каждой сгруппированной ходкой; атомарно материализует default one и отклоняет over-limit planning |
| Coordinators rental-order shipment/completion и reconciliation | Document hooks для rental shipment, terminal return и команды reconciliation request |
| Политики document admission, idempotency, attempts, reads и binding | Узкие leaves warehouse, replay, external-attempt, projection и active-order |
| `RentalOrderService` | Стабильный order facade над reads, creation, lifecycle, reservations, terms и shipment hand-off |
| `RentalOrderUnitReplacementService` | Direct и presentation replacement через ordered batch checkpoints, pre-start отмену driver task и сходимость order/document members |
| `ShipmentFurnitureTaskService` | Полный состав мебели всех active units заказа, readiness существующих movement tasks и replacement recovery checkpoints |
| Rental-order command store, editability и problem/outcome leaves | Row/receipt replay, saved-draft synchronization и canonical local problem mapping; `LogisticsTransactionLock` владеет узким transaction advisory-lock access |
| `InventoryOutcomeService` | Non-transactional orchestration завершённой инвентаризации и frozen successful replay |
| `InventoryOutcomePreparationStore`, `InventoryOutcomeTaskStore`, `InventoryOutcomeTaskProcessor` | Атомарные supersession склада/documents/orders, durable task/asset-lease checkpoints и reconciliation task-board/asset/repair-place вне database transactions |

Owner clients зависят только от общего transport и настроенного private base
URL; они не зависят от peers и не ссылаются обратно на facade. Domain- и
saga-решения остаются в logistics application services и durable stores, а не
в transport layer.

Document facade сохраняет каждый controller/order-facing method и внешнюю
transaction annotation. Coordinators return, shipment и transfer не вызывают
друг друга; rental-order shipment/completion и reconciliation являются
отдельными owners. Общие leaves содержат только warehouse admission,
idempotency records, external-attempt writes, read projection или active-order
binding и не ссылаются обратно на facade.

Rental-order facade сохраняет полный controller/presentation API и внешние
transaction annotations. Read projection, creation, lifecycle клиента/склада,
reservations/equipment, rental terms и shipment hand-off имеют отдельных
owners. Command store является единственным владельцем order-row/receipt/
advisory-lock, а только editability координирует saved document-draft lock; ни
один owner не вызывает facade обратно.

## Изоляция складов, fencing и оркестрация

Пользовательское действие требует соответствующего warehouse grant. Новая физическая logistics
operation сначала получает warehouse admission и local date через private warehouse boundary, затем
фиксирует только local logistics state. Asset, equipment-hold и task-board effects используют stable
operation IDs, expected versions и owner-side fencing: uncertain remote result повторяется, а не
угадывается.

Warehouse admission работает fail closed. Disabled или unavailable dependencies возвращают
существующий ответ `503 LOGISTICS_DEPENDENCY_UNAVAILABLE` до записи document, domain event, outbox
event или operation mark. Warehouse-service отклоняет incoming operation для `DRAINING` или
`INACTIVE`, а logistics после такого отказа не оставляет reserved intent или domain write.

Каждый свежий remote-admission ticket несёт точные direction и неотрицательную warehouse lifecycle
version, которые вернулись для каждого отсортированного requirement. Owning transaction документа,
equipment или driver сохраняет этот vector в постоянных warehouse operation marks вместе с новой
domain work; rollback не оставляет ни work, ни admission evidence. Если после admission теряется
timezone response, logistics повторно читает те же версии admitted intent, а не просит
warehouse-service допустить другую версию.

До любого remote dependency call повтор public create может получить ticket
`EVIDENCED_REPLAY_CANDIDATE`, только если local SQL находит живую durable domain identity и полный
точный набор operation marks с evidence. Для document candidate требуется живой неистёкший receipt
subject/operation/key, который по-прежнему ссылается на свой document; для equipment и driver нужен
соответствующий domain row actor/key. SQL восстанавливает сохранённый vector warehouse/direction из
этого domain row, затем требует постоянные marks с теми же warehouses и directions,
неотрицательными versions, без пропущенных или дополнительных warehouses. Такой candidate не
доказывает идентичность incoming payload. Owning create path остаётся checksum authority: изменение
warehouse/direction или любое другое изменение payload возвращает существующий `409` без dependency
call или mutation, а exact match возвращает сохранённую operation до consume ticket. Replay
candidate отклоняется, если достигает любого new-create consume path. Driver replay checksum
использует исходно запрошенную fixed date до любого current warehouse-local date gate и возвращает
сохранённую effective date; только свежая driver task выводит `AUTO` date из remote ticket.

Historical marks намеренно остаются unproven: migration `V39` не выполняет backfill evidence. Legacy
null evidence, истёкший document receipt, отсутствующий domain row или mark, subset, superset и
несовпадение direction/version никогда не разрешают dependency-free replay. Test-only и parent-owned
continuation marks также сохраняют null evidence. Если candidate evidence отсутствует, retry следует
свежему remote-admission path и возвращает `503 LOGISTICS_DEPENDENCY_UNAVAILABLE`, когда dependency
не ready; точный owner-verified candidate replay не вызывает warehouse admission/timezone и не
создаёт короткий admission intent.

Пользовательский JWT не пересекает `LogisticsDependencyGateway`. Gateway получает
client-credential tokens для asset, warehouse, task-board, maintenance и media. Remote effects
становятся durable attempts и relay запускает их после local commit. Единственное намеренное
исключение remote-under-lock — public move целой ходки: task/document pre-start locks удерживаются на
время bounded version-fenced вызова task-board, чтобы закрыть гонку local-start/remote-`WAITING`, а
существующий status poll сводит remote success с последующим local rollback.

Driver relay обрабатывает не более 100 готовых заданий за проход. Неизменившийся task-board snapshot
`SCHEDULED` или `CURRENT` откладывает fallback poll на 30 секунд без увеличения business aggregate
version; команды оператора по-прежнему запускают немедленную обработку. Поэтому неактивный парк не
создаёт один cross-service HTTP-запрос на каждое задание каждую секунду.

## Хранение, события и восстановление

Flyway migrations в `src/main/resources/db/migration/` владеют logistics schema. JPA использует
`ddl-auto=validate`; service databases изолированы, а cross-service foreign keys/JPA entities
запрещены.

Migration
[`V42__clients_order_delivery_and_acceptable_dates.sql`](src/main/resources/db/migration/V42__clients_order_delivery_and_acceptable_dates.sql)
добавляет client fields contact/manager/comment/source, исходные order delivery facts, предшествующую
коллекцию дат, которую V47 затем losslessly мигрирует в desired windows, а также durable
exact-command receipts для cabin selection inquiry. Она
backfill только правдивый UUID ответственного менеджера из
`created_by_subject_id` и не выдумывает historical display name. Legacy строки без phone/contact
остаются читаемыми, новые writes ограничены constraints, а V39-V41 неизменяемы.

Migration
[`V43__remove_sole_proprietor_client_type.sql`](src/main/resources/db/migration/V43__remove_sole_proprietor_client_type.sql)
переклассифицирует historical строки `SOLE_PROPRIETOR` в `LEGAL_ENTITY` до ограничения типов
клиента значениями `INDIVIDUAL` и `LEGAL_ENTITY`. Если переклассификация столкнётся с существующим
юридическим лицом по нормализованному телефону, она останавливается до update и сохраняет все
записи для ручного разрешения.

Migration
[`V44__driver_task_audience_and_document_driver.sql`](src/main/resources/db/migration/V44__driver_task_audience_and_document_driver.sql)
добавляет opaque ID водителя документа, три поля аудитории durable driver task,
а также source строки документа и kinds shipment/return/transfer. Существующие
driver tasks сохраняют прежнее общее поведение для водителей склада. Schema
хранит только ID и snapshots; cross-service foreign key не создаётся.

Migration
[`V45__correct_driver_task_audience.sql`](src/main/resources/db/migration/V45__correct_driver_task_audience.sql)
удаляет snapshots/ID водителя у transfer и все hints ответственного у общих задач. Shipment и return
становятся `ASSIGNED_DRIVER` при наличии ID и `UNASSIGNED` без него; все movement kinds становятся
identity-free `WAREHOUSE_DRIVERS`. Новые constraints сохраняют эти правила.

Migration
[`V46__shipment_task_grouping.sql`](src/main/resources/db/migration/V46__shipment_task_grouping.sql)
добавляет warehouse-local настройки shipment task, source `LOGISTICS_DOCUMENT` driver task и
упорядоченные cover checkpoints участников shipment. Это только expand: ни одна историческая
`LOGISTICS_DOCUMENT_LINE` задача не перегруппировывается и не переписывается.

Migration
[`V47__order_contacts_windows_and_inquiry_target.sql`](src/main/resources/db/migration/V47__order_contacts_windows_and_inquiry_target.sql)
добавляет упорядоченные коллекции дополнительных контактов, без потерь переименовывает legacy
acceptable dates во включительные desired windows (`startDate=endDate`, null legacy times) и сохраняет
исторические физические колонки scheduled time. Она добавляет стабильные номера сгруппированных ходок с order-wide
backfill истории, nullable conversation для manual inquiry вместе с target order и creation key,
metadata replacement presentation и повторно использует существующий shipment-furniture link как
durable ordered replacement checkpoint. Outbox остаётся только для conversation и становится
unique per inquiry, поэтому повторные добавления в один заказ не конфликтуют.

Migration
[`V48__presentation_booking_client_rental_terms.sql`](src/main/resources/db/migration/V48__presentation_booking_client_rental_terms.sql)
добавляет в существующие строки `presentation_booking` nullable положительное поле receipt
`rental_months`, выбранное клиентом как начальный срок. Historical и replacement receipts остаются
null; normal confirmation сохраняет положительное значение до local order reconciliation, который
создаёт сроки только для newly converted бытовок.

Migration
[`V49__presentation_booking_delivery_confirmation_snapshot.sql`](src/main/resources/db/migration/V49__presentation_booking_delivery_confirmation_snapshot.sql)
additive-схемой сохраняет в существующем idempotency receipt `presentation_booking` нормализованные
delivery address normal-confirmation, необязательную пару координат и JSON дополнительных контактов.
Она не создаёт второй источник истины заказа, не переписывает historical строки и не удаляет
исторические колонки desired window и scheduled time: legacy значения остаются физически только для
persistence и сравнения старых receipt, но никогда не входят в публичные команды или проекции.

Migration
[`V50__authoritative_inventory_outcomes.sql`](src/main/resources/db/migration/V50__authoritative_inventory_outcomes.sql)
добавляет nullable supersession markers в сохранённые строки document, line, guard, rental-order,
rental-term и driver-task; active-read indexes для несуперседированных lines/terms; постоянные command
receipts; per-asset completed-source watermarks и recoverable task/asset-lease action checkpoints.
Это additive migration без backfill, rewrite или удаления исторических строк.

Logistics вместе фиксирует facts, projection checkpoints и transactional outbox. Kafka delivery —
at-least-once: aggregate IDs являются record keys, event IDs — dedupe identities, а consumers хранят
local replay/version-gap handling. Durable stores и relays восстанавливают external attempts, owner
proofs, warehouse operation marks, driver task work и sanitized failure paths.

Producer события rental-inquiry booked сохраняет строгий `DomainEventEnvelopeV2` в booking
transaction: aggregate identity/version берутся из inquiry после flush, correlation — conversation
с booking как causation, actorRef — USER reference менеджера, а payload содержит ровно
`conversationId` и `orderId`. Kafka по-прежнему использует точный UUID conversation как record key;
сгенерированный eventId и сохранённый JSON не меняются между relay retries. Migration
[`V41__rental_inquiry_search_receipts_and_booked_envelope_v2.sql`](src/main/resources/db/migration/V41__rental_inquiry_search_receipts_and_booked_envelope_v2.sql)
канонизирует и pending, и уже published legacy rows без изменения event IDs, keys или delivery
statuses; published rows никогда не становятся relayable снова.

Provider-specific persistence ограничен
[`RentalInquiryBookedOutboxStore`](src/main/java/dev/buhanzaz/rwms/logistics/inquiry/eventing/RentalInquiryBookedOutboxStore.java)
и узкими warehouse-адаптерами
[`admission`](src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseAdmissionPersistence.java),
[`single-statement blocker`](src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseLifecycleBlockerReader.java)
и [`operation-mark`](src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseOperationMarkPersistence.java).
Они сохраняют PostgreSQL transaction time, conflict-safe insert, one-statement blocker snapshot,
`FOR UPDATE SKIP LOCKED` и conditional fencing writes; все business decisions остаются в lifecycle
stores. [`LogisticsTransactionLock`](src/main/java/dev/buhanzaz/rwms/logistics/repository/LogisticsTransactionLock.java)
— единственный application caller одобренного transaction advisory-lock query.

[`LogisticsRecoveryObservationStore`](src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsRecoveryObservationStore.java)
— отдельный read-only technical SQL adapter для recovery-метрик. Он читает только scalar counts и
oldest timestamps из принадлежащих logistics таблиц outbox, DLT, warehouse marks и inbound gaps;
он никогда не выполняет claim, retry, publish или resolve и не возвращает identifier, payload, topic
или error text.

[`LogisticsExternalAttemptClaimService`](src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsExternalAttemptClaimService.java)
выдаёт lease ровно одному due external attempt через стабильную ограниченную pessimistic skip-locked
страницу. PostgreSQL transaction time определяет проверки due и expiry; claim transaction завершается
до любого remote call. Неизменяемый claim без payload несёт IDs attempt/operation, lease token и
fence, claimed row version и request digest. Каждый workflow store блокирует и проверяет именно эту
capability перед записью completion или failure, поэтому истёкший или duplicate worker не может
перезаписать более новый result. [`V40__bounded_logistics_external_attempt_claims.sql`](src/main/resources/db/migration/V40__bounded_logistics_external_attempt_claims.sql)
хранит fence, token и expiry и добавляет due и expired-lease indexes.

Пять owner relay используют один лёгкий trigger scheduler и передают только разрешённую в данный
момент work в отдельный bounded remote-call executor. `LOGISTICS_EXTERNAL_ATTEMPT_LEASE_DURATION`,
`LOGISTICS_EXTERNAL_ATTEMPT_MAXIMUM_PAGE_SIZE`, переменные worker pool/queue/shutdown и пять
переменных `*_WORKER_BUDGET` из `application.yaml` ограничивают recovery capacity; budget каждого
owner должен оставаться меньше worker pool, чтобы сохранить remote-call slot другого owner.
Fixed-name gauges показывают backlog/oldest/terminal для main outbox, sanitized DLT и warehouse
marks; backlog/oldest для rental-inquiry outbox; open/oldest inbound gaps и blocked checkpoints; а
также active/oldest/max-retry/reconciliation-required external attempts и active/queue состояние
executor. Empty state и future age дают zero, а ошибка доступа к базе — `NaN`. Claim counters и
latency timers используют только закрытые labels `owner` и `state`; ни одна метрика не содержит
attempt IDs, topics, payloads или free-form exceptions. Rental-inquiry outbox сейчас имеет только
`PENDING` и `PUBLISHED`: у него всё ещё нет terminal/reviewed recovery state, и это остаётся
follow-up work, а не выдуманным terminal gauge.

Base-конфигурация требует явное значение `LOGISTICS_KAFKA_ENABLED`; только профиль `dev` сохраняет
явный optional default `false`. Canonical primary outputs упорядочены строго как return, shipment,
transfer и `rwms.logistics.rental-inquiry.events.v1`; sanitized DLT bindings остаются отдельным
точным набором. Вне явных `dev`/`test` startup требует включённую Kafka, explicit non-loopback
brokers, отключённое topic auto-creation, synchronous `acks=all`, producer idempotence, положительные
request/delivery/max-block timeouts, где delivery не короче request, сумма max-block и delivery
короче outbox lease, включённый rental-inquiry outbox и все beans main-outbox, sanitized-DLT,
rental-inquiry и output-binding. Профиль `prod` или `production` имеет приоритет над одновременно
активным local profile и дополнительно требует валидные private dependency URLs и client
credentials, готовый dependency gateway и `LOGISTICS_DEV_AUTH_BYPASS=false`.
Disabled mode `LOGISTICS_DEPENDENCIES_ENABLED` остаётся ограничен isolated local/test work. Свежие и
unproven warehouse-bound creates тогда завершаются `503`; без dependency может пройти только точный
replay, подтверждённый durable evidence. Direct fixtures под `test` могут получить test-only ticket,
но его marks с null evidence никогда не разрешают public replay.

## Runtime-конфигурация

Порт HTTP по умолчанию — `8090`. Настройте logistics database, `AUTH_ISSUER`, CORS origin,
client-presentation token secret, а для real integrations — token URI, client ID/secret и private
base URLs. Точные variable names находятся в `src/main/resources/application.yaml`; нельзя
коммитить live credentials или presentation secrets. Startup guard совместно проверяет non-local
Kafka settings и, в production, `LOGISTICS_DEPENDENCIES_ENABLED` с
`LOGISTICS_DEV_AUTH_BYPASS`, не включая configured secrets в failures.

Секрет presentation token должен иметь не менее 32 символов, а production отклоняет известный local
default. Основной API использует stateless OAuth2/JWT; dev auth bypass ограничен профилем `dev`.

## Наблюдаемость и эксплуатация

Actuator предоставляет `health`, `info` и `prometheus`. Логи используют ECS, tracing sampling
задаёт `LOGISTICS_TRACING_SAMPLING_PROBABILITY`. Recovery gauges регистрируются без dynamic labels;
существующий common tag `application=logistics-service` добавляется runtime-конфигурацией. Для
delayed work проверьте document, external-attempt/recovery record, outbox status и correlation ID до
ручного retry effect. Нельзя исправлять состояние другого сервиса напрямую из logistics.

## Локальная разработка

Из корня репозитория:

```bash
./gradlew :services:logistics-service:bootRun
```

Используйте disabled dependencies только для isolated development/test scenarios; свежие и
unproven warehouse-bound public creates в этом режиме намеренно недоступны. Live integrations
используют private URLs и service credentials, но не public gateway и не browser token.

## Исполняемый parity маршрутов и безопасности

[`LogisticsRouteSecurityParityTest`](src/test/java/dev/buhanzaz/rwms/logistics/config/LogisticsRouteSecurityParityTest.java)
разбирает канонический OpenAPI-инвентарь операций, находит все активные mapping из
`@RestController` через merged-аннотации Spring и требует точного равенства множеств method/path без
дубликатов. Нормализуются только имена placeholders и необязательный завершающий slash. Этот же тест
исполняет реальную owner security filter chain с отключённым dev auth bypass: Bearer-операции должны
отклонять неаутентифицированный запрос, а анонимно могут проходить только четыре контрактные операции
под `/api/logistics/public/v1/client-presentations/**`.

Запуск focused gate из корня репозитория:

```bash
bash ./gradlew :services:logistics-service:test --tests 'dev.buhanzaz.rwms.logistics.config.LogisticsRouteSecurityParityTest'
```

## Правила безопасного изменения

- Меняйте OpenAPI/AsyncAPI boundary и всех затронутых producers/consumers одновременно.
- Храните return, shipment, transfer, order и driver workflow state в logistics, но не в UI/gateway saga.
- Сохраняйте expected-version fencing, stable idempotency keys, outbox/inbox dedupe и durable recovery.
- Добавляйте immutable service-local Flyway migrations и проверяйте затронутые JPA mappings.
- Тестируйте success, conflict, timeout/retry и replay paths для изменённой owner boundary.

## Основные исходные материалы

- `src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsDocumentService.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/order/service/RentalOrderService.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/LogisticsWarehouseLifecycle.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/repository/LogisticsTransactionLock.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseAdmissionPersistence.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseLifecycleBlockerReader.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseOperationMarkPersistence.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsEventStore.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/integration/HttpLogisticsDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/logistics/inquiry/service/ClientPresentationService.java`
