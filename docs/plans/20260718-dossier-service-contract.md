# Stage 9 Dossier Service Contract

**Status:** `APPROVED — IMPLEMENTATION MAY START ONLY AFTER THE STAGE 8 EXIT POINTER AND COMMIT ARE RECONCILED`

## Approval and authority

The user's instruction `9 и 10 stage делай` on 2026-07-18 approves this Stage
9 contract and the bounded service-side implementation described below. It
does not waive stage order: `docs/plans/ACTIVE_STAGE.md` must first prove the
complete Stage 8 exit and advance to Stage 9.

This approval covers `dossier-service`, its isolated PostgreSQL database,
canonical contracts introduced during the active stage, an isolated local
development dependency and the stateless service-side gateway route. Panel
work is not authorized. Stage 9 therefore cannot claim panel cutover or that
the detail page no longer uses browser stores.

No producer deployable is changed in Stage 9 v1. Missing source subjects stay
truthfully unlinked until a later producer-owned additive contract is approved.

## Ownership and forbidden scope

`dossier-service` is an append-only, read-only cabin-activity projection. It
owns:

- validated sanitized source-fact evidence and delivery identity;
- cabin subject associations, immutable activity rows and current media-group
  projections;
- inbox, partition progress, aggregate checkpoints, quarantine, sanitized DLT
  and replay generations;
- a read-only cabin dossier HTTP query;
- sanitized cabin-activity facts for the later Stage 10 analytics consumer.

It owns no cabin, repair, inventory, logistics, task-board or media command. It
must not call a producer database or synchronously call a producer API to
derive a missing subject. Cross-database foreign keys, shared tables, shared
JPA entities, mutable shared domain models, free-form source text, actor display
names, object keys, signed media URLs and source credentials are forbidden.

General cabin comment, status and immutable manual-note commands remain in
`asset-service`. Dossier projects only the sanitized committed facts currently
present in canonical producer contracts. It never reconstructs missing text,
reasons, actors, dates, links or status transitions.

## Accepted source families and subject rules

All inputs use `DomainEventEnvelopeV2`. Kafka record key must equal
`aggregateId`. The exact producer, topic, aggregate family, event type/version
and strict payload schema are validated before evidence is stored.

| Producer | Accepted cabin subject | Stage 9 visibility |
| --- | --- | --- |
| asset | `rentalItemId` from `RENTAL_ITEM` facts | direct visible activity after warehouse association is proven |
| maintenance | `rentalItemId` from `ESTIMATE` and `REPAIR` facts | direct visible activity |
| inventory | non-null `findingFact.assetId`; `findingId` is the association key | visible after the finding-to-cabin association is proven |
| media | only `ownerType=INVENTORY_FINDING`; `ownerId` is the inventory `findingId` | deferred until the inventory finding association proves its cabin; arrival order is irrelevant |
| logistics | current document facts have no line-level cabin subjects | validate and journal, then record sanitized `SUBJECT_NOT_PROVIDED`; never show as cabin activity |
| task-board | current task facts have no canonical cabin subject | validate and journal, then record sanitized `SUBJECT_NOT_PROVIDED`; never show as cabin activity |

Stage 9 v1 explicitly accepts the last two technical quarantines as truthful
partial coverage. A later producer contract must carry explicit multi-subject
facts because one logistics document may contain multiple cabin lines. A
single inferred `cabinId`, document/task ID parsing, browser joins and legacy
lookups are forbidden.

Inventory owner type is not an unresolved decision: the current media contract
proves `INVENTORY_FINDING`, and the inventory finding fact proves the optional
`assetId`. Media-before-finding and finding-before-media delivery must converge
to the same projection. A finding whose `assetId` is null remains deferred.

## Source references and safe activity vocabulary

A source reference is semantic data, never a browser URL:

```text
sourceRef = { producer, aggregateType, aggregateId, secondaryId? }
```

The HTTP response may expose this reference. A client may map an approved
reference type to its own route; dossier never persists or emits `href`.

Every visible row stores one fixed `activityCode` selected only from the
allowlist below. It does not store or generate a free-form summary:

- asset: `CABIN_CREATED`, `CABIN_PASSPORT_CHANGED`,
  `CABIN_STATUS_CHANGED`, `CABIN_WAREHOUSE_CHANGED`,
  `CABIN_LOGISTICS_EFFECT_APPLIED`, `CABIN_COMMENT_REVISION_CHANGED`,
  `CABIN_MANUAL_NOTE_ADDED`;
- maintenance: `ESTIMATE_CREATED`, `ESTIMATE_DRAFT_CHANGED`,
  `ESTIMATE_COMPLETED`, `ESTIMATE_AMENDED`, `REPAIR_CREATED`,
  `REPAIR_PLAN_CHANGED`, `REPAIR_QUEUED`, `REPAIR_STAGE_COMPLETED`,
  `REPAIR_PENDING_ACCEPTANCE`, `REPAIR_REWORK_CREATED`, `REPAIR_ACCEPTED`,
  `REPAIR_WRITTEN_OFF`;
- inventory: `INVENTORY_FINDING_ADDED`,
  `INVENTORY_INSPECTION_SAVED`, `INVENTORY_PUBLICATION_READY`,
  `INVENTORY_PUBLICATION_REQUESTED`, `INVENTORY_PUBLICATION_SUCCEEDED`,
  `INVENTORY_PUBLICATION_TRANSIENT_FAILED`,
  `INVENTORY_PUBLICATION_BLOCKED`,
  `INVENTORY_PUBLICATION_CLOSED_BLOCKED`;
- media: `MEDIA_READY`, `MEDIA_FAILED`, `MEDIA_ROTATED`, `MEDIA_DELETED`.

An accepted event type with no listed visible mapping is still journaled but
does not create an activity. Unsupported event types or versions fail strict
validation and enter the sanitized DLT. A comment revision does not reveal
comment text or whether text was cleared. A status fact does not invent a
reason or an unproven prior status.

## Persistence contract

Runtime persistence uses Spring Data JPA only. Manual JDBC, `JdbcTemplate`,
native runtime SQL and Spring Data JDBC are forbidden, including for the
technical tables. Flyway SQL remains the sole schema migration mechanism.
Every profile uses `hibernate.ddl-auto=validate`; `baselineOnMigrate=false`.

The cumulative Flyway V1 schema and JPA model must include these distinct
records:

1. `DossierSourceFact` — immutable validated canonical envelope evidence,
   payload SHA-256, topic/partition/offset and ingestion time; unique by
   `eventId` and by topic/partition/offset.
2. `DossierInbox` — delivery decision keyed by `eventId` and payload SHA-256.
   The same ID and hash is a harmless duplicate; the same ID with another hash
   is `EVENT_IDENTITY_CONFLICT` and cannot mutate projections.
3. `DossierPartitionCheckpoint` — the last transactionally accepted offset per
   consumer group/topic/partition. It is separate from aggregate ordering.
4. `DossierAggregateCheckpoint` — applied version, blocked state and gap
   metadata keyed by consumer group, producer, topic, aggregate type and
   aggregate ID.
5. `DossierSubjectAssociation` — producer-owned source identity to cabin ID and
   warehouse snapshot, including the inventory finding-to-cabin association.
6. `DossierActivity` — immutable projection keyed by the deterministic pair
   `(sourceEventId, cabinId)`. It stores cabin ID, required warehouse snapshot,
   activity code, source reference, nullable `occurredAt`, mandatory
   `recordedAt`, opaque actor reference and active replay generation.
7. `DossierMediaProjection` — current state keyed by cabin ID and media ID,
   with inventory finding source, generation and `PROCESSING`, `READY`,
   `FAILED` or `DELETED` state. It stores no filename, MIME metadata, object
   location or URL.
8. `DossierUnlinkedFact` and sanitized DLT records — fixed reason/failure code,
   identifiers and hashes only; never the rejected raw body.
9. `DossierReplayRun` and projection-generation state — high-water marks,
   canonical comparison hashes, counts, state and the single active generation.
10. transactional outbox rows for Stage 10 cabin-activity publication.

JPA entities use field access, explicit lower-snake-case table/column and
constraint names, UUID identifiers, protected no-argument constructors and no
public mutation setters. Entity `@Data`, builders, generated
`equals/hashCode/toString`, and setters for IDs, versions or timestamps are
forbidden. Projection and transport DTOs remain separate records.

## Transaction, ordering and failure rules

Validation, source-fact journal insert, inbox decision, partition checkpoint,
aggregate checkpoint, subject association, projection mutation and any outbox
insert commit in one local PostgreSQL transaction. JPA pessimistic locking or
optimistic retry serializes the relevant aggregate checkpoint. Append-only
activity uniqueness makes concurrent facts from unrelated producers harmless.

Ordering is scoped to producer/topic/aggregate type/aggregate ID. The initial
applied version follows the canonical producer stream origin: `0` for
`media-service`, whose first committed media fact is version `1`, and `-1` for
the other Stage 9 input families, whose first fact is version `0`. A first fact
above that producer-specific expected version is a missing prefix, not an
implicit baseline. That aggregate is quarantined and later versions remain
blocked until the missing sequence is supplied and replayed. Unrelated
aggregates and cabins continue.

The Stage 9 consumer starts from `earliest`. If broker retention no longer
contains a required prefix, the missing history remains explicitly partial;
dossier cannot ask a producer database for it or silently anchor at the first
observed version. Producer replay/backfill is outside Stage 9 v1 because no
producer deployable changes are authorized.

Delivery is at least once. Transient infrastructure failures receive the first
attempt plus retries after 1s, 2s and 4s. Permanent validation failures are not
retried and go to `<source-topic>.dossier-projection-v1.dlt` as sanitized
metadata. Infinite requeue is forbidden. Kafka offset commit occurs only after
the local transaction; a crash before the broker commit redelivers and is
neutralized by the inbox.

Media generations are monotonic. A lower or equal duplicate generation cannot
overwrite newer state; the same generation with conflicting data is an
identity conflict. `DELETED` removes media from visible groups without deleting
the source activity evidence. A later fact is accepted only when its generation
and canonical media transition are valid.

## Replay and backfill

Replay never clears the active projection. It:

1. captures source-journal and partition high-water marks;
2. builds an inactive projection generation only from the immutable validated
   journal;
3. applies the concurrent tail through the captured activation boundary;
4. compares canonical sorted activity/media output, row counts and SHA-256
   against the active generation;
5. activates the new generation with one database CAS transaction only when
   parity succeeds.

The canonical comparison excludes ingestion timestamps and surrogate storage
details. It includes source IDs, cabin/warehouse association, nullable business
time, recorded time, actor reference, activity code, media generation/state
and deletion/rotation effects. A failed comparison leaves the current
generation active.

Backfill is ingestion of producer-owned canonical facts through the same
validator and journal path, never a database copy. Facts retain their producer
event IDs and versions. Reprocessing a journal or backfill produces identical
activity IDs and outbound event IDs.

## Stage 10 publication contract

Each newly visible activity writes a sanitized local outbox fact in the same
transaction. Stage 9 later introduces the canonical schema during active
implementation with these fixed semantics:

- topic: `rwms.dossier.cabin-activity.v1`;
- event type: `dossier.cabin-activity.projected.v1`;
- aggregate type and Kafka key: `CABIN`, keyed by `cabinId`;
- aggregate version: a dossier-owned monotonic per-cabin publication version;
- event ID: stable UUID derived from contract version, source event ID and
  cabin ID; replay/backfill derives the same ID;
- nullable envelope `occurredAt`, mandatory `recordedAt`, opaque actor
  reference and no display name or PII;
- payload: activity ID, cabin ID, warehouse snapshot, fixed activity code and
  semantic source reference only.

The outbox row is marked published only after broker acknowledgement. Explicit
republication for a new Stage 10 consumer preserves event IDs so its inbox can
deduplicate. Dossier tables are never an analytics database and Stage 10 must
consume the published contract or its own retained source journal, not read the
dossier database.

## Planned HTTP contract

The read-only service route is:

```http
GET /api/dossier/v1/cabins/{cabinId}
```

Supported query parameters are:

- `limit`: 1–100, default 50;
- `after`: opaque server-issued stable keyset cursor;
- `occurredFrom`: optional inclusive RFC3339 instant;
- `occurredBefore`: optional exclusive RFC3339 instant and greater than
  `occurredFrom` when both exist;
- repeated `activityCode` and `sourceType` allowlist filters;
- `actorSubjectId`: optional opaque actor UUID.

There is no v1 free-text search and no display-name actor filter. With no time
range, dated and undated facts are returned; when either time bound is present,
undated facts do not match. Ordering is stable and descending by non-null
`occurredAt`, then `recordedAt`, source event ID and cabin ID, with null
`occurredAt` after dated activity. The cursor is versioned, tamper-detected and
bound to the cabin and normalized filters; clients must treat it as opaque.

The response contains cabin ID, immutable activity rows, media state associated
with returned activity/source groups, `nextCursor` and visibility
`COMPLETE|PARTIAL`. It never returns a hidden-row count. `PARTIAL` means at
least one validated row or unresolved source fact is excluded by warehouse
authorization, a missing subject, a missing prefix or quarantine. A null
`occurredAt` remains null; `recordedAt` is labelled technical evidence only.

Authorization requires a valid `USER` principal, `rwms.read` and at least
`VIEW` in the established `warehouse_access` claim for each returned row's
warehouse snapshot. `SYSTEM_ADMIN` and `WMS_ADMIN` are unrestricted. Rows for
other warehouses are omitted and make the result `PARTIAL`. If no visible
evidence proves the cabin to the caller, the service returns 404 rather than
revealing foreign-warehouse existence.

The contract uses RFC 9457 Problem Details and fixed codes:

- `DOSSIER_UNAUTHORIZED` — 401, missing or invalid bearer token;
- `DOSSIER_FORBIDDEN` — 403, authenticated principal/scope is not eligible;
- `DOSSIER_NOT_FOUND` — 404, no caller-visible cabin evidence;
- `DOSSIER_INVALID_FILTER` — 400, invalid limit, instant or allowlist value;
- `DOSSIER_INVALID_CURSOR` — 400, malformed, expired, tampered or
  filter-mismatched cursor.

There are no public HTTP commands, idempotency keys, ETags or mutable-resource
optimistic concurrency contracts.

## Implementation order

Each subgate must pass its focused tests before the next begins.

1. Reconcile the Stage 8 exit, reviewed commit and sole active-stage pointer.
2. While Stage 9 is active, create canonical dossier OpenAPI, consumer and
   cabin-activity schemas plus contract parity tests; do not modify producers.
3. Add architecture guards, `services/dossier-service`, the Gradle module and
   isolated local `dossier-db`; no panel files.
4. Add cumulative Flyway V1, JPA entities/repositories and production
   `ddl-auto=validate` configuration.
5. Implement strict source validators, source journal, inbox, separate
   partition/aggregate checkpoints and transaction boundaries using JPA only.
6. Implement direct asset/maintenance/inventory projection, deferred inventory
   finding/media association and truthful logistics/task-board unlinked facts.
7. Add bounded retry, aggregate-gap quarantine, sanitized DLT and outage
   recovery.
8. Implement inactive replay generations, canonical parity, high-water tailing
   and CAS activation.
9. Implement the Stage 10 cabin-activity outbox, ack-only relay and stable
   replay/backfill identity.
10. Implement the read-only HTTP query, authorization, filters, cursor and
    Problem Details.
11. Add only the stateless service-side gateway route and its negative routing
    tests after the service gate is green.
12. Run the complete matrix, independently review the final diff, reconcile
    durable memory and create one scoped human Stage 9 commit. Do not mark the
    panel cutover complete.

## Required test matrix and exit evidence

### Contracts and architecture

- canonical OpenAPI/event schema parsing and Java fixture parity for every
  accepted visible, unlinked and rejected fact;
- strict producer/topic/key/aggregate/event-version/payload validation and
  prohibited-field tests;
- architecture tests proving JPA-only runtime persistence, no source database
  or HTTP client, no shared domain entity and no gateway state/Kafka/DB;
- fixed activity-code and semantic-source-reference compatibility tests.

### Flyway and JPA

- PostgreSQL Testcontainers clean V1 install, repeat validation, checksum-drift
  rejection and non-empty unversioned-schema rejection;
- explicit baseline policy and Spring JPA validation against the migrated
  schema with `ddl-auto=validate`;
- database constraints for event identity, source offsets, aggregate
  checkpoints, deterministic activity keys, replay generations and outbox.

### Projection, concurrency and authorization

- asset and maintenance direct subjects, inventory null/non-null `assetId` and
  warehouse snapshot behavior;
- media-before-finding, finding-before-media, duplicate delivery, rotation,
  deletion, failed processing, stale generation and conflicting generation;
- logistics/task-board journaling without visible activity and no opaque-ID
  inference;
- same event ID/same hash deduplication and same event ID/different hash
  quarantine;
- concurrent facts for one aggregate and one cabin, plus unrelated aggregate
  progress while another aggregate is blocked;
- USER/scope/VIEW authorization, admins, malformed warehouse claims,
  foreign-warehouse row filtering, partial response and 404 anti-enumeration.

### Kafka failure and recovery

- earliest bootstrap, missing-prefix detection, ordered application,
  duplicate, version gap, blocked-later-event and explicit recovery tests;
- first attempt plus 1s/2s/4s transient retries, permanent validation without
  retry, sanitized DLT and no raw envelope leakage;
- PostgreSQL outage, Kafka outage, broker-ack race, consumer restart and
  partition checkpoint recovery with no duplicate effect;
- cabin-keyed outbound ordering, stable derived event ID, outbox ack-only mark
  and replay/backfill republication identity.

### Replay and HTTP

- deterministic inactive-generation replay from shuffled equivalent delivery,
  concurrent high-water tail, canonical hash/count parity and failed-parity
  no-activation;
- media deletion/rotation and deferred-link parity across replay;
- pagination without gaps/duplicates, null-date ordering, all filter
  combinations, opaque actor filter, cursor tamper/filter mismatch and page
  limits;
- RFC3339 boundary tests and proof that `recordedAt` is never substituted for
  `occurredAt`;
- gateway route forwarding, bearer rejection, no business aggregation and
  missing downstream failure.

The Stage 9 service-side gate is complete only when this matrix is green,
memory is reconciled and one reviewed scoped commit exists. Panel cutover
remains an explicitly uncompleted roadmap item until separately authorized.
