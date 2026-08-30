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

### Граница бронирования CustomerApp

Выделенная граница `/api/logistics/customer/v1/**` принимает только JWT типа `USER` с ролью
`CUSTOMER`, scope `customer.rental` и client identity `rwms-customer-android`. Клиент создаёт один
профиль физического или юридического лица, выбирает явно доступный склад и работает с принадлежащей
logistics rental session. Склад доступен, только пока warehouse-service считает его активным, а в
logistics включена запись delivery-depot с его UUID. У каждой записи свои координаты, поэтому
ответ склада передаёт тот же origin в CustomerApp: начальная камера карты и маршрут слота
начинаются на выбранном в session складе. Доступность и holds бытовок, фотографии и
положительные остатки мебели остаются во владении asset-service; клиентский каталог отдаёт только
бытовки `FREE` либо уже удерживаемые той же session и никогда не открывает паспорт бытовки. Тип
профиля физлица/юрлица и auth/client bindings неизменяемы; контактные/display-поля обновляются под
version fence и в той же транзакции синхронизируют logistics rental-client projection. Первый
проверенный склад, использованный для аватара, становится неизменяемым media authorization scope, а
не identity профиля или warehouse access. Logistics публикует детерминированный subject-bound proof
`LOGISTICS_CUSTOMER_PROFILE/PROFILE_AVATAR`, перед привязкой проверяет одно текущее поколение `READY`
и хранит только media identity/generation; байтами и вариантами владеет media-service. Nullable
legacy-факты паспорта пропускаются в least-privilege карточке вместо падения всей страницы каталога.
Корзина хранит отдельный начальный срок аренды для каждой выбранной бытовки и при выборе новой
бытовки задаёт один месяц. Полная замена сроков или мебели отвязывает прежний слот. Для старой
незавершённой корзины с неполным сохранённым набором checkout применяет тот же срок по умолчанию и
переносит в booking один точный срок на каждую удерживаемую бытовку. Каждый изменяющий
шаг использует idempotency key и определённый контрактом version fence профиля,
session, asset или slot. При первом создании профиля и inquiry-session берётся transaction-scoped
advisory lock до поиска unique-строки, поэтому конкурентные первые запросы сходятся и не превращают
database uniqueness constraint в API-ошибку.
После того как checkout переводит customer session в `BOOKED`, identity её inquiry остаётся
доступной для истории заказа, но cart-scoped чтения facets, selection и cart возвращают канонический
`409 INQUIRY_ARCHIVED`. Для следующей корзины клиент начинает отдельную идемпотентную inquiry;
terminal-session и её booking не переоткрываются и не перезаписываются. См.
[`CustomerController`](src/main/java/dev/buhanzaz/rwms/logistics/customer/api/CustomerController.java),
[`CustomerAuthorizer`](src/main/java/dev/buhanzaz/rwms/logistics/customer/security/CustomerAuthorizer.java),
[`V59`](src/main/resources/db/migration/V59__customer_app_booking_and_delivery_slots.sql),
[`V61`](src/main/resources/db/migration/V61__customer_terms_capacity_shifts_and_reception.sql) и
[`V63`](src/main/resources/db/migration/V63__customer_profile_edit_and_avatar.sql), а также расширение
динамических слотов/тарифов в
[`V64`](src/main/resources/db/migration/V64__dynamic_delivery_slots_and_tariff_zones.sql) и явный
тип фиксированного/гибкого слота в
[`V66`](src/main/resources/db/migration/V66__customer_delivery_slot_kind.sql).

Предложения доставки используют фиксированные warehouse-local окна `09:00-12:00`, `12:00-15:00`
и `15:00-18:00`, а также один вариант `DURING_DAY` на весь настроенный день доставки.
[`CustomerDeliverySlotService`](src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerDeliverySlotService.java)
сохраняет предложенную и удержанную ёмкость и через private Valhalla truck matrix рассчитывает
точное дорожное время. `travelZoneHours` остаётся информационной неограниченной полосой от склада и
не определяет ни допустимость, ни цену. Настроенные tiers изохрон ограничивают покрытие доставки
только после нахождения точного дорожного маршрута. Каждый фиксированный или дневной
вариант проверяется по маршруту и ёмкости до выдачи клиенту. `kind`, `window_start` и `window_end`
хранятся вместе: фиксированные границы остаются точным обещанием прибытия, а границы полного дня
остаются ненулевыми и ограничивают повторную проверку при hold, не обещая конкретный час.
Допустимость симулирует весь локальный
рабочий день и точные анонимные смены, опубликованные симулятором для склада/даты: локальные
начало/конец, перерыв и вместимость одна или две бытовки; также учитываются консервативные буферы
пути/обслуживания, складские загрузка/выгрузка, все последующие ходки,
удержанные/checkout-pending/подтверждённые клиентские окна, сгенерированные доставки и активные
датированные shipment/transfer. Без опубликованной смены или точного truck route слот не
предлагается. Вывозы проверяются только после приоритетных доставок на обратном пути и откладываются,
если угрожают текущей или следующей доставке. Один водитель может посетить несколько точек в
разных окнах, дождаться раннего окна, вернуться на склад для выгрузки/загрузки и выполнить несколько
ходок, но обязан завершить последнюю складскую операцию к настроенному концу смены (по умолчанию
20:00). Авторитетны направленные времена пути и конец окна начала обслуживания; активная shipment или transfer только с датой консервативно резервирует одного водителя на
весь день, кроме order shipment, уже представленной точным подтверждённым CustomerApp slot. Остаточная ёмкость — это дополнительная нагрузка бытовок
в той же точке/окне, которую ещё примет тот же дневной план, а не арифметически свободное место в
машинах. Для двух и более бытовок CustomerApp передаёт `siteCabinCapacity=1|2`: единица создаёт
последовательные заезды машины без прицепа, двойка разрешает прицеп только при наличии точного truck
route. Любое изменение бытовок, вместимости объекта или мебели отвязывает старый slot от корзины.
Каждый offer также замораживает применимые высоту, ширину, длину, массу, нагрузку на ось и число
осей одиночной машины или автопоезда, с которыми Valhalla проверила truck-route. Одностороннее
дорожное время выбирает первый настроенный часовой тариф изохроны, который его покрывает; самый
дальний настроенный tier является границей доставки, поэтому за его пределами offer не создаётся.
Новые offers публикуют выбранный tier в `priceIsochroneMinutes` и оставляют `priceZoneId=null`;
исторические quotes спецзон сохраняют записанную цену и nullable zone ID. Transport
contract сохраняет возможность `false` search-time подтверждений для совместимых клиентов, которым
нужны только предварительные даты; текущий CustomerApp собирает оба факта и, когда требуется,
вместимость объекта в модальном окне после связки адреса/точки и до запроса слотов. Финальный hold объединяет совместимые search-time
подтверждения со своей повторной проверкой;
без обоих фактов offer нельзя удержать или оформить. Расчёт маршрута идёт вне database transaction; финальная
hold transaction блокирует корзину, offer и текущую локальную нагрузку под warehouse/day и warehouse-capacity
advisory locks и принимает маршрут, только если canonical workload fingerprint не изменился. Тот
же warehouse/day fence захватывают замена simulator snapshot и каждый учитываемый в ёмкости create,
replan, ручной перенос по календарю и lost-response status recovery для shipment/transfer. Поэтому
driver reservation не может зафиксироваться внутри финального окна fingerprint/hold, а
конкурирующие writers получают один детерминированный transaction order. Checkout заменяет `HELD` на долговечную ёмкость `CHECKOUT_PENDING` со стабильным command key до
любого remote presentation/booking call, затем атомарно привязывает durable booking receipt.
Terminal-результат подтверждает или освобождает эту ёмкость. Checkout создаёт
обычный сохранённый rental order, переносит в него отдельный начальный срок каждой бытовки и
создаёт детерминированные furniture tasks по каждой бытовке. Transport
retry с тем же intent повторно использует исходный domain idempotency key и восстанавливает
потерянный ответ в
[`CustomerCheckoutService`](src/main/java/dev/buhanzaz/rwms/logistics/customer/service/CustomerCheckoutService.java),
а не browser rollback. Scheduled recovery за один проход обрабатывает старейший детерминированный
batch не более чем из 100 pending-корзин.

«Мои заказы» считает бытовку прибывшей только по точному shipment-заданию
`LOGISTICS_DOCUMENT`: совпавшим document line/member и terminal-state `COMPLETED`. Прибывшую
бытовку можно один раз идемпотентно принять полноэкранной нарисованной подписью либо до/после
приёмки создать неизменяемый отчёт об отсутствующей мебели, непригодной бытовке или иной проблеме.
Отчёт может ссылаться максимум на 20 READY media generations точной строки shipment. Logistics
хранит факты приёмки/проблемы, а media-service — привязанные к subject байты фото/видео.

Команды shipment и return несут необязательный opaque `driverWorkerId` task-board вместе с
историческим display snapshot; logistics никогда не выводит identity из имени. Transfer plan
хранит worker ID водителя рейса отдельно от необязательных intents перемещения водителя/автомобиля
после прибытия. Неназначенный transfer остаётся общей работой `WAREHOUSE_DRIVERS`, а назначенный
видит только водитель рейса. Само выполнение рейса никогда не меняет домашний или оперативный
склад водителя. Каждая новая запланированная shipment, return или transfer сохраняет
одно durable задание водителя `LOGISTICS_DOCUMENT` с неизменяемым снимком клиента и упорядоченными,
неизменяемыми участниками-бытовками. Сохранённый `tripNumber` стабилен в рамках заказа аренды.
Исторические задания по строкам документа до старта отменяются перед созданием одной группы;
начатый исторический участник блокирует перегруппировку, новые задания по строкам не создаются.

Черновик межскладского перемещения разделяет требования к грузу и выбранные физические бытовки,
отдельную мебель и мебель, уже привязанную к бытовке, склад обслуживания и физический источник
имущества, а также ресурс рейса и действительно перемещаемый ресурс. Подтверждение использует
fenced-резервы asset-service; выезд переводит точное имущество в custody «в пути», а
идемпотентное прибытие один раз фиксирует поступление на склад назначения и освобождает резервы.
Transfer может содержать только мебель. Регистрация в task-board повторно использует существующие
структурированные поля `taskText`, `works`, `materials` и `comments`. Logistics фиксирует точный
маршрут, характеристики бытовок, разницу требуемой и фактической мебели и упорядоченные инструкции
погрузки/поездки/выгрузки в `driver_logistics_task.worker_content_json`, поэтому потерянный ответ
регистрации или исправление до старта сходятся к одной DriverApp/WorkerApp-проекции без второго mobile API.

`POST /api/logistics/v1/historical-rental-movements` фиксирует одну прошлую отгрузку или возврат
прямо из карточки бытовки. Команда принимает доступного пользователю клиента логистики, текущую
версию бытовки и дату не позднее warehouse-local сегодняшнего дня. Shipment принимает полную пару
snapshot/worker-ID водителя либо пару `null` для неизвестного водителя; return отклоняет данные
водителя. Ни один вариант не создаёт маршрут или задание водителя. Команда создаёт обычный logistics
document, строку, событие и durable effect attempts. Импортированная
отгрузка сначала просит maintenance завершить допустимый обычный ремонт либо отменить допустимую
работу капремонта/перемещения с комментарием
`Автоматически закрыто в связи с отгрузкой.`. Освобождённая бытовка `FREE` затем проходит обычный
fenced shipment effect. Если maintenance доказывает, что бытовка уже `RENTED` и закрывать ремонт не
нужно, logistics синхронизирует наблюдаемую версию и завершает импортированный документ как
`SHIPPED` без захвата lease и повторения asset/stock effects.
Импортированный возврат проходит обычный fenced return-intake и достигает
`INSPECTION_REQUIRED`, поэтому смета и ремонт остаются обычной maintenance-owned работой. Факт
документа `historicalRentalImport` не допускает такой импорт в driver planning и обычные ручные
команды жизненного цикла shipment/return; необязательный водитель shipment остаётся только audit
metadata.
Команда сохраняет `CREATE_HISTORICAL_RENTAL_MOVEMENT` в той же durable-таблице
идемпотентности, что и остальные создания документов. Принятый response замораживается как JSON до
продолжения обычной document saga, поэтому exact lost-response retry возвращает исходные
version/state до обращения к warehouse lifecycle или времени. Domain validator и ограничение БД
разрешают именно эту операцию.
`PUT /api/logistics/v1/historical-rental-movements/{documentId}` исправляет клиента,
необязательную пару водителя и warehouse-local дату того же пользовательского shipment под версией
документа и отдельным idempotency key. Он обновляет audit metadata документа и client snapshot
строки под одним следующим aggregate event, сохраняет собственный immutable response, не создаёт
ещё один документ и не повторяет driver, route, lease, stock или asset effects.

Обычный endpoint отмены shipment дополнительно принимает пользовательский historical shipment
только из `CONFLICT` или `RECONCILIATION_REQUIRED`. До перехода он доказывает по durable attempts,
что завершённого или неизвестного `SHIPMENT_ASSET_CONFIRM` нет. Потерянный ответ
`SHIPMENT_ASSET_LEASE_ACQUIRE` открывается повторно с исходным operation ID, поэтому asset-service
идемпотентно возвращает точный lease, а не создаёт второй effect; затем logistics освобождает его,
принимая совпадающий terminal state `RELEASED` или естественный `EXPIRED`. Известные holds и leases
освобождаются, открытые строки reconciliation сохраняются как `RESOLVED`, а доказанный отказ без
захваченной capability сразу становится `CANCELLED`. Успешные historical shipments отменить нельзя.

Worker-задача перемещения оборудования остаётся принятой Task Board и после операционного дедлайна.
Если авторитетное время `DONE` наступило в дедлайн резерва или позднее, логистика фиксирует
завершение и переводит локальное перемещение в `RECONCILIATION_REQUIRED` с
`TASK_BOARD_COMPLETED_AFTER_RESERVATION_EXPIRY`: она не отклоняет факт от рабочего и не применяет
вслепую истёкший резерв источника, который уже мог быть использован другой операцией.

Public board и detail задания показывают всю ходку: операцию, клиента, адрес и координаты, основной
и дополнительные контакты клиента/заказа, комментарий, желаемые даты, фактически назначенную дату,
бытовки и желаемую/фактическую мебель каждой бытовки со статусами movement task и readiness. После
обычной manager-проверки warehouse access с `rwms.read` detail задания `ASSIGNED_DRIVER` может
читать только `WORKER` со scope `driver.tasks`, у которого `worker_id` равен сохранённому
`plannedDriverWorkerId`; session `sub` не является identity рабочего, а `worker.tasks` и
`UNASSIGNED` такого доступа не дают. DriverApp-водитель того же склада может предварительно читать
общее задание `WAREHOUSE_DRIVERS` только на дату позже текущего warehouse-local дня, а затем
зарезервировать его отдельной claim-командой. Task-board проверяет активную квалификацию водителя и
версионно ограждает конкурирующие попытки; claim не начинает выполнение и недоступен для задания
на сегодня. Board move действует на
сгруппированное задание, но не на отдельного участника.
Во время version-fenced вызова task-board намеренно удерживаются блокировки локального задания и
документа, чтобы локально начатая ходка не пересеклась с устаревшим remote-состоянием `WAITING`;
граница зависимости ограничена настроенными connect/read timeout (`2s`/`5s` по умолчанию).
После принятого task-board перемещения до старта logistics обновляет дату owning document в recovery
boundary, сохраняет его дату и пожелания клиента; shipment, return и transfer используют
одинаковое групповое поведение.

Публичная доска водителей показывает только текущие и будущие даты в часовом поясе склада.
Просроченная активная карточка task-board включается в сегодняшнюю колонку, а публичные команды
перемещения и планирования капремонта отклоняют дату раньше этого же warehouse-local дня. Отдельно
relay каждые 30 секунд перепроверяет не более 100 строк `RECONCILIATION_REQUIRED`, только если их
checkpoint относится к общей legacy-ошибке либо stage-specific task-board dependency
configuration/permanent rejection. Ошибки регистрации, статуса и evidence получают
`TASK_BOARD_DEPENDENCY_*`; cover- и maintenance-effects получают `COVER_EFFECT_DEPENDENCY_*` и
`REPAIR_PLACE_EFFECT_DEPENDENCY_*` и не могут быть открыты заново по task-board snapshot. Совпавший
авторитетный снимок task-board привязывает уже созданную remote-регистрацию при потерянном ответе
либо восстанавливает существующий scheduled, current, finalizing или cancelled workflow.
Compensation и остальные бизнес-коды reconciliation остаются терминальными и этим проходом не
открываются повторно. После полной страницы bounded cursor переходит дальше, поэтому сохранённые
бизнес-строки не могут навсегда скрыть более позднее восстанавливаемое задание.

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
legacy строки правдиво читаются, а актуальное normal confirmation заменяет их разными выбранными
клиентом календарными датами (`startDate=endDate`) в хронологическом порядке. Новое normal
confirmation отдаёт четыре warehouse-local даты со второго по пятый день и принимает от одной до
четырёх дат только из этого списка. Это пожелания клиента, а не заранее зарезервированная ёмкость
маршрута. Draft можно
сохранить без delivery facts, но создание отгрузки требует адрес, основной телефон и хотя бы одну
желаемую дату. Обычное manager-расписание документа — его `scheduledDate` и не содержит времени
суток; единственным источником подтверждённого точного окна является выделенная CustomerApp-граница
выше. Человекочитаемый телефон нормализуется в canonical E.164.

`CreateRentalInquiryRequest` может указывать существующий draft или сохранённый, но ещё редактируемый
заказ. Assistant inquiry сохраняет conversation ID; manual inquiry использует ту же сущность без
скрытого чата, а `GET /api/logistics/v1/rental-inquiries?rentalOrderId=...` повторно находит оба типа
с авторизацией заказа и склада. Повторные normal presentation добавляют бытовки в тот же заказ и
соблюдают его зафиксированный склад. Presentation reads объединяют live общий asset-остаток,
атомарно снятое содержимое выбранных held cabins и неназначенный физический излишек уже внутри этого
заказа; строки с нулевой общей доступностью сохраняют максимум на бытовку. Confirmation передаёт
мебель по бытовкам и атомарно конвертирует holds вместе с авторитетным полным составом мебели заказа.
Поэтому каждое публичное `NORMAL` presentation требует одну–четыре разрешённые сервером даты клиента,
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

Отдельный симулятор маршрутов интегрируется только через private-границу
`/api/internal/logistics/v1/planning/**` и exact service token `logistics-planner` с единственным
scope `logistics.planning`. Ограниченный feed передаёт identity/version сохранённого заказа,
адрес/координаты, ещё не включённые в отгрузки ID бытовок и все подтверждённые клиентом даты; у
подтверждённого CustomerApp-бронирования дополнительно передаются точные `windowStart`, `windowEnd`
и информационный положительный `travelZoneHours`, а также выведенный из вместимости объекта факт
доступа с прицепом. Симулятор сохраняет полосу для пояснения, но никогда не использует её или
тарифную зону для допустимости/ранжирования кандидатов. `FIXED_WINDOW` передаётся как прежний
жёсткий интервал; `DURING_DAY` — как мягкая date-only опция с null в полях окна планировщика,
чтобы оптимизатор выбрал допустимый час. Решающими остаются точные сегменты маршрута и каждое
жёсткое окно. Номера телефонов и мебель не передаются. Применение плана повторно использует
существующую idempotent
команду rental shipment с exact version заказа, конкретными бытовками и opaque worker ID task-board.
Автоматическое применение отклоняет сегодня и завтра, а существующая manager-команда остаётся
осознанным ручным override. Отдельно логист может выбрать нераспределённую будущую доставку и явно
опубликовать её как `WAREHOUSE_DRIVERS`: завтра разрешено, warehouse-local сегодня запрещено,
конкретный worker ID не сохраняется. Скрытые `UNASSIGNED` отгрузки остаются скрытыми. Симулятор
может перечитать только статусы созданных планировщиком назначений за один склад/день, поэтому после
claim общая доставка показывается с авторитетным водителем; обычные ручные документы и контакты
клиента исключены. Симулятор никогда не читает и не пишет базу RWMS напрямую.

Та же apply-команда может передавать `driverShiftPlans` для конкретного назначенного симулятором
водителя и рабочей даты. Logistics проверяет уникальность source shift и пары водитель/рабочая
дата, затем публикует все планы до применения отдельных shipment assignments через idempotent
`PUT /api/internal/task-board/v1/driver-shift-plans/{sourceShiftId}`. Snapshot содержит identity и
версию плана, склад, display snapshot водителя, назначенный автомобиль и необязательный прицеп,
начальный одометр, число ходок и точное неокруглённое `routeDistanceMeters`. Владельцем созданной
daily shift является task-board; logistics не хранит второй aggregate смены. Сгенерированные и
ручные jobs остаются только в симуляторе, потому что assignment apply по-прежнему принимает лишь
доставки с source `RWMS`. Private-вызов использует существующие service credentials logistics с
отдельным least-privilege scope `task-board.driver-shifts.plan`.

`GET /api/internal/logistics/v1/planning/warehouses` публикует только факты активных складов от
warehouse-service: `{warehouseId,name,city,address,timeZone}`; принадлежащий owner-у адрес nullable.
После проверки identity склада
`GET /api/internal/logistics/v1/planning/drivers?warehouseId=...` публикует только directory
task-board `{workerId,displayName}` для активных primary-qualified водителей. Связный
[`PlanningResourceDirectoryService`](src/main/java/dev/buhanzaz/rwms/logistics/planning/service/PlanningResourceDirectoryService.java)
закрывает доступ при malformed, duplicate или недоступных owner data и не хранит дублирующий
directory. Каждое чтение planning feed заново читает `SAVED` orders и их подтверждённые слоты
CustomerApp из logistics-owned stores. Поэтому новая доставка CustomerApp появляется при следующем
чтении feed с identity заказа и бытовок и `sourceRevision`; planner-local task ID через эту границу
не передаётся.

Каждая заявка разделяет `orderVersion`, который остаётся fence назначения rental order, и
64-символьный детерминированный `sourceRevision` всех экспортируемых planning-фактов. Ревизия также
охватывает факты подтверждённого слота и текущего остатка неотгруженных бытовок, принадлежащие
другим агрегатам. Поэтому независимое изменение слота или reservation обновляет симулятор без
выдуманного увеличения версии заказа, а различающийся payload с той же ревизией отклоняется как
source conflict.

`PUT /api/internal/logistics/v1/planning/capacity-snapshots/{warehouseId}` атомарно заменяет один
активный анонимный capacity snapshot склада, указанного авторитетным path. Request не повторяет ID
склада, а response не публикует устаревшую source identity. Команда содержит типизированные
координаты сгенерированных доставок и вывозов, жёсткие локальные окна, количество бытовок, время
обслуживания, priority, mandatory/trailer-факты, анонимные датированные смены с локальными
началом/концом, перерывом и вместимостью одна или две бытовки, а также от одного до двенадцати
упорядоченных тарифов изохрон. Tariffs начинаются с 60 минут и идут без пропусков с шагом 60 минут
до максимум 720 минут; каждый tier хранит фактическую неотрицательную цену в рублях. Цену и
дальнюю границу доставки определяет дорожное время Valhalla, а не принадлежность полигону или
расстояние по прямой. Допустимость прицепа остаётся фактом точного vehicle route и ёмкости задания,
а не политикой тарифной зоны.

Identity заказа, клиента, бытовки и персональные данные водителя не передаются. Idempotency key,
64-символьная source revision и warehouse-local монотонный `sourceGeneration` защищают exact replay
и stale replacement. Запоздалый retry принятого generation возвращает immutable ответ, а допустимая
последовательность revision `A -> B -> A` в трёх возрастающих generations применяет третий
snapshot. Реальные слоты доставки хранятся отдельно, поэтому замена capacity не удаляет и не меняет
бронирование. Сгенерированные доставки ограничивают маршрутизацию слотов CustomerApp;
сгенерированные вывозы остаются удаляемой работой обратного пути и никогда не вытесняют доставку.
Apply назначений принимает только доставки с source `RWMS`, поэтому сгенерированные и ручные jobs
остаются в симуляторе. См.
[`WarehouseCapacitySnapshotService`](src/main/java/dev/buhanzaz/rwms/logistics/customer/capacity/service/WarehouseCapacitySnapshotService.java)
и
[`V74__normalize_warehouse_isochrone_tariffs.sql`](src/main/resources/db/migration/V74__normalize_warehouse_isochrone_tariffs.sql).

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

Менеджер с правом EDIT создаёт фото-представление бытовки через
`POST /api/logistics/v1/cabins/{cabinId}/photo-presentations` с текущей asset version и стабильным
idempotency key. Logistics повторно проверяет доступ к складу и asset-owned fence бытовки через
отдельный asset photo-presentation snapshot, затем замораживает габариты, отделку, категорию,
упорядоченные названия характеристик, nullable-линолеум и по порядку от одного до 100 READY image
ID/generation из активной gallery folder media-service, после чего возвращает
бессрочный signed public path. Старые сохранённые gallery folders остаются в
CABIN archive и не смешиваются в новом immutable-представлении. Media также возвращает полный
логический `photoCount` неудалённых IMAGE associations активной folder, включая processing entries.
Создание fail-closed, если count вне `1..100` или не совпадает с ограниченным списком
READY/current-generation: 101-е логическое изображение или незавершённая обработка не создадут
тихо усечённое presentation. READY-позиции должны быть уникальными и непрерывными `0..N-1`;
duplicate или gapped order fail-closed. Anonymous metadata и SMALL/LARGE
media reads ограничены маршрутами `/api/logistics/public/v1/cabin-photo-presentations/{token}/**`;
ответ содержит только номер бытовки, эти пять allowlisted display fields, время создания и
неизменяемые ссылки на фото. В нём никогда нет warehouse, status, rental type или unrestricted
passport data. Pre-V56 представления остаются читаемыми с null catalog fields, пустым списком
characteristics и null linoleum. Media bytes остаются приватными и проксируются лишь после проверки
token, membership в snapshot, generation и variant. Exact replay команды возвращает то же
представление, а повтор idempotency key с другими входными данными завершается conflict.

## Внутренняя структура приложения

`HttpLogisticsDependencyGateway` — стабильная реализация private dependency
port. Его неизменённый constructor собирает шесть owner clients, а facade
делегирует каждую операцию интерфейса:

`LogisticsDependencyGateway` теперь только source-compatible композиция шести
связных портов: `LogisticsWarehouseDependencyPort`,
`LogisticsAssetOperationsDependencyPort`, `LogisticsMaintenanceDependencyPort`,
`LogisticsMediaDependencyPort`, `LogisticsTaskBoardDependencyPort` и
`LogisticsOrderPresentationDependencyPort`. Существующие вложенные transport-типы
и call sites сохраняют имена; бизнес-решения остаются в owning workflow services
и leaf clients.

| Owner client | Private boundary |
| --- | --- |
| `LogisticsWarehouseDependencyClient` | Warehouse identity/directory, включая nullable owner address, admission, timezone и lifecycle |
| `LogisticsAssetOperationsDependencyClient` | Rental и photo-presentation snapshots, leases, fenced effects, equipment holds и movements |
| `LogisticsAssetOrderPresentationDependencyClient` | Order units, reservations, cabin availability/search и presentation holds |
| `LogisticsMaintenanceDependencyClient` | Transfer repair, estimate source, capital repair и repair-place calls |
| `LogisticsMediaDependencyClient` | Media validation, owner proof, evidence, snapshots и binary presentation media |
| `LogisticsTaskBoardDependencyClient` | Movement tasks, driver queue/task, directory активных qualified водителей, board и completion calls |
| `LogisticsOAuthHttpTransport` | Только exact-scope client credentials, HTTP exchange и существующий dependency error mapping |
| `RentalInquiryCabinSearchService` | Non-transactional последовательность warehouse/asset calls над одной frozen downstream command |
| `RentalInquiryCabinSearchStore` | Locked PREPARE/COMPLETE/REJECTED/EXPIRED receipt transactions и frozen-response replay |
| `RentalInquiryCabinSelectionService` | Owner-scoped чтение authoritative holds и exact full-selection replace/release calls вне local transactions |
| `RentalInquiryCabinSelectionStore` | Locked PREPARE/COMPLETE/REJECTED/EXPIRED transactions selection receipt, exact-byte retry и frozen-response replay |
| `RentalInquiryCabinCatalogService` | Ограниченный facts-only cabin lookup с authorization inquiry, warehouse и owner |
| `CustomerRentalService`, `CustomerRentalTermCodec` | Version-fenced полный набор сроков по бытовкам корзины, canonical JSON и exact selected-cabin cardinality |
| `CustomerBookingService`, `CustomerReceptionResponseMapper` | Прибытие по exact completed shipment member, subject-owned booking reads, подписанная приёмка и immutable problem reports |
| `LogisticsDocumentService` | Стабильный facade return/shipment/transfer и rental-order hooks над семью точными owners |
| `HistoricalRentalMovementCoordinator` | Приём одной исторической отгрузки/возврата одной бытовки и клиента плюс version-fenced исправление существующего импортированного shipment; сохраняет необязательную audit metadata водителя shipment и создаёт обычные документы/durable owner effects без driver work или browser-owned saga |
| `CabinPhotoPresentationService`, `CabinPhotoPresentationStore`, `CabinPhotoPresentationTokenService` | Version-fenced неизменяемый snapshot READY-фото, exact idempotent replay и бессрочная signed public capability без общего media proxy и приватных данных бытовки |
| Coordinators документов return, shipment и transfer | Независимые document state machines с исходным порядком transaction и recovery |
| `LogisticsShipmentCancellationRecovery` | Проверки evidence отмены shipment, закрытие audit исторической reconciliation и exact повторное открытие lease-attempt с потерянным ответом; shipment coordinator сохраняет document state machine и порядок компенсации |
| `DocumentDriverTaskPlanner` | Одно idempotent document-owned задание с упорядоченными участниками-бытовками на каждую новую запланированную shipment, return или transfer; ожидающие legacy line tasks сходятся в группу, а начатые блокируют replanning |
| `TransferPlanService`, `TransferPlanWorkflowStore` | Владелец versioned-жизненного цикла transfer draft/confirmation/departure/arrival/cancellation; координирует fenced-резервы asset, trip commitments и явное перемещение ресурсов без изменения остатков во время оценки черновика |
| `TransferDriverTaskContentService`, `DriverTaskWorkerContentCodec` | Детерминированные точные инструкции маршрута/груза/мебели transfer поверх существующей DriverApp/WorkerApp-проекции task-board, сохранённые для идемпотентных retries и замены до старта |
| `DriverTripProjectionService` | Structured task/board facts ходки с одним asset read на отдельный заказ и явным unavailable readiness при dependency failure |
| `ShipmentTaskSettingsService` | Warehouse-scoped version-fenced лимит, повторно используемый каждой сгруппированной ходкой; атомарно материализует default one и отклоняет over-limit planning |
| Coordinators rental-order shipment/completion и reconciliation | Document hooks для rental shipment, terminal return и команды reconciliation request |
| Политики document admission, idempotency, attempts, reads и binding | Узкие leaves warehouse, replay, external-attempt, projection и active-order |
| `RentalOrderService` | Стабильный order facade над reads, creation, lifecycle, reservations, terms и shipment hand-off |
| `RentalOrderUnitReplacementService` | Direct и presentation replacement через ordered batch checkpoints, pre-start отмену driver task и сходимость order/document members |
| `RentalOrderPlanningIntegrationService` | Минимальный versioned feed планировщика и idempotent применение через существующего владельца rental shipment без общего состояния БД |
| `PlanningResourceDirectoryService` | Проверенные least-privilege warehouse и active primary-driver resources от их exact domain owners без local projection |
| `WarehouseCapacitySnapshotService` | Per-warehouse idempotent замена capacity, monotonic generation fencing и canonical persistence упорядоченных часовых тарифов изохрон |
| `FutureDriverTaskClaimService` | Future-only preview/claim общего задания с проверкой квалификации и versions в task-board; выполнение не запускается |
| `ClientDeliveryDatePolicy` | Warehouse-local окно обычного public confirmation со второго по пятый день |
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

Migration
[`V53__future_shipment_driver_pool.sql`](src/main/resources/db/migration/V53__future_shipment_driver_pool.sql)
добавляет false-by-default intent отгрузки `warehouse_driver_pool`. Database constraint разрешает
флаг только для shipment без конкретного worker, поэтому существующие скрытые unassigned и
назначенные документы сохраняют прежний смысл и не переклассифицируются. Task-audience constraint
расширяется только для shipment `WAREHOUSE_DRIVERS`; у returns остаются прежние assigned-or-hidden
режимы.

Миграция
[`V54__historical_rental_documents.sql`](src/main/resources/db/migration/V54__historical_rental_documents.sql)
добавляет false-by-default факт документа `historical_rental_import` и узкий read index. Существующие
документы не меняются; флаг отмечает только введённую пользователем прошлую физическую отгрузку или
возврат, для которой намеренно нет задания водителя RWMS.

Миграция
[`V55__cabin_photo_presentations.sql`](src/main/resources/db/migration/V55__cabin_photo_presentations.sql)
добавляет logistics-owned неизменяемый photo snapshot, subject-scoped уникальность idempotency и
ограничения JSON/числа фото. В ней нет expiry column; media bytes не копируются, а существующие строки
бытовок, клиентов, заказов и документов не изменяются.

Миграция
[`V56__cabin_photo_presentation_metadata.sql`](src/main/resources/db/migration/V56__cabin_photo_presentation_metadata.sql)
добавляет один обязательный неизменяемый metadata JSON snapshot. Существующие строки представлений
получают пустой объект и поэтому остаются читаемыми с null/empty public metadata; миграция не
обращается к asset-service, не переписывает membership фотографий и не создаёт live mutable projection.

Миграция
[`V57__historical_rental_movement_idempotency.sql`](src/main/resources/db/migration/V57__historical_rental_movement_idempotency.sql)
разрешает `CREATE_HISTORICAL_RENTAL_MOVEMENT` и добавляет nullable object-shaped `response_json` в
существующий command receipt. Для старых семейств команд поле остаётся nullable; receipt и logistics
document не переписываются, все ранее разрешённые операции сохраняются.

Миграция
[`V58__historical_rental_movement_update_idempotency.sql`](src/main/resources/db/migration/V58__historical_rental_movement_update_idempotency.sql)
добавляет `UPDATE_HISTORICAL_RENTAL_MOVEMENT` в ту же проверку и требует immutable response JSON
для обеих historical operations. Она не переписывает исторические receipts, documents, lines или events.

Миграция
[`V65__warehouse_capacity_identity_and_tariff_zones.sql`](src/main/resources/db/migration/V65__warehouse_capacity_identity_and_tariff_zones.sql)
переименовывает все пять capacity tables, constraints и indexes в warehouse-owned имена, сохраняет
каждую строку snapshot/job/shift/receipt/zone, удаляет дублирующую source identity, а также code и
priority тарифной зоны. Customer slot quote теперь хранит UUID `price_zone_id`: parseable UUID и
code, всё ещё совпадающий ровно с одной активной зоной склада, переносятся до удаления старой колонки;
сохранённая цена без доступного соответствия честно остаётся с null identity зоны, а не получает
выдуманный UUID. V74 позже удаляет активный расчёт зон, сохраняя эти исторические поля quote.

Миграции
[`V67__interwarehouse_transfer_planning.sql`](src/main/resources/db/migration/V67__interwarehouse_transfer_planning.sql)
и
[`V68__transfer_plan_reservation_workflow.sql`](src/main/resources/db/migration/V68__transfer_plan_reservation_workflow.sql)
добавляют transfer plan, группы конфигурации, точные allocations, мебель на бытовку и отдельную
мебель, intents ресурсов рейса/перемещения и durable checkpoints резервирования/workflow. Оценка
черновика остаётся без side effects, а для принадлежащих asset/task-board агрегатов хранятся только
opaque IDs.
[`V69__warehouse_isochrone_tariffs_and_zone_policies.sql`](src/main/resources/db/migration/V69__warehouse_isochrone_tariffs_and_zone_policies.sql)
добавляет настраиваемые цены склада для 60/120/180/240 минут, точный зафиксированный диапазон
изохроны и warehouse-scoped snapshots ограничений `FORBIDDEN`/`NO_TRAILER`; special-price zones
сохраняют существующую роль явной цены.
[`V70__furniture_only_transfer_driver_tasks.sql`](src/main/resources/db/migration/V70__furniture_only_transfer_driver_tasks.sql)
разрешает driver task transfer без бытовки и точного назначенного водителя рейса без расширения
аудитории остальных перемещений.
[`V71__shipment_inventory_source_warehouse.sql`](src/main/resources/db/migration/V71__shipment_inventory_source_warehouse.sql)
заполняет и фиксирует физический источник по строке документа, сохраняя service warehouse документа.
[`V72__replacement_inventory_source_checkpoint.sql`](src/main/resources/db/migration/V72__replacement_inventory_source_checkpoint.sql)
сохраняет этот источник в существующем recovery checkpoint замены/мебели.
[`V73__driver_task_worker_content.sql`](src/main/resources/db/migration/V73__driver_task_worker_content.sql)
добавляет object-shaped structured worker snapshot без складских effects и с обратно совместимым
значением `{}` для всех исторических заданий.
[`V74__normalize_warehouse_isochrone_tariffs.sql`](src/main/resources/db/migration/V74__normalize_warehouse_isochrone_tariffs.sql)
переносит четыре фиксированные цены snapshot в нормализованные дочерние часовые tariffs, разрешает
непрерывные tiers с пятого по двенадцатый и удаляет фиксированные price columns вместе с активными
таблицами price/restriction zones. Исторические цена customer slot и `price_zone_id` сохраняются;
новые quotes всегда используют настроенный tier от 60 до 720 минут.

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

Публикация планов смен использует существующие `TASK_BOARD_SERVICE_URL`, `AUTH_TOKEN_URI`,
`LOGISTICS_CLIENT_ID` и `LOGISTICS_CLIENT_SECRET`; новый credential или прямое подключение к чужой
БД не вводится. Auth-service должен выдать тому же service client точный scope
`task-board.driver-shifts.plan`.

Секрет presentation token должен иметь не менее 32 символов, а production отклоняет известный local
default. Основной API использует stateless OAuth2/JWT; dev auth bypass ограничен профилем `dev`.

Клиентская доставка fail-closed, пока не заданы `LOGISTICS_CUSTOMER_DELIVERY_ENABLED=true`, private
`LOGISTICS_CUSTOMER_VALHALLA_URL` и хотя бы один включённый индексированный depot. Текущие две
позиции реестра используют `LOGISTICS_CUSTOMER_DEPOT_0_*` и `LOGISTICS_CUSTOMER_DEPOT_1_*`; для
каждой обязательны `ENABLED=true`, `WAREHOUSE_ID`, `LATITUDE` и `LONGITUDE`. Выключенные позиции
игнорируются, а пустой реестр, повтор UUID склада или неверные координаты приводят к fail-closed.
Прежние single-depot настройки `LOGISTICS_CUSTOMER_WAREHOUSE_ID`,
`LOGISTICS_CUSTOMER_DEPOT_LATITUDE` и `LOGISTICS_CUSTOMER_DEPOT_LONGITUDE` больше не читаются.
`LOGISTICS_CUSTOMER_SERVICE_MINUTES`, горизонт бронирования, сроки offer/hold и размеры/массы
грузовика задают общую модель ёмкости и route profile; текущая доступность водителей и вместимость
машин берутся только из опубликованных симулятором смен. Точные имена и defaults находятся в
`application.yaml`. Valhalla должна оставаться private и в Compose
логистического симулятора публикуется на host только через loopback.

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
