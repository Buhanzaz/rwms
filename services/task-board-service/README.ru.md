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
| Operational work | Tasks, route entries, assignment, pinning, move, pause/resume/complete и history | Source domain владеет причиной работы и состоянием своего агрегата |
| Native execution | Раздельные driver/worker feeds, offline action leases, evidence reservation, SSE и transactional FCM invalidation | DriverApp и WorkerApp обновляют authoritative REST state и загружают media через media-service |
| KPI | Warehouse palette/schedule revisions и emitted daily evidence | Analytics владеет KPI read projection |
| Warehouse lifecycle | Local operation marks, admission fence, draining blockers и exact-version readiness | Warehouse-service владеет lifecycle state и admission decisions |

Сервис не владеет users/roles, warehouse identity, repair или logistics
aggregates, media bytes, analytics projections или gateway routing.

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
```

Source-owned task использует stable external identity, поэтому retry находит ту
же задачу, а не создаёт duplicate. Mutable entry operations fenced по
contract-defined version и status. Route order, eligibility, assignment и
terminal transitions остаются server-owned.

Logistics driver task дополнительно несёт одну сохранённую аудиторию:
`UNASSIGNED`, `ASSIGNED_DRIVER` или `WAREHOUSE_DRIVERS`. Задавать её может
только точный driver-task source logistics-service. Только назначенная работа содержит worker
identity; этот worker должен быть активен на том же складе и иметь primary qualification
водительской очереди. Task-board игнорирует переданное caller-ом display name и сохраняет
авторитетный worker snapshot. Неназначенная задача остаётся работой диспетчера. Назначенную видит
только этот водитель. Ожидающую identity-free общую задачу видят все квалифицированные водители
склада до take, после чего доступ остаётся только у фактического исполнителя. DriverApp получает
только primary bindings через `/api/driver/v1/**`; WorkerApp получает обычную работу и только
активную secondary logistics work через `/api/worker/v1/**`. Take водителя транзакционно сохраняет
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
audience под общим task/entry version fence; public board move её не меняет.

## Внутренняя структура приложения

`TaskBoardService` — стабильный transactional facade над шестью collaborators.
Он сохраняет прежнюю поверхность методов controllers/private boundaries, а
решениями владеют следующие компоненты:

| Компонент | Владеющая ответственность |
| --- | --- |
| `TaskBoardReadProjectionService` | Чтения board/task и response projections |
| `TaskBoardExternalRegistrationService` | Создание source-owned task, identity, route и canonical retry fingerprint |
| `TaskBoardExternalMutationService` | Source-authorized pre-start update/cancel, перемещение lane и relocation |
| `TaskBoardLogisticsTaskService` | Граница logistics equipment/driver task |
| `TaskBoardWorkerExecutionService` | Assignment, timing, interruption, cancellation и worker execution |
| `TaskBoardOrderingService` | Manager-owned move, swap, pin и rollover operations |
| `TaskBoardQueuePositionCoordinator` | Только advisory locks, stream fences и persisted queue/pin ordering |
| `TaskBoardRoutePayloadCodec` | Единственный canonical route JSON и fingerprint codec |
| `DriverTaskAudienceService` | Shape аудитории logistics-driver, qualification, visibility и execution authorization |
| `MobileTaskSurfacePolicy` | Непересекающиеся DriverApp primary и WorkerApp secondary capabilities |
| `WorkerTaskAccessService` | Общая worker/group/qualification аудитория очередей для native task reads и media proofs |
| `TaskBoardEntryOwnerProofReconciler` | Bounded idempotent восстановление legacy или workforce-stale аудиторий media proof |
| `WorkerPushOutbox` / `WorkerPushDispatcher` | Transactional уведомление стропальщика, leased FCM delivery и bounded recovery |
| `WorkforceService` | Стабильный фасад worker/group API над тремя владельцами lifecycle |
| `WorkforceProfileService` | Изменение worker profile и qualifications с сохранением credential lock span |
| `WorkforceCredentialLifecycleService` | Durable auth credential intents, completion/failure fencing, reconciliation и deletion recovery |
| `WorkforceGroupService` | Group membership, availability и интервалы current group |
| `WorkforceReadProjectionService` | Read-only сборка DTO worker и group |

Dependency graph ацикличен. Registration и mutation совместно используют route
codec; registration, mutation, worker execution и ordering используют узкий
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
assignees и работников с evidence. Bounded reconciler каждые 30 секунд публикует
только изменившийся proof, дополняет события до additive reader field и сводит
последующие изменения workforce или queue policy. Inactive proof не разрешает
новый upload или finalization.

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
| `/api/warehouses/{warehouseId}/work-queues` | Warehouse-authorized user | Physical queue projections и capabilities |
| `/api/warehouses/{warehouseId}/task-board/**` | Warehouse-authorized user | Board reads и operational commands |
| `/api/warehouses/{warehouseId}/task-board/kpi-settings/**` | Warehouse manager/admin | Palette и effective schedule revisions |
| `/api/worker/v1/**` | Worker credential и `worker.tasks` scope | Context, feed, detail, actions, evidence reservations, devices и events |
| `/api/driver/v1/**` | Worker credential и `driver.tasks` scope | Driver-only context, primary feed, actions, evidence reservations, devices и events |
| `/api/internal/task-board/v1/maintenance/**` | Exact maintenance-service identity | Routing и catalog preflight |
| `/api/internal/task-board/v1/tasks/**` | Exact source service identity | Idempotent task synchronization и evidence reads |
| `/api/internal/task-board/v1/logistics/**` | Exact logistics-service identity | Driver/equipment task integration |
| `/api/internal/queue-definitions/**` | Allow-listed service identity | Durable queue usage references |

Private paths — service-to-service boundaries, а не client shortcuts. Их exact
`principal_type`, `client_id`, scope, source ownership и warehouse checks входят
в контракт.

## Native streams и offline execution

`GET /api/worker/v1/events` — SSE invalidation stream. Текущий producer
отправляет `FEED_CHANGED` при подписке и последующих изменениях; worker app также
периодически делает authoritative REST refresh. Payload не является полной task
projection.

`GET /api/driver/v1/events` имеет ту же invalidation-only семантику. Device
registrations привязаны к surface и принимают текущие Firebase Installation ID
(`targetKind=FID`) и legacy registration tokens. Успешный TAKE водителя сохраняет
уведомление стропальщику в `worker_push_outbox` в той же транзакции; leased dispatcher
повторяет transient FCM failures, отзывает invalid targets и не отправляет вызовы
стропальщика DriverApp installations.

`WorkerTaskDetail.source` присутствует всегда и равен null для обычной работы. Для source-owned
работы он содержит только существующие immutable type и ID источника. Worker client может
использовать ID `LOGISTICS_DRIVER_TASK`, чтобы загрузить принадлежащие logistics детали ходки;
task-board не копирует этот payload или его business state.

Worker action проверяет identity работника, current assignment, entry version,
action/status transition и offline lease, где он нужен. Evidence сначала
резервируется со stable client reference, затем загружается в media-service.
Media fact связывает обработанную generation с reservation до использования в
completion.

Текущий OpenAPI упоминает `Last-Event-ID`, но controller и client не реализуют
durable replay. Reconnect сейчас безопасен благодаря fresh invalidation и
periodic pull; semantic mismatch и необходимое решение записаны в полном аудите.

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

## Безопасность и изоляция

- Все API chains валидируют JWT issuer/audience; worker и driver routes требуют
  непересекающиеся scopes `worker.tasks` и `driver.tasks`.
- Manager commands проверяют user role, warehouse access и command-specific
  write permission в `WarehouseAccessAuthorizer` и services.
- Internal task, queue-reference, maintenance и logistics operations требуют
  exact service identity/scope и проверяют source ownership.
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
