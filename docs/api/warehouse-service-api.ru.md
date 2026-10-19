# API `warehouse-service`

Статус: справочное руководство по текущему API. Каноническая машиночитаемая
спецификация — [OpenAPI](../../contracts/openapi/warehouse-service.yaml); при
расхождении приоритет у неё и у кода сервиса.

## Зачем нужен сервис

`warehouse-service` — единственный владелец идентичности склада, его
отображаемых метаданных, жизненного цикла и истории часовых поясов. Остальные
сервисы хранят только UUID склада и запрашивают ровно ту проекцию, которая им
нужна. Они не читают его базу данных и не меняют состояние склада напрямую.

Это решает четыре практические проблемы:

- Один и тот же склад не получает разные имена, состояния или часовые пояса в
  `asset`, `inventory`, `logistics`, `maintenance` и `task-board`.
- Новый приход нельзя начать на закрывающемся складе, но уже начатый вывоз
  можно безопасно завершить.
- Изменение часового пояса не меняет задним числом дату и время уже проведённых
  операций или отчётов.
- Повтор HTTP-команды или Kafka-доставки не создаёт второй склад, второе
  подтверждение либо вторую публикацию факта.

## Почему ответственность устроена именно так

Склад — общий справочник, но не общий mutable-объект. Его центральный владелец
нужен, чтобы не строить распределённую транзакцию между сервисами и не
допустить «разъезда» справочника.

- **UUID, а не имя.** `id` — стабильная интеграционная ссылка. Имя удобно для
  людей, нормализуется и уникально, но не годится для связей: его можно
  изменить.
- **Однонаправленный lifecycle.** Переходы только
  `ACTIVE -> DRAINING -> INACTIVE`. Это исключает повторное открытие склада с
  недренированными обязательствами.
- **Направленная admission-проверка.** Поле `active` совместимо со старыми
  потребителями, но не различает исходящую работу в `DRAINING`. Владельцы
  операций должны спрашивать отдельный private endpoint с направлением.
- **Подтверждение от каждого владельца.** Перед `INACTIVE` нужны неизменяемые
  подтверждения `asset-service`, `inventory-service`, `logistics-service`,
  `maintenance-service` и `task-board-service`. Таймаут, пустой кэш или
  недоставленное событие не считаются готовностью.
- **Effective-dated timezone.** До первой операции зона исправляется сразу.
  После неё добавляется решение с будущим `effectiveFrom`; историческая
  операция всегда разрешает зону на свой момент времени.
- **Transactional outbox.** Изменение склада и запись события происходят в
  одной локальной транзакции. Kafka доставляет факт как минимум один раз, а
  потребитель дедуплицирует его по `eventId`.

## Как подключаться

Интерактивные клиенты (`panel`, manager и worker Android apps) идут только
через публичный gateway: same-origin `/api/**` для браузера и его публичный
адрес для приложений. Не помещайте private пути `/api/internal/**` в клиент,
не публикуйте их через gateway и не используйте порты сервисов напрямую.

Private API предназначен исключительно для аутентифицированных
service-to-service вызовов. Он специально разделён по получателям: это не
дробление «ради URL», а ограничение прав токена и объёма возвращаемых данных.

Все запросы используют `Authorization: Bearer <JWT>`. Значения токенов и
runtime URL не являются частью этой документации.

## Модель склада

| Поле             | Значение                                                                                                                        |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `id`             | Неизменяемый UUID и единственная интеграционная идентичность.                                                                   |
| `version`        | Версия агрегата для optimistic concurrency. Передаётся как `expectedVersion` во всех изменяющих lifecycle/metadata командах.    |
| `name`           | Отображаемое имя. Перед проверкой уникальности пробелы схлопываются, строка обрезается и приводится к Unicode-aware lower case. |
| `timeZone`       | Текущая эффективная canonical IANA zone, например `Europe/Moscow`. Для исторической операции используйте private as-of read.    |
| `active`         | Совместимая проекция: `true` только в `ACTIVE`; не заменяет проверку направления операции.                                      |
| `lifecycleState` | `ACTIVE`, `DRAINING` или терминальное `INACTIVE`.                                                                               |
| `sortOrder`      | Необязательное неотрицательное значение порядка каталога. Сортировка: `sortOrder`, затем `name`, затем UUID.                    |

### Правила lifecycle

| Состояние  | Новая входящая работа | Исходящая дренирующая работа | Следующий переход                   |
| ---------- | --------------------- | ---------------------------- | ----------------------------------- |
| `ACTIVE`   | разрешена             | разрешена                    | `DRAINING`                          |
| `DRAINING` | запрещена             | разрешена                    | `INACTIVE` после пяти подтверждений |
| `INACTIVE` | запрещена             | запрещена                    | нет                                 |

Последовательность закрытия выглядит так:

1. Администратор переводит склад в `DRAINING`, передав актуальный
   `expectedVersion`.
2. Каждый владелец получает durable backlog `readiness-work`, блокирует новую
   локальную работу, дожидается своих блокеров и подтверждает готовность.
3. Администратор завершает `INACTIVE` с новой версией. Если хотя бы одного
   подтверждения нет, сервер возвращает `409`.

Такой pull backlog нужен даже при Kafka-событиях: после рестарта или потери
сигнала владелец может восстановить работу без предположения, что «события
когда-нибудь придут снова».

## Авторизация

Для внутренних маршрутов «ровно один scope» означает, что токен содержит
только перечисленный scope, а не широкий набор прав.

| Поверхность                                 | Требование                                                                                                                                       |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| Публичное чтение каталога                   | `principal_type=USER` и `warehouse.read`. Это утверждённый глобальный каталог: `warehouse_access` не фильтрует результат.                        |
| Создание, замена, lifecycle, timezone       | `USER`, `rwms.write` и глобальная роль ровно `SYSTEM_ADMIN`.                                                                                     |
| Восстановление outbox                       | `USER`, ровно `rwms.write` и `SYSTEM_ADMIN` либо `WMS_ADMIN`.                                                                                    |
| Auth existence                              | `SERVICE`, `client_id=auth-service`, ровно `warehouse.read`.                                                                                     |
| Asset existence                             | `SERVICE`, `client_id=asset-service`, ровно `warehouse.read`.                                                                                    |
| Inventory metadata                          | `SERVICE`, `sub` и `client_id` равны `inventory-service`, ровно `warehouse.read`.                                                                |
| Logistics identity                          | `SERVICE`, `sub` и `client_id` равны `logistics-service`, ровно `warehouse.logistics`.                                                           |
| Timezone, admission, readiness work/confirm | Признанный lifecycle owner с совпадающими `sub`/`client_id`: asset, inventory, logistics, maintenance или task-board; scope зависит от маршрута. |
| Operation mark                              | Признанный operation owner с совпадающими `sub`/`client_id`: asset, inventory, logistics или maintenance; ровно `warehouse.operation.mark`.      |

## Публичный API

Префикс: `/api/warehouse/v1`. Полные поля и JSON Schema находятся в
[OpenAPI](../../contracts/openapi/warehouse-service.yaml).

| Метод и путь                                   | Назначение                              | Ключевые правила                                                                                                                                                 |
| ---------------------------------------------- | --------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /warehouses`                              | Каталог `ACTIVE` и `DRAINING` складов.  | `includeInactive=true` доступен только `SYSTEM_ADMIN`.                                                                                                           |
| `POST /warehouses`                             | Создать склад.                          | Обязателен UUID header `Idempotency-Key`. Точный повтор возвращает `201` и `Idempotency-Replayed: true`; тот же ключ с иным телом даёт `409`.                    |
| `GET /warehouses/{id}`                         | Получить склад по UUID.                 | Возвращает и `INACTIVE`, чтобы исторические ссылки не терялись.                                                                                                  |
| `PUT /warehouses/{id}`                         | Полностью заменить изменяемые metadata. | Требует `expectedVersion`. Не меняет lifecycle. Зону можно исправить только до первой operation mark. Semantic no-op не поднимает версию и не публикует событие. |
| `POST /warehouses/{id}/draining`               | Начать дренирование.                    | Требует `expectedVersion`; допустимо только из `ACTIVE`.                                                                                                         |
| `POST /warehouses/{id}/inactivation`           | Завершить деактивацию.                  | Требует `expectedVersion` и все пять immutable readiness confirmations.                                                                                          |
| `POST /warehouses/{id}/time-zone-changes`      | Запланировать новую timezone.           | Требует `expectedVersion`, уже записанную operation mark и будущий `effectiveFrom`.                                                                              |
| `POST /admin/outbox-events/{eventId}/recovery` | Вернуть terminal outbox event в relay.  | Только `DLT`/`QUARANTINED`, только первый неопубликованный event агрегата, с `expectedReviewVersion` и причиной. Envelope не редактируется.                      |

### Создание склада

```http
POST /api/warehouse/v1/warehouses
Authorization: Bearer <JWT>
Idempotency-Key: 7492f17a-800b-4f4c-8b43-f9e4e7d6a064
Content-Type: application/json

{
  "name": "Склад Север",
  "city": "Санкт-Петербург",
  "address": "ул. Пример, 1",
  "timeZone": "Europe/Moscow",
  "sortOrder": 10
}
```

Успех — `201 Created` с объектом `Warehouse`. Сохраняйте returned `id` и
`version`; не строите интеграции на `name`.

### Замена metadata и version fence

```http
PUT /api/warehouse/v1/warehouses/{id}
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "expectedVersion": 4,
  "name": "Склад Север",
  "city": "Санкт-Петербург",
  "address": "новый адрес",
  "timeZone": "Europe/Moscow",
  "sortOrder": 20
}
```

При `409` сначала перечитайте склад, покажите пользователю конфликт и
повторите сознательно с новой версией. Не пытайтесь автоматически подставить
свежую версию в команду, которая могла быть основана на устаревшем решении.

### Планирование часового пояса

```http
POST /api/warehouse/v1/warehouses/{id}/time-zone-changes
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "expectedVersion": 7,
  "timeZone": "Asia/Yekaterinburg",
  "effectiveFrom": "2026-11-01T00:00:00Z"
}
```

Это не изменит `Warehouse.timeZone` до `effectiveFrom`. Для существующей
операции другой сервис запрашивает timezone с её точным timestamp, а не берёт
текущую зону из directory response.

## Внутренний API

Префикс: `/api/internal/warehouse/v1`. Все эти маршруты private; браузер и
Android-клиенты их не вызывают.

| Метод и путь                                        | Получатель        | Что возвращает или делает                                                                                                                |
| --------------------------------------------------- | ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /warehouses/{id}/existence`                    | auth-service      | Минимальные `id`, `version`, `active` для проверки.                                                                                      |
| `GET /warehouses/asset/{id}/existence`              | asset-service     | Отдельная legacy incoming-admission проекция. Для outgoing в `DRAINING` нужен admission endpoint.                                        |
| `GET /warehouses/inventory/{id}/metadata`           | inventory-service | Только active `id`, `version`, `timeZone`; `DRAINING` и `INACTIVE` скрыты как `404`.                                                     |
| `GET /warehouses/logistics/{id}/identity`           | logistics-service | Identity без topology/location data; историческая identity остаётся читаемой.                                                            |
| `GET /warehouses/logistics`                         | logistics-service | Только `ACTIVE` identity в canonical order для availability facets.                                                                      |
| `GET /warehouses/{id}/time-zone?at=...`             | Lifecycle owners  | IANA timezone, действующая на конкретный момент, включая исторические и уже запланированные изменения. Scope: `warehouse.timezone.read`. |
| `POST /warehouses/{id}/operation-marks`             | Operation owners  | Записать operation evidence; `operationId` — idempotency identity. Scope: `warehouse.operation.mark`.                                    |
| `GET /warehouses/{id}/admission?direction=INCOMING` | Lifecycle owners  | Точное решение admission; допустимы `INCOMING` и `OUTGOING`. Scope: `warehouse.lifecycle.read`.                                          |
| `POST /warehouses/{id}/lifecycle-readiness`         | Lifecycle owners  | Immutable подтверждение готовности caller-а. Scope: `warehouse.lifecycle.confirm`.                                                       |
| `GET /lifecycle/readiness-work?after=&limit=`       | Lifecycle owners  | Keyset backlog неподтверждённых `DRAINING` складов. Scope: `warehouse.lifecycle.read`; `limit` от 1 до 500, по умолчанию 100.            |

### Как безопасно выполнять внутреннюю операцию

1. Владелец операции берёт service JWT с ровно нужным scope и совпадающими
   `sub`/`client_id`.
2. Перед созданием local fact он вызывает `admission` с правильным
   направлением. `active=false` сам по себе не означает, что исходящая работа
   запрещена.
3. В той же локальной transaction, где пишет первую warehouse-bound business
   fact, он сохраняет durable intent/guard и вызывает/восстанавливает
   `operation-marks` по стабильному `operationId`.
4. После начала `DRAINING` owner регулярно читает `readiness-work`, дренирует
   свои блокеры и отправляет `lifecycle-readiness` с observed version. Точный
   повтор подтверждения безопасен.

Владелец не должен держать свою database transaction открытой во время
синхронного HTTP-вызова к warehouse-service. Нужны локальные короткие
transaction, durable recovery intent и повтор с тем же identity.

## Ошибки, конкуренция и повтор

Ответы об ошибках имеют `application/problem+json` с как минимум `status` и
`code`.

| Статус | Когда ожидать                                                                                        | Действие клиента                                                         |
| ------ | ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| `400`  | Неверный UUID, timezone, timestamp, обязательное поле либо формат тела.                              | Исправить команду; не ретраить без изменения.                            |
| `401`  | Нет или невалиден Bearer token.                                                                      | Получить новый токен.                                                    |
| `403`  | Неверная principal type, role, scope или service identity.                                           | Не расширять токен наугад; использовать контрактный client credential.   |
| `404`  | Неизвестный склад либо intentionally hidden inactive/draining private projection.                    | Не создавать replacement по имени; проверить UUID и подходящий endpoint. |
| `409`  | Устаревшая версия, duplicate normalized name, нарушение lifecycle, не тот idempotency/review replay. | Прочитать актуальное состояние и повторить только осознанно.             |

Создание использует `Idempotency-Key`, scoped по subject. Lifecycle и metadata
команды используют `expectedVersion`. Outbox recovery использует отдельный
`expectedReviewVersion`. Эти механизмы решают разные гонки и не заменяют друг
друга.

## События

После изменения сервис добавляет факт в transactional outbox и bounded relay
публикует его в Kafka topic `rwms.warehouse.warehouse.v1`.

- Типы: `warehouse.warehouse.created.v1`,
  `warehouse.warehouse.changed.v1`,
  `warehouse.warehouse.deactivated.v1`.
- Kafka key — `aggregateId`; `eventId` — идентичность дедупликации для
  потребителя. Доставка at-least-once ожидаема.
- Payload намеренно не содержит name, city и address. Это уменьшает объём
  распространяемых данных и сохраняет warehouse-service владельцем directory
  projection.
- Изменённый факт может включать immutable `timeZoneDecision` с будущим
  `effectiveFrom`; потребитель с календарной семантикой сохраняет его и
  разрешает зону на момент операции.
- Terminal event нельзя «исправить в Kafka». Администратор может только
  review/requeue неизменённый проверенный envelope через recovery API.

Схема: [AsyncAPI](../../contracts/events/warehouse-events.yaml) и
[JSON Schema v1](../../contracts/events/warehouse/warehouse-events-v1.schema.json).

## Чего API намеренно не делает

- Не принимает `DELETE /warehouses` и не возвращает склад в `ACTIVE`.
- Не принимает имя склада как foreign key или интеграционную идентичность.
- Не даёт UI доступ к private routes.
- Не позволяет передавать `readinessOwner` или `operationSource` в теле: их
  выводят из service credential.
- Не переписывает исторические timezone decisions и outbox envelope.
- Не использует gateway как бизнес-агрегатор или место хранения workflow.

Эти ограничения делают API предсказуемым при ретраях, рестартах и параллельной
работе нескольких сервисов — именно там обычный CRUD-справочник чаще всего
теряет согласованность.

## Авторитетные источники

- [OpenAPI contract](../../contracts/openapi/warehouse-service.yaml)
- [Warehouse application service](../../services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/service/WarehouseService.java)
- [Authorization boundary](../../services/warehouse-service/src/main/java/dev/buhanzaz/rwms/warehouse/security/WarehouseAuthorizer.java)
- [Lifecycle migration](../../services/warehouse-service/src/main/resources/db/migration/V5__warehouse_lifecycle.sql)
- [Effective-timezone migration](../../services/warehouse-service/src/main/resources/db/migration/V4__warehouse_effective_time_zones.sql)
- [Event contract](../../contracts/events/warehouse-events.yaml)
