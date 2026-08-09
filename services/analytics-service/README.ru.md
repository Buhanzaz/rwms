# Сервис аналитики RWMS

[English version](README.md)

analytics-service — read-only проекция KPI складов. Он владеет своей PostgreSQL evidence, inbox, checkpoints и sanitised записями delivery failures. Он не владеет работой task-board, группами работников, доступом к складу или командой, изменяющей aggregate производителя.

## Назначение и граница ответственности

Сервис превращает зафиксированные task-board факты дневных KPI в ответы с KPI групп. Он хранит исходные длительности и счётчики, а округлённые KPI, speed и utilization вычисляет только на границе API. Поэтому месячные, квартальные и годовые значения не усредняют уже округлённые дневные проценты.

Сервис намеренно не:

- читает БД task-board и не вызывает команды task-board;
- публикует каноническое business event и не владеет KPI source fact;
- принимает решение об авторизации склада по собственным таблицам; и
- не предоставляет изменяющий публичный API.

Авторитетные границы находятся в [analytics-service.yaml](../../contracts/openapi/analytics-service.yaml) и [analytics-consumers.yaml](../../contracts/events/analytics-consumers.yaml).

## Поток запроса и событий

    зафиксированный KPI-day fact task-board
            |
            v
    Kafka rwms.task-board.group-kpi-day.v1 (aggregate key)
            |
            v
    строгая V2-проверка -> local inbox/source fact/checkpoints -> KPI day evidence
            |
            +--> sanitised DLT для terminally rejected записи
            |
            v
    GET через public gateway -> локально авторизованный KPI response

Consumer начинает с earliest, дедуплицирует по eventId, сохраняет source coordinates, сверяет Kafka key с aggregate ID и применяет только непрерывные aggregate versions. Delivery имеет семантику at least once; local transaction фиксирует projection state до продвижения source offset.

Сервис хранит bounded version gaps и направляет terminal failures в свой sanitised DLT. Публичной команды producer replay или checkpoint reconciliation нет, поэтому terminal gap является operational incident и не доказывает полноту устаревшего KPI.

## Публичный API и авторизация

Interactive clients используют только public gateway:

| Public gateway route | Owning upstream operation | Правило доступа |
| --- | --- | --- |
| GET /api/analytics/v1/warehouses/{warehouseId}/group-kpi | GET /api/v1/warehouses/{warehouseId}/group-kpi | Локально проверенный USER, rwms.read и warehouse VIEW или выше |

Gateway rewrite является только транспортным; OpenAPI operation остаётся каноническим API. SYSTEM_ADMIN и WMS_ADMIN не ограничиваются warehouse claim. Остальные callers проверяются по подписанному warehouse_access claim до выполнения evidence query. Клиент не может обращаться к internal service origin или /api/internal/**.

CoverageStatus и значение asOf в response являются public contract fields. Consumers должны по-разному отображать PARTIAL, PROVISIONAL и NO_DATA и не должны воспроизводить KPI formula в browser или mobile client.

## Persistence, recovery и observability

У сервиса одна private database. Flyway владеет схемой, а Hibernate только валидирует её. Inbox identity, source facts, partition checkpoints и aggregate checkpoints делают повторную Kafka delivery безопасной и делают ordering faults видимыми вместо перезаписи evidence.

Production safety validator требует Kafka вне local profiles, точную конфигурацию consumer group/topic/DLT, отсутствие broker-side DLT и синхронную публикацию DLT. Health, Prometheus и structured logs настраиваются Spring. При расследовании ошибки не логируйте raw Kafka records, JWT или warehouse claims.

Prometheus также предоставляет семь ленивых read-only recovery gauges на основе Spring Data JPA
queries. Сами gauges не регистрируют labels, identifiers, topics, payloads или error text; уже
существующий глобальный Micrometer common tag `application=analytics-service` всё равно может
добавляться:

| Gauge | Значение |
| --- | --- |
| `rwms.analytics.recovery.gaps.active` | Open gaps, ещё допускающие bounded local retry |
| `rwms.analytics.recovery.gaps.terminal` | Open gaps с исчерпанным local retry budget |
| `rwms.analytics.recovery.gaps.oldest.age.seconds` | Возраст oldest retained open gap, включая terminal gaps |
| `rwms.analytics.recovery.gaps.maximum.attempt` | Наибольшая local retry attempt среди retained open gaps |
| `rwms.analytics.recovery.dlt.backlog` | Sanitized DLT records в `PENDING` или `RETRY` |
| `rwms.analytics.recovery.dlt.backlog.oldest.age.seconds` | Возраст oldest sanitized DLT backlog record |
| `rwms.analytics.recovery.dlt.terminal` | Sanitized DLT records в terminal `DLT` state |

Пустой healthy set возвращает zero; ошибка чтения database возвращает `NaN`, а не ложный healthy
zero. Ages используют injected clock только для observation и ограничивают future timestamps нулём.
Эти gauges никогда не делают период `COMPLETE`, не изменяют recovery state и не заменяют reviewed
owner-authoritative replay. Alert thresholds и runtime rollout остаются открытой operational work.

## Локальная разработка и production configuration

| Setting | Назначение |
| --- | --- |
| ANALYTICS_DB_URL, ANALYTICS_DB_USERNAME, ANALYTICS_DB_PASSWORD | Подключение к service-owned PostgreSQL |
| AUTH_ISSUER, AUTH_AUDIENCE | Проверка local JWT issuer и audience |
| PANEL_ORIGIN | Явный browser CORS origin |
| ANALYTICS_KAFKA_ENABLED, ANALYTICS_KAFKA_BROKERS | Projection consumer и private broker bootstrap |
| ANALYTICS_GAP_MAXIMUM_ATTEMPTS | Bounded gap retry limit |

Локально используйте только development-only values. Managed environments должны передавать реальные secrets и private broker addresses; не добавляйте mock KPI fallback, если Kafka, database или authentication недоступны.

## Проверка и безопасные изменения

После изменения сервиса или его контрактов запустите focused module suite:

    bash ./gradlew :services:analytics-service:test

Перед изменением KPI fact, formula, status, source topic или authorization rule:

1. обновите canonical OpenAPI или event schema, если меняется boundary;
2. проследите production task-board, analytics consumer, gateway route и всех panel/mobile consumers;
3. сохраните inbox deduplication, aggregate ordering, bounded failure handling и read-only ownership; и
4. добавьте checks для duplicate, key mismatch, version-gap и warehouse authorization paths.

Основные implementation references: [AnalyticsQueryService](src/main/java/dev/buhanzaz/rwms/analytics/service/AnalyticsQueryService.java), [AnalyticsInboxProcessor](src/main/java/dev/buhanzaz/rwms/analytics/service/AnalyticsInboxProcessor.java), [AnalyticsEnvelopeValidator](src/main/java/dev/buhanzaz/rwms/analytics/eventing/AnalyticsEnvelopeValidator.java) и [AnalyticsProductionSafetyValidator](src/main/java/dev/buhanzaz/rwms/analytics/config/AnalyticsProductionSafetyValidator.java).

Реализация recovery gauges: [AnalyticsRecoveryMetrics](src/main/java/dev/buhanzaz/rwms/analytics/eventing/AnalyticsRecoveryMetrics.java).
