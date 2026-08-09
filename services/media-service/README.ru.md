# RWMS Media Service

[English version](README.md)

`media-service` — единственный stateful Go-сервис RWMS, который владеет
авторизацией загрузки, метаданными, неизменяемыми поколениями объектов в
MinIO, обработкой изображений/видео и фактами о медиа. PostgreSQL — источник
истины для состояния и replay; Kafka используется только как транспорт с
семантикой at-least-once. Браузер загружает и читает байты исключительно через
аутентифицированные same-origin API-пути. MinIO остаётся приватным: клиенту не
возвращаются storage origin, object key, signed URL и учётные данные.

Каждое агрегатное событие и последний snapshot хранят точное полное локальное
состояние, защищённое SHA-256: asset, upload session, processing jobs и
variants. Shadow replay читает только append-only event stream, проверяет
непрерывность версий stream/head/snapshot и точность байтов, после чего
сравнивает восстановленное состояние с live projection. Санитизированные факты
Kafka — отдельное представление и никогда не являются источником replay.

Для изображения сервис создаёт `SMALL`, `MEDIUM`, `LARGE` в WebP и
авторизуемый `ORIGINAL`, не меняя ориентацию пикселей, переданную клиентом.
Видео хранится только как проверенный original после FFprobe. Неподтверждённые
размеры видео остаются `NULL` в SQL.

## Зачем нужен сервис

Медиа в RWMS — не общий «файловый бакет». Фото или видео часто является
операционным доказательством: оно должно быть связано с конкретным владельцем
и складом, переживать повторные запросы без дубликатов и быть доступным без
раскрытия прав к object storage. Один выделенный сервис даёт всем доменам
единый безопасный жизненный цикл медиа, не забирая у них владение их
бизнес-агрегатами.

Такое устройство решает четыре практические проблемы:

- **Приватное хранилище.** Браузер и другие домены не получают адрес MinIO,
  object key, signed URL или credential.
- **Корректные повторы.** Один `Idempotency-Key` создаёт один логический media
  asset. Если незавершённая сессия истекла, для того же asset выдаётся новая
  сессия; completed content нельзя открыть заново или перезаписать.
- **Авторитетный доступ.** Публичные read/mutation требуют USER/WORKER JWT,
  актуальный local owner proof и доступ к складу. Пара owner/warehouse,
  присланная вызывающей стороной, сама по себе не считается доказательством.
- **Восстановимая доставка.** PostgreSQL владеет состоянием и точным replay;
  Kafka переносит at-least-once факты через transactional outbox и
  идемпотентных consumers. Недоступность брокера не превращает Kafka в базу
  данных медиа.

## Как работает жизненный цикл

Каноническое описание HTTP-границы находится в
[`contracts/openapi/media-service.yaml`](../../contracts/openapi/media-service.yaml).
Интерактивные клиенты используют публичные same-origin media routes через API
gateway; private `/api/internal/**` доступны только сервисам.

1. Клиент создаёт upload session с `Idempotency-Key`, доказанным owner context,
   MIME type, длиной и SHA-256.
2. Клиент передаёт байты в возвращённый same-origin content path. Сервис
   сериализует ingress для этой session, сверяет заявленные данные и фиксирует
   точную приватную версию объекта MinIO.
3. Клиент завершает session тем же idempotency key. Upload fact и processing
   request коммитятся атомарно.
4. Kafka worker создаёт canonical original и WebP-variants для изображения
   либо проверяет и копирует original видео. После этого публикуется безопасный
   invalidation-сигнал, а клиенты обновляют только свою scoped projection.
5. Scoped read отдаёт ровно закреплённую версию объекта через media-service с
   `private, no-store`. Логическое удаление меняет PostgreSQL и создаёт один
   факт, но не удаляет версии байтов физически.

Намеренно нет прямой загрузки браузером в MinIO и изменяемых public URL. Иначе
раскрываются storage-права, становятся неоднозначными версии объекта и итог
повтора запроса, а смена владельца может состязаться с чтением. Здесь
авторизация, фиксация версии и проверка владения — одна согласованная операция
на сервере.

## Стартовая проверка схемы

Flyway выполняется вне процесса. До запуска примените по порядку
`db/migration/V1__media_schema.sql`,
`db/migration/V2__media_runtime_recovery.sql`,
`db/migration/V3__inventory_owner_proof.sql`,
`db/migration/V4__cabin_owner_bindings.sql`,
`db/migration/V4_1__prepare_legacy_photo_folder_backfill.sql`,
`db/migration/V5__media_photo_folders.sql`,
`db/migration/V5_1__restore_runtime_source_guard.sql`,
`db/migration/V6__service_owner_proofs_and_soft_delete.sql`,
`db/migration/V7__dynamic_cabin_owner_projection.sql`,
`db/migration/V8__task_board_worker_media.sql`,
`db/migration/V9__asset_import_worker.sql` и
`db/migration/V10__canonical_cabin_photo_library.sql`, затем
`db/migration/V11__bounded_media_processing_recovery.sql`.

Go-приложение не выполняет миграции, baseline, repair и не принимает молча
чужую непустую базу.

- Новая local/test база мигрируется от V1 до V11.
- База с точной историей V10 обновляется применением V11.
- `baselineOnMigrate` должен оставаться `false`; непустая база без истории
  миграций отклоняется.
- На старте и readiness проверяются успешные строки Flyway, их версии,
  descriptions, SQL type и точные checksums. Пропущенная, лишняя, неуспешная
  или изменённая миграция блокирует HTTP ingress.
- В JSONB outbox V1 нет доказанных исходных wire bytes. Он не копируется в
  активный transport outbox V2: опубликованные строки фиксируются как
  `PUBLISHED_HISTORICAL`, неопубликованные evidence-hash-ятся, quarantine-ятся
  как `UNPUBLISHED_QUARANTINED` и не могут быть опубликованы повторно.

V1 и legacy union event schema — только compatibility evidence; их нельзя
редактировать.

## Обязательная конфигурация

Все resource bounds задаются явно: у production намеренно нет «разумных»
дефолтов.

| Переменная | Значение |
|---|---|
| `MEDIA_RUNTIME_PROFILE` | Обязательный профиль: `production` или `local-test` |
| `MEDIA_DATABASE_URL` | PostgreSQL URL базы, которой владеет media-service |
| `MEDIA_AUTH_ISSUER` | Точный URL issuer JWT |
| `MEDIA_AUTH_JWKS_URL` | URL JWKS issuer-а |
| `MEDIA_MINIO_ENDPOINT` | Host и port MinIO |
| `MEDIA_MINIO_ACCESS_KEY` | Access key MinIO |
| `MEDIA_MINIO_SECRET_KEY` | Secret key MinIO |
| `MEDIA_MINIO_BUCKET` | Выделенный bucket медиа |
| `MEDIA_MINIO_USE_SSL` | Явное `true` или `false` |
| `MEDIA_MAX_UPLOAD_BYTES` | Максимальный размер неизменяемого source |
| `MEDIA_MAX_DECODED_PIXELS` | Максимум декодированных пикселей изображения |
| `MEDIA_MAX_IMAGE_OUTPUT_BYTES` | Максимальный размер закодированного image output |
| `MEDIA_ALLOWED_MIME_TYPES` | Allowlist через запятую: JPEG, PNG, WebP, MP4, WebM |
| `MEDIA_UPLOAD_EXPIRY` | Время жизни ограниченной upload capability, например `5m` |
| `MEDIA_PROCESSING_TIMEOUT` | Timeout одного processing job |
| `MEDIA_KAFKA_BROKERS` | Kafka bootstrap addresses через запятую |
| `MEDIA_KAFKA_INVENTORY_TOPIC` | Канонический topic inventory facts |
| `MEDIA_KAFKA_INVENTORY_OWNER_GROUP` | Выделенная media consumer group для owner proof inventory |
| `MEDIA_KAFKA_INVENTORY_OWNER_DLT_TOPIC` | Media-owned DLT inventory owner consumer |
| `MEDIA_KAFKA_ASSET_RENTAL_ITEM_TOPIC` | Канонический topic asset rental-item facts |
| `MEDIA_KAFKA_CABIN_OWNER_GROUP` | Выделенная consumer group dynamic CABIN owner |
| `MEDIA_INSTANCE_ID` | Уникальный безопасный ASCII идентификатор lease/fence owner |

Если разрешён video MIME type, обязательны также `MEDIA_MAX_VIDEO_DURATION` и
`MEDIA_ALLOWED_VIDEO_CODECS` через запятую.

Необязательные параметры: `MEDIA_HTTP_ADDRESS` (по умолчанию `:8085`),
`MEDIA_MANAGEMENT_ADDRESS` (по умолчанию `127.0.0.1:9095`: явный числовой
IPv4- или IPv6-loopback host с ненулевым TCP port) и `MEDIA_AUTH_AUDIENCE`
(по умолчанию `rwms-services`). Processing group и topics имеют канонические
значения и не должны меняться:
`media-service-processing-v1`, `rwms.media.media.v1` и
`rwms.media.processing.v1`. Терминальная ошибка processing публикует только
hash-only запись в
`rwms.media.processing.v1.media-service-processing-v1.dlt`.

Значения inventory owner consumer также зафиксированы:
`rwms.inventory.session.v1`, `media-service-inventory-owner-v1` и
`rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt`.
Для CABIN owner consumer зафиксированы `rwms.asset.rental-item.v1` и
`media-service-cabin-owner-v1`.

В `production` требуются HTTPS issuer/JWKS и TLS для MinIO. Plain HTTP и
`MEDIA_MINIO_USE_SSL=false` допустимы только при явном `local-test` profile.
Startup и readiness проверяют включённое versioning у bucket; неаутентифицированный
API-запрос отклоняется до любой операции MinIO.

## Хранилище и безопасность владельца

У bucket обязательно включено versioning. Authenticated upload ingress
сериализуется по upload session между экземплярами сервиса, передаёт ровно
заявленную длину в private MinIO и коммитит finalization до ответа вызывающей
стороне. Finalization проверяет и фиксирует точные object version, ETag, длину,
content type, checksum и content sniff; derived writes тоже version-pinned.
Публичное чтение стримит закреплённую версию только через media API с
`private, no-store`. В runtime нет unversioned download, физического delete,
retention или orphan cleanup. Owner-scoped deletion — только PostgreSQL
soft-delete: MinIO versions и provenance variants сохраняются, а наружу выходит
один `DELETED` fact.

Публичный доступ разрешён USER JWT с UUID subject, точным RWMS scope и
warehouse grant. Канонические owner/context пары покрывают inventory, CABIN,
maintenance estimate/repair/acceptance/catalog node и logistics
return/shipment/transfer. Все они авторизуются по local owner bindings, а не по
значениям owner/warehouse, присланным клиентом. Логистический браузер посылает
только `documentId` и `lineId`; composite persistence identity формируется
внутри media-service.

`POST /api/media/v1/cabin-covers` возвращает ограниченную warehouse batch.
`photoCount` считает логические не удалённые IMAGE assets. В `previews` не
более 100 READY изображений в каноническом порядке, ровно с одним `SMALL`
variant на логическое изображение; `cover` — первый preview для совместимости.
MEDIUM, LARGE, ORIGINAL и координаты object storage эта projection не выдаёт.

Inventory stream должен начинаться с `inventory.finding.added.v1` версии 0;
последующие facts непрерывны и сохраняют исходный warehouse. Reuse event ID,
смена warehouse, gap, regression и конфликт owner revision quarantine-ят
агрегат, поэтому все public owner operations сразу fail closed. Невалидные или
исчерпавшие попытки records публикуют только санитизированный media-owned DLT.
Reconciliation — operator-only file command; публичной регистрации или admin
bypass нет:

```bash
MEDIA_DATABASE_URL=... media-service reconcile-inventory-owner reviewed-batch.json
```

## Приватный импорт assets из Yandex.Disk

`asset-service` может вызвать private asset-import endpoints только с точным
SERVICE JWT (`sub=client_id=asset-service`, audience `rwms-services`, единственный
scope `media.asset-import`). Preflight принимает лишь
`https://disk.yandex.ru/d/<14-character-key>`, сохраняет parsed key и состояние
ресурса приватно, рекурсивно читает официальный public-resources API без OAuth
и не скачивает файл на этапе preflight.

Activation привязывает каждую source row к актуальному CABIN owner. Затем
durable worker получает свежий download address, переходит только на HTTPS
Yandex domains с публичными DNS address, ограничивает redirects и response
time и никогда не логирует URL/key/href. JPEG, PNG и WebP проходят обычные
invariants versioned ingress, owner proof, 100 assets per owner, processing и
outbox. Видео, ZIP папки и все прочие типы сохраняются лишь как
санитизированное skipped warning.

Transient Yandex и service dependency failures worker повторяет не более трёх
раз; terminal phase можно повторить private идемпотентным endpoint. Ограничения
одного job: до 500 source rows, 100 discovered files на source и 25 000 файлов
всего. Рекурсивное перечисление metadata ограничено 64 страницами на source, а
каждый скачанный байт — `MEDIA_MAX_UPLOAD_BYTES`.

CABIN stream должен начинаться с `asset.rental-item.created.v1` версии 0.
Passport, status, warehouse и logistics-effect facts несут полный
санитизированный owner proof; comments и manual notes служат маркерами порядка.
`WRITTEN_OFF` терминален и деактивирует binding. Точные дубликаты
идемпотентны; gap, regression, конфликт identity/revision и попытка terminal
reactivation quarantine-ят rental item и делают все public CABIN media paths
fail closed. Для проверенного непрерывного восстановления есть отдельная
operator command:

```bash
MEDIA_DATABASE_URL=... media-service reconcile-cabin-owner reviewed-batch.json
```

Maintenance и logistics устанавливают свои scopes через
`POST /api/internal/media/v1/owner-proofs` с точной service identity/scope.
Первый receipt формирует baseline. Каждый последующий owner proof требует
точно следующую owner revision; aggregate version должен строго расти, но может
пропускать мутации, которые не выпускают media proof. Exact replay event/payload
идемпотентен; неувеличение/gap/regression owner revision, неувеличение/regression
aggregate version и event-ID conflict quarantine-ят owner. Повтор receipt,
quarantine-нутого только старым правилом `AGGREGATE_VERSION_GAP`, оценивается
по текущим правилам и может восстановиться; остальные причины quarantine не
ослабляются. Для return proof warehouse — receiving destination; для
transfer-line acceptance — `destinationWarehouseId`, а не source warehouse.

PostgreSQL transport outbox публикует только точные сохранённые bytes. При
broker outage строки возвращаются в `PENDING` с bounded DB backoff и никогда
не исчерпываются в DLT; терминальной может стать лишь permanent corruption
checksum точных байтов. Processing attempts — первая доставка плюс transient
retries 1s/2s/4s; validation terminal на фактической попытке. DLT record
использует source processing-job UUID key для валидного запроса либо
deterministic UUIDv5 в OID namespace от SHA-256 raw bytes для невалидного.

## Ограниченное восстановление processing

Processing consumer разделяет malformed Kafka input, transient outage
object dependency/timeout, terminal processor result и ошибку commit offset
на разные outcomes. Точное поведение задано в
[`internal/worker/consumer.go`](internal/worker/consumer.go), а durable state
transition принадлежит
[`internal/persistence/worker.go`](internal/persistence/worker.go).

- Poison record записывается в hash-only DLT
  `INVALID_PROCESSING_REQUEST` до подтверждения offset, поэтому
  следующая валидная запись может выполниться.
- Dependency failure получает не более четырёх попыток в одном
  durable cycle с retry 1s/2s/4s. Две последовательные dependency
  failure открывают circuit на пять секунд; следующая попытка является
  единственной half-open probe. Validation и другой permanent processor
  result терминален на текущей попытке.
- Если lease четвёртой попытки истёк до сохранения outcome, один claimant
  получает новый fence без увеличения обоих attempt counters и записывает
  `PROCESSING_ATTEMPT_EXHAUSTED` без пятого processor call. Тот же code
  публикуется в sanitized DLT согласно совместимому закрытому enum в
  [`media-processing-dlt-v1.schema.json`](../../contracts/events/media/media-processing-dlt-v1.schema.json).
- Persistence outcome повторяется не более четырёх раз. Commit offset
  имеет не более трёх попыток с отдельным context deadline три
  секунды. При исчерпании или shutdown consumer освобождает
  blocked rebalance и оставляет offset неподтверждённым. Повторная
  delivery видит точный inbox result и не создаёт второй READY fact
  или набор variants.
- [`V11__bounded_media_processing_recovery.sql`](db/migration/V11__bounded_media_processing_recovery.sql)
  ограничивает attempt текущего cycle до `0..4` и хранит versioned
  terminal/review evidence. Хранятся review identity, UUID reviewer-а,
  закрытые decision/reason и SHA-256 исходного message; source payload,
  object coordinates и free-form errors не хранятся. Существующая failed job
  связывается с source evidence только при ровно одной подходящей DLT row;
  при нуле или нескольких rows сохраняется `LEGACY_TERMINAL` без догадки о
  source identity.
- Retry approval является только evidence. Он version-fenced и идемпотентен,
  но не ставит terminal job в очередь. Аутентифицированного operator
  API/command для следующего transition пока нет; его нужно спроектировать
  до публикации retry execution. `ATTEMPT_BUDGET_RESET` фиксирует review
  исчерпанного attempt cycle, но сам также не выполняет reset или requeue.

Typed fixed-cardinality recovery snapshot по-прежнему публикуется через
structured logs и также копируется в standard-library OpenMetrics text на
`GET /metrics`. [`cmd/media-service/main.go`](cmd/media-service/main.go)
собирает exporter из
[`internal/observability/processing_metrics.go`](internal/observability/processing_metrics.go)
с отдельным private listener из
[`cmd/media-service/metrics_runtime.go`](cmd/media-service/metrics_runtime.go).
[`internal/config/config.go`](internal/config/config.go) отклоняет wildcard,
hostname, public, non-loopback и zero-port management bind; public handler
`api.Server` не получает metrics route.

Exporter показывает durable counts active/pending/running и terminal-review,
возраст самой старой active job, maximum cycle attempt и fixed one-hot breaker
state closed/open/half-open. Handled и committed offsets имеют только числовой
label `partition`; несуществующий committed offset не публикуется. Каждый
current snapshot сохраняет не более 64 наименьших неотрицательных partition ID,
поэтому partition labels не растут без cap. В нём нет media/event/user IDs,
payload, error text, topic label или free-form label. Alert thresholds и runtime
rollout остаются open, поскольку отсутствуют Task 0 baseline и deployment
authorization.

## Приватные границы logistics media

`POST /api/internal/media/v1/logistics/references/validate` требует точный
SERVICE JWT `logistics-service` с совпадающими `sub`/`client_id` и единственным
scope `media.logistics`. Запрос выводит opaque owner ID из `documentId:lineId`,
допускает только `LOGISTICS_RETURN`, `LOGISTICS_SHIPMENT` и
`LOGISTICS_TRANSFER` и проверяет от одной до двадцати уникальных пар
`{mediaId,generation}` для подходящего warehouse.

Запрос успешен только при current logistics owner proof и current `READY`
generations. Ответ содержит лишь подтверждённые opaque IDs/generations; в нём
нет URL, object key, filename, MIME type, processing state или retention
policy. Это read-only операция: она не делает upload, не меняет owner binding,
event/outbox/Kafka consumer и не вызывает object storage.

`POST /api/internal/media/v1/logistics/cabin-presentations/snapshots` использует
ту же точную SERVICE identity/scope. Он принимает от одного до ста уникальных
CABIN ID одного warehouse и возвращает только current canonical bindings и
READY/current image references
`{mediaId,generation,sortOrder,availableVariants}`. Нет browser path,
object-store coordinates, signed URL, filename, MIME type или processing data.

`GET /api/internal/media/v1/logistics/cabin-presentations/assets/{mediaId}/variants/{variant}/content`
— парный private byte stream. `variant` ровно `SMALL` или `LARGE`; в запросе
обязательны CABIN, warehouse и current generation. Любое несовпадение scope,
owner, warehouse, state, generation или variant даёт одинаковый opaque 404.
Публичного logistics presentation-media route нет: последующий browser-facing
proxy принадлежит logistics.

## Структурный архитектурный гейт

[`internal/architecture/architecture_test.go`](internal/architecture/architecture_test.go)
разбирает production Go source без тестов и выводит identity модуля из
`go.mod`. Он требует, чтобы в модуле оставался ровно один executable package в
`cmd/media-service`, затем проверяет разрешение и ацикличность service-local
import graph и соблюдение проверенных направлений foundation, capability,
persistence, delivery, observability и composition. Новый production package
не получает роль неявно: гейт падает, пока его направление владения не будет
проверено и классифицировано.

Тот же гейт запрещает public API, worker и observability packages напрямую
использовать datastore или object-store clients. Observability может принимать
только typed worker snapshot, а telemetry structures и structured-log keys не
могут получать payload, credential, domain identity, object coordinates или
raw-error details. Это source boundaries, а не замена runtime, contract или
integration tests.

Focused gate запускается так:

```bash
go test ./internal/architecture -count=1
```

## Локальная проверка

На build host нужны Go 1.25, CGO, libvips, FFmpeg и FFprobe.

```bash
gofmt -w ./cmd ./internal
go test ./...
go build -trimpath -o /tmp/rwms-media-service ./cmd/media-service
```

Проверка миграций выполняется отдельно Flyway и PostgreSQL и должна покрыть
clean install V1-to-V11, upgrade V10-to-V11, повторный запуск, checksum drift и
непустую базу без истории. Проверки MinIO должны использовать versioned
local/test bucket; Kafka-проверки — канонические topics и broker acknowledgements.
