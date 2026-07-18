# Stage 9 Dossier Service Migration

## Scope and outcome

The authorized service-side Stage 9 boundary is complete. The implementation
adds the isolated `dossier-service`, its canonical OpenAPI/AsyncAPI/JSON
schemas, PostgreSQL/Flyway persistence, Kafka consumers and publishers,
deterministic replay, secured read API and stateless gateway route.

No producer deployable or panel file was changed. The browser dossier adapter
was not cut over, so this record does not claim that the warehouse detail page
has stopped reading browser stores. Stage 10/KPI runtime remains explicitly
deferred and unauthorized.

## Contracts and ownership

The canonical entrance and read boundaries are:

- `contracts/openapi/dossier-service.yaml`;
- `contracts/events/dossier-consumers.yaml`;
- `contracts/events/dossier/dossier-cabin-activity-v1.schema.json`;
- `contracts/events/dossier/dossier-sanitized-dlt-v1.schema.json`.

Eleven source topics are accepted across asset, maintenance, inventory, media,
logistics and task-board. Asset/maintenance direct subjects and a non-null
inventory finding `assetId` may create cabin activity. Inventory-finding media
converges through that association in either arrival order. Logistics and
task-board inputs without a canonical cabin subject are journalled only as
sanitized `SUBJECT_NOT_PROVIDED` evidence. No source database/API lookup,
opaque-ID inference, free-form source summary or missing date/actor synthesis
is permitted.

The only public operation is
`GET /api/dossier/v1/cabins/{cabinId}`. It returns fixed activity codes,
semantic source references, current non-deleted media, an opaque stable cursor
and `COMPLETE|PARTIAL` visibility. USER readers need `rwms.read` and warehouse
`VIEW`; administrators retain the approved unrestricted read. Hidden rows are
filtered and hidden-only cabins return 404.

## Flyway V1 and JPA boundary

`V1__dossier_schema.sql` under the dossier service's Flyway location creates
fifteen service-owned tables:

- immutable source journal and inbox;
- partition and aggregate checkpoints;
- projection generations and the active pointer;
- subject associations, append-only activity and current media projection;
- unlinked evidence and sanitized DLT;
- replay run and per-partition high-water records;
- per-cabin publication heads and transactional outbox.

Flyway is the sole schema/checksum authority, `baselineOnMigrate=false`, and
every profile uses Hibernate `ddl-auto=validate`. Runtime persistence for both
business projections and technical inbox/checkpoint/DLT/replay/outbox state is
Spring Data JPA only. Manual JDBC, `JdbcTemplate`, native runtime SQL, Spring
Data JDBC, shared tables/entities and cross-database foreign keys are absent.

The isolated local `dossier-db` is a development/test dependency only; the
validated Compose file is not a deployment manifest.

## Projection, replay and recovery

Envelope validation is strict for producer, topic, Kafka key, aggregate/event
family, version and payload. Delivery identity and source coordinates are
unique. Media begins with aggregate version 1; the other accepted source
families begin with version 0. A missing prefix or later gap blocks only its
source aggregate, while unrelated aggregates and cabins continue.

Duplicates do not duplicate history. Event-identity, subject-identity and
media-generation conflicts fail closed and retain only sanitized failure
metadata. Deferred inventory media uses an immutable finding-to-cabin proof,
and an older fact cannot regress that association or overwrite newer media.

Replay captures journal/partition high-water marks, builds an inactive
generation, applies the bounded tail, compares canonical activity/media counts
and hashes, retires the prior generation and activates the new pointer only in
the successful CAS transaction. A rejected replay leaves the active pointer
unchanged. First-publication contention preserves all facts and does not block
an independent cabin.

Outbound cabin-activity facts are keyed by cabin and use stable derived event
IDs. Outbox and DLT rows are marked published only after broker acknowledgement;
terminal rows require explicit service-local recovery and are not silently
requeued.

## Final review corrections

The closure review fixed and reverified the following cross-cutting cases:

- producer-specific initial stream versions and strict record-key/error
  taxonomy;
- immutable cabin/warehouse subject proof and no provenance regression from
  older deferred facts;
- media generation/lifecycle conflict handling and deleted-media visibility;
- deterministic DLT identity without retaining rejected bodies;
- replay high-water tailing, rejection recovery and generation pointer order;
- `PARTIAL`, filtered-empty/hidden-only, cursor and nullable source-reference
  query semantics;
- fail-closed consumer stop/restart and explicit terminal outbox/DLT recovery;
- 64-bit Kafka offsets and synchronous default-producer acknowledgement.

## Verification evidence

Final Java 25 results on the reconciled Stage 9 tree are:

- `:services:dossier-service:test --no-parallel`: 95/95,
  zero skipped, failures or errors;
- `:platform:architecture-tests:test --no-parallel`: 42/42, zero skipped,
  failures or errors;
- `:services:api-gateway-service:test --no-parallel`: 38/38, zero skipped,
  failures or errors;
- root `verifyApprovedDependencyVersions`: passed;
- `docker compose config --quiet`: passed;
- `git diff --check`: passed.

The combined Gradle test and approved-dependency verification invocation ended
with `BUILD SUCCESSFUL`.

The dossier suite includes Testcontainers PostgreSQL clean/repeat/checksum/
unversioned/JPA validation, real Kafka 4.3.1 acknowledgement and outage
recovery, PostgreSQL outage stop/JPA-health restart/redelivery, sanitized DLT,
deduplication/gaps, media arrival order and lifecycle, deterministic replay,
first-publication concurrency, filters/cursors, authorization, partial
visibility and anti-enumeration. The architecture and gateway suites prove the
JPA-only runtime boundary and stateless public GET route.

No Stage 9 commit SHA is recorded here before the lead creates the scoped
human commit.
