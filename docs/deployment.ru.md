# Развертывание RWMS: сервисы, panel и logistics

Дата проверки исходников: **19 октября 2026 года**. Базовая ревизия исследования: `996e72654abb230c8717f47fc4abd088258f6930`.

## 1. Назначение и границы инструкции

**Для новой установки на Ubuntu 24.04 amd64:** добавлен [интерактивный установщик](../tools/deploy/README.ru.md) с вводом домена/секретов, HTTPS, процентами этапов, журналами и режимами install/update/resume. Он создает отдельную managed Docker topology и не принимает существующий сервер автоматически. Ниже — общая карта компонентов и ручных операций, а не конфигурация этого установщика.

**Уточнение после проверки startup guards:** gateway с профилем `production` запрещает loopback downstream и требует WorkerApp certificate fingerprint; task-board вне dev/test требует Firebase и HTTPS для token/worker-credentials/warehouse endpoints. Приведенные ниже host/loopback примеры сами по себе не удовлетворяют этим ограничениям: для production нужны private DNS/HTTPS и соответствующие credentials. Установщик учитывает их без отключения защит.

Документ описывает состав системы, подготовку окружения, сборку, конфигурацию, порядок запуска и проверку развертывания. Он основан на текущих исходниках, а не на исторических планах.

**Это инструкция, не отчет о выполненном развертывании.** Доступ к реальному серверу, его systemd units, секретам, данным и действующей конфигурации Nginx в рамках исследования не проверялся. Команды ниже выполняет оператор на целевом хосте после подстановки его адресов и путей. Имена units и структура каталогов, помеченные как примеры, не являются обнаруженной конфигурацией сервера.

В репозитории нет единого production-манифеста, поднимающего всю систему:

- корневой [`compose.yaml`](../compose.yaml) — **только dev/test-инфраструктура**;
- Spring-сервисы собираются Gradle, media-service — Go;
- panel публикуется как статические сборки;
- `logistics/` имеет собственный Compose и VPS overlay.

На существующем сервере сначала изучите действующую схему и используйте ее; не заменяйте systemd контейнерами или наоборот ради этой инструкции. Для нового хоста ниже приведена согласованная схема: основные сервисы на loopback хоста, публичный Nginx и отдельный Compose планировщика.

## 2. Что именно развертывается

### 2.1. Два разных компонента logistics

| Компонент | Технология и данные | Назначение |
| --- | --- | --- |
| `services/logistics-service` | Java/Spring, отдельная PostgreSQL, Flyway | Владелец аренды, отгрузок, возвратов, перемещений и логистических workflow |
| `logistics/backend` | Python/FastAPI, отдельная PostGIS, Alembic | Планировщик маршрутов, ресурсов, смен и рабочих дней |
| `logistics/frontend` | React/Vite, отдельная статическая сборка | Рабочее место планировщика `/logistics-panel/` |
| `logistics/valhalla` | Valhalla и OSM-граф | Грузовая маршрутизация; не бизнес-БД |

Базы этих компонентов **не взаимозаменяемы**. Планировщик обращается к бизнес-сервису через отдельный OAuth-клиент и private planning API, не через общие таблицы.

### 2.2. Карта портов

Порты ниже взяты из `application.yaml` и Compose. Колонка БД — **локальные порты корневого Compose**, а не обязательная production-топология.

| Сервис | HTTP по умолчанию | БД dev: порт / имя и пользователь |
| --- | ---: | --- |
| auth-service | 9000; для приведенного VPS bridge задать **9002** | 5433 / `rwms_auth` |
| task-board-service | 8081 | 5434 / `rwms_task_board` |
| warehouse-service | 8083 | 5435 / `rwms_warehouse` |
| asset-service | 8086 | 5436 / `rwms_asset` |
| maintenance-service | 8087 | 5437 / `rwms_maintenance` |
| media-service | 8085; management 127.0.0.1:9095 | 5438 / `rwms_media` |
| inventory-service | 8089 | 5439 / `rwms_inventory` |
| logistics-service | 8090 | 5440 / `rwms_logistics` |
| dossier-service | 8091 | 5441 / `rwms_dossier` |
| assistant-service | 8092 | 5442 / `rwms_assistant` |
| analytics-service | 8093 | 5443 / `rwms_analytics` |
| api-gateway-service | 8088 | БД отсутствует |
| planner backend | 127.0.0.1:8000 на хосте | `db:5432/logistics` внутри отдельного Compose |
| planner frontend | 127.0.0.1:5173 на хосте | Нет |
| Valhalla | 127.0.0.1:8002 на хосте | Persistent volumes OSM и графа |

Дополнительно: MinIO dev — 9000/9001, Kafka dev — 9092. **Auth 9000 конфликтует с MinIO 9000**, поэтому нельзя запускать оба с этими defaults на одном хосте.

### 2.3. Публичная граница

```text
Браузер → HTTPS Nginx
  /                                  → panel/dist
  /admin/                            → panel/dist-admin
  /manager/                          → panel/dist-manager
  /auth/callback                     → основной panel index.html
  /auth/**, /api/**                   → gateway :8088 → сервис-владелец
  /logistics-panel/                  → planner frontend :5173
  /logistics-panel/api/**            → planner backend :8000/api/**
  /api/logistics-planner/v1/admin/**  → gateway → planner backend

Planner backend → private Nginx bridge → auth / logistics-service
Planner backend → PostGIS + Valhalla
Основные сервисы → свои PostgreSQL + Kafka; media → приватный MinIO
```

`/logistics/**` принадлежит основной panel: не перенаправлять этот namespace в самостоятельный планировщик. Публичные `/api/internal/**` запрещены. Browser URL всегда same-origin; внутренние адреса и сервисные секреты не передаются в JS.

## 3. Подготовка хоста

### Инструменты и внешние зависимости

- Linux, Nginx с TLS, Docker Engine и Compose v2 для планировщика/локальной инфраструктуры.
- **JDK 25**: версия закреплена build-logic. Gradle Wrapper — **9.5.1**, Spring Boot — **4.1.0**. Использовать wrapper, не случайный системный Gradle.
- **Go 1.25** для media; FFmpeg и FFprobe в runtime, если разрешена обработка видео.
- Для web-сборок — актуальный **Node.js 22 LTS не ниже 22.12**, npm и lockfiles соответствующих каталогов. Logistics Dockerfile использует `node:22-alpine`.
- Planner Dockerfile использует **Python 3.12**, PostgreSQL/PostGIS image `postgis/postgis:16-3.5-alpine`, Valhalla `3.8.3`.
- Корневое dev-окружение использует PostgreSQL 17, Kafka 4.3.1 и закрепленные MinIO images.
- Production media требует TLS к MinIO, HTTPS issuer и HTTPS JWKS, доверенные сертификаты в runtime.

Build-хосту нужны доступы к Gradle/Maven, npm, Go modules, PyPI и container registries; планировщику при первом запуске — к Geofabrik для OSM. В зависимости от функций браузеру нужны OpenFreeMap/Yandex, assistant — LLM provider. В текущей исследовательской среде доступ к части этих хостов ограничен; полная сборка/запуск здесь не выполнялись.

Точный CPU/RAM/disk sizing из исходников установить нельзя. Измеряйте heap и RSS сервисов, рост БД/MinIO и время построения OSM на стенде. Первое построение двух региональных графов занимает существенно больше времени и ресурсов, чем обычный рестарт; healthcheck Valhalla предусматривает `start_period: 30m`, но это не гарантия длительности.

### До первого изменения сервера

1. Зафиксировать текущую ревизию и состав релиза, проверить `git status --short`.
2. На существующем сервере изучить `systemctl list-units`, нужные `systemctl cat`, `docker compose ls`, `ss -lntp`, `nginx -T`. Не публиковать вывод с секретами.
3. Уточнить DNS, TLS, публичный origin, private subnet и отсутствие конфликтов портов.
4. Создать/проверить service accounts, права каталогов, firewall и резервные копии.
5. Определить окно обслуживания и критерии отката. Не менять live-систему только ради проверки инструкции.

Секреты хранить вне Git, например `/etc/rwms/<service>.env` с ограниченным доступом. Не копировать dev-пароли, не выводить resolved Compose config с секретами в CI-логи. Значения `VITE_*` публичны и встраиваются в bundle.

## 4. Инфраструктура и миграции

### Локальный стенд

Из корня репозитория:

```bash
docker compose --profile core up -d
docker compose --profile core ps
```

Это запускает 11 отдельных PostgreSQL, Kafka, MinIO и одноразовый `media-bucket-init` (создание `rwms-media` и включение versioning), **но не приложения**. RabbitMQ находится в отдельном compatibility-профиле `media-compat-rabbit`; он не заменяет Kafka в текущем media-service.

### Production

- Предоставить каждому владельцу отдельную БД и учетные данные; planner дополнительно требует PostGIS. Изолировать доступ к чужим БД.
- Подготовить Kafka topics из [`contracts/events/`](../contracts/events/) и bindings сервисов, включая DLT; согласовать partitions, retention, ACL и replication factor с выбранной инфраструктурой. Dev auto-create и replication factor 1 не считать production-политикой.
- Обеспечить versioned private MinIO bucket; дать media необходимые операции над объектами и версиями. Не выдавать браузеру bucket credentials и не публиковать консоль MinIO.
- Не считать локальный PLAINTEXT Kafka Compose готовой схемой TLS/SASL: такую конфигурацию нужно отдельно проверить с используемыми Java- и Go-клиентами.

### Три механизма миграций

| Контур | Как применять |
| --- | --- |
| Spring stateful | Flyway при старте из `src/main/resources/db/migration`; затем Hibernate `validate` |
| media-service | **Отдельный Flyway job до старта Go** из `services/media-service/db/migration` |
| planner | `alembic upgrade head`; Compose выполняет в indexer и backend, backend ждет завершения indexer |

Пример запуска установленного Flyway CLI для media из корня репозитория; URL/пользователь/пароль заранее заданы через защищенную конфигурацию `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD`:

```bash
flyway -locations=filesystem:services/media-service/db/migration validate
flyway -locations=filesystem:services/media-service/db/migration migrate
flyway -locations=filesystem:services/media-service/db/migration validate
```

`FLYWAY_URL` — JDBC URL, не Go DSN. Использовать все миграции текущего релиза, не ручной выбранный диапазон. На исследованной ревизии media содержит миграции до V24. Go runtime сам не выполняет migration, baseline или repair и проверяет ожидаемую схему до приема запросов.

Перед миграцией — проверенная резервная копия. Не редактировать примененные SQL, не запускать `repair`, `baseline`, `clean`, `down -v` или reset-db как средство исправления запуска. Для существующей непустой БД без истории Flyway требуется отдельная процедура принятия схемы, а не автопропуск проверки.

## 5. Конфигурация основных сервисов

Авторитетный полный перечень находится в `services/<name>/src/main/resources/application.yaml`; `application-dev.yaml` не является production-шаблоном. Приведенные ниже значения — основа настройки, не универсальный готовый `.env`.

### 5.1. Общие параметры

Для доменных Spring-сервисов:

```dotenv
AUTH_ISSUER=https://rwms.example.org/auth
AUTH_AUDIENCE=rwms-services
PANEL_ORIGIN=https://rwms.example.org
SERVER_ADDRESS=127.0.0.1
```

Заменить `rwms.example.org` на реальный origin. Для хостовых процессов установить `SERVER_ADDRESS=127.0.0.1` явно: один `server.port` не гарантирует приватный bind. Для контейнерной схемы нужны container bind и закрытая сеть вместо loopback.

Для каждого stateful Spring-сервиса задать `<PREFIX>_DB_URL` (JDBC), `<PREFIX>_DB_USERNAME`, `<PREFIX>_DB_PASSWORD`. Префиксы: `AUTH`, `TASK_BOARD`, `WAREHOUSE`, `ASSET`, `MAINTENANCE`, `INVENTORY`, `LOGISTICS`, `DOSSIER`, `ASSISTANT`, `ANALYTICS`.

Для event-driven функциональности явно задать `<PREFIX>_KAFKA_ENABLED=true` и `<PREFIX>_KAFKA_BROKERS` с адресами, доступными данному процессу. Многие defaults выключают интеграцию; успешный старт с ними не означает работоспособность полного продукта. Kafka advertised listeners также должны быть доступны из соответствующей сети.

Не включать `*_DEV_AUTH_BYPASS` и `AUTH_DEV_DEFAULT_CREDENTIALS`, не использовать Spring profile `dev` в production.

### 5.2. Auth и первоначальный доступ

```dotenv
AUTH_SERVICE_PORT=9002
AUTH_ISSUER=https://rwms.example.org/auth
PANEL_ORIGIN=https://rwms.example.org
PANEL_REDIRECT_URI=https://rwms.example.org/auth/callback
PANEL_POST_LOGOUT_REDIRECT_URI=https://rwms.example.org/
AUTH_SESSION_COOKIE_SECURE=true
AUTH_DEV_DEFAULT_CREDENTIALS=false
AUTH_SIGNING_KEY_STORE=/etc/rwms/keys/auth.p12
AUTH_SIGNING_KEY_ALIAS=rwms-auth
```

Отдельно задать секрет `AUTH_SIGNING_KEY_STORE_PASSWORD`, DB credentials, обязательный `TASK_BOARD_CLIENT_SECRET` и bootstrap credentials `AUTH_BOOTSTRAP_ADMIN_USERNAME` / `AUTH_BOOTSTRAP_ADMIN_PASSWORD` для первоначального создания администратора. Bootstrap не является механизмом произвольного сброса пароля существующего пользователя.

Ключ — постоянный RSA в PKCS#12. Пример создания **только при первоначальной установке**, если ключ еще не существует (keytool запросит пароль интерактивно):

```bash
keytool -genkeypair -alias rwms-auth -keyalg RSA -keysize 3072 \
  -storetype PKCS12 -keystore /etc/rwms/keys/auth.p12 \
  -validity 3650 -dname 'CN=RWMS Auth'
```

Ограничить права и обеспечить чтение runtime-пользователю. Не генерировать новый ключ при каждом релизе; backup ключа и продуманная ротация обязательны.

Admin web и rental-manager web имеют отдельные клиенты и callbacks:

- `rwms-panel` → `/auth/callback`;
- `rwms-admin-web` → `/admin/auth/callback`;
- `rwms-rental-manager-web` → `/manager/auth/callback`.

Два последних по умолчанию выводятся из `PANEL_ORIGIN`. Если используются другие origin, задать соответствующие `ADMIN_WEB_*` и `RENTAL_MANAGER_WEB_*`. Для включенных мобильных клиентов также переопределить их localhost origins/redirects; это не выполняется автоматически при изменении только `PANEL_ORIGIN`.

### 5.3. Межсервисные вызовы

Включение клиента в auth и включение зависимости в вызывающем сервисе — **две разные настройки**. Секрет одной identity должен совпадать с обеих сторон; scopes брать из регистрации клиента, не расширять до wildcard.

| Вызывающий сервис | Настройки auth-service | Настройки вызывающего сервиса |
| --- | --- | --- |
| task-board | Клиент уже включен; `TASK_BOARD_CLIENT_SECRET` | Такой же секрет, `AUTH_TOKEN_URI`, `AUTH_WORKER_CREDENTIALS_URL`, `WAREHOUSE_SERVICE_INTERNAL_BASE_URL`, `MEDIA_SERVICE_INTERNAL_BASE_URL`, `MAINTENANCE_SERVICE_URL`; также `TASK_BOARD_WORKER_OFFLINE_LEASE_SECRET`, `WORKER_ORIGIN`, `DRIVER_ORIGIN` |
| auth → warehouse | `AUTH_WAREHOUSE_CLIENT_ENABLED=true` | `AUTH_WAREHOUSE_VALIDATION_ENABLED=true`, `AUTH_WAREHOUSE_TOKEN_URI`, `AUTH_WAREHOUSE_CLIENT_SECRET`, `WAREHOUSE_SERVICE_INTERNAL_BASE_URL` |
| asset | `ASSET_WAREHOUSE_CLIENT_ENABLED=true` | `ASSET_WAREHOUSE_REGISTRY_ENABLED=true`, `ASSET_WAREHOUSE_TOKEN_URI`, `ASSET_WAREHOUSE_CLIENT_SECRET`, `WAREHOUSE_SERVICE_INTERNAL_BASE_URL`; для импорта `ASSET_MEDIA_IMPORT_ENABLED`, `MEDIA_SERVICE_INTERNAL_BASE_URL`, `ASSET_MEDIA_*` |
| maintenance | `MAINTENANCE_CLIENT_ENABLED=true` | `MAINTENANCE_DEPENDENCIES_ENABLED=true`, `AUTH_TOKEN_URI`, `MAINTENANCE_CLIENT_SECRET`, `{ASSET,TASK_BOARD,MEDIA,LOGISTICS,WAREHOUSE}_INTERNAL_BASE_URL` |
| inventory | `INVENTORY_CLIENT_ENABLED=true` | `INVENTORY_DEPENDENCIES_ENABLED=true`, `AUTH_TOKEN_URI`, `INVENTORY_CLIENT_SECRET`, `{WAREHOUSE,TASK_BOARD,ASSET,MAINTENANCE,LOGISTICS,MEDIA}_INTERNAL_BASE_URL` |
| logistics-service | `LOGISTICS_CLIENT_ENABLED=true` | `LOGISTICS_DEPENDENCIES_ENABLED=true`, `AUTH_TOKEN_URI`, `LOGISTICS_CLIENT_SECRET`, `{ASSET,WAREHOUSE,TASK_BOARD,MAINTENANCE,MEDIA}_SERVICE_URL` |
| logistics planner | `LOGISTICS_PLANNER_CLIENT_ENABLED=true`, `LOGISTICS_PLANNER_CLIENT_SECRET` | `RWMS_CLIENT_ID=logistics-planner`, тот же секрет как `RWMS_CLIENT_SECRET`; подробнее §8 |

Фигурные скобки в таблице — сокращение списка отдельных переменных, не синтаксис env-файла. Для хостовых сервисов token endpoint в выбранной схеме — `http://127.0.0.1:9002/oauth2/token`. Для task-board в этой схеме задать `AUTH_WORKER_CREDENTIALS_URL=http://127.0.0.1:9002/api/internal/worker-credentials` (path подтвержден [конфигурацией task-board](../services/task-board-service/src/main/resources/application-dev.yaml)); не подменять его URL gateway.

Дополнительно:

- assistant требует `LLM_API_KEY` при стандартном `LLM_REQUIRE_API_KEY_ON_STARTUP=true`; задать `LLM_BASE_URL`, `LLM_MODEL` и **`LOGISTICS_BASE_URL=http://127.0.0.1:8090`** для хостовой схемы. Default `http://logistics-service:8080` здесь не подойдет.
- logistics требует замены `CLIENT_PRESENTATION_TOKEN_SECRET`: встроенное local-значение не подходит для production.
- `LOGISTICS_CUSTOMER_TEST_CHANGE_PAYMENT_ENABLED` по умолчанию **true**: для production явно установить **false** и отдельно согласовать реальный платежный сценарий.
- Customer delivery включать только после настройки реальных складов/координат, `LOGISTICS_CUSTOMER_VALHALLA_URL` и `LOGISTICS_CUSTOMER_ROUTING_DATA_VERSION`; фиктивные UUID и координаты не использовать.

### 5.4. Gateway

Пример для основных сервисов на одном хосте:

```dotenv
API_GATEWAY_PORT=8088
SERVER_ADDRESS=127.0.0.1
GATEWAY_PUBLIC_BASE_URI=https://rwms.example.org
GATEWAY_TRUSTED_PROXY_ADDRESSES=127.0.0.1,::1
AUTH_ISSUER=https://rwms.example.org/auth
AUTH_AUDIENCE=rwms-services
PANEL_ORIGIN=https://rwms.example.org
AUTH_SERVICE_URL=http://127.0.0.1:9002
TASK_BOARD_SERVICE_URL=http://127.0.0.1:8081
WAREHOUSE_SERVICE_URL=http://127.0.0.1:8083
ASSET_SERVICE_URL=http://127.0.0.1:8086
MAINTENANCE_SERVICE_URL=http://127.0.0.1:8087
MEDIA_SERVICE_URL=http://127.0.0.1:8085
INVENTORY_SERVICE_URL=http://127.0.0.1:8089
LOGISTICS_SERVICE_URL=http://127.0.0.1:8090
LOGISTICS_PLANNER_URL=http://127.0.0.1:8000
DOSSIER_SERVICE_URL=http://127.0.0.1:8091
ANALYTICS_SERVICE_URL=http://127.0.0.1:8093
ASSISTANT_SERVICE_URL=http://127.0.0.1:8092
```

`CAD_SERVICE_URL` — отдельная необязательная интеграция, не развертывается этой инструкцией. До включения трафика убедиться, что публичный issuer/discovery доступен самим JVM-сервисам и сертификат доверен. При первоначальном старте обеспечить доступность цепочки Nginx → gateway → auth для discovery, даже если остальные upstream пока поднимаются.

### 5.5. Media: отдельная конфигурация Go

Обязательные группы из [`internal/config/config.go`](../services/media-service/internal/config/config.go):

| Группа | Переменные |
| --- | --- |
| Runtime | `MEDIA_RUNTIME_PROFILE=production`, `MEDIA_HTTP_ADDRESS=127.0.0.1:8085`, `MEDIA_MANAGEMENT_ADDRESS=127.0.0.1:9095`, уникальный `MEDIA_INSTANCE_ID` |
| PostgreSQL | `MEDIA_DATABASE_URL` — PostgreSQL DSN, **не JDBC** |
| JWT | `MEDIA_AUTH_ISSUER`, `MEDIA_AUTH_JWKS_URL`, `MEDIA_AUTH_AUDIENCE=rwms-services` |
| Object store | `MEDIA_MINIO_ENDPOINT`, `MEDIA_MINIO_ACCESS_KEY`, `MEDIA_MINIO_SECRET_KEY`, `MEDIA_MINIO_BUCKET`, `MEDIA_MINIO_USE_SSL=true` |
| Kafka | `MEDIA_KAFKA_BROKERS`; канонические topic/group defaults не переименовывать |
| Upload/processing | `MEDIA_MAX_UPLOAD_BYTES`, `MEDIA_ALLOWED_MIME_TYPES`, `MEDIA_UPLOAD_EXPIRY`, `MEDIA_PROCESSING_TIMEOUT` |
| Если включено видео | `MEDIA_MAX_VIDEO_DURATION`, `MEDIA_MAX_VIDEO_OUTPUT_BYTES`, `MEDIA_ALLOWED_VIDEO_CODECS`; `MEDIA_FFMPEG_EXECUTABLE` / `MEDIA_FFPROBE_EXECUTABLE` при нестандартном PATH |

Пример duration: `15m`; positive size задается в байтах. `MEDIA_MAX_VIDEO_OUTPUT_BYTES` не должен превышать `MEDIA_MAX_UPLOAD_BYTES`. Endpoint MinIO — адрес сервера для SDK, например `minio.internal.example.org:443`, SSL управляется отдельным флагом. Форматы MIME/codecs и ограничения сверять с валидатором конфигурации.

В production issuer и JWKS **оба HTTPS**, MinIO SSL обязателен. HTTP private JWKS планировщика из §8 нельзя автоматически копировать в media. Management bind допускает только числовой loopback с ненулевым портом.

## 6. Сборка и запуск services

Выполнять из корня чистого согласованного рабочего дерева. Пример сборки всех 11 Java deployables:

```bash
bash ./gradlew \
  :services:auth-service:bootJar \
  :services:api-gateway-service:bootJar \
  :services:task-board-service:bootJar \
  :services:warehouse-service:bootJar \
  :services:asset-service:bootJar \
  :services:maintenance-service:bootJar \
  :services:inventory-service:bootJar \
  :services:logistics-service:bootJar \
  :services:dossier-service:bootJar \
  :services:analytics-service:bootJar \
  :services:assistant-service:bootJar

(cd services/media-service && \
  go build -trimpath -o ../../artifacts/media-service ./cmd/media-service)
```

`artifacts/` здесь — пример локального staging-каталога, не исходный код: не добавлять его в Git. Spring executable JAR находится в `services/<name>/build/libs/`; выбирать bootJar, не `*-plain.jar`. Сохранять SHA исходников, checksums артефактов и использованные версии инструментов.

На новом хосте можно использовать systemd. Следующий unit — **шаблон**, пути и пользователь должны быть созданы оператором, JAR скопирован из bootJar текущего релиза:

```ini
[Unit]
Description=RWMS warehouse service
After=network-online.target
Wants=network-online.target

[Service]
User=rwms
Group=rwms
WorkingDirectory=/opt/rwms/current
EnvironmentFile=/etc/rwms/warehouse-service.env
ExecStart=/usr/bin/java -jar /opt/rwms/current/warehouse-service.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=60
UMask=0077
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

Путь `/usr/bin/java` должен указывать на Java 25. Для media `ExecStart` указывает на Go binary, env — media; FFmpeg/FFprobe доступны runtime-пользователю. Heap/лимиты ресурсов задаются после измерений, не одинаковой случайной величиной для всех сервисов. `After=network-online.target` не означает готовность PostgreSQL/Kafka.

Рекомендуемый порядок запуска:

1. PostgreSQL, Kafka, MinIO/TLS и bucket versioning; проверить доступность.
2. Миграции media отдельным job; backup перед изменением остальных схем.
3. Auth, gateway и публичная issuer/discovery цепочка.
4. Warehouse, asset, task-board, media; далее maintenance, inventory, logistics. Между доменами есть обратные зависимости: это порядок старта, не доказательство полной готовности каждого промежуточного этапа.
5. Dossier, analytics, assistant; проверить event consumers и service credentials.
6. Планировщик и web-сборки; открыть трафик только после сквозного smoke.

Для уже существующего сервера перезапускать реальные unit names, установленные оператором. `bootRun` — средство разработки, не production supervisor.

## 7. Сборка и публикация panel

Из корня:

```bash
npm --prefix panel ci
npm --prefix panel run typecheck
npm --prefix panel run build
npm --prefix panel run build:admin
npm --prefix panel run build:manager
```

Результаты:

| Артефакт | Публичный путь |
| --- | --- |
| `panel/dist/` | `/` |
| `panel/dist-admin/` | `/admin/` |
| `panel/dist-manager/` | `/manager/` |

Обычный `npm run build` не собирает два остальных entry points. `/admin/logistics` необходим для настройки планировщика, поэтому admin-сборка входит в полный web-релиз. `npm run dev` и `vite preview` не заменяют production Nginx.

Для нового хоста возможна структура `/opt/rwms/current/www/`: содержимое `dist` в корне, содержимое `dist-admin` в `admin/`, `dist-manager` в `manager/`. Собрать новое дерево целиком и переключить release pointer атомарно; не удалять старые assets во время активных запросов.

Минимальные **фрагменты** маршрутизации внутри существующего TLS server block (не полный Nginx config):

```nginx
root /opt/rwms/current/www;
index index.html;

# Callback принадлежит SPA, не authorization server.
location = /auth/callback { try_files /index.html =404; }
location = /admin { return 308 /admin/; }
location = /manager { return 308 /manager/; }
location ^~ /admin/assets/ { try_files $uri =404; }
location ^~ /manager/assets/ { try_files $uri =404; }
location ^~ /assets/ { try_files $uri =404; }
location /admin/ { try_files $uri $uri/ /admin/index.html; }
location /manager/ { try_files $uri $uri/ /manager/index.html; }
location ^~ /api/internal/ { return 404; }

location /auth/ {
    proxy_pass http://127.0.0.1:8088;
    proxy_set_header Host $http_host;
    proxy_set_header X-Forwarded-Host $http_host;
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header X-Forwarded-Port 443;
    proxy_set_header X-Forwarded-For $remote_addr;
}
location /api/ {
    proxy_pass http://127.0.0.1:8088;
    proxy_http_version 1.1;
    proxy_set_header Connection '';
    proxy_set_header Host $http_host;
    proxy_set_header X-Forwarded-Host $http_host;
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header X-Forwarded-Port 443;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 1h;
}
location / { try_files $uri $uri/ /index.html; }
```

Добавить snippets planner из §8. Конкретный upload limit Nginx/gateway согласовать с media: стандартный Nginx limit может дать `413`. Для крупных потоковых загрузок отдельно оценить `proxy_request_buffering off`. Не использовать долгий immutable cache для HTML; для hashed assets его можно включить. Проверять MIME и `404` отсутствующих JS, а не возвращать для них index.html.

Установить безопасные access-log snippets из [`nginx/`](../nginx/README.ru.md): query strings, Referer и capability tokens не должны попадать в журналы. TLS/certificate directives, HTTP→HTTPS redirect, firewall и security headers настраиваются для конкретного хоста. Перед reload — `nginx -t`; обычная замена web-артефактов не требует изменения Nginx.

## 8. Развертывание самостоятельного logistics planner

### 8.1. Private bridge на VPS

Репозиторный [`nginx-rwms-private-bridge.conf`](../logistics/deploy/nginx-rwms-private-bridge.conf) ожидает:

- external Docker network `rwms-logistics-private`, gateway `172.21.0.1`;
- `172.21.0.1:19002` → host auth `127.0.0.1:9002`, только `/oauth2/token` и `/oauth2/jwks`;
- `172.21.0.1:18090` → host logistics `127.0.0.1:8090`, только `/api/internal/logistics/v1/planning/`.

Сначала проверить, что subnet не занят. Если сеть отсутствует и диапазон согласован, создать **один раз**:

```bash
docker network inspect rwms-logistics-private
# Только если сеть отсутствует и диапазон свободен:
docker network create --driver bridge --subnet 172.21.0.0/16 \
  --gateway 172.21.0.1 rwms-logistics-private
```

Если сеть уже существует, проверить IPAM, а не пересоздавать ее. Установить private Nginx config и drop-in [`nginx.service.d/rwms-logistics-private-bridge.conf`](../logistics/deploy/nginx.service.d/rwms-logistics-private-bridge.conf), чтобы Nginx стартовал после Docker. Firewall разрешает доступ к этим двум listeners только из доверенного bridge subnet. При выборе другого subnet изменить согласованно network, listen, allow и env.

В публичный TLS server включить [`nginx-public-path.conf`](../logistics/deploy/nginx-public-path.conf). Он публикует `/logistics-panel/`, отключает buffering API и закрывает `/logistics-panel/api/admin` и его подмаршруты. Административный API должен идти через gateway `/api/logistics-planner/v1/admin/**`, не напрямую.

### 8.2. Env планировщика

Создать файл вне Git, например `/etc/rwms/logistics.env`, на основе [`logistics/.env.example`](../logistics/.env.example), затем **переопределить defaults**:

```dotenv
FRONTEND_PORT=5173
BACKEND_PORT=8000
POSTGRES_DB=logistics
POSTGRES_USER=logistics
# POSTGRES_PASSWORD и согласованный DATABASE_URL задаются секретами.
ROUTING_PROVIDER=valhalla
VALHALLA_ENABLED=true
VALHALLA_URL=http://valhalla:8002
VALHALLA_HOST_PORT=8002
AUTH_ISSUER=https://rwms.example.org/auth
AUTH_JWKS_URL=http://172.21.0.1:19002/oauth2/jwks
AUTH_AUDIENCE=rwms-services
AUTH_PANEL_CLIENT_ID=rwms-panel
RWMS_LOGISTICS_BASE_URL=http://172.21.0.1:18090
RWMS_TOKEN_URL=http://172.21.0.1:19002/oauth2/token
RWMS_CLIENT_ID=logistics-planner
RWMS_SYNC_ENABLED=true
RWMS_CAPACITY_PUBLISH_ENABLED=true
VITE_APP_BASE_PATH=/logistics-panel/
VITE_API_BASE_URL=/logistics-panel/api
VITE_MAP_STYLE_URL=https://tiles.openfreemap.org/styles/liberty
```

- `DATABASE_URL` имеет вид `postgresql+psycopg://logistics:<URL-encoded-password>@db:5432/logistics`. Его нужно обновить вместе с `POSTGRES_PASSWORD`; Compose default DSN не собирается автоматически из измененного пароля. В существующем volume смена `POSTGRES_PASSWORD` не меняет пароль PostgreSQL-роли: ротация требует отдельной согласованной процедуры.
- `RWMS_CLIENT_SECRET` равен `LOGISTICS_PLANNER_CLIENT_SECRET` в auth. В auth включить `LOGISTICS_PLANNER_CLIENT_ENABLED=true`; method — `client_secret_basic`, scope — ровно `logistics.planning`.
- `RWMS_SYNC_ENABLED` и `RWMS_CAPACITY_PUBLISH_ENABLED` включать после проверки интеграции; для первого изолированного запуска держать оба `false`, чтобы не публиковать данные в RWMS до приемки.
- `OSM_DATA_VERSION` задать как реальную идентичность подготовленного набора, сохранить source URLs/checksums. Не оставлять `unknown` или дату из примера, если данные другие.
- Geosuggest/Geocoder: отдельные server-side `YANDEX_GEOSUGGEST_API_KEY`, `YANDEX_GEOCODER_API_KEY`. Browser Maps v3 key — отдельная настройка через `/admin/logistics`, не эти env-переменные.
- VPS overlay принудительно задает build paths `/logistics-panel/` и `/logistics-panel/api`. `VITE_*` меняют bundle только при пересборке frontend.

### 8.3. Запуск

Из `logistics/`, всегда использовать **те же project name, env и набор файлов**, в том числе при обновлениях:

```bash
cd logistics
docker compose --env-file /etc/rwms/logistics.env \
  -f docker-compose.yml -f docker-compose.vps.yml config --quiet

docker compose --env-file /etc/rwms/logistics.env \
  -f docker-compose.yml -f docker-compose.vps.yml up -d --build

docker compose --env-file /etc/rwms/logistics.env \
  -f docker-compose.yml -f docker-compose.vps.yml ps -a

docker compose --env-file /etc/rwms/logistics.env \
  -f docker-compose.yml -f docker-compose.vps.yml \
  logs --tail=100 backend truck-restrictions-indexer valhalla
```

Compose сам выстраивает цепочку:

1. `db` становится healthy; `osrm-download` скачивает **два PBF**, несмотря на legacy-название.
2. `valhalla-init` готовит ссылки; Valhalla строит/проверяет граф; entrypoint обеспечивает потолок изохрон **720 минут / 12 контуров**.
3. `truck-restrictions-indexer` применяет Alembic и индексирует ограничения в PostGIS.
4. Backend ждет здоровых DB/Valhalla и успешного indexer, применяет Alembic, запускает Uvicorn на `0.0.0.0:8000` внутри контейнера.
5. Frontend запускается после healthy backend. На хост оба приложения опубликованы только на loopback.

Нормальное состояние одноразовых init/indexer jobs — `Exited (0)`, не постоянный running. `osrm`, `osrm-prepare` находятся за профилем `legacy-routing`: для основной грузовой схемы их не включать. `mock` годится только для тестов, не как fallback при проблемах Valhalla.

Frontend release должен включать **оба** `index.html` и `yandex-map.html`; Docker build уже учитывает оба entry points.

### 8.4. Обновление OSM

Загрузчик пропускает уже существующий непустой PBF. **Одна смена URL в env не скачает файл заново**. Обновление исходников должно быть отдельной контролируемой операцией с backup, проверкой checksum, согласованным обновлением обоих наборов/manifest и `OSM_DATA_VERSION`. Изменение manifest вызывает перестройку производного графа; индекс ограничений использует версию данных. Не удалять production volumes для принудительного обновления. После него проверить truck routes и изохроны, а не только `/status`.

## 9. Приемка после развертывания

### Технические проверки

На хосте (адреса соответствуют схеме выше):

```bash
curl --fail --silent --show-error http://127.0.0.1:9002/actuator/health
curl --fail --silent --show-error http://127.0.0.1:8088/actuator/health/readiness
curl --fail --silent --show-error http://127.0.0.1:8085/health/ready
curl --fail --silent --show-error http://127.0.0.1:8000/api/health
curl --fail --silent --show-error http://127.0.0.1:8002/status
curl --fail --silent --show-error https://rwms.example.org/auth/.well-known/openid-configuration
```

Повторить Spring health/readiness для **каждого** сервиса. `/actuator`, media `/metrics` на 9095, БД, Kafka, MinIO и Valhalla не публиковать наружу. Health alone не подтверждает все downstream integrations, права, outbox или полноту данных.

### Сквозной checklist

- [ ] `/`, `/admin/`, `/manager/`, `/logistics-panel/` отдают соответствующие сборки; deep links работают после reload.
- [ ] OIDC discovery содержит точный public HTTPS issuer и endpoints, не внутренние IP.
- [ ] Вход в panel проходит через `/auth/callback`; refresh и logout работают.
- [ ] Отдельные admin/manager callbacks возвращаются в соответствующие SPA; роли/scopes соблюдаются.
- [ ] Вход из отдельной вкладки planner проходит через общую panel OIDC-сессию и возвращает в `/logistics-panel/`.
- [ ] Без токена защищенные API отклоняют запрос, запрещенные роли/склады не получают данные. Не проверять защиту только скрытыми кнопками UI.
- [ ] Канонические склады, их координаты и timezone загружаются; административные настройки planner сохраняются через gateway.
- [ ] На тестовых данных работают маршруты `costing=truck`, ограничения и включаемые изохроны; ошибки карты не превращаются в выдуманные маршруты.
- [ ] После разрешения интеграции planner получает client_credentials token и demand feed; capacity/confirmed-plan flow проверен на согласованных тестовых данных.
- [ ] Upload/finalize/read media проходит до READY, объекты остаются приватными, versioning включен.
- [ ] SSE работает без накопления событий в Nginx; отсутствующий JS возвращает 404, не HTML с кодом 200.
- [ ] Нет Flyway/Alembic errors, растущего Kafka lag, зависших outbox/inbox и необъясненных DLT; assistant проверен реальным запросом к разрешенному LLM.
- [ ] После согласованного reboot восстанавливаются private bridge, Nginx и runtime processes.

Любые мутирующие smoke-тесты — только на разрешенном тестовом наборе. Playwright planner по умолчанию read-only; `RWMS_E2E_DISPOSABLE_DATABASE=true` допускается лишь для действительно disposable БД, не production.

## 10. Обновление, резервирование и откат

1. Зафиксировать revision работающего релиза и полный diff до нового, включая все изменившиеся deployables, а не только текущую задачу.
2. Согласовать/закоммитить рабочее дерево, выполнить focused tests и собрать артефакты из него. Не использовать старый release/source как альтернативный checkout для новой сборки.
3. До миграций сохранить service-owned БД, planner PostGIS, MinIO **с версиями объектов**, auth keystore, конфигурацию/секреты и OSM manifests. Предусмотреть recovery Kafka offsets/events; один DB dump не является полным backup распределенного workflow.
4. Проверить восстановление в изолированном окружении; определить RPO/RTO. Резервные копии шифровать и хранить отдельно от хоста.
5. Развернуть новый набор артефактов через существующий механизм, выполнить миграции и запуск в порядке зависимостей, затем §9.
6. Зафиксировать SHA, checksums, даты, версии миграций, OSM version, результаты smoke и состояние units/containers.

**Откат кода не равен откату БД.** Предыдущий binary можно возвращать только при совместимости с уже примененной схемой и контрактами. Иначе остановить прием изменений и применить согласованный recovery/forward-fix план; восстановление БД уничтожает изменения после backup и требует явного разрешения. Не выполнять автоматический Alembic downgrade или ручное удаление Flyway history.

Для planner хранить immutable image tags/digests предыдущего релиза: повторный `up --build` из нового кода сам по себе не оставляет надежный механизм возврата старого образа. Volumes сохранять при обновлениях. Корневой `docker compose down -v`, planner `make reset-db` и удаление bucket не являются операциями обычного релиза.

## 11. Типичные неисправности и найденные расхождения

| Симптом / риск | Проверка и действие |
| --- | --- |
| Auth не стартует / address in use | Default auth 9000 совпадает с MinIO 9000; bridge ожидает auth 9002. Выбрать согласованный порт во всех конфигах |
| 401 после входа | Сопоставить `iss`, audience, client_id, scopes, время хоста, JWKS и persistent signing key; не отключать JWT validation |
| Redirect идет на localhost | Проверить auth origins/redirects, `GATEWAY_PUBLIC_BASE_URI` и trusted forwarding headers |
| Planner не видит auth | Проверить external bridge, 19002, firewall и private JWKS; host loopback не является loopback контейнера |
| Admin API planner дает 404 напрямую | Это ожидаемое закрытие `/logistics-panel/api/admin/**`; использовать gateway admin route |
| Planner DB authentication failed | `POSTGRES_PASSWORD` и `DATABASE_URL` разошлись либо пароль уже существующей роли не изменен |
| Backend planner долго не стартует | Посмотреть download, indexer и Valhalla; не повторять бесконтрольно полный build и не переключать на car/mock |
| Media fail-closed при старте | Проверить полную Flyway history, обязательные limits, TLS MinIO и HTTPS issuer/JWKS |
| UI пустой / MIME module error | Проверить base path, наличие hashed assets, SPA fallback и все три сборки panel |
| 413 / обрыв upload или SSE | Согласовать body limits/timeouts gateway/Nginx/media, отключить buffering SSE |
| Сервис healthy, но workflow не выполняется | Проверить enabled-флаги Kafka/dependencies, M2M clients/secrets, outbox/consumer lag и права склада |
| После смены OSM URL используются старые данные | Download job пропускает существующий PBF; нужен контролируемый dataset update, не только env change |

Расхождения в документации репозитория, которые важно учитывать:

- Комментарий `logistics/.env.example` еще упоминает `/logistics-simulator/`. Действующие VPS overlay и Nginx snippets используют **`/logistics-panel/`**.
- В конце logistics README осталась формулировка об отсутствии production auth, но текущий backend и раздел VPS описывают JWT/JWKS-проверку операторов. Не считать planner анонимным приложением и не отключать проверку.
- Старый verification-раздел media README упоминает диапазон до V19; текущие SQL и основной migration-раздел доходят до **V24**. Применять весь каталог текущего релиза.
- Корневой Compose прямо объявлен dev/test-only. Не описывать его как готовое production-развертывание всех services.

Эти расхождения отмечены, но runtime-конфигурация и исходный код данным документом не изменялись.

## 12. Источники для сопровождения документа

- [Корневой README](../README.ru.md), [каталог сервисов](../services/README.md), [архитектура](project-knowledge/architecture.md).
- [Gradle versions](../gradle/libs.versions.toml), [settings](../settings.gradle.kts), [build-logic](../build-logic/).
- [Auth config](../services/auth-service/src/main/resources/application.yaml), [gateway config](../services/api-gateway-service/src/main/resources/application.yaml).
- [Logistics-service config](../services/logistics-service/src/main/resources/application.yaml), [media README](../services/media-service/README.md), [media config validator](../services/media-service/internal/config/config.go).
- [Panel package scripts](../panel/package.json), [admin Vite config](../panel/vite.admin.config.ts), [manager Vite config](../panel/vite.manager.config.ts).
- [Planner Compose](../logistics/docker-compose.yml), [VPS overlay](../logistics/docker-compose.vps.yml), [env example](../logistics/.env.example), [backend Dockerfile](../logistics/backend/Dockerfile), [frontend Dockerfile](../logistics/frontend/Dockerfile).
- [Planner README](../logistics/README.md), [public Nginx routes](../logistics/deploy/nginx-public-path.conf), [private bridge](../logistics/deploy/nginx-rwms-private-bridge.conf), [безопасные access logs](../nginx/README.ru.md).

При изменении портов, issuer, service registrations, миграций, build entry points или Compose dependencies обновлять этот runbook вместе с соответствующим runtime-изменением.
