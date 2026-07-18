# План перехода новой панели с mock-реализации на сервисы

**Статус:** готов к исполнению после стабилизации текущего незавершённого cutover
**Дата среза:** 2026-07-18
**Основной UI:** `panel/`
**Подход к тестам:** обычный — код и обязательные focused-тесты в одном задании

## 1. Цель

Перевести пользовательские потоки текущей React-панели с браузерного business
state (`localStorage`, IndexedDB, seed/mock-клиенты и browser orchestration) на
существующие RWMS-сервисы через stateless API gateway.

Итоговое состояние:

- `panel/` остаётся единственной основной панелью;
- браузер обращается только к same-origin `/auth/**` и `/api/**` gateway;
- бизнес-состояние, версии, idempotency, блокировки и переходы принадлежат
  сервисам, а не вкладке браузера;
- каждый переведённый поток удаляет свой mock/browser adapter, storage keys,
  seed, runtime selection и связанные mock-тесты в том же задании;
- отсутствие сервиса или токена является ошибкой, без fallback на mock;
- UI-состояние без бизнес-смысла — тема, выбранный UUID склада, порядок/ширина
  колонок — может остаться локальной пользовательской настройкой;
- новые Spring persistence-изменения используют JPA + Flyway, безопасный
  Lombok и MapStruct по правилам проекта.

Это план перестройки application/service boundary. Он не включает production
deployment, Kubernetes/Helm/Terraform, постоянный ingress или TLS-топологию.

## 2. Фактический срез репозитория

### 2.1 Панель

Текущая новая панель уже находится в `panel/`. Удаление прежнего
`wms-panel-old/` присутствует в рабочем дереве, поэтому второй primary UI
создавать нельзя.

Панель сейчас является гибридом:

- OIDC Authorization Code + PKCE и `/api/users/me` уже являются реальным
  auth-путём;
- склады, администрирование пользователей, реестр бытовок и часть оборудования
  уже имеют HTTP-клиенты;
- inventory, estimates, repairs, acceptance, большая часть task-board,
  returns, shipments, transfers и media всё ещё хранят или оркестрируют
  бизнес-состояние в браузере;
- `/` и `/kpi` пока пустые страницы, а не сервисные реализации;
- текущий незавершённый core cutover не собирается: `npm run typecheck`
  завершился с кодом 2. Зафиксированы несовпавший импорт
  `RentalItemManualStatus`, несовместимый `HttpRentalItemDossierAdapter`,
  тест без `expectedVersion` и неиспользуемые значения в registry page.

Основные browser business stores, которые должны исчезать по мере cutover:

| Область | Текущая реализация |
| --- | --- |
| Inventory | `features/inventory/adapters/local-storage-inventory-adapter.ts` |
| Repair estimates | `features/repair-estimates/adapters/local-storage-repair-estimates-adapter.ts` |
| Repair tasks/acceptance/write-off | `features/repair-tasks/adapters/local-storage-repair-tasks-adapter.ts` |
| Repair catalog | `features/settings/estimates-repairs/api/repair-estimate-catalog-store.ts` и seed |
| Task board runtime | `features/task-board/mock/**` |
| Return flow | монолитный `features/logistics/api/logistics-api.ts` |
| Shipments | `features/logistics/shipments/adapters/browser-shipment-client.ts` |
| Warehouse transfers | browser client + local-storage store в `warehouse-transfers/` |
| Cabin contents transfers | browser client + local-storage store в `contents-transfer/` |
| Media | `indexed-db-repair-estimate-media-adapter.ts` |
| Warehouse topology | `api/warehouse-location-api.ts` |
| Старый dossier | `browser-rental-item-dossier-adapter.ts` |

`theme-provider`, выбранный canonical warehouse UUID и grid/table preferences не
являются business stores и не требуют отдельного preference-service.

### 2.2 Сервисы и контракты

Названия модулей не считаются доказательством готовности. Ниже перечислены
фактически найденные публичные контракты и текущая связь с панелью.

| Компонент | Фактический публичный контракт | Текущее использование панелью | Вывод |
| --- | --- | --- | --- |
| `api-gateway-service` | routes для auth, task-board, warehouse, asset, maintenance, media, inventory, logistics, dossier | частично | Маршруты есть; panel runtime config не содержит logistics/dossier и часть клиентов обходит gateway через `localhost:8081`. |
| `auth-service` | OIDC, `/api/users/me`, `/api/admin/users` | используется | Сохранить как baseline, исправить незавершённую передачу token/version. |
| `warehouse-service` | CRUD `/api/warehouse/v1/warehouses` | используется | Core path близок к cutover; canonical UUID уже принят. |
| `asset-service` | rental items, passport/status/warehouse/comment/notes, equipment/catalog/totals/transfers/dispositions | частично | Registry/equipment reads работают через HTTP; часть команд всё ещё отключена или вызывается через старые stubs. |
| `task-board-service` | board reads/commands, queues, worker classes, workers/groups, internal task registration | settings частично; runtime не подключён | Operational board продолжает строиться из local repair tasks. Schedule/notification API в контракте нет. |
| `maintenance-service` | catalog, estimates, repairs, repair plan, rework, acceptance, write-offs | не подключён | Почти прямое покрытие UI есть, но panel models и browser orchestration нужно заменить. |
| `inventory-service` | sessions/findings/inspection/completion/publication/statistics | не подключён | Публичный API покрывает основной flow и должен заменить весь local inventory aggregate. |
| `logistics-service` | returns, shipments, transfers, reconciliation | не подключён | Документные агрегаты есть; UI-specific candidate/company/import flows требуют contract-gap решения. |
| `media-service` (Go) | upload sessions, finalize, owner media, original, rotation | не подключён | Должен заменить IndexedDB/data URL. |
| `dossier-service` | read-only `/api/dossier/v1/cabins/{cabinId}` | не подключён | Текущий `HttpRentalItemDossierAdapter` фактически читает asset-service и создаёт пустой pseudo-dossier. |
| Analytics | модуля и контракта нет | `/kpi` пуст | Не создавать до утверждения KPI-формул, периода и backfill. |

В рамках этого анализа service test suites не запускались. Наличие API выше
подтверждено текущими OpenAPI, controllers, migrations и source wiring; оно не
означает, что соответствующий end-to-end flow уже проверен. Такая проверка
входит в задачу owning vertical.

Все stateful Spring-сервисы используют PostgreSQL, JPA и Flyway. В локальном
`compose.yaml` отсутствует `logistics-db`, хотя logistics-service ожидает
`jdbc:postgresql://localhost:5440/rwms_logistics`. Это блокирует честный запуск
всех сервисов и должно быть исправлено как local-development dependency.

## 3. Матрица потоков: mock → владелец

| UI/маршрут | Что сейчас | Целевой владелец | Что изменить | Очередь |
| --- | --- | --- | --- | --- |
| Login/current user | OIDC HTTP | auth | Стабилизировать token/current-user wiring; удалить bypass sentinels после последнего mock consumer. | 0 |
| `/settings/users` | auth HTTP | auth | Проверить version/warehouse access errors и gateway-only URL. | 0–1 |
| Warehouse selector и `/settings/warehouses` | warehouse HTTP + локальный selected UUID | warehouse | Завершить текущий cutover; локально хранить только возвращённый UUID. | 0–1 |
| `/warehouse` registry/create | asset HTTP | asset | Довести mapping/filter/paging/create и error states; не возвращать mock DTO. | 1 |
| `/warehouse/:id` core | asset HTTP, dossier wiring сломан | asset | Core fields/status/comment/notes — asset; history и media не подделывать. | 1 |
| Cabin dossier/history | browser adapter или asset pseudo-dossier | dossier read projection + asset commands | Разделить core query и activity query; dossier остаётся read-only. | 6 |
| Cabin photos/original | disabled/IndexedDB | media | Upload session/finalize/list/original через gateway; owner context только из контракта. | 2+ |
| `/equipment` и equipment write-offs | asset HTTP частично | asset | Использовать catalog/totals/transfers/dispositions; убрать unsupported mock constants после последнего caller. | 1 |
| Warehouse location graph | localStorage generated graph | UNKNOWN | Не изобретать bin/topology contract; скрыть business controls до отдельного решения. | blocked |
| `/settings/task-board` queues/classes/workers/groups | HTTP напрямую на `localhost:8081` | task-board | Перевести на `/api/task-board`; убрать runtime mock selection. | 1 |
| Task-board schedule/simulation | mock clock/schedules | UNKNOWN | Удалить из production UI или показать недоступность; timezone/DST/downtime semantics требуют решения. | blocked |
| `/task-board` | projection из local repair tasks | task-board | Читать board snapshot и отправлять take/pause/resume/complete/move в сервис. | 1 |
| Worker notifications | mock или пустой массив | UNKNOWN | Не имитировать; скрыть до контракта worker delivery/notification. | blocked |
| Repair catalog/settings | localStorage seed/store | maintenance | Подключить versions/nodes/links/import/activate. | 2 |
| `/estimates` | local aggregate + IndexedDB media | maintenance + media | Перенести draft/complete/amend и media refs; сервис владеет переходами. | 3 |
| `/repairs` | local repair task aggregate | maintenance + task-board + media | Использовать repair/plan/rework endpoints; board state не дублировать. | 3 |
| `/acceptance`, cabin write-offs | local repair tasks | maintenance | Использовать acceptance/write-off projections и commands. | 3 |
| `/inventory/**` | local aggregate, client totals/publication | inventory + media | Полный HTTP cutover; actor/date/stats/reconciliation принадлежат серверу. | 3 |
| `/logistics/returns` | local monolith + browser journals | logistics | Перенести documents/register/accept/request-estimate; browser journal заменить Idempotency-Key. | 4–5 |
| `/logistics/shipments` | BrowserShipmentClient | logistics | create/plan/confirm/cancel; task-board вызов выполняет logistics-service. | 4–5 |
| `/logistics/transfers` | local store/browser client | logistics | create/depart/arrive/cancel/reconcile; media refs через media-service. | 4–5 |
| Contents between cabins | browser saga + direct task-board | logistics/asset по утверждённому additive contract | Не оставлять browser saga; один command owner и server-side orchestration. | 4–5 gap |
| Company/candidate lists/import rented cabin | browser-derived данные | UNKNOWN/contract gap | Не создавать company/reservation service. Оставить только доказанный text/snapshot input либо заблокировать control. | blocked/gap |
| `/` и `/kpi` | пустые страницы | UNKNOWN/analytics после решения | Не входят в mock cutover; KPI не начинать без формул. | deferred |

## 4. Целевая схема интеграции

```text
React page
  -> feature port/client
    -> same-origin /api/<service>/...
      -> stateless API gateway
        -> owning service public API
          -> service-local JPA aggregate / projection
          -> internal service calls, outbox/Kafka where already designed
```

Правила для каждого вертикального среза:

1. Сначала сверить нужный UI action с canonical OpenAPI. Browser DTO, seed ID и
   localStorage envelope не становятся контрактом автоматически.
2. Если контракт покрывает flow, адаптировать панель к нему без service shim.
3. Если контракт не покрывает flow, один backend owner добавляет минимальный
   endpoint в owning service, его OpenAPI, service tests и при необходимости
   Flyway migration. Только после фиксации схемы подключается panel owner.
4. Browser не вызывает `/api/internal/**`, не координирует task-board/asset/media
   вручную и не рассчитывает authoritative totals.
5. Mutation использует `expectedVersion`/ETag и единый `409`; повторяемый create
   или effect использует `Idempotency-Key`/stable external ID.
6. После успешного HTTP cutover того же flow удалить его browser adapter,
   storage key, seed, event subscription, fallback branch и mock-only tests.
7. Не переносить содержимое browser stores в PostgreSQL автоматически. Это
   fixture/recovery evidence, не доверенный production import.
8. Gateway не агрегирует business responses. Страница может параллельно читать
   несколько публичных projections, но команды всегда идут владельцу домена.

### Spring implementation rules

Если контрактный gap требует persistence-изменения:

- активировать `spring-data-jpa` перед изменением entity/repository/projection;
- JPA mapping остаётся application model, Flyway — единственный schema/history
  authority, `hibernate.ddl-auto=validate`;
- migration добавляется в БД owning service; cross-database FK/join запрещены;
- Lombok не использовать как `@Data`, builder или generated
  equals/hashCode/toString на JPA entities; ID/version/timestamps/invariants не
  получают generated setters;
- MapStruct использовать для entity/projection → DTO и sanitized payload:
  Spring component model, constructor injection, `unmappedTargetPolicy=ERROR`;
- MapStruct не мутирует entity из request, не меняет version/security fields и
  не выполняет domain transition;
- tests включают clean Flyway install/affected upgrade path и JPA validation,
  если менялась схема.

## 5. Зависимости и критический путь

```text
0. Зелёный build + gateway-only clients + local runtime
             |
             +------------------------+
             v                        v
1A. Warehouse/asset core       1B. Task-board
             |                        |
             +-----------+------------+
                         v
2A. Media                 2B. Maintenance catalog
             |                        |
             +-----------+------------+
                         v
3A. Maintenance lifecycle       3B. Inventory implementation
             |                        |
             +--- inventory publication smoke
                         |
                         v
4. Logistics contract gaps
                         |
                         v
5. Logistics panel cutover
                         |
                         v
6. Dossier projection cutover (после producer facts)
```

Asset UUIDs являются общей опорой для inventory, maintenance, logistics, media
owner references и dossier. Dossier выполняется последним, потому что без
реальных producer events он честно вернёт partial/empty coverage.

## 6. Волны реализации

### Волна 0 — стабилизировать текущий незавершённый cutover

Эта волна выполняется одним интеграционным агентом. До её завершения нельзя
давать нескольким агентам общие panel foundation-файлы.

#### Task 0.1: вернуть панели зелёный typecheck

**Files:**

- Modify: `panel/src/features/rental-items/api/rental-items-api.ts`
- Modify: `panel/src/features/rental-items/dossier/adapters/http-rental-item-dossier-adapter.ts`
- Modify: `panel/src/features/rental-items/rental-item-detail-page.tsx`
- Modify: `panel/src/features/rental-items/rental-items-page.tsx`
- Modify/update: затронутые `*.test.ts(x)`

- [ ] согласовать типы dossier commands, `expectedVersion`, token и return types;
- [ ] убрать stale imports/unused values без возвращения browser fallback;
- [ ] добавить success/error tests для исправленных HTTP adapters;
- [ ] выполнить `npm run typecheck` и focused Vitest;
- [ ] не начинать Task 0.2, пока typecheck не зелёный.

#### Task 0.2: закрепить единый same-origin gateway client

**Files:**

- Modify: `panel/src/lib/gateway-config.ts`
- Modify: `panel/src/features/settings/task-board/api/http-task-board-settings-client.ts`
- Modify: task-board HTTP clients в logistics/contents-transfer/warehouse-transfer
- Create/update: gateway config/client tests

- [ ] добавить typed base URLs для logistics и dossier;
- [ ] заменить `VITE_TASK_BOARD_API_URL` и `http://localhost:8081` на
  `${window.location.origin}/api/task-board`;
- [ ] запретить service origins как browser runtime configuration;
- [ ] проверить URL encoding, Bearer и Problem Details error mapping;
- [ ] выполнить focused tests, `npm run typecheck` и `npm run build`.

#### Task 0.3: сделать local all-services runtime непротиворечивым

**Files:**

- Modify: `compose.yaml`
- Modify only if needed: existing local run documentation/scripts

- [ ] добавить local-only `logistics-db` на `127.0.0.1:5440` и отдельный volume;
- [ ] не публиковать service DB/Kafka/MinIO наружу;
- [ ] проверить `docker compose --profile core config`;
- [ ] выполнить clean Flyway install logistics-service на новой БД без удаления
  существующих volumes;
- [ ] зафиксировать минимальный smoke: auth → gateway → warehouse endpoint.

### Волна 1 — две параллельные вертикали

После Волны 0 можно запускать двух больших агентов одновременно.

#### Task 1A: завершить warehouse/asset core

**Owner:** Full-stack Agent A
**Exclusive files:** `panel/src/features/rental-items/**`,
`panel/src/features/equipment/**`, `panel/src/api/equipment-api.ts`,
`panel/src/api/warehouse-api.ts`, при gap — `services/asset-service/**`,
`contracts/openapi/asset-service.yaml`

- [ ] завершить registry/detail/create/status/comment/manual-note HTTP flows;
- [ ] подключить equipment catalog/totals/dispositions только по доказанному API;
- [ ] явно убрать/скрыть controls без server contract;
- [ ] удалить заменённые asset/equipment browser paths и subscriptions;
- [ ] добавить adapter tests: success, malformed response, 401/403/404/409;
- [ ] выполнить panel focused tests/build и asset-service focused tests при его
  изменении.

#### Task 1B: перевести task-board settings и operational board

**Owner:** Full-stack Agent B
**Exclusive files:** `panel/src/features/task-board/**`,
`panel/src/features/settings/task-board/**`, `services/task-board-service/**`,
`contracts/openapi/task-board-service.yaml`

- [ ] подключить queues/classes/workers/groups через gateway;
- [ ] заменить board projection и take/pause/resume/complete/move на public API;
- [ ] маппить external references в links, не загружая local repair aggregate;
- [ ] удалить `features/task-board/mock/**`, mock toolbar и runtime selector для
  переведённых возможностей;
- [ ] удалить из production UI schedule/simulation/notifications, если для них
  нет утверждённого контракта;
- [ ] добавить success/conflict/forbidden tests и focused service tests.

### Волна 2 — media foundation и maintenance catalog параллельно

#### Task 2A: первый HTTP media cutover

**Owner:** Agent A с Go + React опытом
**Exclusive files:** `services/media-service/**`,
`contracts/openapi/media-service.yaml`, `panel/src/features/media/**` и один
согласованный первый media consumer

- [ ] реализовать typed client для upload session/finalize/list/original/rotation;
- [ ] передавать Bearer, Idempotency-Key и только contract-approved owner refs;
- [ ] перевести первый законченный media flow без data URL persistence;
- [ ] удалить IndexedDB path именно для переведённого owner flow;
- [ ] добавить Go/API tests и panel adapter tests;
- [ ] после полного Kafka media replacement удалить media Rabbit compatibility
  runtime/config/tests, не затрагивая volumes без отдельного разрешения.

#### Task 2B: перевести maintenance catalog/settings

**Owner:** Full-stack Agent B
**Exclusive files:** `panel/src/features/repair-estimate-catalog/**`,
`panel/src/features/settings/estimates-repairs/**`,
`services/maintenance-service/**`, `contracts/openapi/maintenance-service.yaml`

- [ ] подключить catalog versions/nodes/links/import/activate;
- [ ] сохранить UI canvas только как presentation, а canonical graph — в
  maintenance-service;
- [ ] удалить catalog seed/store и mock-only repair settings actions;
- [ ] не менять task-board queue ownership через maintenance;
- [ ] добавить validation/conflict/activation tests;
- [ ] выполнить panel и maintenance focused checks.

### Волна 3 — maintenance lifecycle и inventory параллельно

Task 2A должен завершить media client contract; Task 2B — catalog mapping.

#### Task 3A: estimates/repairs/acceptance/write-off

**Owner:** Full-stack Agent B
**Exclusive files:** `panel/src/features/repair-estimates/**`,
`panel/src/features/repair-tasks/**`, `panel/src/features/repairs/**`,
`panel/src/features/acceptance/**`, cabin `write-offs` UI,
`services/maintenance-service/**`

- [ ] заменить LocalStorageRepairEstimatesAdapter на maintenance HTTP client;
- [ ] заменить LocalStorageRepairTasksAdapter на repair/plan/rework commands;
- [ ] брать eligible workers/board state из task-board, не из mock directory;
- [ ] подключить media refs через готовый media client;
- [ ] перевести acceptance/write-off projections и commands;
- [ ] удалить local adapters, worker mock, storage events и mock tests;
- [ ] добавить version/idempotency/partial-failure tests и focused service tests.

#### Task 3B: inventory full cutover

**Owner:** Full-stack Agent A
**Exclusive files:** `panel/src/features/inventory/**`,
`services/inventory-service/**`, `contracts/openapi/inventory-service.yaml`

- [ ] заменить LocalStorageInventoryAdapter на `/api/inventory/v1` client;
- [ ] использовать server-issued session/finding versions и Idempotency-Key;
- [ ] не вычислять authoritative date, reconciliation, completion totals или
  publication state в браузере;
- [ ] подключить media refs только по inventory/media contract;
- [ ] удалить inventory local store, leases, storage subscriptions и mock tests;
- [ ] добавить active/history/findings/preview/complete/publication/statistics
  tests и focused service checks.

### Волна 4 — подготовить logistics boundary

Текущий `features/logistics/api/logistics-api.ts` слишком большой и является
общей точкой записи. Нескольким агентам нельзя одновременно менять его.

#### Task 4.1: один owner разделяет logistics ports по flows

**Owner:** один Full-stack Logistics Agent
**Files:** `panel/src/features/logistics/**`, без изменения runtime behavior до
активации HTTP clients

- [ ] выделить отдельные ports/models для returns, shipments и transfers;
- [ ] определить UI action → существующий logistics operationId;
- [ ] перечислить только реальные contract gaps: candidates/company/import,
  contents transfer и отсутствующие transition semantics;
- [ ] не создавать параллельные runtime implementations или feature flag;
- [ ] добавить characterization tests на сохраняемое UI behavior;
- [ ] получить зелёный typecheck до раздачи подпапок разным агентам.

#### Task 4.2: закрыть минимальные logistics-service contract gaps

**Owner:** тот же backend owner или отдельный backend agent с exclusive ownership
`services/logistics-service/**` и `contracts/openapi/logistics-service.yaml`

- [ ] сохранить logistics-service command owner для document workflows;
- [ ] перенести task-board/asset/maintenance/media orchestration на internal
  clients сервиса;
- [ ] добавить только те public reads/commands, без которых перенесённый UI flow
  невозможен;
- [ ] не создавать company/reservation service и не угадывать ownership;
- [ ] при JPA изменениях добавить Flyway migration и применить
  `spring-data-jpa` rules;
- [ ] покрыть idempotency, CAS, dependency outage и compensation focused tests.

### Волна 5 — logistics cutover

После фиксации OpenAPI tasks 5A–5C могут выполняться параллельно, но только если
Task 4.1 уже разнёс код по непересекающимся подпапкам. Один backend owner
остаётся владельцем logistics-service и Flyway numbering.

#### Task 5A: returns

- [ ] подключить list/create/register/accept-undamaged/request-estimate;
- [ ] заменить browser journals на service idempotency/status;
- [ ] убрать client-side rental-item mutation и equipment reconciliation saga;
- [ ] удалить return mock state в том же задании;
- [ ] добавить focused UI/API tests.

#### Task 5B: shipments

- [ ] подключить list/create/plan/confirm-preparation/cancel;
- [ ] не создавать task-board task из браузера;
- [ ] удалить BrowserShipmentClient, shipment storage и active guards,
  вычисляемые из localStorage;
- [ ] добавить duplicate/conflict/cancel tests;
- [ ] выполнить logistics-service focused checks.

#### Task 5C: warehouse и contents transfers

- [ ] подключить create/depart/arrive/cancel/reconcile;
- [ ] перенести media refs на media service;
- [ ] заменить browser contents-transfer saga утверждённой server command;
- [ ] удалить local transfer stores, IndexedDB transfer media и direct
  task-board clients;
- [ ] добавить departure/arrival/retry/conflict tests.

### Волна 6 — подключить read-only dossier

**Owner:** один Full-stack Dossier Agent
**Files:** `panel/src/features/rental-items/dossier/**`,
`panel/src/features/rental-items/rental-item-detail-page.tsx`, при необходимости
`services/dossier-service/**`, `contracts/openapi/dossier-service.yaml`

- [ ] читать asset core и dossier activity раздельными TanStack queries;
- [ ] маппить `activityCode`, opaque actor ref, source ref, visibility и cursor
  без выдумывания display names/скрытых rows;
- [ ] команды status/comment оставить у asset, media actions — у media;
- [ ] удалить BrowserRentalItemDossierAdapter и asset pseudo-dossier mapping;
- [ ] показать truthful `PARTIAL` coverage и pagination;
- [ ] добавить 404/partial/filter/cursor/authorization tests и focused dossier
  service checks.

## 7. Что можно делать параллельно

| Параллельная пара | Условие | Почему безопасно |
| --- | --- | --- |
| Asset core ↔ Task-board | Волна 0 завершена | Разные feature folders, services и OpenAPI files. |
| Media client ↔ Maintenance catalog | Gateway base заморожен | Catalog не зависит от загрузки media. |
| Inventory ↔ Maintenance lifecycle | Media interface зафиксирован; backend contracts существуют | Разные сервисы/БД/панельные features; финальный inventory publication smoke после maintenance. |
| Logistics backend ↔ Dossier panel mapping | Dossier contract уже фиксирован | Разные модули; финальный dossier acceptance ждёт logistics events. |
| Backend service ↔ panel adapter одного flow | Один agent владеет OpenAPI и schema уже заморожена | Файлы не пересекаются, panel работает против утверждённого response/request. |
| Returns ↔ shipments ↔ transfers | Только после Task 4.1 | У каждого flow отдельная подпапка; общий logistics service меняет один backend owner. |

Параллельность означает независимые coding tasks. Отдельные reviewer, QA,
discovery или memory agents не нужны: каждый coding agent сам добавляет и
запускает focused validation.

## 8. Что нельзя раздавать параллельно

- `panel/src/lib/gateway-config.ts`, `App.tsx`, `app-sidebar.tsx`, root
  `compose.yaml` — по одному владельцу на волну.
- Один OpenAPI-файл, Flyway directory или JPA aggregate одного сервиса — только
  один backend owner.
- Нельзя дать трём агентам returns/shipments/transfers, пока они все пишут в
  монолитный `logistics-api.ts`.
- Нельзя одному агенту подключать HTTP flow, а другому позже удалять его mock.
  Cutover и removal — одна задача и один owner.
- Нельзя писать panel adapter до стабилизации нового/изменённого контракта.
- Нельзя считать dossier готовым до появления реальных producer events.
- Нельзя начинать KPI/analytics, warehouse topology, schedules или worker push
  без продуктового решения, даже если agent свободен.
- Нельзя разрешать browser direct access к service ports или `/api/internal/**`.

## 9. Рекомендуемый график для двух больших агентов

Это наиболее безопасная конфигурация для общего worktree.

| Цикл | Большой Agent A | Большой Agent B | Handoff |
| --- | --- | --- | --- |
| 0 | Один из агентов выполняет Волны 0.1–0.3 | Не редактирует shared files | Зелёный build, gateway URLs и local runtime. |
| 1 | Warehouse/asset core | Task-board settings/runtime | Canonical asset UUID + реальная board API. |
| 2 | Media client и первый owner flow | Maintenance catalog/settings | Замороженные media DTO/owner rules и catalog DTO. |
| 3 | Inventory full cutover | Maintenance lifecycle | Inventory publication smoke выполняется после готовности maintenance. |
| 4 | Logistics decomposition + backend gaps | Dossier client/projection mapping без финального coverage claim | Зафиксированный logistics OpenAPI. |
| 5 | Logistics panel flows | Dossier final integration и removal | Сквозные producer events → dossier. |

Agent A и Agent B не должны менять один файл в одном цикле. Любая необходимость
изменить shared foundation оформляется коротким handoff владельцу, а не
параллельной правкой.

## 10. Вариант для трёх-четырёх агентов

После Волны 0 максимальная полезная раскладка:

1. Agent Asset — warehouse/rental-items/equipment vertical;
2. Agent Task-board — board/settings vertical;
3. Agent Media — Go service + media panel client;
4. Agent Maintenance — только catalog vertical, пока media interface не готов.

На следующей итерации:

1. Agent Inventory — весь inventory vertical;
2. Agent Maintenance — estimates/repairs/acceptance;
3. Agent Logistics Backend — service contract gaps;
4. Agent Logistics Panel — только decomposition/characterization до handoff.

Больше четырёх агентов не ускорит критический путь: возрастёт конфликтность в
shared panel models, gateway config, logistics monolith и service contracts.

## 11. Шаблон задания агенту

Каждое задание выдаётся в следующем формате:

```text
Outcome: один законченный service-backed пользовательский flow без mock fallback.

Exclusive ownership:
- точные panel directories/files;
- один owning service и его OpenAPI/Flyway files, если нужны изменения.

Inputs:
- canonical OpenAPI operationIds;
- уже зафиксированные DTO/owner rules;
- зависимости, которые должны быть зелёными до старта.

Required changes:
1. Подключить public gateway API.
2. Сохранить UI behavior, если оно подтверждено contract/domain semantics.
3. Добавить 401/403/404/409/idempotency handling по применимости.
4. Удалить заменённый browser store/mock/seed/runtime selection.
5. Не изменять перечисленные shared/out-of-scope files.

Required validation:
- focused panel tests;
- npm run typecheck;
- owning service focused tests, если backend изменён;
- Flyway/JPA validation, если менялась schema;
- один focused browser flow через gateway, если UI behavior изменён.

Report:
- changed files;
- commands actually run and results;
- remaining contract gap/blocker;
- implementation risk.

Do not commit unless the user explicitly asks.
```

## 12. Definition of Done для каждого flow

- [ ] все reads/writes проходят через same-origin gateway;
- [ ] browser не хранит authoritative aggregate/document/task/media metadata;
- [ ] отсутствует production mock/fallback branch;
- [ ] `expectedVersion`, idempotency и Problem Details корректно отображаются;
- [ ] заменённые mock/browser files и tests удалены в той же задаче;
- [ ] service contract и implementation совпадают;
- [ ] focused panel/service/Flyway tests зелёные;
- [ ] UI не показывает fabricated data для отсутствующего contract field;
- [ ] права проверяются сервисом, а UI лишь скрывает/disable controls для UX;
- [ ] agent report перечисляет фактические проверки и ограничения.

## 13. Финальная проверка всего перехода

Финальная проверка выполняется владельцами последней волны, а не отдельным QA
agent.

- [ ] `npm run typecheck`, `npm run lint`, `npm run build`;
- [ ] focused Vitest/e2e по каждому перенесённому вертикальному flow;
- [ ] affected Gradle module tests и `go test ./...` для media;
- [ ] `rg` не находит production business use `localStorage`, IndexedDB,
  browser mock clients или seed, кроме утверждённого allowlist UI preferences;
- [ ] `rg` не находит browser service origins/`VITE_*_API_URL` для внутренних
  сервисов;
- [ ] gateway блокирует internal/private paths;
- [ ] local all-services smoke проходит с отдельными PostgreSQL databases,
  Kafka и MinIO;
- [ ] OIDC/token/service outage fail closed;
- [ ] `/`, `/kpi`, topology, schedules, company/reservation и notifications
  остаются честно deferred/disabled, пока нет отдельного продуктового решения.

## 14. Риски и решения, которые нельзя выдумывать во время исполнения

1. **Warehouse topology/bin model.** Контракта нет — не переносить generated
   graph в warehouse-service без команды.
2. **Company/contract/reservation ownership.** Не создавать новый сервис ради
   dropdown; использовать только доказанный snapshot/text контракт.
3. **Task-board schedules.** Нужны timezone/DST/downtime правила.
4. **Worker notifications/offline delivery.** Текущий mock не определяет
   production delivery semantics.
5. **KPI.** Нужны формулы, период, backfill и ownership; analytics-service сейчас
   отсутствует.
6. **Browser data migration.** Не импортировать localStorage/IndexedDB как
   production данные без отдельного reviewed import contract.
7. **Irreversible logistics departure.** Compensation после departure требует
   отдельного domain decision; не имитировать rollback в панели.

## Post-Completion

После выполнения всех не-deferred вертикалей требуется ручной smoke с реальным
USER token на двух warehouse access levels: VIEW и EDIT/MANAGE. Production
deployment и внешний ingress остаются отдельной задачей.
