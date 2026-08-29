# Сервис dossier RWMS

[English version](README.md)

dossier-service — read-only cross-domain проекция активности кабин. Он владеет только своей PostgreSQL journal, projection generations, inbox/checkpoints, outbox и sanitised dead-letter records. Он никогда не владеет source aggregate кабин, maintenance, inventory, logistics, task-board или media и не принимает команду для них.

## Назначение и граница ответственности

Сервис предоставляет warehouse-authorized хронологический dossier активности cabin, собранный из validated committed facts. Он сохраняет opaque source references и immutable warehouse snapshots, поэтому response можно отфильтровать без обращения к другому сервису за текущими данными.

Сервис намеренно не:

- читает БД producer, outbox, inbox или event store;
- синхронно вызывает source services для заполнения response;
- выпускает команды для producer aggregate; или
- не предоставляет public mutation route.

Авторитетные границы находятся в [dossier-service.yaml](../../contracts/openapi/dossier-service.yaml) и [dossier-consumers.yaml](../../contracts/events/dossier-consumers.yaml).

## Поток проекции

    producer-owned committed facts
            |
            v
    Kafka source topics, aggregate key и V2 envelope
            |
            v
    validation -> inbox/source journal/checkpoints -> active generation
            |
            +--> sanitised per-consumer DLT и replayable evidence
            |
            v
    read-only dossier API через public gateway
            |
            +--> sanitised cabin-activity outbox fact

Consumer начинает с earliest, проверяет каждый accepted source topic и record key, дедуплицирует event identity, сохраняет source coordinates и quarantines version gaps вместо выдумывания missing prefix. Source facts без доказуемого cabin subject сохраняются как unlinked, а не фабрикуются в cabin activity.

Факты inventory finding membership-departed, membership-refreshed и completed-observation-restored проходят validation и сохраняются в journal как evidence порядка, но намеренно не создают cabin activity. Additive-поле `membershipActive` необязательно в исторических added/inspection facts; lifecycle markers требуют значение, соответствующее типу event. Поэтому dossier продвигает checkpoint finding без выдуманного статуса бытовки или повторного открытия media owner proof.

Maintenance repair transfer facts проецируются как `REPAIR_TRANSFER_PREPARED` и `REPAIR_TRANSFERRED`. Каждая activity сохраняет cabin из `rentalItemId` и snapshot склада из `warehouseId` зафиксированного maintenance event, поэтому подготовка отправления остаётся связана с исходным складом, а завершённая передача — с целевым без синхронного вызова producer. Durable mapping определён [consumer contract](../../contracts/events/dossier-consumers.yaml) и проверяется [DossierEnvelopeValidator](src/main/java/dev/buhanzaz/rwms/dossier/eventing/DossierEnvelopeValidator.java).

Cover facts `rwms.media.cabin-photo.v1` также сохраняются как media evidence. Только `media.cabin.cover-changed.v1` с non-null `taskBoardEntryId` создаёт `MEDIA_TASK_EVIDENCE_ATTACHED`; прямые изменения cover остаются только в journal и не попадают в normal media lifecycle projection. Dossier API возвращает лишь opaque `mediaId`, `generation` и `taskBoardEntryId`; клиент получает изображение через свой authorized task-board media owner scope в public gateway. Dossier никогда не сохраняет и не публикует object-store location, signed URL или прямую ссылку на фото.

Корректные media facts для owners вне dossier, включая `LOGISTICS_CUSTOMER_PROFILE`, принимаются и
сохраняются как unlinked operational evidence с `subjectCapable=false`; они не создают cabin activity
и не считаются ошибочной source-схемой. Только media owners `CABIN` и `INVENTORY_FINDING` могут
передать subject для dossier. Это правило классификации consumer, а не владение media профиля.

Replay строит новую projection generation из local source journal, tail/verify её и атомарно активирует. Под write lock active pointer успешная parity переносит unresolved DLT visibility coverage с source generation на target до смены pointer; rejected replay оставляет coverage на source generation и никогда не создаёт повторную DLT publication. Replay является service-owned operational work и включается только документированной configuration; Kafka retention не является replay authority. Владеющие пути: [DossierReplayTransactions](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierReplayTransactions.java) и [DossierSanitizedDeadLetterRepository](src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierSanitizedDeadLetterRepository.java).

## Публичный API, security и warehouse isolation

Interactive clients используют только gateway:

| Public route | Contract operation | Ограничение |
| --- | --- | --- |
| GET /api/dossier/v1/cabins/{cabinId} | getCabinDossier | Только чтение; locally validated USER, rwms.read и row-level warehouse VIEW |

Gateway допускает для dossier route только GET. Сервис строит fail-closed warehouse scope из JWT claims до запроса activities и media. SYSTEM_ADMIN и WMS_ADMIN не ограничиваются; malformed или отсутствующий warehouse access не предоставляет строк. Unauthorized rows исключаются, hidden-row count не раскрывается, а caller без visible evidence получает 404.

Visibility является projection-coverage signal. Cabin имеет PARTIAL только из-за warehouse-hidden activity/media или unresolved evidence, связанного именно с этой cabin и active generation. Globally unlinked operational evidence, raw validation failures и legacy DLT rows без доказанной cabin остаются наблюдаемыми для operators, но не ухудшают unrelated cabin reads. Failure scope централизованно доказывает [DossierVisibilityCoverageResolver](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierVisibilityCoverageResolver.java), принимая только generation-local association proof, constrained source snapshot или direct cabin/warehouse pair. Consumers должны рассматривать PARTIAL как неполную visibility и не должны выводить omitted activity, ownership или source command из dossier response. Query определяется [DossierQueryService](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierQueryService.java) и вызываемыми им cabin/generation repositories.

## Persistence, recovery и observability

Сервис владеет одной private database. Flyway владеет изменениями schema, а Hibernate только валидирует schema. Local source journal, inbox, aggregate checkpoints и partition checkpoints делают duplicate delivery безопасной, сохраняют facts для replay и делают source identity или ordering failures наблюдаемыми.

Source и outbound failures становятся sanitised DLT records, а не raw data dumps. Processing DLT сохраняет immutable audit/relay identity и имеет nullable coverage generation, proven subject cabin и recovery time. DLT transport status (`PENDING`, `RETRY`, `PUBLISHED` или `DLT`) не зависит от visibility coverage: успешный explicit source recovery разрешает coverage без удаления или повторной публикации audit row. Coverage attachment/resolution и relay-state mutations сериализуются на одной audit row, поэтому обе семьи state сохраняются при concurrent recovery и transport work. Raw validation failures остаются unscoped. Schema authority этих правил — Flyway V3: [V3__dossier_cabin_visibility_scope.sql](src/main/resources/db/migration/V3__dossier_cabin_visibility_scope.sql).

### Метрики восстановления

[DossierRecoveryMetrics](src/main/java/dev/buhanzaz/rwms/dossier/eventing/DossierRecoveryMetrics.java) публикует десять фиксированных неразмеченных lazy gauges через service-owned read-only Spring Data JPA queries. Предсуществующий фиксированный Micrometer common tag `application` может добавляться вне этого компонента, но сервис не регистрирует IDs, topics, payloads или error text как labels. Источники запросов: [DossierAggregateCheckpointRepository](src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierAggregateCheckpointRepository.java), [DossierUnlinkedFactRepository](src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierUnlinkedFactRepository.java), [DossierSanitizedDeadLetterRepository](src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierSanitizedDeadLetterRepository.java) и [DossierOutboxEventRepository](src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierOutboxEventRepository.java).

| Metric | Определение |
| --- | --- |
| `rwms.dossier.recovery.checkpoints.blocked` | Количество retained aggregate checkpoints с `blocked=true`. |
| `rwms.dossier.recovery.checkpoints.blocked.oldest.age.seconds` | Возраст `min(blockedAt)` среди этих blocked checkpoints. |
| `rwms.dossier.recovery.unlinked-facts.unresolved` | Количество retained unlinked facts с `resolvedAt is null` по всем generations; это только operational signal, а не cabin visibility decision. |
| `rwms.dossier.recovery.dlt.coverage.unresolved` | Количество retained DLT rows с exact generation-and-cabin coverage и `coverageResolvedAt is null` по всем generations; это только operational signal, а не cabin visibility decision. |
| `rwms.dossier.activity-outbox.backlog` | Все activity-outbox rows в `PENDING` или `RETRY`, включая not-yet-due или non-head rows. |
| `rwms.dossier.activity-outbox.backlog.oldest.age.seconds` | Возраст `min(createdAt)` среди activity-outbox backlog. |
| `rwms.dossier.activity-outbox.terminal` | Activity-outbox rows в terminal `DLT`. |
| `rwms.dossier.recovery.dlt.backlog` | Sanitized DLT rows в `PENDING` или `RETRY`. |
| `rwms.dossier.recovery.dlt.backlog.oldest.age.seconds` | Возраст `min(failedAt)` среди sanitized DLT backlog. |
| `rwms.dossier.recovery.dlt.terminal` | Sanitized DLT rows в terminal `DLT`. |

Empty query возвращает zero. Future timestamp возвращает zero age. `DataAccessException` возвращает `NaN`, а не выдуманный healthy zero. Ни одна из этих gauges не разрешает coverage, не делает dossier response `COMPLETE`, не запускает replay source record, не активирует generation, не публикует и не requeue строку и не владеет командой. Alert thresholds и runtime rollout остаются открытой operational work.

Production safety validator требует private Kafka, OIDC, CORS, cursor-secret и schema-validation configuration. Health, Prometheus, tracing и structured logs поддерживают recovery; не помещайте raw producer payloads, JWT или private media URLs в logs.

## Локальная разработка и production configuration

| Setting | Назначение |
| --- | --- |
| DOSSIER_DB_URL, DOSSIER_DB_USERNAME, DOSSIER_DB_PASSWORD | Подключение к service-owned PostgreSQL |
| AUTH_ISSUER, AUTH_AUDIENCE | Проверка local JWT issuer и audience |
| PANEL_ORIGIN | Явный browser CORS origin |
| DOSSIER_KAFKA_ENABLED, DOSSIER_KAFKA_BROKERS | Source consumer и private broker bootstrap |
| DOSSIER_CURSOR_SECRET | Tamper-detection key pagination cursors |
| DOSSIER_DEV_AUTH_BYPASS | Только development authentication bypass |
| rwms.dossier.replay.enabled | Явно включает scheduled generation replay |

Локально используйте development-only values. Production должен использовать non-loopback origins и issuer, non-default cursor secret, private brokers и реальные secrets. Не возвращайте mock dossier activity, если producer, database или authorization input недоступны.

## Проверка и безопасные изменения

После изменения сервиса или accepted source contract запустите focused suite:

    bash ./gradlew :services:dossier-service:test

Перед изменением source family, activity code, visibility rule, generation/replay behavior или warehouse scope:

1. обновите canonical event/OpenAPI contract, если меняется boundary;
2. проследите каждого producer, dossier consumer, DLT destination, replay path, gateway route и client;
3. сохраните transactional inbox/source-journal/outbox behavior, aggregate ordering и no-cross-database ownership; и
4. добавьте tests для duplicate delivery, record-key mismatch, gap/quarantine, replay activation, hidden warehouse rows и cursor tampering.

Основные implementation references: [DossierInboxProcessor](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierInboxProcessor.java), [DossierQueryService](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierQueryService.java), [DossierReplayTransactions](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierReplayTransactions.java) и [DossierAuthorizer](src/main/java/dev/buhanzaz/rwms/dossier/security/DossierAuthorizer.java).
