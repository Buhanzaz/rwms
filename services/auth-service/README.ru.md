# Auth Service RWMS

[English version](README.md)

`auth-service` — stateful-сервис, который является владельцем идентичности и
авторизации в RWMS. Он владеет интерактивными пользователями и worker,
парольными учётными данными, глобальными ролями, warehouse grants, регистрацией
OAuth/OIDC-клиентов, выпуском токенов и публикацией JWT/JWKS.

Это намеренно не общий бизнес-сервис: бытовки, жизненный цикл склада, задачи,
обслуживание, инвентаризация, логистика и медиа принадлежат соответствующим
доменным сервисам. Эти сервисы локально валидируют выданные Bearer JWT и сами
принимают решения о своей бизнес-авторизации.

## Зачем нужен этот сервис

У идентичности и политики доступа должен быть один ответственный владелец. Если
каждый доменный сервис будет хранить свои пароли, клиентов, роли и grants,
появятся дублирующиеся учётные записи, противоречивые права, неодинаковые logout
и revocation-сценарии, а поверхность атаки на учётные данные резко вырастет.

| Проблема | Ответственность auth-service | Результат |
| --- | --- | --- |
| Клиентам нужен единый доверенный протокол входа | Запускает standards-based OAuth 2.1/OIDC authorization server и публикует JWKS | Panel и мобильные приложения интегрируются один раз, а API валидируют подписанный JWT без общих паролей. |
| У пользователей и worker разные модели доступа | Владеет USER/WORKER subjects, credentials, principal type и разрешёнными типами клиентов | Токен worker нельзя получить через user-only client и наоборот. |
| Сервисам нужен стабильный контекст авторизации | Выпускает claims субъекта, роли, scope, audience и warehouse access | Downstream-сервисы авторизуют локально без синхронного вызова на каждый запрос. |
| Админские изменения должны быть безопасны | Ограждает команды `expectedVersion`, защищает SYSTEM_ADMIN-инварианты и отзывает сохранённые authorizations при снятии доступа | Устаревшее обновление вернёт `409`; пользователь не сохранит снятую сессию/авторизацию незаметно. |
| Клиенты и секреты должны развиваться контролируемо | Provisioning управляемых OAuth clients с явным revision и fail-closed validation | Security-конфигурация клиента не дрейфует между деплоями. |
| Изменения идентичности должны надёжно попадать в другие сервисы | Сохраняет authorization events и использует transactional outbox/inbox/replay | Доставка Kafka восстанавливаема и не становится источником истины. |

## Владение и границы

Auth-service владеет:

- логином, проверкой и сменой пароля пользователей и worker RWMS;
- OIDC sessions, OAuth authorization, выпуском токенов, signing keys, JWKS и
  registered clients;
- глобальными ролями, mobile/rental entitlements и per-user warehouse grants;
- authorization events и состоянием их транзакционной доставки.

Он не владеет:

- идентичностью, статусом, timezone или жизненным циклом склада — это зона
  `warehouse-service`;
- бизнес-авторизацией внутри aggregate другого сервиса;
- OIDC PKCE-транзакцией panel или долгоживущим browser token storage;
- публичной маршрутизацией. В production клиент приходит через
  [`api-gateway-service`](../api-gateway-service/README.ru.md); приватные
  межсервисные вызовы используют private address и service credentials.

Такая граница не даёт auth-service превратиться в cross-domain workflow engine
или общую БД продуктового состояния.

## Как работают вход и выпуск токена

```text
Panel / manager app / ClientApp / WorkerApp / DriverApp
          |
          | Authorization Code + PKCE через public /auth/**
          v
API gateway (public edge; только routing и transport policy)
          |
          v
auth-service: login, consent/session, OAuth/OIDC authorization server
          |
          +--> signed access token / ID token
          +--> JWKS на authorization-server endpoint
          |
          v
Сервис-владелец API: local JWT validation + domain authorization
```

Для public panel и mobile clients сервис использует Authorization Code flow с
PKCE. Для confidential internal clients применяется `client_credentials` с
секретом из deployment configuration. Политика клиента явно задаёт grants,
authentication methods, scopes, audience, allowed principal type, redirect и
post-logout URI, allowed origins, PKCE и сроки жизни токенов.

В production public issuer имеет вид `<gateway>/auth`. Upstream-сервис сохраняет
свои native authorization-server paths (`/oauth2/**`, `/login`, `/logout` и
`/api/**`); gateway снимает ровно один префикс `/auth`. Callback panel остаётся
`/auth/callback`, принадлежит panel и не пересылается в auth-service.

WorkerApp и DriverApp используют одну WORKER credential identity, но два
непересекающихся public clients; authorization requests обоих клиентов требуют
PKCE `S256`. `rwms-worker-android` возвращает
query-free HTTPS `/auth/worker/callback` и получает `worker.tasks`;
`rwms-driver-android` возвращает `/auth/driver/callback` и получает только
`driver.tasks`. Native login clients валидируют и потребляют redirect Location
в памяти, поэтому ни один Android manifest не открывает custom-scheme или App
Link receiver. Оба client ID выбирают login surface для worker credentials.

ClientApp сначала получает CSRF cookie/header pair через `GET /api/auth/csrf`,
а затем вызывает `POST /api/customer/v1/registrations`. Регистрация атомарно
создаёт активного пользователя `CUSTOMER`, его private encoded credential,
начальный authorization fact и outbox row. До password hashing auth-service
атомарно расходует долговечные per-source и глобальный fixed-window budgets
регистрации. Учётная запись не имеет warehouse
grants, manager-mobile access или rental-manager entitlement. Она может
использовать только `rwms-customer-android` с query-free HTTPS callback
`/auth/customer/callback` и единственным бизнес-scope `customer.rental`; другие
пользователи не могут применять этот client.

User access и ID tokens содержат канонические claims `sub`,
`preferred_username`, `principal_type=USER`, `global_role` и camel-case
`rentalAccess`, а также managed `client_id`, выпустивший токен. Источником истины
для точной формы claims и endpoint остаётся публичный контракт, а не этот
README.

## Публичный API и правила доступа

Канонический контракт находится в
[`contracts/openapi/auth-service.yaml`](../../contracts/openapi/auth-service.yaml).
Он определяет administration/current-user API, приватные команды worker credentials
и восстановление authorization events. OAuth/OIDC endpoints остаются standards-based.

| Публичный endpoint | Назначение | Правило доступа |
| --- | --- | --- |
| `GET /api/auth/csrf` | Получение CSRF cookie/header pair для регистрации | Анонимное чтение. |
| `POST /api/customer/v1/registrations` | Создание customer-only credential и authorization stream | Анонимно с точной CSRF cookie/header pair; логин из 3–64 portable символов, пароль из 8–128 символов и совпадающее подтверждение. |
| `GET /api/admin/users` | Список администрируемых пользователей | USER JWT с `SYSTEM_ADMIN` или `WMS_ADMIN`. |
| `POST /api/admin/users` | Создание администрируемого пользователя | Та же роль; только `SYSTEM_ADMIN` может создать `SYSTEM_ADMIN` account. |
| `GET /api/admin/users/{id}` | Чтение administrative user projection | USER JWT с `SYSTEM_ADMIN` или `WMS_ADMIN`. |
| `PUT /api/admin/users/{id}` | Version-fenced изменение профиля и авторизации | Та же роль; нужен `expectedVersion`, а `WMS_ADMIN` не управляет `SYSTEM_ADMIN`. |
| `GET /api/users/me` | Текущая access projection активного пользователя | USER Bearer JWT. |

Маршруты администрирования пользователей требуют USER bearer token от
`rwms-admin-web` со scope `admin.manage` и текущую сохранённую роль
`SYSTEM_ADMIN` или `WMS_ADMIN`. Эти stateless API не требуют CSRF token.

| Дополнительный endpoint | Поведение и доступ |
| --- | --- |
| `PUT /api/admin/users/{id}/password` | Замена write-only пароля с expectedVersion, отзыв сохранённых authorizations; `204`. |
| `PUT /api/admin/users/{id}/warehouse-accesses` | Полная замена `accesses` с expectedVersion; ответ `AdminUser`. Пустой массив удаляет все явные grants. |
| `DELETE /api/admin/users/{id}` | Физическое удаление запрещено; существующий видимый пользователь возвращает `409`. |
| `GET /api/users/actor-displays` | USER token и текущая сохранённая роль администратора; до 100 повторяющихся `subjectId`, порядок первого появления, неизвестные субъекты пропускаются. Семь полей projection не содержат credentials или grants. |
| `PUT /api/internal/worker-credentials/{workerId}` | Создание или замена worker credential; ответ содержит безопасный статус и канонический UUID склада. |
| `POST /api/internal/worker-credentials/{workerId}/reset` | Замена пароля существующей credential и отзыв сохранённых authorizations; `204`. |
| `POST /api/internal/worker-credentials/{workerId}/disable` | Отключение credential; отсутствующая или уже отключённая также возвращает `204`. |
| `POST /api/internal/worker-credentials/{workerId}/enable` | Включение существующей credential; `204`, при отсутствии `404`. |
| `DELETE /api/internal/worker-credentials/{workerId}` | Идемпотентное удаление, включая отсутствующую credential; `204`. |
| `GET /api/internal/worker-credentials/{workerId}/status` | `ACTIVE` или `DISABLED`; при отсутствии `404`. |
| `POST /api/admin/eventing/outbox/{eventId}/requeue` | Повторная постановка подходящей outbox DLT row с `expectedAttemptCount`; `204`, при отсутствии или несовпадении условий `409`. |
| `POST /api/admin/eventing/sanitized-dlt/{dltId}/requeue` | Повторная постановка FAILED sanitized DLT row с `expectedAttemptCount`; `204`, при отсутствии или несовпадении условий `409`. |
| `POST /api/admin/eventing/shadow/{aggregateType}/{aggregateId}/reconcile` | Восстановление заблокированного checkpoint по ожидаемой версии и причине; ответ содержит восстановленный хвост stream. |
| `POST /api/admin/eventing/shadow/rebuild` | Перестроение shadow с долговечным `operationId` и причиной; ответ содержит replay parity. Завершённый receipt используется повторно, неуспешный нельзя повторить с тем же ID. |

Приватные worker operations требуют одновременно SERVICE principal, client
`task-board-service` и scope `worker-credentials.manage`. `workerId` — внешняя
непустая строка, не обязательно UUID. Ответы содержат только `workerId`,
канонический `warehouseId`, `appLogin` и `status`.
Recovery operations требуют `SYSTEM_ADMIN`, без ограничения на dedicated admin
client или scope. Reconciliation и rebuild дополнительно разрешают username
оператора в канонический auth subject. Обе группы используют stateless Bearer
доступ без CSRF. Browser clients не вызывают приватные worker routes.
Отсутствующий сохранённый shadow checkpoint пока возвращает HTTP `500` с JSON;
эта ошибка восстановления отличается от обычного Problem Details mapping.
Отсутствующий shadow checkpoint сейчас возвращает необработанный `500 application/json`;
каноническая reconciliation operation фиксирует этот наблюдаемый отказ.

Сервис возвращает единые Problem Details для invalid, unauthenticated,
forbidden, not-found, conflict и registration-rate-limit случаев. Ответ `429`
регистрации содержит `Retry-After`; недоступная БД throttle приводит к
fail-closed отказу до расходования password-hash capacity. Изменения admin profile, password и
warehouse accesses используют optimistic concurrency. `409` означает, что
клиент должен обновить authoritative state перед повтором команды.

В current-user projection `SYSTEM_ADMIN` и `WMS_ADMIN` имеют доступ ко всем
складам; другие роли получают активные grants с effective access level.
`rentalAccess` — сохранённый entitlement, который нельзя вычислять на клиенте
из роли. При создании пропуск поля задаёт role-based default; при update пропуск
поля сохраняет текущее значение.

## Свойства безопасности

- Пароли и password hashes никогда не возвращаются в public responses или
  events. Передача initial/reset worker credential физическому работнику —
  отдельный operational contract.
- Disable пользователя блокирует новый form login и выпуск нового токена из его
  session. Уже выпущенные self-contained access tokens действуют только до
  своего короткого configured lifetime (в текущей managed-client policy — пять
  минут).
- При изменении логина или пароля, relevant entitlement или client access
  удаляются сохранённые authorizations и consents. Там, где изменение касается
  одного client audience, применяется client-specific revocation.
- Physical user deletion намеренно fail-closed. Поддерживаемая операция —
  disable, пока не доказано отсутствие внешней audit history и active sessions.
- Последнего active `SYSTEM_ADMIN` нельзя отключить, понизить или удалить;
  non-system administrator не может создать или управлять system-admin account.
- Имя пользователя не может конфликтовать с зарезервированным OAuth client ID.
- Case-insensitive self-registration одного логина сериализуется
  transaction-scoped advisory lock до password hashing и unique writes.
  Независимо от этого auth-service атомарно обеспечивает настраиваемые
  per-source и глобальный fixed-window budgets до hashing. Он хранит только
  SHA-256 source key, удаляет истёкшие counters, возвращает русские Problem
  Details с `Retry-After` при `429` и пишет rejection/unavailable metrics.
  Production ingress rate limiting остаётся дополнительным слоем защиты, а не
  единственным механизмом.
- Пользователи `CUSTOMER` и `rwms-customer-android` взаимно изолированы от всех
  остальных user clients при authorization-code и refresh-token exchange.
- Пользователь `RENTAL_MANAGER` получает interactive token только через
  `rwms-rental-manager-web` или `rwms-rental-manager-android`. Оба client имеют
  только `rental.manage` и не могут выпустить panel, logistics или admin token.
  `rwms-admin-web` доступен только `SYSTEM_ADMIN` и `WMS_ADMIN` и имеет только
  `admin.manage`.

Так durable access invariants находятся там, где владелец credentials и токенов
может обеспечить их в транзакции, а не зависят от UI-проверки или дублирования
во всех downstream-сервисах.

## Жизненный цикл OAuth-клиента

Managed clients объявлены под `rwms.auth.oauth.clients`. Provisioning сначала
валидирует всю конфигурацию, затем берёт PostgreSQL advisory lock. Для
неизменённой конфигурации сохраняются registered-client ID, issued timestamp и
encoded secret.

Security-relevant изменение конфигурации или секрета требует монотонно
увеличить `revision`. `revoke-authorizations: true` вместе с новой revision —
явный выбор для удаления сохранённых authorizations/consents. `enabled: false`
сохраняет client и audit rows, но делает lookup fail-closed. Если managed client
уже существует, убрать его из configuration нельзя: его надо явно отключить.

Это лучше, чем пересоздавать clients на каждом старте: stable client IDs
сохраняют корректное состояние, а осмысленная revision делает security change
проверяемым и не позволяет случайно реактивировать client при rollback деплоя.

Machine client `task-board-service` всегда требует внешний
`TASK_BOARD_CLIENT_SECRET`, включая профили `dev` и `test`; прежний секрет из
репозитория отклоняется даже при передаче через environment. Точный набор SERVICE
scopes и audience проверяется до любых изменений provisioning.
`TASK_BOARD_CLIENT_REVISION` по умолчанию равен `6`; при ротации ранее изменённого
client задайте значение выше сохранённой revision. Смена секрета этого client
атомарно удаляет его сохранённые authorizations и consents, сохраняя registered-client
identity. Повторный запуск с той же revision и секретом сохраняет новые grants.
Уже выпущенные JWT, проверяемые resource servers локально, действуют до своего
собственного `exp`; настроенный по умолчанию срок составляет пять минут.

Managed interactive inventory раздельно хранит существующий client руководителя
склада, отдельные web/Android clients менеджера аренды, admin web client,
CUSTOMER-only ClientApp client и два WORKER-only WorkerApp/DriverApp clients.
Dedicated clients требуют S256 PKCE, access token на пять минут и rotating
refresh token на 30 дней. Изменение callback, scope или principal type требует
собственной revision и согласованного client release; refresh token одного
client нельзя обменять через client ID другого.

Managed machine client `inventory-service` запрашивает ровно один downstream
scope на токен. Revision 5 добавила `media.inventory` для передачи фотографий
бытовки из завершённой инвентаризации; revision 6 добавляет только
`logistics.inventory` для авторитетного замещения логистической работы по всему
итоговому плану. Subject и `client_id` каждого токена остаются равны
`inventory-service`, audience — `rwms-services`. Asset, maintenance, media,
logistics и warehouse scopes по-прежнему запрашиваются отдельными токенами.

Revision 5 managed machine client `asset-service` добавляет существующий scope
`media.asset` для точного private-подтверждения READY-фотографий при durable
создании бытовки. Отдельный `media.asset-import` для media HTML-импорта
сохраняется; каждый downstream-вызов по-прежнему запрашивает ровно один scope,
а subject и `client_id` остаются равны `asset-service`.

Revision 4 managed machine client `task-board-service` добавляет только
`warehouse.identity.read`, чтобы владелец смены Driver Up получал актуальные название, город,
timezone и координаты склада через private boundary warehouse. Revision 7 client
`logistics-service` добавляет только `task-board.driver-shifts.plan` для idempotent публикации
snapshot плана в смену. Logistics по-прежнему запрашивает существующие downstream scopes
раздельно; ни одно из этих прав не выдаётся mobile или browser clients.

Выключенный по умолчанию machine client `logistics-planner` предназначен только для отдельного
симулятора маршрутов. При явном включении он использует только `client_credentials`, audience
`rwms-services`, `client_secret_basic` и единственный scope `logistics.planning`. Runtime-секрет читается из
`LOGISTICS_PLANNER_CLIENT_SECRET`; включение или изменение security shape требует revision managed
client. Он не получает user, warehouse-administration или общие read/write scopes.

## Проверка warehouse grants

Идентичностью склада владеет `warehouse-service`, поэтому auth-service не
создаёт и не изменяет warehouse records. Он принимает только canonical UUID и
проверенные aliases `spb`/`msk`, а затем при необходимости проверяет distinct
warehouses через Warehouse Service до сохранения grant или worker credential.

`rwms.auth.warehouse-validation.enabled` по умолчанию `false`. Тогда no-op port
не требует URL Warehouse Service, token URI, client secret и не делает network
calls; известные aliases по-прежнему canonicalize локально.

При включении adapter получает bounded `client_credentials` token ровно со
scope `warehouse.read` и обращается только к private singular existence
endpoint. Он требует strict JSON, exact matching canonical ID и active
warehouse. OAuth/network/malformed-response/missing/inactive/invalid ID ошибки
отклоняют всю mutation. JWT reads и token issuance Warehouse Service не вызывают.

Adapter не следует redirects и не кэширует токены. Поэтому default deployment
не зависит от Warehouse Service, а enabled integration остаётся явной,
ограниченной и fail-closed.

## Eventing и восстановление

PostgreSQL authorization projections и event store — authoritative; Kafka —
at-least-once transport. Изменения authorization и worker-access сохраняются
вместе со stream и transactional outbox. Consumers используют
inbox/deduplication и version-aware replay: duplicate, out-of-order или
временно недоступный broker не становятся вторым источником истины.

В сервисе есть replay, shadow reconciliation, quarantine и audit-компоненты для
доказательства или восстановления projection parity. Это operational recovery
mechanisms, а не public command API и не разрешение чинить business state из
произвольного Kafka message.

## Почему стоит делать именно так

| Альтернатива | Проблема | Выбранный дизайн |
| --- | --- | --- |
| Каждый сервис хранит своих users и passwords | Дублируются credentials, конфликтуют roles, нет единого владельца revocation. | Одна identity authority; остальные сервисы валидируют JWT и владеют своей domain authorization. |
| Gateway владеет login, tokens и clients | Public transport edge становится stateful и связывается с identity persistence. | Gateway маршрутизирует `/auth/**`; auth-service владеет OIDC state и signing material. |
| Introspection каждого токена при каждом API call | Добавляет latency и делает каждый domain request зависимым от доступности auth-service. | Локальная проверка signed short-lived JWT по JWKS. |
| Выводить permissions только из роли | Нельзя выразить явный rental entitlement или scoped warehouse grants. | Claims роли и entitlement вместе с effective warehouse access. |
| Принимать stale admin commands | Один администратор незаметно перезапишет изменение другого. | `expectedVersion` и `409` для stale write. |
| Публиковать в Kafka напрямую внутри business transaction | При сбое расходятся DB commit и broker publish. | Транзакционно сохранять event/outbox и отдельно relay/recover delivery. |

## Локальная разработка

Поднимите PostgreSQL из корня репозитория, затем запустите сервис с явным
development profile:

```powershell
docker compose up -d auth-db
.\gradlew.bat :services:auth-service:bootRun --args="--spring.profiles.active=dev"
```

На пустой development DB startup запускает Flyway migration V2 перед JPA
validation. Непустая DB без `flyway_schema_history` отклоняется; её можно
adopt только через документированный version-2 preflight и явный baseline
workflow.

Development profile включает локальное PostgreSQL-подключение `rwms_auth`,
`admin` / `admin` и ephemeral RSA signing key. Development credentials требуют
буквальный loopback issuer (`localhost`, `127.0.0.1` или IPv6 loopback);
по умолчанию это `http://localhost:9000`. Base configuration отключает эти defaults.
Даже перед локальным запуском задайте внешний `TASK_BOARD_CLIENT_SECRET`,
а при включённом maintenance client — `MAINTENANCE_CLIENT_SECRET`.

Для публичного issuer с профилем `dev` задайте `AUTH_DEV_DEFAULT_CREDENTIALS=false`
и `AUTH_SESSION_COOKIE_SECURE=true`. Настройте внешние bootstrap credentials,
persistent signing key, client secrets и public HTTPS issuer/origins/redirects
из списка ниже. Профиль `dev` учитывает эти environment settings; один лишь
публичный `AUTH_ISSUER` при сохранённых development credentials блокирует запуск.

`processResources` запускает `npm ci` и `npm run build` в `ui/`, затем кладёт
`ui/dist` в `BOOT-INF/classes/static`. Authorization server выдаёт сборку через
`GET /login`; `POST /login` остаётся Spring Security form-login processing.

## Требования production

Нужно предоставить минимум:

- `AUTH_DEV_DEFAULT_CREDENTIALS=false`;
- `AUTH_BOOTSTRAP_ADMIN_USERNAME` и `AUTH_BOOTSTRAP_ADMIN_PASSWORD` длиной от
  12 символов;
- `TASK_BOARD_CLIENT_SECRET` и секрет каждого enabled client;
- `AUTH_SIGNING_KEY_STORE`, `AUTH_SIGNING_KEY_STORE_PASSWORD` и
  `AUTH_SIGNING_KEY_ALIAS` для persistent PKCS12 signing key;
- `AUTH_DB_URL`, `AUTH_DB_USERNAME` и `AUTH_DB_PASSWORD` с non-loopback
  PostgreSQL endpoint и отдельными deployment credentials;
- public HTTPS issuer/base, allowed origins и registered redirect URIs;
- `CUSTOMER_ORIGIN` и `CUSTOMER_REDIRECT_URI` как same-origin HTTPS значения,
  причём последнее оканчивается query-free путём `/auth/customer/callback`;
- `WORKER_ORIGIN`, `WORKER_REDIRECT_URI`, `WORKER_POST_LOGOUT_REDIRECT_URI`,
  `DRIVER_ORIGIN`, `DRIVER_REDIRECT_URI` и
  `DRIVER_POST_LOGOUT_REDIRECT_URI`: same-origin HTTPS значения с отдельными
  query-free callbacks.

Вне dev/test startup не допускает отсутствующие bootstrap credentials, client
secrets, signing material, а также отсутствующие или development-default
datasource settings. Datasource guard выполняется после загрузки profile
configuration и до того, как application context создаст Flyway, JPA или
`DataSource`; сообщения называют только обязательную переменную и не печатают
credential values. Production session cookies по умолчанию secure. Framework
обрабатывает trusted forwarding metadata; public gateway обязан выводить их из
configured public values, а не принимать client-supplied forwarding headers.

Чтобы включить Warehouse Service validation одним согласованным деплоем:

1. Установите `AUTH_WAREHOUSE_CLIENT_ENABLED=true` и увеличьте
   `AUTH_WAREHOUSE_CLIENT_REVISION`.
2. Передайте `AUTH_WAREHOUSE_CLIENT_SECRET` из deployment secret store.
3. Установите `AUTH_WAREHOUSE_VALIDATION_ENABLED=true`,
   `WAREHOUSE_SERVICE_INTERNAL_BASE_URL` и `AUTH_WAREHOUSE_TOKEN_URI`.
4. При необходимости задайте положительные `AUTH_WAREHOUSE_CONNECT_TIMEOUT` и
   `AUTH_WAREHOUSE_READ_TIMEOUT`; оба значения ограничены 30 секундами.

## Схема и безопасные изменения

Flyway — единственный active authority для schema migration и checksum.
Hibernate настроен как `ddl-auto=validate`: он не создаёт, не обновляет и не
удаляет схему. Используйте immutable service-local migrations из
`src/main/resources/db/migration/`. Полная процедура adoption существующей DB —
в [database/flyway/README.md](database/flyway/README.md).

При изменении сервиса:

1. Начинайте с канонического OpenAPI/event contract и owner rule; не создавайте
   gateway-only или client-only identity transition.
2. Не выносите passwords, password hashes, client secrets, refresh tokens и
   signing-key material в public DTO, events, logs или документацию.
3. Сохраняйте public/private address split: browser traffic использует gateway
   `/auth/**`, internal client-credentials traffic — private service routes.
4. Для state/authorization change сохраняйте optimistic concurrency,
   event-stream ordering, transactional outbox/inbox и replay safety.
5. Меняйте DB только через immutable Flyway migration и JPA validation; никогда
   не полагайтесь на Hibernate schema mutation.

Полезные команды проверки из корня репозитория:

```bash
bash ./gradlew :services:auth-service:test
bash ./gradlew :services:auth-service:javadoc
```

## Основные материалы

- [Канонический публичный API](../../contracts/openapi/auth-service.yaml)
- [Схемы authorization events](../../contracts/events/)
- [Конфигурация сервиса](src/main/resources/application.yaml)
- [Конфигурация authorization server](src/main/java/dev/buhanzaz/rwms/auth/config/AuthorizationServerConfiguration.java)
- [Владелец user administration](src/main/java/dev/buhanzaz/rwms/auth/service/UserAdministrationService.java)
- [Реализация eventing](src/main/java/dev/buhanzaz/rwms/auth/eventing/)
- [Текущая архитектура проекта](../../docs/project-knowledge/architecture.md)
- [Владение identity и access](../../docs/project-knowledge/domain-logic.md)
