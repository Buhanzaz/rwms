# Сервис обслуживания RWMS

[English version](README.md)

## Назначение и владение

`maintenance-service` владеет версиями каталога обслуживания, сметами, ремонтами, ремонтными
местами и мощностью, решениями о приёмке/списании и своей частью сверки публикации
инвентаризации. Только сервис является источником истины для этих переходов; другие сервисы не
записывают его PostgreSQL-таблицы и не восстанавливают состояние ремонта из Kafka.

Авторитетные HTTP- и event-контракты находятся в
[`contracts/openapi/maintenance-service.yaml`](../../contracts/openapi/maintenance-service.yaml)
и [`contracts/events/maintenance-events.yaml`](../../contracts/events/maintenance-events.yaml).
[`docs/project-knowledge/domain-logic.md`](../../docs/project-knowledge/domain-logic.md) — это
индекс: при расхождении правило необходимо проверить по текущим контрактам и коду сервиса.

## Публичная и внутренняя HTTP-граница

Публичные пользовательские операции версионированы под `/api/maintenance/v1/**`; они покрывают
каталог, смету, ремонт, настройки/ремонтные места и распоряжение имуществом. Изменяющие команды
используют определённые контрактом ключ идемпотентности и expected-version fields. Клиент обязан
обработать канонический конфликт `409`, а не отправлять изменившийся повтор.

Версионированный каталог обслуживания и цвета отображения сложности ремонта едины для всей
инсталляции. Их публичные endpoints не принимают выбор склада: авторизованные пользователи могут
читать эти данные, а изменять их могут только `SYSTEM_ADMIN` или `WMS_ADMIN`. Сохранённый у версии
каталога `warehouseId` является внутренним контекстом создания/аудита и никогда не выбирает и не
создаёт отдельную складскую копию каталога.

Интерактивные panel и Android-клиенты обращаются к этому namespace только через публичный маршрут
`/api/maintenance/**` в `api-gateway-service`. Им нельзя напрямую вызывать host этого модуля или
маршрут `/api/internal/**`.

Внутренние маршруты намеренно узкие:

- `/api/internal/maintenance/v1/inventory/**` обслуживает inventory-owned work с планами,
  источниками и сверкой публикации.
- `/api/internal/maintenance/v1/logistics/**` обслуживает оркестрацию смет возврата и
  ремонтных мест для logistics.

Inventory-service может читать
`GET /api/internal/maintenance/v1/inventory/return-estimates/{estimateId}` только со своим точным
service principal и единственным scope `maintenance.inventory`. Read-only ответ доказывает, что
смета строки возврата logistics находится в `COMPLETED`, содержит ненулевое время физического
прибытия, а её склад/бытовка и порядок source/completion согласованы. Ответ содержит только identity
строки возврата, версии сметы и asset snapshot, completion kind и необязательный repair ID; строки
сметы, media и паспортные данные не копируются, новая maintenance work не создаётся.

В этой logistics boundary точная private-команда
`historical-shipments/{shipmentId}/close` позволяет только logistics-service подготовить
импортированную отгрузку аренды. Maintenance сначала фиксирует durable shipment audit identity и
комментарий `Автоматически закрыто в связи с отгрузкой.`, затем завершает допустимый обычный ремонт
либо отменяет допустимую ветку капремонта/перемещения. Задание task-board до старта получает тот же
комментарий и безопасно отменяется; его этап ремонта фиксируется как системно завершённый без
подделки worker evidence. Начатая работа или version conflict отклоняются для operator
reconciliation.
Когда ремонта нет, а asset-service уже возвращает `RENTED`, maintenance отвечает явным proof
`ALREADY_RENTED` с неизменённой версией бытовки; он не отправляет переход статуса или release lease.
Поэтому logistics может восстановить недостающие факты отгрузки без повторения физического выезда.

Preflight публикации инвентаризации по-прежнему требует ограниченный список `findings`, принимает
пустой список, когда итоговый план не содержит maintenance work, и всегда возвращает пустой массив
`candidates` для каждого finding с работами. Последняя completed inventory является авторитетной,
поэтому активная смета или ремонт — это predecessor evidence, а не collision, требующий выбранного
caller-ом merge или replacement.

Каждый finding completed inventory с работами направляется в один полный замороженный `REPAIR`,
включая `AFTER_RENT`; старого inventory-only пути materialization сметы больше нет. Исторические
значения `CREATE`, `REPLACE` и `MERGE` остаются допустимым immutable request evidence, но все
исполняются как полная авторитетная замена. Точная no-work граница
`PUT /api/internal/maintenance/v1/inventory/outcomes/{inventoryId}/findings/{findingId}/no-work`
не создаёт смету или ремонт и не оставляет non-terminal maintenance target для finding со статусом
`FREE`.

`POST /api/internal/maintenance/v1/inventory/cabin-write-offs` — узкая companion-граница для
бытовки, оставшейся ненайденной после review отгрузок инвентаризации. Она принимает только точный
credential inventory-service и один стабильный source/key, читает текущее наполнение бытовки из
asset-service и замораживает его как `DISPOSE_WITH_CABIN` в обычном решении о списании
`PENDING_APPROVAL`. Терминальный статус здесь не применяется: единственным терминальным путём
остаётся существующее согласование глобальным администратором и durable property-disposition saga.

Work- и no-work-потоки durable фиксируют каждую non-terminal DRAFT смету и активный ремонт,
отменяют принадлежащие maintenance задания task-board и перемещение водителя, освобождают operation
leases и ремонтные места через их владельцев и только затем supersede локальные сметы, ремонты и
этапы. Rows, lines, stages, evidence, media references и event history сохраняются; workflow не
удаляет maintenance history. Terminal truth ремонта `ACCEPTED` или `WRITTEN_OFF`, несовпадение
warehouse/asset и устаревший либо неоднозначный порядок completed inventory отклоняются до любого
local или remote effect.

Apply требует `authoritativeAssetVersion`, который asset-service возвращает после применения
результата inventory. Отстающая local asset projection принимается только тогда, когда её версия
входит во включительный диапазон `assetVersion..authoritativeAssetVersion`. `LOST`, `WRITTEN_OFF`,
несовпадение warehouse и projection вне этого диапазона остаются конфликтами. Завершённый
idempotency key возвращает замороженный ответ без новых эффектов. Новый ключ для того же immutable
source заново обнаруживает и supersede non-terminal predecessors, созданные после предыдущего
успеха, сохраняя exact same-source repair и никогда не создавая дубликат.
Если этот exact work coordinator уже находится в состоянии `APPLIED`, а local projection позже
продвинулась выше исходного authority запроса, восстановление с новым ключом остаётся намеренно
узким. Immutable запрос, warehouse, applied coordinator, watermark и active bound repair должны
по-прежнему совпадать. Текущая immutable source row должна называть этот repair; когда у
эквивалентного исправленного плана намеренно нет собственной source row, immutable source другого
плана уже должна владеть ровно этим retained repair. Его классификация должна соответствовать
текущей asset truth `REPAIR`/`CAPITAL_REPAIR`. Текущий статус может совпадать с desired status,
либо сохранённый `REPAIR` может быть повышен до `CAPITAL_REPAIR`. Тогда recovery повторно утверждает
только существующие status/task/driver/lease reconciliation effects связанного repair,
не выполняет asset-status transition и не переписывает request, authoritative version или
response coordinator. `FREE`, `RENTED`, `BOOKED`, desired `CAPITAL_REPAIR` при текущем `REPAIR`,
terminal state, изменённый source, отсутствующий либо не-`APPLIED` coordinator, несвязанный либо
несовпадающий repair и устаревший plan/watermark остаются конфликтами. Этим fence владеют
[`InventoryPublicationAssetFence`](src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryPublicationAssetFence.java)
и
[`InventoryAuthoritativeOutcomeStore`](src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryAuthoritativeOutcomeStore.java).
Для no-work reassertion увеличенный `authoritativeAssetVersion` от того же immutable source
считается новым техническим fence, а не другим решением инвентаризации. Исходный outcome
coordinator остаётся неизменным, а новый idempotency key сохраняет точный fingerprint запроса и
ответ в отдельном receipt.

Исправленный completed plan может продвинуть тот же inventory/finding в точный тот же момент
завершения только строго большей `finalPlanVersion` после состояния предыдущего outcome `APPLIED`.
Неизменная work-коррекция принимает существующий repair и продвигает outcome watermark; поскольку
исходная immutable source row уже владеет этим repair, исправленные coordinator и receipt ссылаются
на него без дубликата source или repair. Если более поздние completed receipts уже заменили
исходный repair predecessor coordinator, сохраняется новейший live repair, связанный receipt.
Driver reassert разрешает новейший `APPLIED` coordinator bounded-запросом по completion time и
plan version, поскольку исправленные планы намеренно оставляют один repair в нескольких
исторических coordinator rows. Retry из `EFFECTS_SETTLED` снимает compensation guard, записанный до
обнаружения этой связи, и повторно утверждает task-, driver- и lease-работу сохранённого repair. Изменение finding revision,
наблюдаемой asset version, fingerprint, приоритета, дат, media, выбранной цели, перемещения,
обычного или капитального routing, а также классификации `WORK`/`NO_WORK` вместо этого запускает ту
же durable remote compensation и local supersession, что и более поздняя inventory. Прежний active
outcome остаётся историей, а активным остаётся ровно один исправленный repair либо ни одного repair
для `NO_WORK`. Поиск predecessor всегда сохраняет стабильный внешний task ID ремонта, даже если
после неоднозначного ответа регистрации его локальная task-board version осталась пустой.
Compensation сначала читает эту source-owned задачу, сохраняет live version до отмены и записывает
явный terminal `NOT_FOUND`, когда task-board подтверждает отсутствие identity. Поэтому замена из
завершённой inventory не может оставить видимый исполнителю осиротевший task только из-за
потерянного ответа первоначальной регистрации. Меньшая
версия, drift той же версии, изменение identity inventory/finding/warehouse/completion,
предыдущий outcome не в состоянии `APPLIED` и terminal accepted или written-off работа остаются
конфликтами.

Если compensation успела durable отменить ordinary task сохраняемого repair до распознавания
эквивалентной коррекции, maintenance не может повторно использовать identity этой задачи. Только
inventory-owned repair в состоянии queued, без перемещения и до начала работ, все stages которого
ещё подтверждают отменённые mappings, переходит на один детерминированный replacement task ID,
очищает только эти mappings и ровно один раз регистрирует replacement. То же восстановление
помечает освобождённый operation lease, получает и сохраняет новый fence до reconciliation
статуса, а затем подтверждает доставку task. Более поздний reassert также соблюдает durable
локальный fence `RECONCILIATION_REQUIRED`, когда release-ledger принадлежит старой версии плана:
вместо подтверждения уже совпадающего asset status он обязан заново получить lease. Новый fence
завершает локальную reconciliation, только если ordinary task уже generated и перемещение не
ожидается; иначе последним fence остаётся подтверждение task или driver. Started, completed,
movement и capital routes не открываются заново; exact replay не вращает task и не получает ещё
один lease.

Регистрации публикации и immutable source rows, записанные до V45 coordinator, остаются audit
history и не блокируют последнюю completed inventory. Operation-only row или source, создавший
смету, считается predecessor evidence. Если старый repair source ранее был заморожен в уже
`APPLIED` coordinator, новый idempotency key атомарно создаёт один replacement repair, supersede
этот legacy repair и связывает текущую generation в новой completed receipt без перезаписи старых
source, outcome или receipt. Outcome lock сериализует создание generation, а repair-specific
compatibility queue key не конфликтует с quarantined `QUEUE_REPAIR` старого ремонта. Same-key
replay остаётся замороженным; текущий V45 repair source reassert, а не заменяется.
Repair reads публикуют `inventorySource` через read-only
[`InventoryRepairSourceReadProjection`](src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryRepairSourceReadProjection.java).
Он сначала разрешает новейший completed receipt, в замороженном ответе которого указан repair,
затем исходный `targetRepairId` authoritative outcome и, наконец, legacy
`InventoryRepairSource`. Authoritative reference сохраняет identity inventory/finding,
`findingRevision`, `finalPlanSha256` как plan fingerprint и `requestSha256` как source fingerprint,
чтобы клиент мог адресовать immutable media owner inventory finding без перезаписи media rows.
Legacy repair с точными lease owner и fencing token в `RECONCILIATION_REQUIRED` освобождается через
asset owner до replacement. Ответ asset может подтвердить как `RELEASED`, так и естественный
`EXPIRED`; lease ID, owner и fencing token по-прежнему обязаны совпасть, а версия не может
уменьшиться. Для уже локально `RELEASED` lease повторный вызов не выполняется.
Когда новая inventory generation сохраняет этот current repair, maintenance повторно утверждает
вычисленный asset-статус `REPAIR`/`CAPITAL_REPAIR` и пересоздаёт отсутствующую работу с
generation-specific ключами. Обычная входящая работа `DELIVER_TO_REPAIR` указывает authoritative
inventory finding, даже если current repair был принят из source до V45. Явный выбор капремонта
взаимоисключаем с входящим перемещением и остаётся в active capital-route.

Work apply сначала сохраняет `INVENTORY` repair в `DRAFT` с полным замороженным планом и одной
durable reconciliation `ASSET/QUEUE_REPAIR`. Существующий queue reconciler получает lease и меняет
статус asset. При `forceCapitalRepair=true` он использует `QUEUE_TO_CAPITAL_REPAIR`, направляет local
repair в активный внешний капремонт (`QUEUED/NOT_READY`, `EXTERNAL_CAPITAL`), не считает публикацию
inventory завершением работы и не создаёт обычное task-board задание. Для обычных работ он ставит
repair в очередь и либо сразу регистрирует
задание task-board, либо при `movementToRepair=true` создаёт logistics driver task
`DELIVER_TO_REPAIR` и регистрирует ремонтную работу после доставки.
Перед сохранением плана сметы или ремонта и повторно перед публикацией обычного задания
maintenance применяет фиксированную последовательность `СЭС -> сварка -> внешние -> внутренние ->
электрика -> сантехника`. Отсутствующие фазы не создают entries маршрута, завершённые entries больше
не блокируют promotion, а все переданные группы с одинаковым физическим task-board `queueId`
объединяются в одну исполняемую стадию с полным упорядоченным составом работ, материалов и
комментариев. Имена очередей являются display snapshots и никогда не объединяют разные queue IDs.
Публикация замороженного inventory-плана применяет то же правило до распределения строк. Уже
зарегистрированный ремонт с повторяющимися стадиями одной очереди перестраивается в одну
объединённую подзадачу task-board, только пока весь его маршрут и назначения не начаты. Maintenance
не объединяет сохранённые строки до атомарного принятия этой pre-start замены владельцем
task-board; начатая и завершённая история остаётся неизменяемой.
Каждый snapshot стадии обычного ремонта ставит выбранную обложку ремонта первой в упорядоченном
`sourceMedia`; остальные общие фото сохраняют стабильный порядок, а каждая строка работы содержит
только ID собственных фотографий. Общим заголовком задания служит рассчитанная maintenance
сложность (`Лёгкий ремонт`, `Средний ремонт`, `Тяжёлый ремонт` или `Капитальный ремонт`) вместо
технического `Maintenance repair`; task-board сохраняет этот source-owned title через существующий
контракт. Эту worker-facing проекцию строит
[`MaintenanceTaskBoardSupport`](src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceTaskBoardSupport.java).
Для стадии `REWORK` со строкой `REPEAT` эта же проекция добавляет все фотографии результата
рабочих из точной исходной стадии строки в общие `sourceMedia` дочерней стадии, сохраняя порядок
evidence и удаляя повторы по media ID. Эти ссылки не прикрепляются к отдельной строке работы, не
копируются в result evidence дочерней стадии и не могут выполнить её требование результата;
рабочий всё равно обязан зафиксировать новый результат. Разрешением исходной стадии и пакетным
чтением media владеет
[`MaintenanceReworkSourceMediaResolver`](src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceReworkSourceMediaResolver.java).
Проверенный task-evidence сначала попадает в durable replay journal, а затем в ordered inbox до
изменения проекции стадии ремонта. Миграция
[`V47__admit_task_evidence_transport_topic.sql`](src/main/resources/db/migration/V47__admit_task_evidence_transport_topic.sql)
согласует оба PostgreSQL allow-list топиков с существующей Kafka-подпиской, разрешая только
`rwms.task-board.task-evidence.v1`; она не переписывает существующие сообщения и не переносит
владение транспортом.
Коллекция смет фильтрует склад, lifecycle и необязательную бытовку, затем выбирает страницу
в PostgreSQL по убыванию времени создания и ID. Ревизии, строки, планы и media загружаются
четырьмя пакетными чтениями только для выбранных смет. Общее число и полное содержимое
ревизий каждой выбранной сметы сохраняют существующий контракт ответа.

Публичная коллекция ремонтов применяет склад, состояния, бытовку, точный необязательный
`estimateId`, опциональный ограниченный `repairIds` и пагинацию в PostgreSQL до сборки DTO ремонтов.
Потребители task-board используют ID-фильтр порциями не более 200, а workspace сметы — точный
estimate-фильтр; оба lookup не гидратируют посторонние ремонты склада. Нефильтрованный endpoint
сохраняет прежнее постраничное поведение. Этот read path принадлежит
[`MaintenanceRepairUseCases`](src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceRepairUseCases.java).
Финализация прямого ремонта блокирует maintenance-owned rental-item fact, после ожидания повторно
проверяет точный receipt идемпотентности subject/key/request и затем отклоняет другой non-terminal
PRIMARY root для той же бытовки. Поэтому конкурентный lost-response retry возвращает
исходный ремонт, а другая семантическая команда создания получает `MAINTENANCE_STATE_CONFLICT`;
пока удерживается эта локальная блокировка, remote call не выполняется.
Миграция
[`V49__repair_acceptance_and_creation_indexes.sql`](src/main/resources/db/migration/V49__repair_acceptance_and_creation_indexes.sql)
добавляет только индексы source-rework и active-primary для этих bounded queries; lifecycle rows
она не переписывает.
При старте maintenance идемпотентно ставит существующий pre-start update workflow в очередь для
всех уже зарегистрированных ожидающих ремонтов. Этот owner-local проход не выполняет remote I/O и
позволяет обычному reconciliation worker исправить старые presentation snapshots, включая порядок
стадий, обложки и заголовок сложности, без прямой мутации хранилища task-board. Presentation generation v4
использовала новый stable key, но live-запуск выявил расхождение owner-version, поэтому это поколение
остаётся неизменяемым audit evidence, а не успешным recovery. Generation v5 начала читать текущую
source-owned версию task-board, после чего live gate выявил вторую половину того же recovery gap:
успешный pre-start replacement сменил route-entry ID, которые maintenance всё ещё считал
неизменяемыми. Presentation generation v6 использует следующий stable key, сохраняет чтение
owner-version и разрешает только этому presentation recovery перепривязать уже подтверждённую
стадию `QUEUED/GENERATED` к возвращённому owner entry. Task-board принимает полностью идентичный
snapshot как replay без мутации; изменённые данные сохраняют version- и pre-start-fence. Поэтому
eligible queued work получает канонический порядок стадий, обложку и заголовок, а quarantined
v2/v3/v4/v5 work не возобновляется и не изменяется напрямую. Presentation generation v7 добавляет
один новый stable refresh только для queued repair, у которого repair-level доставка presentation
завершилась ошибкой, но каждая стадия всё ещё имеет подтверждённые task-board entry и version в
состоянии `QUEUED/GENERATED`. Он может восстановить общий заголовок сложности, обложку и source
photos без принятия сомнительных mappings и без изменения начатой работы. Quarantined stable refresh текущего
поколения остаётся в quarantine для reviewed resume, учитывается и пропускается; он не может сорвать
application-ready event или запустить цикл рестартов. Любой другой конфликт stable identity
по-прежнему отклоняется fail-closed. Presentation generation v8 сохраняет эти fences и дополнительно
сводит legacy queued repairs, содержащие больше одной стадии одной физической очереди. Сначала она
строит одну исходящую стадию со всеми упорядоченными работами, материалами, комментариями и
source-media references, не меняя локальные строки. Затем task-board выполняет version-fenced
атомарную pre-start замену маршрута. Только после ответа владельца maintenance объединяет свои
queued stages и связывает возвращённые entry IDs в одной локальной транзакции. Конкурентный старт
отклоняет remote replacement и оставляет локальный план без изменений; refresh не может ухудшить
delivery state ремонта. См.
[`MaintenanceWorkerCoverReconciliation`](src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceWorkerCoverReconciliation.java).
Если catalog-enforced капремонт сохраняет `movementToRepair=true`, тот же frozen-выбор создаёт или
переиспользует `CAPITAL_TO_PRODUCTION`; пересчёт не очищает этот выбор только потому, что целевой
repair является капитальным. Капремонт всё равно остаётся `QUEUED/NOT_READY` до завершения работ.
Эффективная дата задания водителя в private-ответе принадлежит logistics. HTTP-граница по-прежнему
требует непустую дату для `AUTO`; для `FIXED_DATE` она отклоняет дату раньше immutable request, но
разрешает более поздний эффективный результат. До подтверждения durable-работы
`CREATE_DRIVER_TASK` maintenance определяет текущий локальный день склада вне своей транзакции БД.
Текущий или будущий fixed request должен совпасть точно, а для просроченного request эффективная
дата принимается только во включительном диапазоне от исходной даты до этого локального дня. Более
поздняя дата отклоняется существующим bounded reconciliation failure path. Запрошенная дата repair
и stable idempotency key не меняются, а confirmation receipt записывает принадлежащую logistics
эффективную дату. Проверки identity, source, kind, priority и state не меняются. См. transport fence
[`MaintenanceLogisticsHttpClient`](src/main/java/dev/buhanzaz/rwms/maintenance/integration/MaintenanceLogisticsHttpClient.java)
и confirmation fence
[`MaintenanceTaskReconciliationUseCases`](src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceTaskReconciliationUseCases.java).
No-work publication использует отдельный authoritative cleanup и оставляет бытовку `FREE`.
Коллекция приёмки применяет пагинацию, необязательные фильтры состояния и точного `repairId`,
исключение unresolved дочерней доработки и proof исполнения inventory прямо в PostgreSQL до
материализации лёгкой проекции. Для inventory-origin rows каждая сохранённая стадия маршрута должна
иметь `DONE`, task-board entry/version, событие завершения и время завершения; версия регистрации
ремонта и системно записанное историческое завершение не являются proof исполнения. Тот же proof
ограничивает команды приёмки и доработки, поэтому исторические capital rows, созданные одной
публикацией, не попадают на эти поверхности. Повторное применение их завершённого результата
inventory возвращает прежние `COMPLETED/PENDING` rows в активный capital-route
`QUEUED/NOT_READY`.

Публикация проверяет fingerprint точного raw frozen snapshot до любой compatibility adaptation.
Только для schema version 1 исторический snapshot, в котором один и тот же непустой список
aggregate media скопирован в каждую строку, исполняется с очищенными в памяти line-копиями;
aggregate evidence прикрепляется один раз. Никакая другая форма version 1 не нормализуется, а
version 2 по-прежнему проходит строгую текущую validation. Raw snapshot и fingerprint сохраняются
без изменений, поэтому перезапись БД или миграция не требуется.

Внутренние вызовы используют service credentials, а не проброшенный пользовательский token.
Authorizer проверяет точные identity сервиса, audience и single-purpose scope для inventory и
logistics. HTTP security chain — stateless OAuth2/JWT; dev auth bypass ограничен профилем `dev` и
отклоняется production-валидатором.

## Изоляция складов и правило транзакций

Пользовательские операции авторизуются по запрошенному складу и уровню доступа. Maintenance хранит
состояние каталога, ремонта и распоряжения в собственной БД и никогда не выполняет join с БД другого
сервиса.

Удалённая истина доступна через `MaintenanceDependencyGateway` с client credentials для asset,
task-board, media, logistics и warehouse-service. Нельзя выполнять remote call, пока local
write-транзакция удерживает maintenance locks. Application flow фиксирует local preparation до
remote preflight/effects, затем продолжает local fenced state через durable recovery records.

Окно создания сметы — единая для всех складов настройка с контролем версии (`1..3650` дней, по
умолчанию `7`). Новая ручная смета читает последнее физическое прибытие возврата из logistics, а
автоматический источник возврата передаёт то же immutable evidence прибытия. Maintenance считает
inclusive deadline в часовом поясе склада. При прибытии 1 августа и семи днях создание разрешено
по 8 августа включительно, а с 9 августа возвращает `ESTIMATE_CREATION_WINDOW_EXPIRED`; независимая
команда прямого ремонта остаётся доступной.

Границы сложности ремонта также общие: изначально `60/180/360` целых минут.
Обе настройки используют GET/PUT в `/api/maintenance/v1/settings/` без идентификатора склада;
чтение доступно авторизованному пользователю, изменение — глобальному администратору.
Изменение границ в одной транзакции ставит активные ремонты всех складов на пересчёт
через существующий механизм reconciliation с сохранением readiness fences. Конфликт
откатывает всё изменение. Flyway V51 сохраняет прежние складские строки как архив,
переносит совпадающие значения либо значения по умолчанию и останавливается при
различающихся складских значениях, не выбирая одно из них автоматически.

## Явный выбор капитального ремонта

Команды сметы, первичного ремонта, inventory freeze и inventory publication могут передавать
необязательный `forceCapitalRepair`: отсутствие означает `false`, а явный JSON `null` отклоняется.
Выбор хранится независимо от флагов каталога и копируется в каждую ревизию сметы, ответ ремонта,
inventory snapshot и каждый новый Kafka-факт ESTIMATE/REPAIR v1. Для совместимости повторного
чтения отсутствие поля в историческом факте v1 принимается как `false`; явные не-boolean значения
остаются недопустимыми. Сложность ремонта становится CAPITAL, когда этот явный выбор истинен либо
любая текущая catalog WORK принудительно требует капремонт. Границы inventory freeze и сохранённого
snapshot отклоняют план, который одновременно запрашивает перемещение на ремонт, поэтому
maintenance не владеет двумя конкурирующими направлениями одного finding. Capital routing и
отдельный active-capital список остаются server-owned. Одна публикация inventory никогда не
открывает приёмку или доработку: для этих переходов требуется авторитетное завершение исполнения.
Клиенты сами эти эффекты не создают.
Миграция [`V44__manual_capital_repair_selection.sql`](src/main/resources/db/migration/V44__manual_capital_repair_selection.sql)
заполняет существующие сметы, ревизии и ремонты значением `false`.

## Настройки мебели и конфликты забронированной бытовки

Существующий maintenance editor мебели показывает asset-owned nullable максимум на бытовку ниже
сопоставления с характеристикой бытовки. Maintenance хранит только durable intent связи catalog
node, ожидаемую asset version и последнее подтверждённое наблюдение; второй каталог оборудования
или остаток здесь не создаётся. Save завершает local preparation, синхронно выполняет существующую
private asset-команду ensure/update вне local write transaction и подтверждает возвращённые version
и maximum до успешного завершения mutation каталога. Catalog reads обогащают подтверждённые связи
актуальными asset-owned значениями. Миграция `V42__furniture_equipment_maximum_sync.sql` расширяет
существующий link intent. `V43__backfill_furniture_equipment_link_intents.sql` создаёт один `PENDING`
intent для каждого мебельного node UUID со старой локальной snapshot и выбирает active snapshot, а не
копию из draft или superseded каталога, чтобы существующий reconciler установил недостающую external
reference в asset-service без выдумывания локальных настроек или remap каталога. Источником истины
остаётся asset-service.

Когда asset-service отклоняет maintenance lease или fenced status command из-за активной order
reservation бытовки, `MaintenanceHttpTransport` пропускает только точный upstream `409` code
`BOOKED_UNIT_REPLACEMENT_REQUIRED`. Публичный maintenance Problem Details сохраняет этот code и
понятное сообщение о замене. Все прочие dependency codes, включая неизвестные upstream `409`,
остаются `MAINTENANCE_DEPENDENCY_UNAVAILABLE`; maintenance не создаёт и не принимает решение о
workflow замены бытовки.

После атомарной замены бытовки asset-service также отклоняет maintenance lease acquisition, пока
существующее перемещение мебели замены имеет live source hold. Maintenance не копирует эту
готовность и не создаёт другой тип задания; после выполнения существующего movement (либо когда
его hold больше не live) тот же maintenance acquisition можно повторить.

## Внутренняя структура приложения

`MaintenanceApplicationService` — стабильный compatibility facade над шестью collaborators. Он
сохраняет API controllers/private boundaries и transaction annotations, а работу делегирует
связным application owners:

| Семейство collaborators | Владеющая ответственность |
| --- | --- |
| `MaintenanceCatalogUseCases` и catalog model/support types | Чтения catalog versions, draft mutation, forking, validation и activation |
| `MaintenanceEstimateUseCases`, `MaintenanceEstimateCreationUseCases` и estimate/furniture/revision supports | Фасад lifecycle сметы; admission/deadline/idempotency создания; lines, plans, furniture admission и immutable revisions |
| `MaintenanceRepairUseCases` и repair lifecycle/model/media/task-board supports | Создание ремонта, queueing, execution, acceptance и подготовка rework |
| `HistoricalShipmentRepairClosureService` | Private-closure отгрузки logistics: durable marker, компенсация pre-start task/movement, fenced release бытовки либо явный неизменённый proof `ALREADY_RENTED`, и audit-finalization ремонта вне local transactions |
| `MaintenanceTransferUseCases` и `MaintenanceTransferSupport` | Maintenance continuation при transfer departure/arrival |
| `MaintenanceInboundUseCases` и `MaintenanceInboundFactProjectionUseCases` | Приём owner facts и обновление projections |
| `MaintenanceReconciliationUseCases` | Только claim dispatch, media-owner proof и failure recording |
| Asset-, task- и repair-lifecycle reconciliation use cases | Три независимые ветки remote effect/recovery |
| `InventoryMaintenanceService` | Стабильный фасад private inventory boundary над freeze, upsert и repair-snapshot projection |
| Collaborators freeze/upsert/validation/transaction для inventory maintenance | Admission замороженного плана, validation каталога/routing/media и изолированные local transactions |
| `InventoryAuthoritativeOutcomeStore` и `InventoryAuthoritativeOutcomeService` | Ordering, compatibility и idempotent применение исходных или исправленных outcomes завершённого плана |
| `InventoryPublicationReconciliationService` | Стабильный фасад completed inventory над preflight projection и durable apply |
| Collaborators authoritative outcome/source/target/materialization для inventory | Completed-at watermark, replay/reassertion receipt, compensation predecessors, local supersession и materialization единственного full-plan repair |
| `PropertyDispositionApplicationService` | Стабильный decision facade над creation, furniture materialization, review/recovery, reads и processing callbacks |
| Collaborators property-disposition boundary, persistence, actor, repair-chain и finalization | Механика transaction/lock/hash, event parity, validation repair chain и terminal repair effects |
| `HttpMaintenanceDependencyGateway` | Стабильный private transport facade над владельцами warehouse, asset, logistics, task-board и media |
| `MaintenanceHttpTransport` и owner HTTP clients | Exact-scope OAuth/HTTP/error policy, owner-local endpoints, DTO validation и fence checks |

`MaintenanceCommandSupport` централизует только исходную механику local transaction, lock, hash и
JSON-команд; event-payload/model supports остаются узкими mappers. Dependency graph use cases
ацикличен, ни один collaborator не ссылается обратно на facade, а каждая constructor/direct
dependency surface не превышает 15. Package-local workflow records не связывают collaborators с
публичным nested result type фасада; только facade адаптирует carrier обратно в неизменённый public
response.

Inventory maintenance boundary следует тому же правилу. У его фасада только
три collaborators; validation и allocation плана образуют отдельную границу,
freeze и upsert владеют отдельными командами, а snapshot projection доступна
только для чтения. Routing- и warehouse-preflight calls выполняются лишь после
rollback короткой local transaction и освобождения её locks.

Публикация completed inventory использует тот же remote-outside-lock pattern. Её authoritative
outcome coordinator владеет permanent receipts, per-asset completed-at ordering, target-effect
ledgers, восстановлением lost response, local supersession, exact-source reassertion и
receipt-bound compatibility generations для immutable source history до coordinator. Source,
target, repair и plan components остаются однонаправленными зависимостями apply owner. Preflight
является независимой read projection и не может инициировать эффект публикации.

У property disposition пять однонаправленных application branches. Creation,
furniture materialization и review/recovery используют одинаковые узкие leaves
command, decision-persistence, actor и repair-chain; reads остаются только
projection, а processing callbacks владеют порядком mixed decision/repair
streams. Ни одна ветка не вызывает facade обратно и не выполняет remote
admission при удержании финального local decision lock.

Private HTTP gateway образует отдельный transport-only DAG. Пять owner clients
хранят свои endpoints и wire-validation vocabulary, а один общий transport
владеет только exact-scope client credentials, HTTP exchange и существующим
error policy. Owner clients не вызывают peers или application use cases и не
ссылаются обратно на gateway facade.

## Хранение, события и восстановление

Flyway-миграции в `src/main/resources/db/migration/` — единственный источник схемы. JPA использует
`ddl-auto=validate`; нельзя включать Hibernate schema mutation или добавлять межсервисные foreign
keys.

Миграция
[`V45__authoritative_inventory_maintenance_outcomes.sql`](src/main/resources/db/migration/V45__authoritative_inventory_maintenance_outcomes.sql)
добавляет permanent rows результата, receipt, per-asset watermark и ledger эффектов predecessor.
Remote attempts фиксируются до вызовов вне local transactions; lost response разрешается через
owner readback или тот же stable cancellation identity, а не через fabricated success. Local domain
rows помечаются historical только после terminal-состояния каждого обязательного remote ledger.

Миграция
[`V48__historical_rental_shipment_repair_closure.sql`](src/main/resources/db/migration/V48__historical_rental_shipment_repair_closure.sql)
аддитивно хранит identity импортирующей отгрузки и её audit-state `CLOSING`/`CLOSED` на ремонте. Она
не переписывает исторические repair/task evidence и не создаёт cross-service foreign key.

Зафиксированный факт агрегата записывается в local event stream, snapshot/checkpoint и
transactional outbox в одной PostgreSQL-транзакции. Доставка Kafka — at-least-once: relay повторно
проверяет envelope и отмечает outbox row только после broker acknowledgement; consumers выполняют
deduplication и хранят version-gap/recovery state; sanitized terminal failures проходят через
принадлежащий сервису DLT path, без копирования исходного тела сообщения в логи.

Inbound validator task-board принимает и точные исторические формы событий, и текущие канонические
формы: board-task fact обязательно содержит `lane` и может нести schedule, priority, pinning и пару
driver audience; queue-entry fact содержит nullable original/current budgets и не содержит
исторический `queueName`. Ошибка validation происходит до replay staging и поэтому не может быть
переиграна из DLT; после исправления validator восстановление повторно публикует immutable source
envelopes по порядку aggregate version, а не редактирует inbox или domain rows.

Завершение queue-entry не зависит исключительно от соответствующего факта создания board-task.
Если этот ранний факт появился до запуска consumer group maintenance, inbound domain effect
определяет владельца только по существующей локальной связи
`repair_stage.external_queue_entry_id` и external task ID связанного ремонта, без межсервисного
запроса. При готовности приложения
[`MaintenanceProcessedTaskOutcomeRecovery`](src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceProcessedTaskOutcomeRecovery.java)
запускается до worker-presentation reconciliation и идемпотентно применяет через обычный task-
outcome use case неизменяемые факты завершения `PROCESSED`, чья связанная стадия всё ещё
`QUEUED`. Он не изменяет audit-строки inbox/replay, исключает закрытия исторической отгрузкой и
использует существующие locks потока ремонта и стадии для безопасности повторного запуска и
конкурентных экземпляров.

`MAINTENANCE_KAFKA_ENABLED` управляет Kafka-specific relay и consumer beans. Он может быть `false`
только в явном профиле `dev` или `test`; любой другой профиль отклоняет startup при выключенной
доставке. Канонические owner outputs имеют строгий порядок: catalog-version, estimate, repair,
property-disposition и sanitized DLT. Они совпадают с channel addresses в
[`maintenance-events` contract](../../contracts/events/maintenance-events.yaml) и совместно
используются startup validation и output binding initialization.

## Runtime-конфигурация

Порт HTTP по умолчанию — `8087`. Настройте maintenance database, `AUTH_ISSUER`, CORS origin, а
для реальных dependencies — token URI, client ID/secret и private base URLs из
`src/main/resources/application.yaml`. Нельзя коммитить реальные credentials.

No-op dependency boundary доступна только для отключённых `dev`/`test` конфигураций. Production
runtime обязан включить real dependencies; production validator проверяет dependency configuration и
отклоняет dev auth bypass. Сервис не переносит business aggregation в API gateway.

## Наблюдаемость и эксплуатация

Actuator предоставляет `health`, `info` и `prometheus`. Консольные логи используют ECS, а
tracing sampling задаётся `MAINTENANCE_TRACING_SAMPLING_PROBABILITY`. Расследуйте задержанную работу
по local outbox/inbox/recovery records и correlation ID до изменения domain data или повторения
effect.

Prometheus предоставляет low-cardinality gauges `rwms.maintenance.outbox.backlog`,
`rwms.maintenance.outbox.oldest.age.seconds` и `rwms.maintenance.outbox.terminal`. Они читают
pending/in-flight age и DLT/quarantine work без tags из событий, не дренируя и не переписывая rows.
Terminal count может сканировать сохранённые terminal rows, потому что в текущей схеме нет
partial index по terminal status; такой индекс остаётся отдельно рассматриваемым Flyway follow-up.

## Локальная разработка

Из корня репозитория:

```bash
bash ./gradlew :services:maintenance-service:bootRun --args='--spring.profiles.active=dev'
```

Используйте изолированную dependency-конфигурацию только для разработки или тестов. Service
integrations используют private URLs и client credentials, но не public gateway и не browser bearer
token.

## Production Kafka safety

У base profile нет Kafka-enable fallback. Вне явных `dev`/`test` startup требует точный порядок
пяти topics, явные не-loopback brokers формата `host:port`, выключенный topic auto-creation,
синхронную идемпотентную публикацию с `acks=all`, положительные bounded timeouts с maximum publish
wait короче outbox lease, а также beans outbox relay, sanitized-DLT relay и output binding.
Production profile имеет приоритет при объединении с local profile. Validator не дренирует backlog:
recovery остаётся явной reviewed operation.

## Правила безопасного изменения

- Меняйте OpenAPI или AsyncAPI contract и всех затронутых producers/consumers одновременно.
- Оставляйте aggregate transitions, expected-version fencing, idempotency и compensation в этом сервисе.
- Добавляйте неизменяемые service-local Flyway migrations и валидируйте затронутые JPA mappings.
- Сохраняйте outbox/inbox deduplication, порядок агрегата и operator-reviewed recovery.
- Тестируйте focused public/private contract, а также persistence и failure/retry path.

## Основные исходные материалы

- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceApplicationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceCatalogUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceEstimateUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceRepairUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/MaintenanceReconciliationUseCases.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryMaintenanceService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryPublicationReconciliationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/service/InventoryAuthoritativeOutcomeService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/disposition/application/PropertyDispositionApplicationService.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/MaintenanceEventStore.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceKafkaOutboxRelay.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceTransportTopics.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/eventing/transport/MaintenanceEventingMetrics.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/config/MaintenanceProductionSafetyValidator.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/integration/MaintenanceDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/integration/HttpMaintenanceDependencyGateway.java`
- `src/main/java/dev/buhanzaz/rwms/maintenance/security/MaintenanceAuthorizer.java`
