# API `task-board-service`

Статус: справочное руководство по текущему API. Каноническая машиночитаемая
спецификация — [OpenAPI](../../contracts/openapi/task-board-service.yaml); если
она, этот документ и код расходятся, приоритет у OpenAPI и текущего кода
сервиса.

## Зачем нужен сервис

`task-board-service` — единственный владелец реестра работников и квалификаций,
очередей, назначений и оперативного состояния задач. `maintenance-service` и
`logistics-service` могут запросить создание своей работы по private-контракту,
но не выполняют переходы очереди и не меняют её данные напрямую.

Это решает практические проблемы распределённого WMS:

- У задачи один владелец статуса, позиции в очереди, назначения и временных
  событий; браузер, worker-app и исходные сервисы не расходятся в её состоянии.
- Общий процесс не копируется вручную по складам: глобальная очередь даёт
  стабильные складские проекции, но исторические ссылки и назначения остаются
  локальными.
- Повтор команды, reconnect worker-app или доставка Kafka не создают вторую
  задачу, действие работника либо резервирование фотографии.
- Работник видит только разрешённые ему задания, а его JWT определяет склад и
  `workerId`; эти значения нельзя подменить телом запроса.
- Закрывающийся склад не принимает новую работу, а межскладское перемещение
  проверяет направление на обоих складах до локальной мутации.

## Почему ответственность устроена именно так

- **Один command owner.** Внешний источник описывает работу с устойчивым
  `externalTaskId`; сервис создаёт route entries, позиции, назначения, timer и
  события. Поэтому нет browser-saga и нет двух сервисов, одновременно меняющих
  одну задачу.
- **Глобальная definition, локальная проекция.** `QueueDefinition` задаёт
  единый стандарт `GENERAL`: порядок, видимость, лимиты и квалификации.
  `WorkQueue` — derived-очередь конкретного склада с собственным UUID, который
  сохраняет историю задач. Специальная `LOGISTICS_DRIVER` очередь настраивается
  только для своего склада.
- **Версия — не косметика.** Команды используют `expectedVersion`, а для
  маршрутной задачи различают версию entry и версию task. Это предотвращает
  потерю параллельного изменения позиции, статуса либо состава маршрута.
- **Идемпотентность там, где сеть ненадёжна.** Source task определяется парой
  `client_id + externalTaskId`; worker action и evidence reservation требуют
  `Idempotency-Key`, равный `operationId`; активация KPI также имеет отдельный
  operation UUID. Эти механизмы нельзя заменять друг другом.
- **Offline не означает неконтролируемый.** Контекст выдаёт ограниченный по
  времени offline lease. При reconnect сервер принимает `occurredAt` только в
  рамках lease и сверяет action с версией и operation ID.
- **SSE — invalidation, а не источник правды.** В stream нет task snapshot.
  После сигнала worker-app заново получает авторизованный feed; так событие не
  раскрывает данные и не оставляет экран со старой проекцией.
- **Private-пути разделены по получателю.** Точный service credential и scope
  не позволяют `maintenance-service` использовать логистический маршрут, а
  логистике — менять общий catalog или задачу другого source.

## Как подключаться

OpenAPI использует service-local base `/api`. Для интерактивных клиентов
gateway публикует этот сервис под `/api/task-board/**`: например,
`GET /api/task-board/worker/v1/feed` приходит в service-local
`GET /api/worker/v1/feed`. Браузер использует тот же origin `/api/**`, Android
приложения — публичный адрес gateway.

Пути таблиц ниже указаны относительно service-local `/api`. Все public-запросы
несут `Authorization: Bearer <JWT>`. Не публикуйте и не вызывайте из panel,
manager-app или worker-app маршруты `/api/internal/**`, порты сервисов или их
базы данных. Private API — только для service-to-service запросов с отдельными
учётными данными.

## Модель и основные инварианты

| Сущность                   | Назначение и правило                                                                                                                                                     |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `WorkerClass`              | Глобальная квалификация. Её можно удалить лишь если нет queue bindings, групп и квалификаций работников; используемую сущность надо деактивировать.                      |
| `QueueDefinition`          | Глобальный стандарт `GENERAL`. Полное переупорядочивание передаёт весь каталог и версии его элементов; holding queues остаются terminal.                                 |
| `WorkQueue`                | Физическая складская проекция definition с устойчивым UUID. Менеджер не заменяет `GENERAL` queue вручную.                                                                |
| `Worker` и `WorkerGroup`   | Складские сущности. Группа имеет один worker class, active memberships и независимую operational availability. При disable активная работа сначала возвращается в board. |
| `BoardTask`                | Задача с source-facing `externalTaskId`, датой, priority, lane и route. `taskVersion` применяется к task-wide командам.                                                  |
| `QueueEntry`               | Шаг маршрута в очереди. `version` применяется к take/pause/resume/complete и к перемещению entry.                                                                        |
| `Assignment` и `TimeEvent` | Серверные факты назначения и времени, а не локальные состояния UI.                                                                                                       |
| `TaskEvidence`             | Серверно атрибутированное резервирование/результат. Worker не передаёт собственный `workerId` или группу в теле.                                                         |
| KPI settings               | Палитра и график склада имеют версию; график сначала pending, затем отдельно и идемпотентно активируется.                                                                |

Для `409` клиент сначала получает актуальную проекцию и предлагает осознанно
повторить действие. Нельзя просто подставить новую версию к команде, смысл
которой пользователь принимал на старых данных.

## Авторизация

| Поверхность                                  | Требование                                                                                                                                                                                     |
| -------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Чтение глобальных классов и definitions      | `principal_type=USER` и `rwms.read`.                                                                                                                                                           |
| Изменение классов и global queue definitions | `USER`, `rwms.write`, глобальная роль `SYSTEM_ADMIN` или `WMS_ADMIN`.                                                                                                                          |
| Складские queues, workforce и KPI            | `USER`, соответствующий `rwms.read`/`rwms.write` и warehouse grant не ниже `VIEW`/`MANAGE`.                                                                                                    |
| Операционный board                           | Пользователь: `rwms.read` либо `rwms.write` и warehouse grant; работник: `WORKER`, `worker.tasks` и его собственный `warehouse_id`. Manager-only команды требуют `USER`, `rwms.write`, `EDIT`. |
| Worker API                                   | `WORKER` и `worker.tasks`; `worker_id` и `warehouse_id` берутся исключительно из JWT.                                                                                                          |
| Queue reference                              | `SERVICE`, ровно разрешённый maintenance credential с `queue-registry.write`.                                                                                                                  |
| Maintenance preflight и task sync            | `SERVICE` с совпадающими `sub`/`client_id` и точным task-sync scope; source ID сервер извлекает из credential.                                                                                 |
| Dedicated logistics API                      | Только `logistics-service` с точным `task-board.logistics`.                                                                                                                                    |

Разработческий bypass ограничен profile `dev`; он не является частью
production-контракта.

## Публичный API: каталог и очереди

Пути таблицы относительны `/api`. Поля JSON, enums и ответы описаны в
[OpenAPI](../../contracts/openapi/task-board-service.yaml).

| `operationId`                   | Метод и путь                                       | Что делает и важное правило                                                                                                  |
| ------------------------------- | -------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `listQueueDefinitions`          | `GET /queue-definitions`                           | Возвращает shared catalog. `GENERAL` definitions — один стандарт для всех складов; driver definition настраивается отдельно. |
| `createQueueDefinition`         | `POST /queue-definitions`                          | Создаёт global `GENERAL` definition и derived проекции на складах.                                                           |
| `updateQueueDefinition`         | `PUT /queue-definitions/{id}`                      | Версионно заменяет definition и синхронизирует проекции.                                                                     |
| `deleteQueueDefinition`         | `DELETE /queue-definitions/{id}?expectedVersion=`  | Удаляет только неиспользуемую global definition.                                                                             |
| `reorderQueueDefinitions`       | `PUT /queue-definitions/order`                     | Принимает полный version-fenced порядок global catalog.                                                                      |
| `listWorkQueues`                | `GET /warehouses/{warehouseId}/work-queues`        | Возвращает stable physical queues выбранного склада.                                                                         |
| `updateDriverQueue`             | `PUT /warehouses/{warehouseId}/driver-queue`       | Создаёт/обновляет только warehouse-specific `LOGISTICS_DRIVER`; запрос не выбирает definition.                               |
| `getWarehouseQueueCapabilities` | `GET /warehouses/{warehouseId}/queue-capabilities` | Возвращает активные видимые routing capabilities.                                                                            |
| `listWorkerClasses`             | `GET /worker-classes`                              | Возвращает global catalog квалификаций.                                                                                      |
| `createWorkerClass`             | `POST /worker-classes`                             | Создаёт global worker class.                                                                                                 |
| `updateWorkerClass`             | `PUT /worker-classes/{id}`                         | Версионно обновляет класс.                                                                                                   |
| `deleteWorkerClass`             | `DELETE /worker-classes/{id}?expectedVersion=`     | Удаляет только неиспользуемый класс.                                                                                         |

## Публичный API: workforce

| `operationId`                      | Метод и путь                                                                | Что делает и важное правило                                                                 |
| ---------------------------------- | --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `listWorkers`                      | `GET /warehouses/{warehouseId}/workers`                                     | Список работников склада.                                                                   |
| `createWorker`                     | `POST /warehouses/{warehouseId}/workers`                                    | Создаёт profile и при необходимости запускает provisioning credentials через auth boundary. |
| `updateWorker`                     | `PUT /warehouses/{warehouseId}/workers/{id}`                                | Версионно заменяет profile, qualifications и нужную credential operation.                   |
| `deleteWorker`                     | `DELETE /warehouses/{warehouseId}/workers/{id}?expectedVersion=`            | Удаляет работника после проверки зависимостей и credential-side операции.                   |
| `setWorkerCurrentGroup`            | `PUT /warehouses/{warehouseId}/workers/{id}/current-group`                  | Версионно меняет текущую группу.                                                            |
| `resetWorkerCredentials`           | `POST /warehouses/{warehouseId}/workers/{id}/credentials/reset`             | Сбрасывает credential через явную external operation; пароль не попадает в события/логи.    |
| `disableWorkerCredentials`         | `POST /warehouses/{warehouseId}/workers/{id}/credentials/disable`           | Запускает version-fenced disable.                                                           |
| `enableWorkerCredentials`          | `POST /warehouses/{warehouseId}/workers/{id}/credentials/enable`            | Запускает version-fenced enable.                                                            |
| `reconcileWorkerCredentialDisable` | `POST /warehouses/{warehouseId}/workers/{id}/credentials/reconcile-disable` | Сверяет expired/failed credential operation с auth-service, не предполагая успеха.          |
| `reconcileWorkerDeletion`          | `POST /warehouses/{warehouseId}/workers/{id}/deletion/reconcile`            | Повторяет reconciliation удаления после внешнего credential шага.                           |
| `listWorkerGroups`                 | `GET /warehouses/{warehouseId}/worker-groups`                               | Список групп с current operational status.                                                  |
| `createWorkerGroup`                | `POST /warehouses/{warehouseId}/worker-groups`                              | Создаёт группу одного worker class с memberships.                                           |
| `updateWorkerGroup`                | `PUT /warehouses/{warehouseId}/worker-groups/{id}`                          | Версионно заменяет группу и членство.                                                       |
| `deleteWorkerGroup`                | `DELETE /warehouses/{warehouseId}/worker-groups/{id}?expectedVersion=`      | Удаляет только неиспользуемую группу.                                                       |
| `disableWorkerGroup`               | `POST /warehouses/{warehouseId}/worker-groups/{id}/disable`                 | Возвращает активную работу группы, затем помечает её unavailable; reason обязателен.        |
| `enableWorkerGroup`                | `POST /warehouses/{warehouseId}/worker-groups/{id}/enable`                  | Возвращает группу в operational availability.                                               |

## Публичный API: manager task board и KPI

| `operationId`                            | Метод и путь                                                                                      | Что делает и важное правило                                                                                                                        |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| `getTaskBoard`                           | `GET /warehouses/{warehouseId}/task-board`                                                        | Агрегированная ordinary board: все активные реальные карточки и ограниченное окно ожидания каждой очереди. Поддерживает weak semantic ETag.         |
| `getDriverLogisticsBoard`                | `GET /warehouses/{warehouseId}/task-board/logistics`                                              | Отдельный driver board: sticky current lane и непустые scheduled-date колонки, без обычных repair queues.                                          |
| `getWarehouseKpiSettings`                | `GET /warehouses/{warehouseId}/task-board/kpi-settings`                                           | Active и pending KPI settings выбранного склада.                                                                                                   |
| `replaceWarehouseKpiPalette`             | `PUT /warehouses/{warehouseId}/task-board/kpi-settings/palette`                                   | Версионно заменяет ranges и overdue color.                                                                                                         |
| `replacePendingWarehouseKpiWorkSchedule` | `PUT /warehouses/{warehouseId}/task-board/kpi-settings/work-schedule`                             | Сохраняет единственный pending future-effective график.                                                                                            |
| `deletePendingWarehouseKpiWorkSchedule`  | `DELETE /warehouses/{warehouseId}/task-board/kpi-settings/work-schedule/pending?expectedVersion=` | Удаляет pending schedule.                                                                                                                          |
| `activateWarehouseKpiSettings`           | `POST /warehouses/{warehouseId}/task-board/kpi-settings/activate`                                 | Активирует pending schedule; UUID `Idempotency-Key` обязателен.                                                                                    |
| `listEligibleWorkerGroups`               | `GET /warehouses/{warehouseId}/task-board/queues/{queueId}/eligible-groups`                       | Возвращает текущие eligible groups для physical queue.                                                                                             |
| `getTaskEntryHistory`                    | `GET /warehouses/{warehouseId}/task-board/entries/{entryId}/history`                              | Durable time-event history entry.                                                                                                                  |
| `registerBoardTask`                      | `POST /warehouses/{warehouseId}/task-board/tasks`                                                 | Создаёт manager-originated task с полным ordered route.                                                                                            |
| `getBoardTaskByExternalId`               | `GET /warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}`                  | Возвращает source-facing task registration в данном warehouse scope.                                                                               |
| `cancelBoardTaskByExternalId`            | `POST /warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}/cancel`          | Версионно отменяет задачу с auditable reason.                                                                                                      |
| `takeTaskEntry`                          | `POST /warehouses/{warehouseId}/task-board/entries/{entryId}/take`                                | Версионно берёт entry; если вызывает worker, actor выводится из JWT.                                                                               |
| `pauseTaskEntry`                         | `POST /warehouses/{warehouseId}/task-board/entries/{entryId}/pause`                               | Версионно ставит active entry на паузу.                                                                                                            |
| `resumeTaskEntry`                        | `POST /warehouses/{warehouseId}/task-board/entries/{entryId}/resume`                              | Возобновляет paused entry по его версии.                                                                                                           |
| `completeTaskEntry`                      | `POST /warehouses/{warehouseId}/task-board/entries/{entryId}/complete`                            | Завершает entry по версии и completion rules.                                                                                                      |
| `setTaskPinned`                          | `POST /warehouses/{warehouseId}/task-board/tasks/{taskId}/pin`                                    | Pin/unpin во всех route queues без изменения позиции.                                                                                              |

### Получение board с ETag

```http
GET /api/task-board/warehouses/{warehouseId}/task-board
Authorization: Bearer <JWT>
If-None-Match: W/"task-board-..."
```

При неизменном semantic state сервер ответит `304` и вернёт ETag. Не считайте
падение `remainingSeconds` самостоятельным изменением board: клиент выводит
rolling timer из server time, timer state и `nextTransitionAt`.

У ordinary board нет query-параметров даты/shadow и публичных команд move или обмена дат. Каждая
очередь возвращает все реальные карточки `IN_PROGRESS`/`PAUSED` и первые `availableTaskLimit`
реальных карточек `WAITING` в server priority order; `TAKE` проверяет то же окно. Датированное
планирование водителей и отгрузок остаётся на logistics board.

## Публичный API: worker-app

Эти операции вызываются через gateway namespace `/api/task-board/worker/v1` и
только worker JWT.

| `operationId`               | Метод и путь                                              | Что делает и важное правило                                                                                                                         |
| --------------------------- | --------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `getWorkerContext`          | `GET /worker/v1/context`                                  | Возвращает profile, группы, qualifications, visible categories, server-time anchor, revision и 24-hour offline lease.                               |
| `getWorkerFeed`             | `GET /worker/v1/feed?cursor=&limit=`                      | Cursor page видимых entries; cursor opaque, limit 1–50. Изменившаяся revision между страницами даёт `409`; первая страница поддерживает ETag/`304`. |
| `getWorkerTaskDetail`       | `GET /worker/v1/entries/{entryId}`                        | Sanitized detail. Невидимый entry неотличим от `404`.                                                                                               |
| `streamWorkerEvents`        | `GET /worker/v1/events`                                   | SSE authorization-filtered invalidations; payload содержит ID/revision, не snapshot.                                                                |
| `applyWorkerAction`         | `POST /worker/v1/entries/{entryId}/actions`               | `Idempotency-Key` обязан равняться `operationId`; action принимается только в referenced offline lease.                                             |
| `reserveWorkerTaskEvidence` | `POST /worker/v1/entries/{entryId}/evidence-reservations` | До media upload резервирует stable `evidenceId`; exact replay возвращает существующее reservation.                                                  |
| `registerWorkerDevice`      | `PUT /worker/v1/devices/{installationId}`                 | Создаёт или заменяет binding только текущего worker.                                                                                                |
| `unregisterWorkerDevice`    | `DELETE /worker/v1/devices/{installationId}`              | Удаляет только installation binding аутентифицированного worker.                                                                                    |

### Offline worker action

```http
POST /api/task-board/worker/v1/entries/{entryId}/actions
Authorization: Bearer <worker JWT>
Idempotency-Key: 1f0a7c23-7e4f-4f96-96ef-7a03c2cb2b87
Content-Type: application/json

{
  "operationId": "1f0a7c23-7e4f-4f96-96ef-7a03c2cb2b87",
  "action": "COMPLETE",
  "expectedVersion": 12,
  "occurredAt": "2026-08-07T10:45:00Z",
  "offlineLeaseId": "bc7e8b75-e1b5-4f90-bf74-8926eb8b9b11"
}
```

Exact replay возвращает ранее применённый result. Другой body с тем же key,
просроченный lease, stale version или неверный state возвращают `409`; не
создавайте новый operation ID, пока не поняли, что первая команда не была
принята.

## Private API: очередь и maintenance preflight

Это service-local private-пути: `/api/internal/...`. Они не маршрутизируются
для интерактивных клиентов.

| `operationId`                           | Метод и путь относительно `/api`                                                              | Получатель и правило                                                                                                    |
| --------------------------------------- | --------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `registerQueueReference`                | `POST /internal/queue-definitions/{queueDefinitionId}/references`                             | Только maintenance credential. Регистрирует typed usage reference и не даёт удалить используемую definition.            |
| `deleteQueueReference`                  | `DELETE /internal/queue-definitions/references/{type}/{externalReferenceId}?expectedVersion=` | Только maintenance credential. Удаляет известную typed reference.                                                       |
| `preflightMaintenanceRouting`           | `POST /internal/task-board/v1/maintenance/routing-preflight`                                  | Read-only bulk check UUID-based global definitions и warehouse bindings; ничего не создаёт, не резервирует и не меняет. |
| `preflightMaintenanceCatalogRouting`    | `POST /internal/task-board/v1/maintenance/catalog-routing-preflight`                          | Read-only check global catalog до подготовки maintenance command.                                                       |
| `getInternalWarehouseQueueCapabilities` | `GET /internal/task-board/v1/warehouses/{warehouseId}/queue-capabilities`                     | Narrow private view active visible process capabilities для task-sync credential.                                       |

`MaintenanceRoutingPreflightResponse` отдельно сообщает missing global definition,
missing warehouse binding и mismatch (`TYPE`, `ACTIVE`, `HIDDEN`). Source не
должен угадывать название очереди или создавать её как побочный эффект
preflight.

## Private API: source task sync и logistics

| `operationId`                             | Метод и путь относительно `/api`                                                          | Получатель и правило                                                                                                        |
| ----------------------------------------- | ----------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `registerExternalBoardTask`               | `POST /internal/task-board/v1/tasks`                                                      | Maintenance или logistics с exact task-sync credential. Пара source client + `externalTaskId` задаёт replay-safe ownership. |
| `getExternalBoardTask`                    | `GET /internal/task-board/v1/tasks/{externalTaskId}`                                      | Возвращает только task authenticated source.                                                                                |
| `updateExternalBoardTaskBeforeStart`      | `PUT /internal/task-board/v1/tasks/{externalTaskId}`                                      | Меняет source content только до начала выполнения.                                                                          |
| `cancelExternalBoardTask`                 | `POST /internal/task-board/v1/tasks/{externalTaskId}/cancel`                              | Version-fenced cancellation source-owned task.                                                                              |
| `cancelExternalBoardTaskIfPreStart`       | `POST /internal/task-board/v1/tasks/{externalTaskId}/cancel-if-pre-start`                 | Возвращает typed outcome: отменено, уже отменено, начато или version conflict.                                              |
| `relocateExternalBoardTask`               | `POST /internal/task-board/v1/tasks/{externalTaskId}/relocate`                            | Межскладской перенос после OUTGOING admission исходного и INCOMING admission целевого склада.                               |
| `setExternalDriverTaskLane`               | `POST /internal/task-board/v1/tasks/{externalTaskId}/lane`                                | Меняет lane source-owned driver task по `expectedTaskVersion`.                                                              |
| `getExternalDriverTaskCompletionEvidence` | `GET /internal/task-board/v1/tasks/{externalTaskId}/completion-evidence`                  | Возвращает selected READY result photo после DONE только в разрешённом source scope.                                        |
| `registerLogisticsEquipmentMovementTask`  | `POST /internal/task-board/v1/logistics/equipment-movement-tasks`                         | Только logistics. Logistics передаёт immutable equipment facts; title, route и queue выводит task-board.                    |
| `getLogisticsEquipmentMovementTask`       | `GET /internal/task-board/v1/logistics/equipment-movement-tasks/{externalTaskId}`         | Typed snapshot matching logistics-owned movement task.                                                                      |
| `cancelLogisticsEquipmentMovementTask`    | `POST /internal/task-board/v1/logistics/equipment-movement-tasks/{externalTaskId}/cancel` | Version-fenced cancel typed logistics task.                                                                                 |
| `getInternalDriverLogisticsBoard`         | `GET /internal/task-board/v1/logistics/warehouses/{warehouseId}/board`                    | Logistics-only driver board.                                                                                                |
| `moveInternalDriverLogisticsTask`         | `POST /internal/task-board/v1/logistics/tasks/{externalTaskId}/move`                      | Версионно двигает logistics driver entry по lane/date/index.                                                                |

## Типовой безопасный поток

1. Администратор задаёт global worker classes и `GENERAL` queue definitions.
   Сервис сам создаёт и synchronizes physical queues на активных складах.
2. Warehouse manager заводит работников и группы, настраивает local driver
   queue и KPI schedule.
3. `maintenance-service` перед собственной командой выполняет read-only
   preflight. Затем source service регистрирует external task со стабильным
   `externalTaskId`.
4. Manager читает board с ETag; worker-app получает context, feed и события.
   Любой transition передаёт актуальную версию.
5. Worker перед upload резервирует evidence. Task-board публикует owner proof,
   которым media-service ограничивает доступ к task media.
6. После completion source service читает только contract-defined completion
   evidence либо события; он не читает task-board database.

## Ошибки, параллельность и retry

Ответ ошибки — `application/problem+json` с типизированным `code`, нарушениями
полей и correlation data.

| Статус | Когда ожидаем                                                                                                                 | Действие клиента                                                                          |
| ------ | ----------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `400`  | Неверный UUID/дата/body, отсутствует обязательное поле, невалиден schedule или request не проходит validation.                | Исправить команду, не ретраить вслепую.                                                   |
| `401`  | Отсутствует, просрочен или неверен Bearer token.                                                                              | Получить корректный token.                                                                |
| `403`  | Неверны principal type, scope, global role, warehouse grant или private service identity.                                     | Использовать ровно contract-defined credential, не расширять scope догадками.             |
| `404`  | Ресурс отсутствует либо намеренно скрыт source/worker authorization.                                                          | Проверить identity и scope; не создавать замену по name.                                  |
| `409`  | Stale version, task state/queue rule, changed idempotent replay, expired lease, changed feed revision или неполный date swap. | Перечитать актуальное состояние и решить, нужен ли новый осознанный command.              |
| `502`  | Auth credential operation или другой declared dependency недоступны.                                                          | Сохранить контекст; использовать dedicated reconciliation route, когда это предусмотрено. |

## События

Изменение и факт outbox фиксируются в одной локальной PostgreSQL транзакции.
AsyncAPI: [task-board events](../../contracts/events/task-board-events.yaml),
JSON Schema: [v1](../../contracts/events/task-board/task-board-events-v1.schema.json).

- Kafka delivery at-least-once; ключ — `aggregateId`, а `eventId` — identity
  consumer-side inbox deduplication.
- Публикуются facts для board task, queue entry, worker/class/group, work queue,
  queue reference, media owner proof, task evidence и daily group KPI.
- Канал `rwms.task-board.entry-owner-proof.v1` содержит monotonic authorization
  proof. Media-service отклоняет WORKER access, пока proof не active или worker
  в нём не указан.
- Канал task evidence передаёт server-attributed факт downstream owner; имена
  работника и группы намеренно не реплицируются.
- `group-kpi-day` — replaceable warehouse-local evidence для
  `analytics-service`, а не источник команд обратно в task-board.

## Чего API намеренно не делает

- Не отдаёт private endpoints браузеру или Android-клиенту.
- Не принимает `workerId`, warehouse identity либо source client identity как
  доверенную identity из public body.
- Не позволяет складу редактировать shared `GENERAL` process standard как
  локальную копию.
- Не превращает SSE в task-data transport или offline cache в authoritative
  state.
- Не переносит workflow и compensation в gateway или UI.
- Не позволяет source service напрямую менять очередь, assignment, time event
  либо database task-board-service.

Эти ограничения дают предсказуемые retries, restart recovery и корректную
изоляцию складов при параллельной работе нескольких сервисов.

## Авторитетные источники

- [OpenAPI contract](../../contracts/openapi/task-board-service.yaml)
- [AsyncAPI event contract](../../contracts/events/task-board-events.yaml)
- [Task board application service](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/TaskBoardService.java)
- [Queue registry owner](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/RegistryService.java)
- [Worker API service](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/service/WorkerTaskBoardService.java)
- [Authorization boundaries](../../services/task-board-service/src/main/java/dev/buhanzaz/rwms/taskboard/security/)
- [Gateway route boundary](../../services/api-gateway-service/README.ru.md)
