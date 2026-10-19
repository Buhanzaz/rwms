# RWMS Analytics Service

[Русская версия](README.ru.md)

analytics-service is the read-only warehouse KPI projection. It owns its PostgreSQL evidence, inbox, checkpoints and sanitized delivery-failure records. It does not own task-board work, worker groups, warehouse access, or a command that changes a producer aggregate.

## Purpose and boundary

The service turns committed task-board daily KPI facts into queryable group KPI responses. It retains raw duration and count components, then derives rounded KPI, speed and utilization only at the API boundary. Month, quarter and year values therefore never average already-rounded day percentages.

It deliberately does not:

- query a task-board database or invoke a task-board command;
- publish a canonical business event or own a KPI source fact;
- decide warehouse authorization from its own tables; or
- expose a mutable public API.

The authoritative boundaries are [analytics-service.yaml](../../contracts/openapi/analytics-service.yaml) and [analytics-consumers.yaml](../../contracts/events/analytics-consumers.yaml).

## Request and event flow

    task-board committed KPI-day fact
            |
            v
    Kafka rwms.task-board.group-kpi-day.v1 (aggregate key)
            |
            v
    strict V2 validation -> local inbox/source fact/checkpoints -> KPI day evidence
            |
            +--> sanitized DLT for a terminally rejected record
            |
            v
    GET through the public gateway -> locally authorized KPI response

The consumer starts at earliest, deduplicates by eventId, records source coordinates, validates the Kafka key against the aggregate ID, and applies only contiguous aggregate versions. Delivery is at least once; the local transaction commits projection state before the source offset may advance.

The service keeps bounded version gaps and routes terminal failures to its sanitized DLT. It does not expose a public producer-replay or checkpoint-reconciliation command, so a terminal gap is an operational incident and is not evidence that stale KPI data is complete.

## Public API and authorization

Interactive clients use the public gateway only:

| Public gateway route | Owning upstream operation | Access rule |
| --- | --- | --- |
| GET /api/analytics/v1/warehouses/{warehouseId}/group-kpi | GET /api/v1/warehouses/{warehouseId}/group-kpi | Locally validated USER, rwms.read, and warehouse VIEW or higher |

The gateway rewrite is transport-only; the OpenAPI operation remains the canonical API. SYSTEM_ADMIN and WMS_ADMIN are unrestricted by warehouse claim. Other callers are checked against the signed warehouse_access claim before the evidence query runs. No client may call an internal service origin or an /api/internal/** route.

CoverageStatus and the response asOf value are public contract fields. Consumers must render PARTIAL, PROVISIONAL and NO_DATA differently and must not recreate KPI formulae in a browser or mobile client.

## Persistence, recovery and observability

The service has one private database. Flyway owns the schema and Hibernate validates it only. Inbox identity, source facts, partition checkpoints and aggregate checkpoints make duplicate Kafka delivery harmless and make ordering faults visible instead of overwriting evidence.

The production safety validator requires Kafka outside local profiles, an exact consumer group/topic/DLT configuration, no broker-side DLT, and synchronous DLT publication. Health, Prometheus and structured logs are configured by Spring. Do not log raw Kafka records, JWTs or warehouse claims while investigating a failure.

Prometheus also exposes seven lazy, read-only recovery gauges backed by Spring Data JPA queries.
The gauges themselves register no labels, identifiers, topics, payloads or error text; the
pre-existing global Micrometer `application=analytics-service` common tag may still be attached:

| Gauge | Meaning |
| --- | --- |
| `rwms.analytics.recovery.gaps.active` | Open gaps still eligible for bounded local retry |
| `rwms.analytics.recovery.gaps.terminal` | Open gaps whose local retry budget is exhausted |
| `rwms.analytics.recovery.gaps.oldest.age.seconds` | Age of the oldest retained open gap, including terminal gaps |
| `rwms.analytics.recovery.gaps.maximum.attempt` | Highest local retry attempt among retained open gaps |
| `rwms.analytics.recovery.dlt.backlog` | Sanitized DLT records in `PENDING` or `RETRY` |
| `rwms.analytics.recovery.dlt.backlog.oldest.age.seconds` | Age of the oldest sanitized DLT backlog record |
| `rwms.analytics.recovery.dlt.terminal` | Sanitized DLT records in terminal `DLT` state |

An empty healthy set reports zero; a database read failure reports `NaN` rather than a false healthy
zero. Ages use the injected clock only for observation and clamp future timestamps to zero. These
gauges never make a period `COMPLETE`, mutate recovery state, or replace reviewed
owner-authoritative replay. Alert thresholds and runtime rollout remain open operational work.

## Local development and production configuration

| Setting | Purpose |
| --- | --- |
| ANALYTICS_DB_URL, ANALYTICS_DB_USERNAME, ANALYTICS_DB_PASSWORD | Service-owned PostgreSQL connection |
| AUTH_ISSUER, AUTH_AUDIENCE | Local JWT issuer and audience validation |
| PANEL_ORIGIN | Explicit browser CORS origin |
| ANALYTICS_KAFKA_ENABLED, ANALYTICS_KAFKA_BROKERS | Projection consumer and private broker bootstrap |
| ANALYTICS_GAP_MAXIMUM_ATTEMPTS | Bounded gap retry limit |

Use development-only values locally. Managed environments must provide real secrets and private broker addresses; do not add a mock KPI fallback if Kafka, the database or authentication is unavailable.

## Verification and safe changes

Run the focused module suite after changing this service or its contracts:

    bash ./gradlew :services:analytics-service:test

Before changing a KPI fact, formula, status, source topic or authorization rule:

1. update the canonical OpenAPI or event schema when the boundary changes;
2. trace task-board production, the analytics consumer, gateway route and every panel/mobile consumer;
3. preserve inbox deduplication, aggregate ordering, bounded failure handling and read-only ownership; and
4. add checks for duplicate, key mismatch, version-gap and warehouse authorization paths.

Primary implementation references: [AnalyticsQueryService](src/main/java/dev/buhanzaz/rwms/analytics/service/AnalyticsQueryService.java), [AnalyticsInboxProcessor](src/main/java/dev/buhanzaz/rwms/analytics/service/AnalyticsInboxProcessor.java), [AnalyticsEnvelopeValidator](src/main/java/dev/buhanzaz/rwms/analytics/eventing/AnalyticsEnvelopeValidator.java), and [AnalyticsProductionSafetyValidator](src/main/java/dev/buhanzaz/rwms/analytics/config/AnalyticsProductionSafetyValidator.java).

Recovery-gauge implementation: [AnalyticsRecoveryMetrics](src/main/java/dev/buhanzaz/rwms/analytics/eventing/AnalyticsRecoveryMetrics.java).
