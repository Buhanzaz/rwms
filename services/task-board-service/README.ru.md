# Task Board Service RWMS

[English version](README.md)

`task-board-service` владеет операционным каталогом очередей, warehouse queue
projections, registry и группами работников, assignments, состоянием task board,
исполнением работниками, evidence linkage и KPI settings/evidence. Source-домены
могут запросить задачу через явный private contract, но не изменяют task state
или workforce tables напрямую.

## Зачем он нужен

Maintenance, logistics, inventory и менеджерам нужна операционная работа, но у
одной queue entry должен быть один владелец порядка, назначения, исполнения,
паузы, завершения и истории. Task-board централизует эту operational truth,
сохраняя исходные факты и решения у их доменных владельцев.

| Задача | Ответственность task-board | Внешняя ответственность |
| --- | --- | --- |
| Queue standard | Global definitions, warehouse bindings, order, capabilities и usage references | Maintenance/logistics регистрируют только contract-defined references |
| Workforce | Worker classes, workers, groups, current membership и credentials workflow | Auth-service владеет credential material и token issuance |
| Operational work | Tasks, route entries, assignment, pinning, pause/resume/complete и history | Source domain владеет причиной работы и состоянием своего агрегата |
| Native execution | Раздельные driver/worker feeds, offline action leases, evidence reservation, SSE и transactional FCM invalidation | DriverApp и WorkerApp обновляют authoritative REST state и загружают media через media-service |
| Ежедневная смена водителя | Рабочая дата склада, state machine подготовки/закрытия, snapshot осмотра, дефекты, audit timestamps и media proof | Logistics передаёт проверенный план водитель/машина/дата; warehouse владеет identity/timezone; media владеет байтами |
| KPI | Installation-wide display palette, work-schedule revisions и emitted daily evidence | Analytics владеет KPI read projection |
| Warehouse lifecycle | Local operation marks, admission fence, draining blockers и exact-version readiness | Warehouse-service владеет lifecycle state и admission decisions |

Сервис не владеет users/roles, warehouse identity, repair или logistics
aggregates, media bytes, analytics projections или gateway routing.

Каждая публичная операция, привязанная к складу, сначала получает актуальную
identity склада из warehouse-service, а уже затем проверяет роль подписанного
principal, warehouse grant или home-склад работника. Поэтому `SYSTEM_ADMIN` и
`WMS_ADMIN` не обходят warehouse authorization rules при прямой подстановке
warehouse ID.

## Поток команды и задачи

```text
manager / source service
          |
          v
public- или exact-credential private-команда
          |
          v
authorization + warehouse admission + idempotency/expectedVersion
          |
          v
транзакция task / route / queue-entry
          |
          +--> append-only history + domain event + outbox
          +--> worker invalidation
          +--> Kafka fact для projections/owners

driver app --> primary feed/detail --> take/action/evidence reservation
worker app --> обычная работа + active slinger feed --> join/action/evidence reservation
           --> media upload --> media fact --> shared completion
logistics-service --> snapshot точного задания подрядчика --> evidence reservation --> start/complete
```

Source-owned task использует stable external identity, поэтому retry находит ту
же задачу, а не создаёт duplicate. Mutable entry operations fenced по
contract-defined version и status. Route order, eligibility, assignment и
terminal transitions остаются server-owned.

Private pre-start replacement считает no-op только полностью идентичный по значению snapshot и
возвращает его текущую регистрацию, даже если после потерянного ответа у caller осталась старая
версия task. Любое изменение заголовка, metadata или route по-прежнему требует точную текущую
версию и полностью не начатое задание; иначе команда завершается конфликтом.

Обычная доска ремонтов — единое агрегированное представление склада, а не календарь. Каждая
manager-очередь возвращает все незавершённые `REAL` и `SHADOW` entries. Приоритет регистрации уже
отражён в сохранённой позиции очереди; чтение не сортирует по приоритету второй раз. Сначала идёт
активная работа, затем ожидающие REAL в порядке pin/позиции, а после них — будущие `SHADOW`.
`availableTaskLimit` физической очереди (начально шесть) отмечает первые ожидающие REAL в плане
manager-а, не обрезая полный manager-ответ. Global queue definition задаёт только начальное
значение для новой проекции склада. Затем складская очередь сама владеет этим числом и
`workerFeedEnabled`. Maintenance-маршруты нормализуются в порядок `СЭС -> сварка -> внешние ->
внутренние -> электрика -> сантехника`. Первая существующая незавершённая фаза по умолчанию
становится `REAL`; отсутствующие или завершённые фазы пропускаются, а все последующие начинают как
`SHADOW`. Пользователь с `EDIT` может под version fence явно переключить строго будущий обычный
`WAITING` entry между `SHADOW` и `REAL`. Будущий `REAL` допускается к публикации в WorkerApp и
параллельному выполнению без предварительного завершения более ранней обычной стадии; при этом
по-прежнему действуют switch и waiting-real план складской очереди, а также квалификация работника.
Самую раннюю незавершённую стадию нельзя понизить, а незавершённый СЭС запрещает открывать любую
более позднюю стадию. При promotion будущий entry возвращает сохранённое место перед более поздней
незакреплённой работой и в той же транзакции обновляет media-owner proof, выдавая или отзывая
соответствующую аудиторию чтения исходных evidence; закреплённый REAL остаётся впереди. Любой gate
`REAL` можно взять, а `SHADOW` никогда не является исполнимым.

Каноническая стадия СЭС остаётся единственной исполнимой карточкой задачи до конца обработки, в том
числе для сохранённых definitions с историческим типом очереди `REPAIR`. Manager snapshot содержит
её последующие read-only shadows, чтобы панель могла явно показать полный маршрут; WorkerApp во
время обработки по-прежнему получает только gate СЭС. В публичной доске нет выбора даты,
перемещения entry между очередями, обмена дат, планирования по daily capacity и фонового rollover
просрочки. Manager может переставлять незакреплённые `WAITING REAL` внутри их текущей очереди под
version fence entry и queue плюс проверку identity наблюдавшейся целевой карточки; active work,
pinned-карточки, shadows и queue identity не двигаются. План на день не создаёт датированное
расписание. Датированное планирование водителей и отгрузок остаётся в отдельных logistics
surfaces. Инварианты подтверждаются
[`task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml),
[`TaskBoardReadProjectionService`](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardReadProjectionService.java),
[`TaskBoardFutureAvailabilityService`](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardFutureAvailabilityService.java) и
[`OrdinaryQueueAvailabilityPolicy`](src/main/java/dev/buhanzaz/rwms/taskboard/service/OrdinaryQueueAvailabilityPolicy.java).

Display palette KPI и рабочий график образуют одну installation-wide настройку с общей version fence.
Склад не выбирается ни для одной из этих настроек. Один активированный график задаёт всем
складам одинаковые локальную дату
вступления, смену, перерывы и выходные, а operational clock каждого склада интерпретирует их в его
authoritative timezone. Сохранение оставляет ревизию в `DRAFT`; активация идемпотентно планирует
её, а ревизия на текущую UTC configuration date сразу становится `ACTIVE`. Будущая ревизия
остаётся `SCHEDULED` до своей даты, а дата раньше текущей UTC-даты отклоняется. Активация замены на
ту же дату выводит предыдущую scheduled/active ревизию из действия под существующими receipt и
общей optimistic-concurrency fence. Поведением владеют
[`KpiSettingsService`](src/main/java/dev/buhanzaz/rwms/taskboard/service/KpiSettingsService.java) и
[канонический контракт](../../contracts/openapi/task-board-service.yaml).

Flyway V31 нормализует существующие значения `queue_entry.queue_position` в эту агрегатную
последовательность и исправляет real/shadow-форму полностью ожидающего holding-маршрута. Она не
переписывает append-only историю `domain_event`. Поэтому replay-сравнение допускает только различия
`queuePosition` и `entryType` у queue-entry tail, записанного не позже успешного cutover V31; каждый
последующий event tail и каждое другое поле сравниваются точно. Cutover читается из
`flyway_schema_history`, поэтому rebuild сохраняет immutable facts, не ослабляя обнаружение дрейфа
после миграции. Правило реализовано в
[`TaskBoardReplayVerifier`](src/main/java/dev/buhanzaz/rwms/taskboard/eventing/TaskBoardReplayVerifier.java).

Flyway V33 фиксирует позиции шести глобальных колонок и переставляет только активные
maintenance-маршруты, все entries которых ещё находятся в `WAITING`; начатые, приостановленные,
завершённые, logistics- и не-maintenance-маршруты не меняются. Durable ledger миграции проекции
перечисляет точные work queues и entries, чья event-sourced проекция изменилась. Replay-совместимость
ограничена этими aggregate ID и tail до V33 и только полями `sortOrder`, `routeIndex` и `entryType`;
все последующие tail по-прежнему сравниваются точно.

Flyway V34 добавляет `worker_feed_enabled=true` каждой существующей физической очереди, не меняя её
сохранённое число плана. Пользователь с `MANAGE` может менять switch и число одной складской
очереди. WorkerApp не получает ни одной карточки выключенной обычной очереди, включая активную
работу; во включённой очереди он никогда не получает `SHADOW`, сохраняет активную `REAL`-работу и
получает только первое настроенное число ожидающих `REAL`. Native detail, media-reader proof и
`TAKE`/`JOIN` повторяют тот же серверный fence. Work-queue facts совместимо добавляют оба поля, а
replay удаляет их только при сравнении immutable historical facts, записанных до этого добавления.

Logistics driver task дополнительно несёт одну сохранённую аудиторию:
`UNASSIGNED`, `ASSIGNED_DRIVER` или `WAREHOUSE_DRIVERS`. Задавать её может
только точный driver-task source logistics-service. Только назначенная работа содержит worker
identity; этот worker должен быть активен. Штатный worker обязан иметь primary qualification
целевой водительской очереди. Точное назначение активного `CONTRACTOR` — узкое исключение, потому
что профиль подрядчика не имеет staff qualification, group или app credential; подрядчик никогда
не становится кандидатом identity-free пула `WAREHOUSE_DRIVERS`. Точный `ASSIGNED_DRIVER` может
сохранять другой домашний склад; identity-free работа остаётся доступной только на своём складе. Task-board
игнорирует переданное caller-ом display name и сохраняет
авторитетный worker snapshot. Неназначенная задача остаётся работой диспетчера. Назначенную видит
только этот водитель. Ожидающую identity-free общую задачу видят все квалифицированные водители
склада до take, после чего доступ остаётся только у фактического исполнителя. DriverApp получает
видимую primary work из lane `SCHEDULED` и `CURRENT` через `/api/driver/v1/**`, поэтому датированный
экран показывает назначенную будущую работу, точные межскладские назначения и общие будущие
варианты. Для remote detail, actions, evidence ownership и task content физический склад
определяется по серверной entry; домашний склад worker/JWT не меняется. WorkerApp получает обычную
работу и только активную `CURRENT` secondary logistics work через `/api/worker/v1/**`; scheduled
работа водителей не попадает на поверхность стропальщика. Take водителя транзакционно сохраняет
push `TASK_JOIN_AVAILABLE` для подходящих стропальщиков. Ожидающая logistics task анонсируется
только в DriverApp SSE: WorkerApp не получает ни pre-take `NEW_TASK`, ни entry ID из другой surface.
Стропальщик присоединяется из current group; если он выполнял другое групповое задание, task-board
ставит на паузу всю предыдущую entry и
возобновляет её после закрытия совместной задачи. Каждый logistics-driver secondary binding
необязателен для native execution и остаётся interrupting, когда стропальщик присоединяется.
Водитель может закрыть задание до JOIN стропальщика; после JOIN закрыть может любой активный
участник при хотя бы одном READY result photo от любого участника. Primary assignment без группы
никогда не становится secondary assignment, даже если у водителя также есть квалификация
стропальщика. Только private source replan boundary может заменить
audience под общим task/entry version fence; публичная обычная доска предоставляет только
перестановку ожидающих карточек внутри одной очереди, но не logistics replanning и не cross-queue
move.

Существующая SSE-подписка DriverApp остаётся привязанной к домашнему складу. Точная remote work
сходится через authoritative REST feed: opaque revision DriverApp использует максимальное значение
из одной глобальной revision sequence, поэтому мутация любого склада меняет comparison token.
Консервативный token может вызвать лишнее обновление из-за чужого склада, но response data всё
равно фильтруется точной аудиторией; отдельное remote-событие пока не создаёт адресный home SSE
item.

Private directory водителей для exact logistics-service разрешает ownership workforce при каждом
чтении. Для одного склада она возвращает только `{workerId, displayName}` активных работников, чья
активная primary qualification соответствует активным logistics-driver queue и definition этого
склада, в порядке normalized display name, затем UUID. Secondary bindings, неактивные работники,
queues, definitions и qualifications исключаются; login, group, contact, credential и остальные
персональные поля через эту границу не проходят.

Private-граница contractor execution принимает только точный SERVICE credential logistics-service
с единственным scope `task-board.logistics`. Каждое чтение и каждая команда доказывают source client
`logistics-service`, source type `LOGISTICS_DRIVER_TASK`, `ASSIGNED_DRIVER`, точный активный
`WorkerEmploymentType.CONTRACTOR` и принадлежность точному route. Snapshot содержит упорядоченные
entry/status/version, worker-visible title, description, unit, task text, works, materials,
comments и immutable source-media identities, generation и content type. В нём нет телефона,
credentials, общей доски, чужих задач или bearer-only media read paths. На этой границе task-board
не владеет отдельными структурированными адресом/координатами клиента; адрес доступен лишь тогда,
когда source уже передал его в worker-visible text. `START` и `COMPLETE` делегируются
`TaskBoardWorkerExecutionService`, сохраняют порядок route и optimistic entry versions и используют
общий native-инвариант ready/selected result evidence. Stable operation UUID использует существующий
immutable `worker_action_receipt`; response возвращает изменившуюся entry version для следующей
команды. Пока точный route entry находится в `IN_PROGRESS`, та же private-граница может
зарезервировать identity результата через существующие `worker_task_evidence` и entry-owner-proof
pipeline. Task, route, warehouse, worker и logistics source facts выводятся сервером, каждый request
и replay заново доказывает live-назначение подрядчика, а native offline lease не принимается и не
выдаётся. Response содержит только owner и declared media metadata для mediated upload; upload/read
path в нём нет. Миграция схемы не требуется.

Регистрация внешнего задания не выводит дату из глобального Moscow-timezone
или календаря сервера. Явный `scheduledDate` остаётся авторитетным; иначе
deadline instant, либо инъецированное серверное время при отсутствии deadline,
преобразуется через `WarehouseTimeZoneGateway` для склада задания. Та же
warehouse-local дата определяет, нужно ли публиковать уведомление о доступности
запланированного задания сегодня.

## Ежедневный жизненный цикл смены водителя

Task-board является авторитетным владельцем одной смены Driver Up на
`driverId + workDate`. Сначала logistics регистрирует проверенный план
водителя/автомобиля по стабильной source-shift identity; только после этого
startup-read водителя может заморозить plan в shift. Startup-транзакция получает
текущие identity и IANA timezone склада из warehouse-service, применяет
настраиваемую локальную границу 06:00, блокирует соответствующий plan и создаёт
не более одной shift. Повторные и конкурентные чтения возвращают тот же
aggregate.

Зарегистрированный plan может дополнительно содержать одну непрерывную
последовательность операций. Task-board проверяет sequence, временной порядок,
identity конечных точек и цепочку загрузки, затем сохраняет операции под
заменяемым plan в `driver_shift_route_operation`. Более новая source-версия
может целиком заменить список только до того, как реальная смена заморозит plan;
после этого plan и операции неизменяемы. `GET /shift/today` возвращает
упорядоченный snapshot с плановыми instant прибытия/отправления и переходами
загрузки. Локальные маршруты совместимы с отсутствующим/пустым списком, а
межскладской snapshot обязан обрамлять складские/клиентские операции стартом на
исходном складе, входящим и обратным перегоном.

Неизменяемый snapshot автомобиля может дополнительно содержать точную эффективную
`cabinCapacity`. Transfer-груз использует парные операции `TRANSFER_LOAD`/`TRANSFER_UNLOAD` с одним
каноническим `sourceTransferId` на складе старта и входящем складе назначения. Task-board отклоняет
дублирующиеся или несбалансированные identity, обратное изменение загрузки, неверные endpoints и
превышение cabin capacity на конкретном участке. Перемещение только мебели остаётся физической
парой операций, но намеренно не меняет число бытовок в загрузке. Legacy plan может не передавать
capacity только пока в нём нет transfer-cargo операций.

Восстановление опубликованного плана переиспользует те же агрегаты заданий и смен через три
доступные только logistics команды: `PREPARE`, `COMMIT` и `RELEASE` под
`/internal/task-board/v1/logistics/planning-replan-holds/**`. `PREPARE` блокирует полный состав
source plan и точные fences задания, entry, смены, склада, даты и версии. `COMMIT` атомарно
сохраняет lineage tombstone удалённого участника и заменяет ревизию каждого оставшегося задания и
смены; `RELEASE` допустим только пока hold остаётся prepared. Уже отменённое владельцем удаляемое
задание принимается лишь в точной паре состояний task/entry
`CANCELLED/SCHEDULED/CANCELLED` до старта и второй раз не отменяется; каждый оставшийся участник
обязан оставаться неназначенным `ACTIVE/SCHEDULED/WAITING`. Flyway
[`V44`](src/main/resources/db/migration/V44__published_plan_reschedule_hold.sql) владеет hold и
полями tombstone, а
[`V45`](src/main/resources/db/migration/V45__single_active_planning_replan_hold.sql) разрешает только
один prepared hold на source lineage.

JWT DriverApp по-прежнему идентифицирует worker и его неизменяемый домашний
склад. Перед созданием новой смены task-board выводит текущий оперативный склад
из истории назначений. Только временное назначение в состоянии `ACTIVE` или
завершённое постоянное назначение может выбрать склад назначения; состояния
`PLANNED` и `IN_TRANSIT` не раскрывают и не создают смену на складе назначения.
Рабочую дату и замороженную смену определяет IANA timezone оперативного склада.
После создания незакрытая смена восстанавливается по точной паре водитель/смена
даже после окончания временного назначения, при этом каждая команда продолжает
сверять домашний склад из JWT с профилем worker. Фотографии смены и другие
warehouse-owned эффекты используют склад замороженной смены, а не home claim.

Явная последовательная state machine:
`DAILY_BRIEFING_REQUIRED -> MEDICAL_CHECK_REQUIRED ->
VEHICLE_INSPECTION_REQUIRED -> READY_TO_START -> SHIFT_ACTIVE ->
SHIFT_CLOSING -> RETURN_TO_WAREHOUSE_REQUIRED ->
END_VEHICLE_CHECK_REQUIRED -> SHIFT_READY_TO_CLOSE -> SHIFT_CLOSED`.
Каждый публичный ответ содержит `nextRequiredAction`; после перезапуска Android
продолжает с этой серверной проекции и не двигает workflow локальными boolean.
Бизнес-подтверждения используют серверное время. Текущий medical-шаг явно
помечен `SELF_CONFIRMATION_TEST`, а nullable-поля внешней проверки и
`EXTERNAL_MEDICAL_SYSTEM` сохранены для будущего провайдера.

При создании смены копируется активный DB-backed шаблон осмотра для `TRUCK`,
`TRUCK_WITH_TRAILER` или `TRUCK_WITH_CRANE`. У каждого обязательного пункта есть
собственная версия и результат `NOT_CHECKED`, `OK` либо `DEFECT`. Сообщённый
дефект нельзя стереть переключением пункта в OK; новые дефекты предрейсового
осмотра консервативно считаются `BLOCKING` и запрещают обычный старт. Для работы
используется существующий экран logistics-задач. Task-board переводит активную
смену к закрытию только когда для точного водителя на эту дату существует хотя
бы одна задача среди всех физических складов и все такие задачи имеют статус
`DONE`.

Closing отдельно сохраняет ручное возвращение на склад, состояние автомобиля,
пробег и топливо. Пробег не может уменьшиться; настраиваемый подозрительный
скачок требует явного подтверждения. End-of-shift дефект связан с общей моделью
vehicle defect и до закрытия требует коррелированное media
`END_SHIFT_DEFECT` в авторитетном состоянии `READY`. Необязательные обзорные
фото используют тот же media owner, upload и event path. Переходы и
резервирования фото version-fenced и receipt-backed: точный retry идемпотентен,
а изменённый replay конфликтует.

Погода является информационной. `MetNoWeatherProvider` нормализует бесплатные
данные MET Norway Locationforecast, делит bounded ETag-aware cache по
округлённым координатам склада и после ограниченных timeout/retry возвращает
unavailable DTO. `WeatherHazardRules` создаёт только настраиваемые рекомендации
и никогда не выдаёт их за официальное экстренное предупреждение. Доступность
погоды не влияет ни на один переход смены.

## Внутренняя структура приложения

`TaskBoardService` — стабильный transactional facade над связными collaborators.
Он сохраняет прежнюю поверхность методов controllers/private boundaries, а
решениями владеют следующие компоненты:

| Компонент | Владеющая ответственность |
| --- | --- |
| `TaskBoardReadProjectionService` | Чтения board/task и response projections |
| `DailyBrigadeActivityService` | Warehouse-local проекция фактических интервалов взятия/завершения assignments текущего дня с ограниченным объединением пересекающихся legacy-дублей |
| `TaskBoardExternalRegistrationService` | Создание source-owned task, identity, route и canonical retry fingerprint |
| `TaskBoardExternalMutationService` | Source-authorized pre-start update/cancel, перемещение lane и relocation |
| `TaskBoardLogisticsTaskService` | Граница logistics equipment/driver task |
| `TaskBoardWorkerExecutionService` | Assignment, timing, interruption, cancellation и worker execution |
| `TaskBoardFutureAvailabilityService` | Version-fenced promotion/demotion строго будущих ordinary entries, включая gate СЭС, transactional media-reader proof и post-commit invalidation WorkerApp |
| `TaskBoardPinningService` | Version-fenced manager-команда pin/unpin |
| `TaskBoardEntryOrderingService` | Перестановка незакреплённых ожидающих REAL внутри очереди под entry/queue/target-identity fences |
| `WorkerQueuePlanService` | Warehouse-local switch публикации WorkerApp и команда waiting-real плана |
| `WorkerQueuePlanPolicy` | Общий fence публикации WorkerApp для feed, detail, TAKE и media readers |
| `TaskBoardQueuePositionCoordinator` | Только advisory locks, stream fences и persisted queue/pin ordering |
| `TaskBoardRoutePayloadCodec` | Единственный canonical route JSON и fingerprint codec |
| `DriverTaskAudienceService` | Shape аудитории logistics-driver, qualification, visibility и execution authorization |
| `LogisticsDriverDirectoryService` | Least-privilege directory активных primary logistics-drivers для exact caller logistics-service |
| `ContractorDriverService` / `WorkerOperationalAssignmentService` | Принадлежащий складу каталог вызываемых по необходимости подрядчиков и датированные оперативные назначения; профиль подрядчика не требует автомобиля или модели внутреннего route cycle |
| `ContractorTaskExecutionService` | Snapshot точного logistics-owned route подрядчика, evidence reservation и replay-safe adapter START/COMPLETE поверх существующей worker state machine и evidence invariant |
| `DriverShiftService` | Регистрация plan, вычисление work date, переходы shift/inspection/defect, receipts и startup projection |
| `HttpWarehouseIdentityGateway` | Точное private-чтение identity/timezone/coordinates склада для владельца смены |
| `MetNoWeatherProvider` / `WeatherHazardRules` | Fail-open нормализованный weather cache и настраиваемые рекомендации |
| `MobileTaskSurfacePolicy` | Непересекающиеся DriverApp primary и WorkerApp secondary capabilities |
| `WorkerTaskAccessService` | Общая worker/group/qualification аудитория очередей для native task reads и media proofs |
| `WorkerFeedCountProjection` | Однозапросные route cardinality и READY-evidence counts для bounded native feed page |
| `WorkerFeedRevisionStore` | Transactional warehouse-scoped opaque revision, продвигаемая authoritative task-board facts |
| `WorkerActionReceiptStore` | Immutable receipts canonical native- и exact-contractor action request и frozen response под advisory lock |
| `TaskBoardEntryOwnerProofReconciler` | Bounded idempotent восстановление legacy или workforce-stale аудиторий media proof |
| `WorkerPushOutbox` / `WorkerPushDispatcher` | Transactional уведомление стропальщика, leased FCM delivery и bounded recovery |
| `WorkforceService` | Стабильный фасад worker/group API над тремя владельцами lifecycle |
| `WorkforceProfileService` | Изменение worker profile и qualifications с сохранением credential lock span |
| `WorkforceCredentialLifecycleService` | Durable auth credential intents, completion/failure fencing, reconciliation и deletion recovery |
| `WorkforceGroupService` | Group membership, availability и интервалы current group |
| `WorkforceReadProjectionService` | Read-only сборка DTO worker и group |

Dependency graph ацикличен. Registration и mutation совместно используют route
codec; registration, mutation, worker execution и pinning используют узкий
queue-position coordinator. Ни один technical collaborator не владеет
authorization, source status, worker transitions или агрегатом другого домена.

Workforce graph также ацикличен. Profile-команды вызывают узкий credential
lifecycle и read projection, а group-команды используют только read projection.
Credential recovery не вызывает facade или group owner, и ни один collaborator
не предоставляет больше 15 direct dependencies.

Create/update группы может передавать `currentGroupChanges` вместе с полной
заменой состава. `WorkforceGroupService` блокирует изменяемых работников в
стабильном порядке UUID, проверяет version, membership, availability и отсутствие
активной задачи и в одной транзакции фиксирует состав, интервалы current group,
events и KPI facts. Отсутствие поля сохраняет прежние current groups для старых
callers; отдельный worker current-group endpoint остаётся совместимым.

Task-entry owner proof разделяет uploaders результата (`allowedWorkerIds`) и
читателей (`readerWorkerIds`). Пока entry открыта, read audience рассчитывается
теми же native worker/group/qualification и driver-правилами, что feed/detail:
рабочий может видеть исходные и итоговые фотографии ожидающего задания, но не
получает права upload. Создание task публикует исходный proof в той же локальной
транзакции. После закрытия reader audience сокращается до всех исторических
assignees и работников с evidence. Bounded reconciler запускается при старте и после изменения
warehouse audience revision, публикует только изменившийся proof и не сканирует все задачи на
простаивающем складе. Неуспешный проход не продвигает revision watermark. Inactive proof не
разрешает новый upload или finalization.

## HTTP-границы

Канонический контракт:
[`contracts/openapi/task-board-service.yaml`](../../contracts/openapi/task-board-service.yaml).
Public gateway преобразует `/api/task-board/**` в downstream `/api/**`; клиенты
не обращаются к private address сервиса.

| Граница | Audience | Назначение |
| --- | --- | --- |
| `/api/queue-definitions/**` | Authenticated manager/admin policy | Global queue catalog, ordering и reference-safe deletion |
| `/api/worker-classes/**` | Authenticated manager/admin policy | Каталог квалификаций работников |
| `/api/warehouses/{warehouseId}/workers/**` | Warehouse-authorized manager | Workers, groups, credential operations и reconciliation |
| `/api/warehouses/{warehouseId}/logistics-drivers/contractors` и `.../{workerId}` | Warehouse-authorized manager | Полный каталог вызываемых по необходимости подрядчиков, создание, version-fenced замена профиля и удаление неиспользованных профилей; список содержит неактивные профили |
| `/api/warehouses/{warehouseId}/work-queues` | Warehouse-authorized user | Physical queue projections и capabilities |
| `/api/warehouses/{warehouseId}/task-board/**` | Warehouse-authorized user | Чтение агрегированной ordinary board и поддерживаемые task-команды |
| `/api/warehouses/{warehouseId}/task-board/daily-brigade-activity` | Warehouse-authorized user | Фактические интервалы assignments, пересекающие текущий warehouse-local день |
| `/api/kpi-palette` | Authenticated user; global management для `PUT` | Одна version-fenced KPI palette для всех складов установки |
| `/api/kpi-settings/**` | Authenticated user; global management для mutations | Один version-fenced рабочий график для всех складов установки; выбор склада отсутствует |
| `/api/worker/v1/**` | Worker credential и `worker.tasks` scope | Context, feed, detail, actions, evidence reservations, devices и events |
| `/api/driver/v1/**` | Worker credential и `driver.tasks` scope | Driver-only context, primary feed, actions, evidence reservations, devices и events |
| `/api/driver/v1/shift/today` и `/api/driver/v1/shifts/{shiftId}/**` | Точная identity водителя и `driver.tasks` | Startup aggregate и version-fenced переходы ежедневной смены |
| `/api/internal/task-board/v1/inventory/warehouses/{warehouseId}/work-calendar` | Точная SERVICE identity `inventory-service` и единственный scope `task-board.inventory-calendar.read` | Bounded snapshot effective object calendar: timezone, revision schedule, результат `daysOff` и fingerprint для inventory planning; browser access и семантика Driver Up shifts отсутствуют |
| `/api/internal/task-board/v1/maintenance/**` | Exact maintenance-service identity | Routing и catalog preflight |
| `/api/internal/task-board/v1/tasks/**` | Exact source service identity | Idempotent task synchronization и evidence reads |
| `/api/internal/task-board/v1/logistics/**` | Exact logistics-service identity | Driver/equipment task integration |
| `/api/internal/task-board/v1/logistics/warehouses/{warehouseId}/drivers` | Exact identity и scope logistics-service | Только identities активных primary-qualified водителей |
| `/api/internal/task-board/v1/logistics/contractor-execution/workers/{workerId}/tasks/{externalTaskId}`, `.../entries/{entryId}/actions` и `.../evidence-reservations` | Exact identity logistics-service и единственный scope `task-board.logistics` | Snapshot route точного назначенного активного подрядчика, reservation result evidence и START/COMPLETE без credentials, раскрытия контакта, native offline lease, media bearer path или доступа к общей доске |
| `/api/internal/task-board/v1/driver-shift-plans/{sourceShiftId}` | Точная identity logistics-service и `task-board.driver-shifts.plan` | Идемпотентная регистрация проверенного плана водитель/машина/дата |
| `/api/internal/queue-definitions/**` | Allow-listed service identity | Durable queue usage references |

Private paths — service-to-service boundaries, а не client shortcuts. Их exact
`principal_type`, `client_id`, scope, source ownership и warehouse checks входят
в контракт.
Подрядчики остаются записями `WorkerEmploymentType.CONTRACTOR` с контактом,
примечанием и флагом active. Каталог не хранит даты доступности: выбранный день
планирования относится к последующему назначению. Каталог не создаёт учётные
данные, не требует автомобиль и не делает работника кандидатом обычного
primary-driver оптимизатора. Задание становится исполнимым через service-границу только после того,
как logistics зарегистрирует для этого подрядчика точную аудиторию `ASSIGNED_DRIVER`; сам каталог
не выдаёт доступ к заданиям.

Чтение дневной активности бригад использует сохранённый `startedAt` assignment
из TAKE и `finishedAt` из completion. Границы смены только выбирают и размещают
данные на шкале, но никогда не заменяют эти timestamps. Пересекающиеся строки
joined-worker или legacy одной бригады, задачи и physical queue образуют один
интервал; непересекающиеся повторные взятия остаются отдельными, а у живого
интервала finish равен null. Проекция read-only, warehouse-authorized и целиком
принадлежит task-board.

## Native streams и offline execution

`GET /api/worker/v1/events` — SSE invalidation stream. Текущий producer
отправляет `FEED_CHANGED` при подписке и последующих изменениях; worker app также
периодически делает authoritative REST refresh. Reconnect открывает новую
подписку и сопровождается authoritative REST refresh feed; этот contract не
имеет cursor replay и не зависит от `Last-Event-ID`. Payload не является полной task
projection. Подписка keyed authenticated warehouse, native surface и worker,
поэтому факт другого склада не продвигает и не уведомляет этот stream. Feed page
читает warehouse revision и projection в одном repeatable-read snapshot;
изменения постороннего склада не делают cursor недействительным. Weak ETag
также scoped authenticated warehouse, native surface и worker вместе с этой revision.

`GET /api/driver/v1/events` имеет ту же invalidation-only семантику: reconnect
открывает новую подписку, а DriverApp обновляет authoritative REST feed. Device
registrations привязаны к surface и принимают текущие Firebase Installation ID
(`targetKind=FID`) и legacy registration tokens. Успешный TAKE водителя сохраняет
уведомление стропальщику в `worker_push_outbox` в той же транзакции; leased dispatcher
повторяет transient FCM failures, отзывает invalid targets и не отправляет вызовы
стропальщика DriverApp installations.

`WorkerTaskDetail.source` присутствует всегда и равен null для обычной работы. Для source-owned
работы он содержит только существующие immutable type и ID источника. Worker client может
использовать ID `LOGISTICS_DRIVER_TASK`, чтобы загрузить принадлежащие logistics детали ходки;
task-board не копирует этот payload или его business state.

Для `MAINTENANCE_REPAIR` последовательные route entries одной physical queue образуют один пакет
выполнения рабочего. Detail объединяет снимки работ, материалов, комментариев и source media всего
сегмента; duration и timer учитывают только ещё не завершённые части. TAKE сохраняет одно
representative assignment и один KPI segment. Один version-fenced COMPLETE атомарно переводит в
done representative и все последующие незавершённые shadow members, записывает assignment/time
audit для каждого, выдаёт существующий queue-entry completion fact для каждой связанной стадии
ремонта и продвигает только следующий отличный route segment. Для источников не из maintenance
семантика остаётся entry-scoped. Схема persistence и форма event payload не меняются.

`WorkerTaskDetail.sourceMedia` сохраняет порядок источника, включая заданную источником обложку на
первой позиции. Каждый `WorkerWork.sourceMediaIds` является точной связью строки работы с её
собственными references в этом массиве; task-board не выравнивает и не угадывает эту связь.

Каждый `WorkerFeedEntry` возвращает raw zero-based `routeIndex`, zero-based `routeStepIndex`,
положительный `routeStepCount`, обязательный `entryType` и обязательный `pinned`;
`WorkerTaskDetail` также возвращает оба route index и тот же положительный авторитетный package
count. `routeIndex` остаётся persisted route-row и
evidence identity, а `routeStepIndex` является ordinal worker execution package. WorkerApp получает только выбранные сервером
`REAL` из включённых очередей: активную работу плюс ограниченный план ожидающих карточек. Будущие
`SHADOW` остаются в manager snapshot и никогда не публикуются в WorkerApp. Для maintenance один
package образуют только последовательные rows одной physical queue: A-A-B даёт два package, а
A-B-A — три. Для остальных источников каждый persisted route row является отдельным package.
Package coordinates и число READY evidence загружаются для выбранной страницы feed одной database
projection, а не отдельным запросом для каждой карточки.

Worker action проверяет identity работника, current assignment, entry version,
action/status transition и offline lease, где он нужен. 24-часовое окно строго
для `TAKE`, `JOIN`, `PAUSE` и `RESUME`. Для уже назначенного задания WorkerApp
тот же подписанный lease может передать фото результата и `COMPLETE` после этого
окна: task state, assignment, expected version, photo gate и время не из
будущего остаются обязательными. `deadlineAt` остаётся операционным metadata
задания и не блокирует корректное завершение. Каждое принятое завершение
публикует канонический факт `QUEUE_ENTRY_COMPLETED`; maintenance по финальному
факту связанной стадии переводит ремонт в ожидание приёмки.

До любого live task read action path сериализует попытки одного `operationId`
transaction-scoped advisory lock. Первый успешный request сохраняет complete
canonical request identity и frozen response в `worker_action_receipt` в той же
транзакции, что event/outbox effects. Точный retry возвращает исходный response
без изменений; изменение surface, worker, warehouse, entry или любого request
field даёт `409`. Pre-V36 event с тем же correlation ID, но без receipt, также
fail-closed отвечает `409`, потому что его исходный response нельзя безопасно
восстановить. Volatile SSE invalidation отправляется только после commit.
Private adapter подрядчика повторно использует эту receipt table и lock, добавляя external task и
service channel в canonical request; перед принятием replay он заново доказывает точное назначение
активного подрядчика.

Evidence сначала резервируется со stable client
reference, затем загружается в media-service. Media fact связывает обработанную
generation с reservation до использования в completion. Legacy-декларация `image/jpeg` может занимать не более 15 MiB; логический клиентский
bundle `image/webp` — не более 1 MiB, а его `sha256` является детерминированным checksum manifest
пакета. Оба формата сохраняют одну логическую evidence row, а replay reservation обязан совпадать
с исходными entry, operation, route step, capture time, MIME type, size и checksum. Endpoint
reservation точного подрядчика применяет те же правила declaration и replay, выводит
route и owner facts на сервере и требует `IN_PROGRESS` entry и точное live-назначение подрядчика
перед первой reservation и replay. Те же ограничения
формата и числа байт enforced миграцией
[`V32__support_worker_evidence_webp_bundles.sql`](src/main/resources/db/migration/V32__support_worker_evidence_webp_bundles.sql).

Завершённая фотография рабочего является первым внешне видимым фактом своего потока
task-evidence, поэтому этот поток всегда начинается с aggregate version `0` независимо от версии
внутренней reservation row. Миграция
[`V35__repair_task_evidence_stream_origins.sql`](src/main/resources/db/migration/V35__repair_task_evidence_stream_origins.sql)
добавляет детерминированные отсутствующие начала только полностью неопубликованным потокам
`TASK_EVIDENCE` и по порядку повторно ставит в очередь их исходные факты из quarantine с разрывом
версии. Она не переписывает исходные факты и не затрагивает уже опубликованный поток; контракт
payload события не меняется.

[`V36__worker_feed_revision_and_action_receipts.sql`](src/main/resources/db/migration/V36__worker_feed_revision_and_action_receipts.sql)
добавляет warehouse revision sequence/projection и immutable worker-action receipts. Существующие
warehouse rows backfill-ятся выше прежнего global revision fence; domain events, tasks и evidence
rows не переписываются.

Reconnect SSE намеренно остаётся invalidation-only. Controller и mobile clients
не принимают и не отправляют replay cursor; reconnect запускает authoritative
REST refresh, а локальные event IDs остаются только для deduplication
invalidation и audit.

## Persistence и eventing

Сервис владеет одной PostgreSQL БД и immutable Flyway migrations в
[`src/main/resources/db/migration`](src/main/resources/db/migration/).
Hibernate только валидирует и не создаёт/обновляет схему.

Task-board локально хранит owner state, append-only domain facts, outbox rows,
inbox deduplication, aggregate checkpoints, sanitized DLT metadata, worker
evidence, warehouse lifecycle intent и recovery state. Kafka publication
использует aggregate-keyed, synchronously acknowledged leased outbox relay.
Consumer проверяет envelopes/payloads, делает bounded retries и блокирует/
quarantine aggregate version gaps вместо молчаливого пропуска.

Produced families включают worker class, worker, group, queue, usage reference,
board task, queue entry, owner-proof, task-evidence и group-KPI-day facts. Сервис
получает warehouse facts для metadata/lifecycle projection и media facts для
worker evidence.

Migration
[`V28__driver_task_audience.sql`](src/main/resources/db/migration/V28__driver_task_audience.sql)
добавляет в `board_task` аудиторию и snapshot назначенного worker. Существующие logistics driver tasks backfill-ятся как общие для
водителей склада. Board-task events получают только необязательные поля
аудитории и worker ID, поэтому сохранённые V1 events без них остаются валидными;
display names в этих событиях не публикуются.

Migration
[`V29__remove_shared_driver_identity.sql`](src/main/resources/db/migration/V29__remove_shared_driver_identity.sql)
очищает устаревшие worker IDs/names у задач `WAREHOUSE_DRIVERS` и усиливает DB constraint, чтобы
общая и неназначенная аудитории всегда оставались identity-free.

[`V30__driver_worker_surfaces_and_push_outbox.sql`](src/main/resources/db/migration/V30__driver_worker_surfaces_and_push_outbox.sql)
разделяет WorkerApp/DriverApp installations, добавляет leased push outbox, нормализует каждый
настроенный logistics secondary binding как required/interrupting/notified и повышает минимум
result photo до одного. Текущая mobile surface policy намеренно показывает настроенный secondary
binding как необязательный: notification и прерывание группы при JOIN сохраняются, но driver
completion никогда не ждёт стропальщика. Миграция не придумывает отсутствующий класс стропальщиков.

[`V39__driver_daily_shift.sql`](src/main/resources/db/migration/V39__driver_daily_shift.sql)
добавляет проверенный plan, ежедневную shift, versioned snapshot/results
осмотра, общие vehicle defects, shift photos, неизменяемые command receipts и
media inbox. Она seed-ит редактируемую template-конфигурацию без checklist в
boolean columns, расширяет allow-list event store и не переписывает
существующие tasks, facts или данные водителей.

[`V40__contractor_profiles_without_availability_range.sql`](src/main/resources/db/migration/V40__contractor_profiles_without_availability_range.sql)
удаляет колонки периода доступности подрядчика и индекс диапазона. Профиль подрядчика становится
складским справочником для вызова по необходимости; точная дата хранится только в логистическом
назначении, которое использует подрядчика.

[`V41__driver_shift_route_operations.sql`](src/main/resources/db/migration/V41__driver_shift_route_operations.sql)
добавляет неизменяемых упорядоченных дочерних operations под заменяемым до freeze планом смены.
[`V42__driver_shift_transfer_route_operations.sql`](src/main/resources/db/migration/V42__driver_shift_transfer_route_operations.sql)
добавляет nullable эффективную cabin capacity и каноническую identity transfer, расширяет
ограничения kind/identity и сохраняет совместимость всех существующих plan через отсутствие новых
nullable полей.

## Безопасность и изоляция

- Все API chains валидируют JWT issuer/audience; worker и driver routes требуют
  непересекающиеся scopes `worker.tasks` и `driver.tasks`.
- Manager commands проверяют user role, warehouse access и command-specific
  write permission в `WarehouseAccessAuthorizer` и services.
- Internal task, queue-reference, maintenance и logistics operations требуют
  exact service identity/scope и проверяют source ownership.
- Driver-shift планы принимает только точный credential logistics-service со
  scope `task-board.driver-shifts.plan`; mobile shift routes — только совпавшие
  `WORKER` identity, warehouse и scope `driver.tasks`.
- Межскладские действия DriverApp сохраняют этот home-warehouse token fence и дополнительно требуют
  точную remote-аудиторию `ASSIGNED_DRIVER`; один entry ID никогда не предоставляет доступ к складу.
- Auth-service остаётся владельцем credentials. Task-board хранит только
  operational workflow state, нужный для reconciliation.
- CORS использует explicit panel/worker/driver origins. Browser/mobile clients идут
  через gateway; сервисы — по private routes с client credentials.
- OAuth registrations для worker credentials, warehouse lifecycle read/confirm
  и warehouse timezone используют один `TASK_BOARD_CLIENT_SECRET`. Профиль
  `dev` передаёт одинаковый local fallback во все registrations; в
  base/production fallback отсутствует и обязателен deployment secret.
- Dev auth bypass разрешён только в explicit dev profile и запрещён вне
  local/test.

## Concurrency, idempotency и recovery

- Mutable board/queue/workforce commands передают expected versions или другой
  contract-defined fence и возвращают `409` при stale state.
- Retry source task creation/synchronization использует stable external task ID
  и source identity.
- Driver plans уникальны по source shift и по driver/work date. Создание
  сериализуется блокировкой plan row, а каждая изменяющая shift-команда
  использует root/child versions и неизменяемый operation receipt.
- Retry native action использует один durable operation receipt: точный request
  возвращает frozen первый response, а divergent или невосстановимый legacy
  replay отвечает `409` без повторного effect.
- Credential reset/disable/delete workflows сохраняют pending/ambiguous states
  и имеют explicit reconciliation commands вместо local rollback.
- Worker media inbox остаётся pending, пока не появится referenced evidence;
  reconciler повторяет применение идемпотентно.
- Push стропальщику доставляется at least once: expired lease можно reclaim,
  transient failures получают bounded delay, invalid installations отзываются,
  а exhausted rows остаются `DEAD` для operations вместо ложного delivery success.
- Warehouse readiness невозможен, пока task/queue/credential/evidence или
  operation-mark work остаётся unresolved.
- Outbox и sanitized DLT recovery сохраняют immutable envelope и audit review.

## Наблюдаемость и ошибки

Actuator предоставляет health/readiness и Prometheus; Micrometer tracing, ECS
logs и `X-Correlation-Id` связывают public requests, remote effects и facts.
Нельзя логировать passwords, Bearer tokens, worker secrets, raw rejected events
или personal task content.

Dependency timeouts становятся явной ошибкой или durable reconciliation state,
а не mock data/command success. Kafka failure оставляет local outbox
recoverable и не отменяет committed task.

В репозитории всё ещё есть runtime Rabbit-to-Kafka cutover rehearsal и
historical mapping table, хотя у текущего продукта нет active cutover program.
Не включайте runner как обычную operational feature; contract-safe removal
запланирован в architecture audit.

## Локальная разработка

Запустите dependencies:

```bash
docker compose --profile core up -d task-board-db kafka
```

Для credentials/lifecycle сначала запустите auth и warehouse, затем task-board:

```bash
bash ./gradlew :services:task-board-service:bootRun --args='--spring.profiles.active=dev'
```

Dev defaults: PostgreSQL `127.0.0.1:5434`, auth `http://localhost:9000`,
warehouse-service `http://localhost:8083`, Kafka `localhost:9092`, service port
`8081`. Public browser/mobile entry остаётся на gateway.

## Обязательная production-конфигурация

Задайте database credentials, HTTPS auth issuer/token URI, task-board service
secret, worker offline-lease secret, private auth worker-credential URL,
private warehouse lifecycle URL, explicit CORS origins, Kafka brokers и
`TASK_BOARD_KAFKA_ENABLED=true`, `TASK_BOARD_FCM_ENABLED=true`,
`TASK_BOARD_FCM_PROJECT_ID` и Google Application Default Credentials.

Новый flow включается `DRIVER_DAILY_SHIFT_ENABLED=true`. Нужно настроить
`DRIVER_SHIFT_DAY_START`, `DRIVER_SHIFT_SUSPICIOUS_ODOMETER_JUMP_KM`,
идентифицирующий несекретный `MET_NO_USER_AGENT`, ограниченные transport/cache
параметры `MET_NO_*` и thresholds `WEATHER_HAZARD_*`. OAuth-клиент task-board
также должен иметь `warehouse.identity.read` и существующий private base URL
warehouse-service; weather API key не используется и не возвращается Android.

`TaskBoardProductionSafetyValidator` запрещает missing/insecure endpoints,
disabled Kafka, topic drift, unsafe binder retry/DLT, topic auto-creation,
non-acknowledged publishing и publish timeouts, способные превысить outbox lease,
вне dev/test.

## Проверка

Из корня репозитория:

```bash
bash ./gradlew :services:task-board-service:test
bash ./gradlew :services:task-board-service:javadoc
bash ./gradlew :platform:architecture-tests:test
```

Native actions/streams требуют WorkerApp и DriverApp contract/unit tests и exact APK builds.
Source-task integration, Kafka families или warehouse lifecycle требуют focused
producer/consumer, idempotency, version-gap, dependency-outage и recovery
coverage.

## Правила безопасного изменения

1. Сохраняйте queue, workforce, assignment, task execution и worker evidence
   state здесь; source-domain decisions — у их producer.
2. Начинайте public/private API change с canonical OpenAPI и обновляйте gateway,
   clients и service consumers вместе.
3. Сохраняйте warehouse authorization, source identity, stable external IDs,
   expected-version fencing и exact task ownership.
4. Фиксируйте state, history и outbox атомарно; сохраняйте inbox deduplication,
   aggregate ordering, bounded retry и gap recovery.
5. Считайте SSE invalidation-сигналом без явно одобренного producer-owned
   durable replay contract, реализованного end to end.
6. Удаляйте obsolete cutover runtime только в reviewed slice, сохраняя нужные
   historical evidence и не удаляя live data неявно.

## Основные исходные материалы

- [Канонический OpenAPI](../../contracts/openapi/task-board-service.yaml)
- [Task-board controller](src/main/java/dev/buhanzaz/rwms/taskboard/api/TaskBoardController.java)
- [Worker API](src/main/java/dev/buhanzaz/rwms/taskboard/api/WorkerTaskBoardController.java)
- [Driver API](src/main/java/dev/buhanzaz/rwms/taskboard/api/DriverTaskBoardController.java)
- [Driver shift API](src/main/java/dev/buhanzaz/rwms/taskboard/api/DriverShiftController.java)
- [Владелец driver shift](src/main/java/dev/buhanzaz/rwms/taskboard/service/DriverShiftService.java)
- [MET weather adapter](src/main/java/dev/buhanzaz/rwms/taskboard/service/MetNoWeatherProvider.java)
- [Task-board application service](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardService.java)
- [Task-board read projection](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardReadProjectionService.java)
- [External task registration](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalRegistrationService.java)
- [External task mutation](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalMutationService.java)
- [Queue position coordinator](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardQueuePositionCoordinator.java)
- [Worker application service](src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerTaskBoardService.java)
- [Mobile surface policy](src/main/java/dev/buhanzaz/rwms/taskboard/service/MobileTaskSurfacePolicy.java)
- [Push outbox](src/main/java/dev/buhanzaz/rwms/taskboard/push/WorkerPushOutbox.java)
- [Workforce service](src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkforceService.java)
- [Production safety validator](src/main/java/dev/buhanzaz/rwms/taskboard/config/TaskBoardProductionSafetyValidator.java)
- [Event store](src/main/java/dev/buhanzaz/rwms/taskboard/eventing/TaskBoardEventStore.java)
- [Карта runtime flows](../../docs/project-knowledge/runtime-flows.md)
