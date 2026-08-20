# RWMS Dossier Service

[Русская версия](README.ru.md)

dossier-service is the read-only cross-domain cabin activity projection. It owns only its PostgreSQL journal, projection generations, inbox/checkpoints, outbox and sanitized dead-letter records. It never owns a source cabin, maintenance, inventory, logistics, task-board or media aggregate and never accepts a command for one.

## Purpose and boundary

The service provides a warehouse-authorized chronological dossier of cabin activity assembled from validated committed facts. It preserves opaque source references and immutable warehouse snapshots so a response can be filtered without asking another service for current data.

It deliberately does not:

- read a producer database, outbox, inbox or event store;
- synchronously call source services to fill a response;
- issue commands for a producer aggregate; or
- expose a public mutation route.

The authoritative boundaries are [dossier-service.yaml](../../contracts/openapi/dossier-service.yaml) and [dossier-consumers.yaml](../../contracts/events/dossier-consumers.yaml).

## Projection flow

    producer-owned committed facts
            |
            v
    Kafka source topics, aggregate key and V2 envelope
            |
            v
    validation -> inbox/source journal/checkpoints -> active generation
            |
            +--> sanitized per-consumer DLT and replayable evidence
            |
            v
    read-only dossier API through the public gateway
            |
            +--> sanitized cabin-activity outbox fact

The consumer begins at earliest, validates every accepted source topic and record key, deduplicates event identity, records source coordinates and quarantines version gaps instead of inventing a missing prefix. Source facts without a provable cabin subject are journaled as unlinked rather than fabricated into a cabin activity.

Maintenance repair transfer facts are projected as `REPAIR_TRANSFER_PREPARED` and `REPAIR_TRANSFERRED`. Each activity keeps the `rentalItemId` cabin and `warehouseId` snapshot from that committed maintenance event, so departure preparation remains attached to the source warehouse and completed transfer to the target warehouse without a synchronous producer lookup. The durable mapping is defined by the [consumer contract](../../contracts/events/dossier-consumers.yaml) and enforced by [DossierEnvelopeValidator](src/main/java/dev/buhanzaz/rwms/dossier/eventing/DossierEnvelopeValidator.java).

`rwms.media.cabin-photo.v1` cover facts are also journaled as media evidence. Only `media.cabin.cover-changed.v1` with a non-null `taskBoardEntryId` creates `MEDIA_TASK_EVIDENCE_ATTACHED`; direct cover changes remain journal-only and never enter the normal media lifecycle projection. The dossier API returns only the opaque `mediaId`, `generation` and `taskBoardEntryId`; a client resolves the image through its authorized task-board media owner scope at the public gateway. Dossier never stores or publishes an object-store location, signed URL or direct photo link.

A replay builds a new projection generation from the local source journal, tails and verifies it, then atomically activates it. Under the active-pointer write lock, successful parity advances unresolved DLT visibility coverage from the source generation to the target before the pointer changes; rejected replay leaves coverage on the source generation and never creates another DLT publication. Replay is service-owned operational work and is enabled only by the documented configuration; Kafka retention is not the replay authority. The owning paths are [DossierReplayTransactions](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierReplayTransactions.java) and [DossierSanitizedDeadLetterRepository](src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierSanitizedDeadLetterRepository.java).

## Public API, security and warehouse isolation

Interactive clients use the gateway only:

| Public route | Contract operation | Restriction |
| --- | --- | --- |
| GET /api/dossier/v1/cabins/{cabinId} | getCabinDossier | Read-only; locally validated USER, rwms.read and row-level warehouse VIEW |

The gateway admits GET only for the dossier route. The service creates a fail-closed warehouse scope from JWT claims before querying activities and media. SYSTEM_ADMIN and WMS_ADMIN are unrestricted; malformed or absent warehouse access grants no row. Unauthorized rows are omitted, no hidden-row count is disclosed, and a caller with no visible evidence receives 404.

Visibility is a projection-coverage signal. A cabin is PARTIAL only for warehouse-hidden activity/media or unresolved evidence linked to that exact cabin and the active generation. Globally unlinked operational evidence, raw validation failures and legacy DLT rows without a proven cabin remain observable to operators but do not degrade unrelated cabin reads. Failure scope is proven centrally by [DossierVisibilityCoverageResolver](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierVisibilityCoverageResolver.java), which accepts only generation-local association proof, a constrained source snapshot or a direct cabin/warehouse pair. Consumers must treat PARTIAL as incomplete visibility and must not infer omitted activity, ownership or a source command from a dossier response. The query is defined by [DossierQueryService](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierQueryService.java) and the cabin/generation repositories it calls.

## Persistence, recovery and observability

The service owns one private database. Flyway owns schema changes and Hibernate validates the schema only. The local source journal, inbox, aggregate checkpoints and partition checkpoints make duplicate delivery harmless, retain facts needed for replay, and make source identity or ordering failures observable.

Source and outbound failures become sanitized DLT records rather than raw data dumps. A processing DLT keeps its immutable audit/relay identity and has nullable coverage generation, proven subject cabin and recovery time. DLT transport status (`PENDING`, `RETRY`, `PUBLISHED` or `DLT`) is independent from visibility coverage: successful explicit source recovery resolves coverage without deleting or republishing the audit row. Coverage attachment/resolution and relay-state mutations serialize on the same audit row, so both state families survive concurrent recovery and transport work. Raw validation failures remain unscoped. Flyway V3 is the schema authority for these rules: [V3__dossier_cabin_visibility_scope.sql](src/main/resources/db/migration/V3__dossier_cabin_visibility_scope.sql).

### Recovery metrics

[DossierRecoveryMetrics](src/main/java/dev/buhanzaz/rwms/dossier/eventing/DossierRecoveryMetrics.java) exposes ten fixed, untagged, lazy gauges through service-owned read-only Spring Data JPA queries. A pre-existing fixed Micrometer `application` common tag may be added outside this component, but the service registers no IDs, topics, payloads or error text as labels. The query sources are [DossierAggregateCheckpointRepository](src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierAggregateCheckpointRepository.java), [DossierUnlinkedFactRepository](src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierUnlinkedFactRepository.java), [DossierSanitizedDeadLetterRepository](src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierSanitizedDeadLetterRepository.java), and [DossierOutboxEventRepository](src/main/java/dev/buhanzaz/rwms/dossier/repository/DossierOutboxEventRepository.java).

| Metric | Definition |
| --- | --- |
| `rwms.dossier.recovery.checkpoints.blocked` | Count of retained aggregate checkpoints with `blocked=true`. |
| `rwms.dossier.recovery.checkpoints.blocked.oldest.age.seconds` | Age of `min(blockedAt)` over those blocked checkpoints. |
| `rwms.dossier.recovery.unlinked-facts.unresolved` | Count of retained unlinked facts with `resolvedAt is null` across all generations; it is operational-only, not a cabin visibility decision. |
| `rwms.dossier.recovery.dlt.coverage.unresolved` | Count of retained DLT rows with exact generation-and-cabin coverage and `coverageResolvedAt is null` across all generations; it is operational-only, not a cabin visibility decision. |
| `rwms.dossier.activity-outbox.backlog` | All activity-outbox rows in `PENDING` or `RETRY`, including not-yet-due or non-head rows. |
| `rwms.dossier.activity-outbox.backlog.oldest.age.seconds` | Age of `min(createdAt)` over the activity-outbox backlog. |
| `rwms.dossier.activity-outbox.terminal` | Activity-outbox rows in terminal `DLT`. |
| `rwms.dossier.recovery.dlt.backlog` | Sanitized DLT rows in `PENDING` or `RETRY`. |
| `rwms.dossier.recovery.dlt.backlog.oldest.age.seconds` | Age of `min(failedAt)` over the sanitized DLT backlog. |
| `rwms.dossier.recovery.dlt.terminal` | Sanitized DLT rows in terminal `DLT`. |

An empty query reports zero. A future timestamp reports a zero age. A `DataAccessException` reports `NaN`, not a fabricated healthy zero. None of these gauges resolves coverage, makes a dossier response `COMPLETE`, replays a source record, activates a generation, publishes or requeues a row, or owns a command. Alert thresholds and runtime rollout remain open operational work.

The production safety validator requires private Kafka, OIDC, CORS, cursor-secret and schema-validation configuration. Health, Prometheus, tracing and structured logs support recovery; never put raw producer payloads, JWTs or private media URLs in logs.

## Local development and production configuration

| Setting | Purpose |
| --- | --- |
| DOSSIER_DB_URL, DOSSIER_DB_USERNAME, DOSSIER_DB_PASSWORD | Service-owned PostgreSQL connection |
| AUTH_ISSUER, AUTH_AUDIENCE | Local JWT issuer and audience validation |
| PANEL_ORIGIN | Explicit browser CORS origin |
| DOSSIER_KAFKA_ENABLED, DOSSIER_KAFKA_BROKERS | Source consumer and private broker bootstrap |
| DOSSIER_CURSOR_SECRET | Tamper-detection key for pagination cursors |
| DOSSIER_DEV_AUTH_BYPASS | Development-only authentication bypass |
| rwms.dossier.replay.enabled | Explicitly enables scheduled generation replay |

Use development-only values locally. Production must use non-loopback origins and issuer, a non-default cursor secret, private brokers and real secrets. Do not return mock dossier activity when a producer, database or authorization input is unavailable.

## Verification and safe changes

Run the focused suite after changing this service or an accepted source contract:

    bash ./gradlew :services:dossier-service:test

Before changing a source family, activity code, visibility rule, generation/replay behavior or warehouse scope:

1. update the canonical event/OpenAPI contract when the boundary changes;
2. trace every producer, the dossier consumer, DLT destination, replay path, gateway route and client;
3. preserve transactional inbox/source-journal/outbox behavior, aggregate ordering and no-cross-database ownership; and
4. add tests for duplicate delivery, record-key mismatch, gap/quarantine, replay activation, hidden warehouse rows and cursor tampering.

Primary implementation references: [DossierInboxProcessor](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierInboxProcessor.java), [DossierQueryService](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierQueryService.java), [DossierReplayTransactions](src/main/java/dev/buhanzaz/rwms/dossier/service/DossierReplayTransactions.java), and [DossierAuthorizer](src/main/java/dev/buhanzaz/rwms/dossier/security/DossierAuthorizer.java).
