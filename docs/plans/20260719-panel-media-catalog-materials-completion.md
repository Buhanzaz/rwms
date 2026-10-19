# План завершения фото, каталога смет и материалов в `panel/`

**Статус:** готов к последовательной реализации
**Дата среза:** 2026-07-19
**Основной UI:** `panel/`
**Связанный план:** `docs/plans/20260718-panel-mocks-to-services-transition.md`
**Подход к тестам:** обычный — реализация и focused-проверки в одном задании

## 1. Цель и ожидаемый результат

Закрыть подтверждённые разрывы трёх связанных вертикалей:

1. загрузить в PostgreSQL проверенный каталог смет из прежних mock/legacy
   данных без возвращения browser mock runtime;
2. сделать работы и материалы каталога доступными настройкам и рабочему
   редактору смет через `maintenance-service`;
3. расширить `media-service` за пределы одной инвентаризации и подключить
   реальные фото бытовок, смет, ремонтов, приёмки, узлов каталога и затем
   логистических строк.

Итоговое состояние:

- старые mock/IndexedDB файлы используются только как проверяемое историческое
  доказательство UX и исходных данных;
- каталог хранится в БД `maintenance-service`, а не в браузере;
- браузер работает только через same-origin `/api/**`;
- ни один экран не показывает Unsplash/mock-фото и не сообщает об успешной
  загрузке, пока `media-service` не вернул реальный статус;
- сервис-владелец доказывает существование и склад media owner, а пользователь
  не может привязать файл к произвольному UUID;
- повторные команды безопасны через `Idempotency-Key`, изменения опубликованных
  агрегатов проверяют `expectedVersion`.

Этот документ не переписывает исторические stage claims и не меняет
`docs/plans/ACTIVE_STAGE.md`.

## 2. Проверенный срез репозитория

### 2.1 Что уже работает

- `panel/` проходит `npm run typecheck` без ошибок.
- Gateway config панели уже использует same-origin `/api/maintenance`,
  `/api/media`, `/api/asset`, `/api/task-board` и другие публичные маршруты.
- Настройки каталога и рабочий picker уже имеют HTTP-клиенты к
  `maintenance-service`; текущий файл с названием `repair-estimate-catalog-store`
  больше не является browser business store.
- `maintenance-service` реализует версии, узлы, связи, импорт, активацию, сметы,
  ремонты и сохранение opaque media references.
- `media-service` реализует PostgreSQL metadata, versioned MinIO upload,
  finalize, обработку, список, preview/original и rotation.
- Полная публичная media-вертикаль сейчас подключена только для
  `INVENTORY_FINDING + INSPECTION`; её нужно использовать как образец
  авторизации, owner proof, обработки и UI-состояний.
- В текущем `panel/src` не осталось authoritative business state в
  `localStorage` или IndexedDB. Локально хранятся только допустимые UI
  preferences: тема, выбранный UUID склада, таблицы, canvas layout и media
  preferences.

### 2.2 Фактическое состояние данных и runtime

Read-only проверка поднятых локальных PostgreSQL показала:

| БД | Таблица | Строк |
| --- | --- | ---: |
| maintenance | `catalog_version` | 0 |
| maintenance | `catalog_node` | 0 |
| media | `media_asset` | 0 |
| media | `media_owner_binding` | 0 |

В `docker compose ps` на момент аудита работали инфраструктурные зависимости,
но не процессы Spring/Go сервисов, gateway и panel. Поэтому текущий runtime сам
по себе также не может отдать фото, даже для уже реализованной инвентаризации.
Это отдельная эксплуатационная причина, но она не заменяет найденные
контрактные разрывы.

### 2.3 Что найдено в старых версиях

В родителе cutover-коммита `59a1e97` находились:

- `repair-estimate-catalog-seed.ts` и browser catalog adapter;
- IndexedDB media adapters для смет и перемещений;
- uploader бытовки, photo manager сметы и logistics photo dialog;
- mock task-board seed со старыми очередями.

Их нельзя возвращать в production runtime. Они остаются источником примеров
компоновки UI, названий и проверяемых fixture expectations.

Старый catalog seed и текущий backend artifact описывают один набор:

| Показатель | Значение |
| --- | ---: |
| Узлы | 232 |
| Связи | 254 |
| Категории | 10 |
| Подкатегории | 30 |
| Работы | 89 |
| Материалы | 91 |
| Материалы с ценой | 91 |
| Локации | 3 |
| Опции | 9 |

Проверенный набор уже находится в:

- `services/maintenance-service/src/main/resources/legacy/maintenance-catalog-v1.json`;
- `services/maintenance-service/src/main/resources/legacy/maintenance-catalog-manifest.json`;
- `ReviewedLegacyCatalogManifest`.

Он привязан к московскому складу
`00000000-0000-0000-0000-000000000002`, содержит 232/254 записей, не имеет
битых ссылок и прошёл отдельное hash-review. Поэтому создавать второй seed в
панели не требуется.

## 3. Подтверждённые разрывы

### 3.1 Каталог смет и материалы

| Разрыв | Доказательство | Последствие |
| --- | --- | --- |
| Artifact не загружен в БД | `catalog_version=0`, `catalog_node=0` | Рабочий picker не находит ACTIVE-каталог; материалы не загружаются. |
| Нет безопасной команды «использовать встроенный проверенный каталог» | `approvedRequest()` доступен только внутри package/tests; UI требует внешний JSON-файл | Обычный пользователь не может восстановить каталог из уже упакованных данных. |
| Импорт одноразовый | Уникальность `(warehouse_id, source_sha256)` возвращает уже импортированную версию | После первой активации тот же artifact не создаёт новый DRAFT. |
| Нет fork/clone версии | В OpenAPI есть import/edit/activate, но нет «создать черновик из ACTIVE» | После активации каталог невозможно нормально версионировать и редактировать. |
| ACTIVE нельзя просматривать в настройках | Кнопки drilldown отключены, если lifecycle не `DRAFT` | Администратор видит счётчик версии, но не может открыть работы и материалы read-only. |
| Routing ссылается на отсутствующие очереди | Artifact содержит шесть legacy queue UUID; текущий task-board bootstrap создаёт только `MOVEMENT` | Импорт может пройти, но регистрация ремонтных задач затем упадёт на несуществующей очереди. |
| Routing references не синхронизируются | `task-board-service` умеет хранить `CATALOG_POSITION`, но maintenance его не вызывает | Очередь можно удалить, пока активный каталог всё ещё на неё ссылается. |
| Artifact определён только для Москвы | Manifest fail-closed проверяет точный warehouse UUID | Нельзя молча копировать московские маршруты в Санкт-Петербург. |
| Старые canvas/comment поля намеренно исключены | Mapping policy исключает `CANVAS_X/Y`, `COMMENT_`, audit/PII-поля | Их нельзя незаметно сделать backend business data. Canvas может остаться presentation-only настройкой. |

Точные routing prerequisites старого каталога:

| Queue UUID | Code | Type |
| --- | --- | --- |
| `019f21e8-4526-7462-95e8-3309ce19fc9c` | `EXTERNAL_WORKS` | `REPAIR` |
| `019f21e8-cd97-7a20-a876-8306e77f94bd` | `INTERNAL_WORKS` | `REPAIR` |
| `019f21e9-6057-736c-8999-6808191a1362` | `ELECTRICS` | `REPAIR` |
| `019f21e9-f54f-7184-954e-29f237b9c424` | `PLUMBING` | `REPAIR` |
| `019f21ea-6015-753f-ad3a-be58947aa252` | `WELDING` | `REPAIR` |
| `019f21ed-eb53-782d-a73f-a24357e262b2` | `SANITARY_DISINFECTION` | `HOLDING` |

### 3.2 Фото и media ownership

| Поток | Текущее состояние | Реальный gap |
| --- | --- | --- |
| Инвентаризация | HTTP media editor подключён | Единственная поддержанная owner-вертикаль; сохранить как baseline. |
| Бытовки | Asset adapter всегда отдаёт `mediaAvailability: UNAVAILABLE`; dialog только показывает сообщение | Нет owner proof `ASSET_RENTAL_ITEM`, upload/list wiring и batch summary для реестра. |
| Карусель бытовок | Содержит `MOCK_FALLBACK_PHOTO_URLS` с Unsplash | UI способен сфабриковать фото при неполном DTO; fallback должен быть удалён. |
| Сметы | Adapter и API бросают ошибку для любого pending upload/media | Media API не принимает `MAINTENANCE_ESTIMATE`; для новой сметы сначала нужен server ID. |
| Ремонты | Adapter и UI явно отключают фото | Media API не принимает `MAINTENANCE_REPAIR`; после create/rework нет отдельной versioned-команды прикрепить refs. |
| Приёмка | UI сообщает о недоступности; `RepairDecisionRequest` содержит только version/comment | Не определена команда с `MAINTENANCE_ACCEPTANCE` refs. |
| Узел каталога | Maintenance умеет сохранять refs, но panel uploader отсутствует | Media не умеет доказать owner `MAINTENANCE_CATALOG_NODE`; новый узел надо сначала сохранить. |
| Возвраты/отгрузки/перемещения | Logistics умеет только приватно проверить готовые refs | Публичного upload/list нет; owner имеет форму `<documentId>:<lineId>`, несовместимую с публичным UUID-only owner. |
| Общий panel media client | Типы жёстко привязаны к `InventoryFindingMediaOwner` | Нельзя безопасно переиспользовать client для других владельцев. |
| Media contract/schema/DB | OpenAPI, facts schema, Go validation и SQL CHECK разрешают только `INVENTORY_FINDING` | Простого включения UI недостаточно: нужны contract, migration и owner consumers. |

`maintenance-service` уже принимает и проверяет READY/current-generation refs
для `MAINTENANCE_ESTIMATE`, `MAINTENANCE_REPAIR` и
`MAINTENANCE_CATALOG_NODE`, а схема БД также перечисляет
`MAINTENANCE_ACCEPTANCE`. Но `media-service` никогда не может создать эти refs.
Это межсервисное несоответствие контрактов, а не только незавершённый React UI.

### 3.3 Другие явные незавершённые области панели

Аудит также обнаружил следующие production stubs. Они фиксируются здесь, но
не включаются в реализацию без отдельной прямой команды:

| Область | Состояние | Решение для текущего плана |
| --- | --- | --- |
| Equipment mutations/return dispositions | `equipment-api.ts` содержит explicit unsupported stubs | Отдельный asset/logistics cutover; не мешает каталогу и фото. |
| Изменение наполнения бытовки | UI ждёт versioned balance/transfer command | Отдельный flow после утверждения ownership. |
| Вкладки reserves/returns/shipments в карточке | Часть вкладок — честные unavailable placeholders | Не заполнять фиктивными данными; ждать producer facts/контракт. |
| `/`, `/kpi`, общий `/settings` | `EmptyPage` | KPI и analytics явно deferred; не создавать formulas/service. |
| Warehouse topology/bin model | Контракта нет | Deferred решением проекта. |
| Company/customer reservation, candidate lists | Ownership не определён | Deferred; не создавать opportunistic service. |
| Schedule/DST/notifications | Семантика не утверждена | Deferred. |
| Media retention/orphan cleanup | Политика не утверждена | Не смешивать с подключением фото. |

## 4. Обязательные архитектурные решения

1. **Москва — первый и единственный legacy bootstrap target.** Для Санкт-Петербурга
   нужен отдельный подтверждённый mapping каталога и очередей.
2. **Backend artifact — единственный seed authority.** Не копировать 232 узла в
   TypeScript и не возвращать browser mock adapter.
3. **Bootstrap запускает администратор.** Не изменять production БД скрытым
   startup runner. Команда авторизована `MANAGE`, idempotent и fail-closed.
4. **Каждый сервис пишет только в свою БД.** Task-board создаёт очереди;
   maintenance создаёт каталог; media создаёт metadata; MinIO хранит bytes.
5. **Никакой browser saga между task-board и maintenance.** Это две явные
   admin-команды с backend preflight: сначала owning task-board import, затем
   maintenance catalog import/activate.
6. **Новые aggregate owners создаются до upload.** Для новой сметы, ремонта,
   бытовки или узла каталога панель сначала получает server ID, затем загружает
   файл и versioned-командой прикрепляет READY refs. Browser UUID не доказывает
   существование owner.
7. **Owner proof приходит от owning service.** Media projection должна
   поддерживать ordering, deduplication, version gaps, quarantine/reconcile и
   revocation так же, как текущий inventory consumer.
8. **Файл в памяти не является business state.** Допустимо временно держать
   выбранный `File` до server create; после частичного успеха UI показывает
   созданный агрегат и отдельную retry-загрузку, а не откатывает его фиктивно.
9. **Старые фото-компоненты — только UX reference.** IndexedDB/data URL adapters
   не возвращаются.
10. **MapStruct/JPA/Flyway правила обязательны.** Тронутые Spring DTO read
    boundaries переводятся на MapStruct; schema меняется только Flyway;
    `ddl-auto=validate` остаётся включённым.

Предлагаемая media owner matrix, которую следует зафиксировать в OpenAPI/event
schemas до кодирования consumers:

| Domain | Owner type | Public context | Owner identity |
| --- | --- | --- | --- |
| Inventory | `INVENTORY_FINDING` | `INSPECTION` | finding UUID |
| Asset | `ASSET_RENTAL_ITEM` | `ASSET_CARD` | rental item UUID |
| Maintenance estimate | `MAINTENANCE_ESTIMATE` | `ESTIMATE` | estimate UUID |
| Maintenance repair | `MAINTENANCE_REPAIR` | `REPAIR` | repair UUID |
| Maintenance acceptance | `MAINTENANCE_ACCEPTANCE` | `ACCEPTANCE` | repair UUID |
| Maintenance catalog | `MAINTENANCE_CATALOG_NODE` | `CATALOG` | node UUID scoped by warehouse |
| Logistics return | `LOGISTICS_RETURN` | `RETURN_INSPECTION` | service-derived document/line identity |
| Logistics shipment | `LOGISTICS_SHIPMENT` | `SHIPMENT` | service-derived document/line identity |
| Logistics transfer | `LOGISTICS_TRANSFER` | `TRANSFER` | service-derived document/line identity |

Логистический browser contract не должен принимать произвольную строку
`ownerId`. Он передаёт UUID документа и строки, а `media-service` сам выводит
каноническую identity `<documentId>:<lineId>`.

## 5. Зависимости

```text
reviewed task-board queues
        -> reviewed maintenance DRAFT
        -> route preflight + activation
        -> 91 материалов в рабочем picker

asset / maintenance / logistics owner facts
        -> media owner projections
        -> public upload/list/original/rotation
        -> panel editors
        -> READY refs в owning aggregates
```

Каталог и общий media foundation после фиксации контрактов могут выполняться
как независимые file lanes. Внутри каждой цепочки шаги последовательны.

## 6. План реализации

### Волна 1 — восстановить legacy prerequisites каталога

#### Task 1.1: task-board-owned импорт шести проверенных очередей Москвы

**Outcome:** task-board PostgreSQL содержит шесть очередей с точными UUID,
code/type из reviewed evidence; повтор команды ничего не дублирует.

**Основные файлы:**

- Modify: `contracts/openapi/task-board-service.yaml`
- Modify: `services/task-board-service/src/main/java/**/api/WorkQueueController.java`
- Modify: `services/task-board-service/src/main/java/**/service/RegistryService.java`
- Modify: `services/task-board-service/src/main/java/**/domain/WorkQueue.java`
- Create: reviewed legacy queue manifest/loader под
  `services/task-board-service/src/main/resources/legacy/`
- Update: focused OpenAPI, security, service, event-store и PostgreSQL tests

**Действия:**

- добавить MANAGE-only idempotent command для reviewed legacy queue import;
- разрешить exact legacy ID только через закрытую domain factory, без общего
  setter для JPA ID;
- валидировать warehouse UUID, hash manifest, ID/code/type и конфликтовать при
  существующей несовпадающей строке;
- создавать очереди через обычный task-board domain/event/projection path, не
  прямым cross-service SQL и не panel seed;
- добавить exact read-only internal preflight для списка routing snapshots,
  доступный только allow-listed `maintenance-service` credential;
- не добавлять московские очереди в SPB автоматически.

**Проверки:**

- повтор одного Idempotency-Key возвращает тот же результат;
- новый ключ после завершённого импорта возвращает тот же набор без дублей;
- wrong warehouse, wrong hash, ID/code conflict и недостаточные права дают
  ожидаемые 4xx;
- события и projection rows созданы для каждой новой очереди;
- `TaskBoardOpenApiParityTest`, focused service/PostgreSQL tests и
  `:services:task-board-service:test` зелёные.

#### Task 1.2: maintenance-owned импорт встроенного каталога

**Outcome:** администратор создаёт DRAFT из packaged reviewed artifact одной
кнопкой, без выбора JSON-файла и без копии данных в panel bundle.

**Основные файлы:**

- Modify: `contracts/openapi/maintenance-service.yaml`
- Modify: `MaintenanceCatalogController`, `MaintenanceApplicationService`
- Modify: `ReviewedLegacyCatalogManifest`
- Modify/create: exact-scope task-board dependency client и auth registration
- Update: `ReviewedLegacyCatalogImportIntegrationTest`, OpenAPI/security tests

**Действия:**

- добавить `POST` command ресурса reviewed legacy import с обязательным
  `Idempotency-Key` и warehouse scope;
- внутри сервиса использовать `ReviewedLegacyCatalogManifest.approvedRequest()`
  и существующий import path;
- до импорта/активации выполнить private read-only preflight всех шести
  ID/code/type в task-board;
- вернуть понятный Problem Detail с отсутствующими queue codes, не создавать
  частичный каталог;
- сохранить точную hash/count/distribution validation;
- не принимать изменённый artifact и не разрешать другой warehouse.

**Проверки:**

- clean DB создаёт ровно один DRAFT: 232 узла, 254 связи, 91 MATERIAL;
- все 91 материала имеют цену и `includeInEstimate=true` согласно artifact;
- retry безопасен, изменённый payload и SPB отклоняются;
- отсутствующая/неверная очередь блокирует импорт до локального commit;
- OpenAPI parity, bearer/warehouse access и PostgreSQL integration зелёные.

### Волна 2 — сделать каталог долговечным и доступным panel

#### Task 2.1: fork ACTIVE/SUPERSEDED в новый DRAFT

**Outcome:** после первой активации каталог можно безопасно редактировать новой
версией, не переимпортируя тот же source hash.

**Основные файлы:**

- Modify: `contracts/openapi/maintenance-service.yaml`
- Modify: `CatalogVersion`, repository, service/controller и DTO mapper
- Create: следующая service-local Flyway migration при необходимости lineage
- Update: Flyway clean/upgrade, JPA validation, domain/controller tests

**Действия:**

- добавить versioned/idempotent command «создать DRAFT на основе версии»;
- копировать node/link domain IDs и immutable routing snapshots, создавая новые
  row IDs и новый catalog version ID;
- явно хранить origin/lineage; импортный source SHA не использовать как
  фиктивный hash fork-версии;
- оставить ACTIVE/SUPERSEDED неизменяемыми;
- блокировать второй незавершённый DRAFT одного склада либо возвращать его как
  idempotent replay;
- применить MapStruct для затронутого entity/projection → response mapping.

**Проверки:**

- fork сохраняет 232/254 и все material prices/routing;
- изменение DRAFT не меняет источник;
- stale expectedVersion даёт единый `409`;
- migration проходит clean install, upgrade path и JPA validation.

#### Task 2.2: settings UI для bootstrap, read-only ACTIVE и fork

**Outcome:** в `/settings/estimates-repairs` администратор видит пустое состояние,
создаёт reviewed DRAFT, просматривает любую версию, активирует и создаёт новый
черновик; editor сметы после активации получает материалы из БД.

**Основные файлы:**

- Modify: `panel/src/features/repair-estimate-catalog/api/http-maintenance-catalog-client.ts`
- Modify: `panel/src/features/settings/estimates-repairs/api/repair-estimate-catalog-store.ts`
- Modify: `panel/src/features/settings/estimates-repairs/estimates-repairs-settings-page.tsx`
- Modify: catalog work/material/picker tests

**Действия:**

- заменить основной empty-state file import на действие «Загрузить проверенный
  старый каталог»; ручной JSON import оставить только если он имеет отдельный
  доказанный use case, иначе удалить;
- показывать task-board prerequisite error и ссылку на его настройки, не
  запускать второй сервисный command скрыто из браузера;
- открыть ACTIVE/SUPERSEDED версии в read-only drilldown;
- включать mutation controls только для DRAFT;
- добавить кнопку fork для MANAGE;
- после активации инвалидировать settings и operational catalog queries;
- сохранить canvas coordinates только как UI preference. Если нужен старый
  layout, вынести его в отдельно reviewed presentation fixture и применять
  только при отсутствии пользовательского layout; не писать его в business DB.

**Acceptance:**

- после bootstrap и activation настройки показывают 232 узла/254 связи;
- раздел «Материалы» показывает 91 позицию с ценами из PostgreSQL;
- picker новой сметы показывает active works/materials после reload браузера;
- новая смета с material line сохраняется и повторно читается из
  `maintenance-service` без localStorage/IndexedDB;
- VIEW видит read-only, EDIT меняет DRAFT, MANAGE импортирует/активирует/fork;
- focused Vitest, `npm run typecheck` и `npm run build` зелёные.

#### Task 2.3: защитить routing active-каталога

**Outcome:** task-board знает `CATALOG_POSITION` references активной версии и не
позволяет удалить используемую очередь.

**Действия:**

- при активации в той же maintenance-транзакции сохранить outbox/reconciliation
  intents для routed catalog nodes;
- idempotently зарегистрировать stable external references через private
  task-board API; не выдавать maintenance credential права менять очереди;
- при supersede удалить только references прежней версии после подтверждения
  новой;
- показывать delivery/reconciliation truth, если синхронизация неоднозначна;
- не использовать 2PC и не считать timeout успехом.

### Волна 3 — расширить общий media contract и storage

#### Task 3.1: зафиксировать multi-owner OpenAPI/event contracts

**Основные файлы:**

- Modify: `contracts/openapi/media-service.yaml`
- Modify: `contracts/events/media/media-facts-v1.schema.json`
- Modify: соответствующие asset/maintenance/logistics event schemas
- Modify: AsyncAPI documents и contract tests

**Действия:**

- закрепить owner matrix из раздела 4;
- сделать create/list/original/variant/rotation scope согласованным;
- для logistics принять documentId/lineId и вывести opaque owner server-side;
- оставить URLs только same-origin, а object keys/MinIO origins закрытыми;
- сохранить max count, MIME/size/checksum, READY generation и authorization
  rules явными для каждого owner/context;
- определить owner active/revoked semantics и source event version.

#### Task 3.2: multi-owner projections и Flyway migration media-service

**Основные файлы:**

- Create: `services/media-service/db/migration/V4__multi_domain_owner_proof.sql`
- Modify: `internal/persistence`, `internal/worker`, `internal/api`, config/main
- Update: contract, repository, consumer, recovery и Kafka integration tests

**Действия:**

- расширить CHECK constraints и owner binding projection без ослабления
  inventory guarantees;
- вынести общий projection/reconciliation core и оставить source-specific
  schema validators/consumers;
- потреблять asset rental-item facts и maintenance/logistics owner-proof facts;
- сохранять aggregate-key ordering, inbox deduplication, version-gap quarantine,
  replay/reconcile и revocation;
- запретить upload при отсутствующем, чужом, перенесённом или revoked owner;
- сохранить versioned MinIO, outbox и processing state machine без второго
  media deployable.

**Проверки:**

- clean migration и V3→V4 upgrade сохраняют inventory media;
- owner mismatch, warehouse mismatch, version gap, duplicate и revocation
  покрыты тестами каждого producer;
- `go test ./internal/contract ./internal/persistence ./internal/worker ./internal/api`
  и `go test ./...` зелёные.

#### Task 3.3: обобщить panel media client

**Основные файлы:**

- Modify: `panel/src/features/media/model/**`
- Modify: `panel/src/features/media/api/http-media-client.ts`
- Create/refactor: shared owner media editor/viewer components
- Update: HTTP client and component tests

**Действия:**

- заменить `InventoryFindingMediaOwner` на discriminated owner scope;
- централизовать checksum, create/upload/complete, processing polling,
  list/preview/original/rotation и Problem Details mapping;
- не сохранять Blob/data URL как authoritative state;
- корректно отзывать object URLs и прерывать polling при unmount/owner change;
- оставить inventory behavior неизменным regression-тестом.

### Волна 4 — фото maintenance lifecycle

#### Task 4.1: фото смет

- публиковать/проецировать owner proof для DRAFT/COMPLETED estimate;
- для новой сметы сначала создать server DRAFT без refs, получить estimate ID,
  дождаться подтверждённого owner scope, загрузить файлы и `PUT` DRAFT с
  `expectedVersion` и READY refs;
- для completed amendment загружать refs к существующему owner до versioned
  amendment command;
- подключить старую компоновку photo manager как UX reference, но не IndexedDB;
- удалить throws и unavailable banners для `MAINTENANCE_ESTIMATE`;
- покрыть частичный upload, processing failure, stale version и reload.

#### Task 4.2: фото ремонтов и доработок

- добавить минимальную versioned maintenance-команду замены repair media refs
  после создания owner; не пытаться прикрепить refs до server repair ID;
- direct repair/rework создавать без refs, затем upload + attach;
- сохранить existing plan/stage transitions и task-board orchestration в
  maintenance-service;
- удалить `MAINTENANCE_REPAIR` unsupported branches и banners;
- проверить create, rework, READY-only refs и `409`.

#### Task 4.3: фото приёмки и узлов каталога

- расширить accept/rework decision contract media refs для доказанного
  `MAINTENANCE_ACCEPTANCE` owner;
- существующий catalog node сначала сохранить без refs, затем upload и replace
  DRAFT nodes; опубликованную версию не мутировать;
- для нового node не считать browser-generated UUID owner proof;
- скрывать uploader в read-only lifecycle и при недостаточном warehouse access;
- покрыть wrong owner/generation и photo-required validation.

**Проверки волны:**

- focused maintenance controller/service/media-fact tests;
- `:services:maintenance-service:test`;
- affected panel Vitest, `npm run typecheck`, `npm run build`;
- ручной или автоматизированный same-origin smoke: create → upload → READY →
  preview/original → reload → rotate → reload.

### Волна 5 — фото бытовок

#### Task 5.1: asset owner proof

- использовать rental-item aggregate facts как источник существования,
  warehouse и revision owner `ASSET_RENTAL_ITEM`;
- обработать warehouse change/revocation в media projection;
- при необходимости добавить только sanitized owner-proof event, не media
  metadata в asset DB;
- проверить cross-warehouse denial и ordering.

#### Task 5.2: panel detail/create/registry

- подключить list/upload/preview/original/rotation в карточке и dialog;
- при создании бытовки держать выбранные `File` только в памяти, сначала создать
  asset, затем загрузить; при сбое показать retry на уже созданной бытовке;
- для реестра добавить bounded batch owner summary из `media-service`, а не N+1
  запрос на каждую строку и не business aggregation в gateway;
- удалить `mediaAvailability: UNAVAILABLE` hard-code и
  `MOCK_FALLBACK_PHOTO_URLS`;
- при отсутствии фото показывать пустое состояние, не stock image.

**Проверки:** asset fact tests, media consumer tests, rental-item component/API
tests, typecheck/build и reload smoke.

### Волна 6 — логистические фото

Эта волна выполняется после freeze line ownership и не блокирует сметы,
материалы, maintenance и asset photos.

- logistics-service публикует active/revoked proof на document line с
  warehouse и revision;
- media public API принимает documentId/lineId и сам выводит composite owner;
- returns, shipments и transfers подключают shared media editor;
- READY refs сохраняются versioned logistics commands и продолжают проходить
  текущую private validation;
- старые IndexedDB transfer media и logistics photo dialog используются только
  как UX reference и не возвращаются;
- покрываются wrong line/document/warehouse, canceled document, retry и reload.

## 7. Финальная end-to-end проверка

На чистой локальной среде:

1. запустить PostgreSQL, Kafka, MinIO, auth, warehouse, asset, task-board,
   maintenance, inventory, logistics, media, gateway и panel;
2. войти через OIDC и выбрать московский склад;
3. импортировать reviewed task-board queues и убедиться, что ровно шесть
   required codes имеют точные UUID/type;
4. создать reviewed catalog DRAFT, открыть работы/материалы, активировать;
5. проверить в БД и API 232 nodes, 254 links, 91 materials с ценами;
6. создать смету с материалом, перечитать её после reload и убедиться, что
   snapshot/price пришли из maintenance PostgreSQL;
7. загрузить фото в estimate, repair, acceptance/catalog node и rental item;
8. дождаться READY, открыть derived preview и original, повернуть фото и
   проверить новую generation после reload;
9. проверить, что чужой склад, wrong owner, stale generation, VIEW-only upload,
   stale expectedVersion и повтор Idempotency-Key дают ожидаемые результаты;
10. остановить media/MinIO и убедиться, что UI показывает явную ошибку без mock
    fallback; после восстановления retry не создаёт дубликаты.

Минимальный финальный набор команд:

```text
./gradlew :services:task-board-service:test
./gradlew :services:maintenance-service:test
./gradlew :services:asset-service:test
./gradlew :services:logistics-service:test

(cd services/media-service && go test ./...)

(cd panel && npm test -- <affected test files>)
(cd panel && npm run typecheck)
(cd panel && npm run build)
```

Testcontainers/Kafka outage suites запускаются для изменённых owner proof,
outbox/retry и migration boundaries, а не как замена focused tests.

## 8. Definition of Done

- В panel нет production import из browser seed, IndexedDB media adapter,
  Unsplash fallback или silent mock fallback.
- Московский reviewed catalog воспроизводимо импортируется и версионируется.
- Работы и все 91 материала читаются из active maintenance catalog после
  перезагрузки браузера.
- Каждая включённая photo-вертикаль имеет source owner proof, public OpenAPI,
  DB migration при необходимости, backend tests, panel wiring и failure UI.
- Browser не вызывает `/api/internal/**`, MinIO или service port напрямую.
- Все mutable commands используют version/idempotency semantics контракта.
- Flyway clean/upgrade и JPA validation зелёные для изменённых Spring схем.
- В финальном отчёте перечислены точные проверки и любые ещё отложенные owner
  types; незапущенная проверка не заявляется как успешная.
