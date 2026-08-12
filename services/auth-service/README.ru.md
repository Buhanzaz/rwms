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
Panel / manager app / WorkerApp / DriverApp
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

User access и ID tokens содержат канонические claims `sub`,
`preferred_username`, `principal_type=USER`, `global_role` и camel-case
`rentalAccess`. Источником истины для точной формы claims и endpoint остаётся
публичный контракт, а не этот README.

## Публичный API и правила доступа

Канонический контракт находится в
[`contracts/openapi/auth-service.yaml`](../../contracts/openapi/auth-service.yaml).
Он определяет публичный administration/current-user API; OAuth/OIDC
authorization endpoints остаются standards-based.

| Публичный endpoint | Назначение | Правило доступа |
| --- | --- | --- |
| `GET /api/admin/users` | Список администрируемых пользователей | USER JWT с `SYSTEM_ADMIN` или `WMS_ADMIN`. |
| `POST /api/admin/users` | Создание администрируемого пользователя | Та же роль; только `SYSTEM_ADMIN` может создать `SYSTEM_ADMIN` account. |
| `GET /api/admin/users/{id}` | Чтение administrative user projection | USER JWT с `SYSTEM_ADMIN` или `WMS_ADMIN`. |
| `PUT /api/admin/users/{id}` | Version-fenced изменение профиля и авторизации | Та же роль; нужен `expectedVersion`, а `WMS_ADMIN` не управляет `SYSTEM_ADMIN`. |
| `GET /api/users/me` | Текущая access projection активного пользователя | USER Bearer JWT. |

Сервис возвращает единые Problem Details для invalid, unauthenticated,
forbidden, not-found и conflict случаев. Изменения admin profile, password и
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

Managed mobile inventory включает USER-only manager client и два указанных
WORKER-only WorkerApp/DriverApp clients. Изменение callback, scope или principal
type требует собственной revision и согласованного client release; refresh
token одного mobile client нельзя обменять через client ID другого.

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
`admin` / `admin`, local task-board client secret и ephemeral RSA signing key.
Эти defaults отключены в base configuration. Он также устанавливает direct
local issuer `http://localhost:9000`; production использует gateway issuer с
окончанием `/auth`.

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
