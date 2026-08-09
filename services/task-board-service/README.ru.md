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
| Worker execution | Scoped feed/context, offline action lease, evidence reservation, SSE/FCM invalidation | Worker app обновляет authoritative REST state и загружает media через media-service |
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

worker app --> feed/detail --> take/action/evidence reservation
          --> media upload --> media fact --> evidence state --> completion
```

Source-owned task использует stable external identity, поэтому retry находит ту
же задачу, а не создаёт duplicate. Mutable entry operations fenced по
contract-defined version и status. Route order, eligibility, assignment и
terminal transitions остаются server-owned.

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
| `/api/internal/task-board/v1/maintenance/**` | Exact maintenance-service identity | Routing и catalog preflight |
| `/api/internal/task-board/v1/tasks/**` | Exact source service identity | Idempotent task synchronization и evidence reads |
| `/api/internal/task-board/v1/logistics/**` | Exact logistics-service identity | Driver/equipment task integration |
| `/api/internal/queue-definitions/**` | Allow-listed service identity | Durable queue usage references |

Private paths — service-to-service boundaries, а не client shortcuts. Их exact
`principal_type`, `client_id`, scope, source ownership и warehouse checks входят
в контракт.

## Worker stream и offline execution

`GET /api/worker/v1/events` — SSE invalidation stream. Текущий producer
отправляет `FEED_CHANGED` при подписке и последующих изменениях; worker app также
периодически делает authoritative REST refresh. Payload не является полной task
projection.

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

## Безопасность и изоляция

- Все API chains валидируют JWT issuer/audience; worker routes требуют узкий
  worker scope.
- Manager commands проверяют user role, warehouse access и command-specific
  write permission в `WarehouseAccessAuthorizer` и services.
- Internal task, queue-reference, maintenance и logistics operations требуют
  exact service identity/scope и проверяют source ownership.
- Auth-service остаётся владельцем credentials. Task-board хранит только
  operational workflow state, нужный для reconciliation.
- CORS использует explicit panel/worker origins. Browser/mobile clients идут
  через gateway; сервисы — по private routes с client credentials.
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
`TASK_BOARD_KAFKA_ENABLED=true`.

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

Worker actions/streams требуют worker-app contract/unit tests и exact APK build.
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
- [Task-board application service](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardService.java)
- [Task-board read projection](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardReadProjectionService.java)
- [External task registration](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalRegistrationService.java)
- [External task mutation](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardExternalMutationService.java)
- [Queue position coordinator](src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardQueuePositionCoordinator.java)
- [Worker application service](src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerTaskBoardService.java)
- [Workforce service](src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkforceService.java)
- [Production safety validator](src/main/java/dev/buhanzaz/rwms/taskboard/config/TaskBoardProductionSafetyValidator.java)
- [Event store](src/main/java/dev/buhanzaz/rwms/taskboard/eventing/TaskBoardEventStore.java)
- [Карта runtime flows](../../docs/project-knowledge/runtime-flows.md)
