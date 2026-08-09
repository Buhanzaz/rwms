# RWMS Media Service

[Русская версия](README.ru.md)

`media-service` is the single stateful Go runtime for upload authorization,
metadata, immutable MinIO object generations, image/video processing and media
facts. PostgreSQL is its replay authority; Kafka is at-least-once transport.
Browsers upload and read bytes only through authenticated same-origin media API
paths. MinIO remains private: no storage origin, object key, signed URL or
credential is returned to the browser.

Every aggregate event and latest snapshot stores the exact SHA-256-protected
full local state (asset, upload session, processing jobs and variants). Shadow
replay reads only the append-only event stream, proves contiguous stream/head/
snapshot versions and exact bytes, then compares the rebuilt state with the
live projection. Sanitized Kafka facts are a separate representation and are
never used as replay authority.

Images produce `SMALL`, `MEDIUM`, `LARGE` WebP variants and an authorized
`ORIGINAL` without changing the uploaded pixel orientation. Videos are
original-only and validated with FFprobe. An unproved video dimension remains
SQL `NULL`.

## Why this service exists

Media is not a generic shared file bucket in RWMS. A photograph or video is
business evidence that must remain attributable to one warehouse owner, survive
retries without duplicate objects, and be read without exposing storage
credentials. Keeping that responsibility in one stateful service gives every
domain the same safe media lifecycle while preserving its own aggregate
ownership.

This design solves four concrete problems:

- **Private storage:** browsers and other domain services never receive a
  MinIO endpoint, object key, signed URL, or credential.
- **Correct retries:** an `Idempotency-Key` creates one logical media asset;
  an expired unfinished upload session is replaced for that same asset, never
  duplicated. Completed content is never reopened or overwritten.
- **Authoritative access:** public reads and mutations require a USER/WORKER
  JWT plus a current local owner proof and warehouse scope. Caller-provided
  owner or warehouse values are never trusted by themselves.
- **Recoverable delivery:** PostgreSQL owns state and exact replay; Kafka
  carries at-least-once facts through a transactional outbox and idempotent
  consumers. A broker outage cannot make Kafka the media database.

## How the lifecycle works

The canonical HTTP boundary is
[`contracts/openapi/media-service.yaml`](../../contracts/openapi/media-service.yaml).
Interactive clients use the public same-origin media routes through the API
gateway; private `/api/internal/**` routes are service-to-service only.

1. A client creates an upload session with an `Idempotency-Key`, the proven
   owner context, declared MIME type, length, and SHA-256 checksum.
2. It streams bytes to the returned same-origin content path. Media-service
   serializes ingress for that session, verifies the declared content and pins
   the exact private MinIO version.
3. It completes the session with the same idempotency key. The service commits
   the upload fact and processing request atomically.
4. A Kafka worker creates a canonical image original plus WebP variants, or
   validates and copies a video original. It then publishes a safe
   invalidation; clients refresh their scoped projection.
5. Scoped reads stream only the pinned object version through media-service
   with `private, no-store` headers. Logical deletion changes PostgreSQL state
   and emits one fact; it never physically deletes versioned bytes.

The design deliberately avoids direct client-to-MinIO uploads and mutable
public URLs. Those approaches would leak storage authority, make object
versions and retry outcomes ambiguous, and let an owner change race a media
read. The service instead makes authorization, version pinning, and ownership
checks one coherent server-side operation.

## Schema startup gate

Flyway is external to this process. Apply
`db/migration/V1__media_schema.sql` and
`db/migration/V2__media_runtime_recovery.sql`, then
`db/migration/V3__inventory_owner_proof.sql` and
`db/migration/V4__cabin_owner_bindings.sql`, then the additive
`V4_1__prepare_legacy_photo_folder_backfill.sql`,
`db/migration/V5__media_photo_folders.sql` and
`V5_1__restore_runtime_source_guard.sql`, then
`db/migration/V6__service_owner_proofs_and_soft_delete.sql`, then
`db/migration/V7__dynamic_cabin_owner_projection.sql`, then
`db/migration/V8__task_board_worker_media.sql` and
`db/migration/V9__asset_import_worker.sql`, then
`db/migration/V10__canonical_cabin_photo_library.sql`, then
`db/migration/V11__bounded_media_processing_recovery.sql` before starting the
service. The Go application never migrates, baselines, repairs or silently
adopts a database.

- New local/test databases migrate through V1 to V11.
- A database already at the exact V10 history is upgraded by applying V11.
- `baselineOnMigrate` must remain `false`; a non-empty unversioned database is
  rejected.
- Startup verifies both successful Flyway history rows, their versions,
  descriptions, SQL type and exact checksums. Missing, extra, failed or changed
  migrations fail closed before HTTP ingress starts.
- V1 outbox JSONB has no proven original wire bytes and is never copied into the
  active V2 transport outbox. Published rows are recorded only as
  `PUBLISHED_HISTORICAL`; unpublished rows are evidence-hashed, quarantined as
  `UNPUBLISHED_QUARANTINED` and cannot be republished.

V1 and the legacy union event schema are compatibility evidence and must not be
edited.

## Required configuration

All resource bounds are explicit; production-size defaults are intentionally
absent.

| Variable | Meaning |
|---|---|
| `MEDIA_RUNTIME_PROFILE` | Required `production` or `local-test` profile |
| `MEDIA_DATABASE_URL` | PostgreSQL URL for the media-owned database |
| `MEDIA_AUTH_ISSUER` | Exact JWT issuer URL |
| `MEDIA_AUTH_JWKS_URL` | Issuer JWKS URL |
| `MEDIA_MINIO_ENDPOINT` | MinIO host and port |
| `MEDIA_MINIO_ACCESS_KEY` | MinIO access key |
| `MEDIA_MINIO_SECRET_KEY` | MinIO secret key |
| `MEDIA_MINIO_BUCKET` | Dedicated media bucket |
| `MEDIA_MINIO_USE_SSL` | Explicit `true` or `false` |
| `MEDIA_MAX_UPLOAD_BYTES` | Maximum immutable source size |
| `MEDIA_MAX_DECODED_PIXELS` | Maximum decoded image pixels |
| `MEDIA_MAX_IMAGE_OUTPUT_BYTES` | Maximum encoded image output |
| `MEDIA_ALLOWED_MIME_TYPES` | Comma-separated allowlist: JPEG, PNG, WebP, MP4 or WebM |
| `MEDIA_UPLOAD_EXPIRY` | Constrained upload capability lifetime, for example `5m` |
| `MEDIA_PROCESSING_TIMEOUT` | Per-job processor timeout |
| `MEDIA_KAFKA_BROKERS` | Comma-separated Kafka bootstrap addresses |
| `MEDIA_KAFKA_INVENTORY_TOPIC` | Canonical inventory fact topic |
| `MEDIA_KAFKA_INVENTORY_OWNER_GROUP` | Dedicated media owner-proof consumer group |
| `MEDIA_KAFKA_INVENTORY_OWNER_DLT_TOPIC` | Media-owned inventory owner consumer DLT |
| `MEDIA_KAFKA_ASSET_RENTAL_ITEM_TOPIC` | Canonical asset rental-item fact topic |
| `MEDIA_KAFKA_CABIN_OWNER_GROUP` | Dedicated dynamic CABIN owner consumer group |
| `MEDIA_INSTANCE_ID` | Unique safe ASCII lease/fence owner ID |

When a video MIME type is allowed, `MEDIA_MAX_VIDEO_DURATION` and the
comma-separated `MEDIA_ALLOWED_VIDEO_CODECS` are also required.

Optional settings are `MEDIA_HTTP_ADDRESS` (default `:8085`),
`MEDIA_MANAGEMENT_ADDRESS` (default `127.0.0.1:9095`, an explicit numeric IPv4
or IPv6 loopback host with a non-zero TCP port), and `MEDIA_AUTH_AUDIENCE`
(default `rwms-services`). The processing group and topic variables have
canonical defaults and may not be changed:
`media-service-processing-v1`, `rwms.media.media.v1` and
`rwms.media.processing.v1`. Terminal processing failures publish only a
hash-only record to
`rwms.media.processing.v1.media-service-processing-v1.dlt`.
The inventory owner consumer settings are likewise fixed to
`rwms.inventory.session.v1`, `media-service-inventory-owner-v1` and
`rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt`.
The CABIN owner consumer settings are fixed to
`rwms.asset.rental-item.v1` and `media-service-cabin-owner-v1`.

`production` requires HTTPS issuer/JWKS URLs and TLS MinIO. Plain HTTP and
`MEDIA_MINIO_USE_SSL=false` are accepted only under the explicit `local-test`
profile. Startup and readiness verify bucket versioning; unauthenticated API
requests are rejected before any MinIO operation.

## Storage and owner safety

The bucket must have versioning enabled. Authenticated upload ingress is
serialized per upload session across service instances, streams the exact
declared length to private MinIO, and commits finalization before acknowledging
the caller. Finalization verifies and pins the exact object version, ETag,
length, content type, checksum and content sniff; derived writes are
version-pinned as well. Public reads stream the pinned version through the media
API with private/no-store headers. The runtime has no unversioned download path
and no physical object delete, retention or orphan-cleanup behavior. The
owner-scoped deletion command is a PostgreSQL soft-delete only: it preserves all
MinIO versions and variant provenance while emitting one DELETED fact.

Public access is restricted to a `USER` JWT with a UUID subject, exact RWMS
scope and warehouse grant. Canonical owner/context pairs cover inventory,
cabins, maintenance estimates/repairs/acceptance/catalog nodes and logistics
returns/shipments/transfers. All are authorized from local owner bindings,
never from caller-supplied owner/warehouse values. Logistics browser requests
send only `documentId` and `lineId`; the composite persistence identity is
derived inside media-service. The V4 CABIN bindings retain the 195 deterministic
old-panel cabins as migration evidence. The required asset rental-item consumer
registers every server-created UUID from its version-0 create fact and then owns
the live warehouse, revision and active state. There is no proof TTL: activity
is determined by the binding checkpoint and quarantine state.

`POST /api/media/v1/cabin-covers` returns a bounded warehouse batch. Its
`photoCount` counts logical non-deleted IMAGE assets. `previews` contains at
most 100 READY images in canonical asset order, with exactly one `SMALL`
variant per logical image; `cover` is the first preview for compatibility.
MEDIUM, LARGE, ORIGINAL and object-store locations are never returned by this
projection.

Task 1B consumes only canonical FINDING markers and owner-proof facts. A stream
must begin with `inventory.finding.added.v1` version 0; later facts are
contiguous and must retain the bootstrapped warehouse. Event-ID reuse,
warehouse changes, gaps, regressions and owner-revision conflicts quarantine
the affected aggregate and immediately make public owner operations fail
closed. Invalid or exhausted records publish only the sanitized media-owned
DLT contract. Reconciliation is an operator-only file command; there is no
public registration or administrative bypass:

```bash
MEDIA_DATABASE_URL=... media-service reconcile-inventory-owner reviewed-batch.json
```

## Private Yandex.Disk asset imports

`asset-service` may call the private asset-import endpoints only with an exact
SERVICE JWT (`sub=client_id=asset-service`, audience `rwms-services`, sole
scope `media.asset-import`). Preflight accepts only
`https://disk.yandex.ru/d/<14-character-key>`, stores the parsed key and
resource state privately, and recursively lists the official public-resources
API without OAuth. It never downloads while preflighting.

Activation binds every source row to a current CABIN owner, then the durable
worker obtains a fresh download address, follows only HTTPS Yandex-domain
targets whose DNS addresses are public, limits redirects and response time,
and never logs a URL/key/href. JPEG, PNG and WebP are sent through the normal
versioned ingress, owner-proof, 100-assets-per-owner, processing and outbox
invariants. Video, folder ZIP and every other type are retained only as a
sanitized skipped warning. The worker retries transient Yandex and service
dependency failures up to three attempts; a terminal phase can be retried by its private idempotent
endpoint. Per job, imports are bounded to 500 source rows, 100 discovered files
per source and 25,000 discovered files total. Recursive public-resource metadata
enumeration is also capped at 64 pages per source; every downloaded byte is
bounded by `MEDIA_MAX_UPLOAD_BYTES`.

The CABIN stream must start with `asset.rental-item.created.v1` version 0.
Passport, status, warehouse and logistics-effect facts carry the complete
sanitized owner proof; comment and manual-note facts are ordering markers.
`WRITTEN_OFF` is terminal and deactivates the binding. Exact duplicates are
idempotent; gaps, regressions, identity/revision conflicts and attempted
terminal reactivation quarantine the rental item and make every public CABIN
media path fail closed. Reviewed contiguous recovery uses the separate
operator command:

```bash
MEDIA_DATABASE_URL=... media-service reconcile-cabin-owner reviewed-batch.json
```

Maintenance and logistics services establish their own scopes through
`POST /api/internal/media/v1/owner-proofs` with exact service identity/scope.
The first receipt establishes a baseline. Every later owner proof requires the
exact next owner revision, while its aggregate version must strictly advance
but may skip aggregate mutations that do not emit a media proof. Exact event
and payload replay is idempotent; owner-revision non-increase/gap/regression,
aggregate-version non-increase/regression and event-ID conflicts quarantine the
owner. An exact replay of a receipt quarantined only by the former
`AGGREGATE_VERSION_GAP` rule is re-evaluated under these rules and can recover;
no other quarantine cause is relaxed. For returns the proof warehouse is the
receiving destination. For transfer-line acceptance it is
`destinationWarehouseId`, never the source warehouse.

The PostgreSQL transport outbox publishes only exact stored bytes. Broker
outages return rows to `PENDING` with bounded DB backoff and never exhaust into
a DLT; only a permanent exact-byte checksum corruption can terminally fail an
outbox row. Processing attempts remain first delivery plus 1s/2s/4s transient
retries; validation is terminal on its actual attempt. A DLT record uses the
source processing-job UUID key for a valid request, or deterministic UUIDv5 in
the OID namespace over SHA-256 of the raw bytes for an invalid request.

## Bounded processing recovery

The processing consumer classifies malformed Kafka input, a transient object
dependency/timeout, a terminal processor result and an offset-commit failure as
separate outcomes. Its exact behavior is owned by
[`internal/worker/consumer.go`](internal/worker/consumer.go) and the durable
state transition is owned by
[`internal/persistence/worker.go`](internal/persistence/worker.go).

- A poison record is written as a hash-only `INVALID_PROCESSING_REQUEST` DLT
  before its offset is acknowledged, so the next valid record can proceed.
- A dependency failure receives at most four attempts in one durable cycle,
  with 1s/2s/4s retry scheduling. Two consecutive dependency failures open a
  five-second circuit; the next attempt is a single half-open probe. A
  validation or other permanent processor result is terminal on its current
  attempt.
- If the fourth lease expires before an outcome is persisted, one claimant
  receives a new fence without incrementing either attempt counter and records
  `PROCESSING_ATTEMPT_EXHAUSTED` without a fifth processor call. The same code
  is emitted in the sanitized DLT under the compatible closed enum in
  [`media-processing-dlt-v1.schema.json`](../../contracts/events/media/media-processing-dlt-v1.schema.json).
- Persistence of an outcome is retried at most four times. Offset commit is
  retried at most three times, each with a three-second context deadline. On
  exhaustion or shutdown, the consumer releases the blocked rebalance and
  leaves the offset uncommitted. A later delivery observes the exact inbox
  result and cannot create a second READY fact or variant set.
- [`V11__bounded_media_processing_recovery.sql`](db/migration/V11__bounded_media_processing_recovery.sql)
  constrains the per-cycle attempt to `0..4` and stores versioned terminal and
  review evidence. Review identity, reviewer UUID, closed decision/reason and
  source message SHA-256 are retained; source payload, object coordinates and
  free-form errors are not. An existing failed job is linked to source evidence
  only when exactly one eligible DLT row exists; zero or multiple rows become
  `LEGACY_TERMINAL` without guessed source identity.
- A retry approval is evidence only. It is version-fenced and idempotent, but
  does not requeue a terminal job. There is currently no authenticated operator
  API/command for that follow-up transition; it must be designed before retry
  execution is exposed. `ATTEMPT_BUDGET_RESET` records review of an exhausted
  attempt cycle but likewise performs no reset or requeue by itself.

The typed, fixed-cardinality recovery snapshot continues to be emitted through
structured logs and is also copied to standard-library OpenMetrics text at
`GET /metrics`. [`cmd/media-service/main.go`](cmd/media-service/main.go)
composes the exporter from
[`internal/observability/processing_metrics.go`](internal/observability/processing_metrics.go)
with the separate private listener in
[`cmd/media-service/metrics_runtime.go`](cmd/media-service/metrics_runtime.go).
[`internal/config/config.go`](internal/config/config.go) rejects wildcard,
hostname, public, non-loopback and zero-port management binds; the public
`api.Server` handler receives no metrics route.

The exporter exposes durable active/pending/running and terminal-review counts,
oldest active age, maximum cycle attempt, and the fixed one-hot
closed/open/half-open breaker state. Handled and committed offsets use only a
numeric `partition` label; an unavailable committed offset is omitted. Each
current snapshot retains at most the lowest 64 non-negative partition IDs, so
partition labels cannot grow without a cap. It contains no media, event or user
IDs, payload, error text, topic label or free-form label. Alert thresholds and
runtime rollout remain open because the Task 0 baseline and deployment
authorization are absent.

## Private logistics media boundaries

`POST /api/internal/media/v1/logistics/references/validate` requires the exact `logistics-service` SERVICE JWT with
matching `sub`/`client_id` and one `media.logistics` scope. The request derives
an opaque owner ID from `documentId:lineId`, permits only
`LOGISTICS_RETURN`, `LOGISTICS_SHIPMENT` and `LOGISTICS_TRANSFER`, and validates
one to twenty unique `{mediaId,generation}` values for the matching warehouse.

The query succeeds only for a current logistics owner proof and current `READY`
generations, and returns only the
validated opaque IDs/generations. It never exposes a URL, object key,
filename, MIME type, processing state or retention policy. It is read-only:
no upload, owner binding mutation, event, outbox, Kafka consumer or object
storage call is made.

`POST /api/internal/media/v1/logistics/cabin-presentations/snapshots` uses the
same exact SERVICE identity and scope. It accepts one to one hundred unique
CABIN IDs for one warehouse and returns only current canonical bindings plus
READY/current image `{mediaId,generation,sortOrder,availableVariants}`
references. No browser path, object-store coordinate, signed URL, filename,
MIME type or processing data is returned.

`GET /api/internal/media/v1/logistics/cabin-presentations/assets/{mediaId}/variants/{variant}/content`
is the paired private byte stream. `variant` is exactly `SMALL` or `LARGE`; the
request must name the CABIN, warehouse and current generation. Every owner,
warehouse, state, generation and variant mismatch is an opaque 404. There is
no public logistics presentation-media route; logistics owns any later
browser-facing proxy.

## Structural architecture gate

[`internal/architecture/architecture_test.go`](internal/architecture/architecture_test.go)
parses non-test Go source and derives the module identity from `go.mod`. It
requires this module to retain exactly one executable package at
`cmd/media-service`, then checks that the service-local import graph resolves,
remains acyclic and follows the reviewed foundation, capability, persistence,
delivery, observability and composition directions. A new production package
has no implicit role: the gate fails until its ownership direction is reviewed
and classified.

The same gate prevents public API, worker and observability packages from
using datastore or object-store clients directly. Observability may consume
only the typed worker snapshot, and telemetry structures and structured-log
keys cannot acquire payload, credential, domain-identity, object-coordinate or
raw-error details. These are source boundaries, not substitutes for runtime,
contract or integration tests.

Run the focused gate with:

```bash
go test ./internal/architecture -count=1
```

## Local verification

The build host needs Go 1.25, CGO, libvips, FFmpeg and FFprobe.

```bash
gofmt -w ./cmd ./internal
go test ./...
go build -trimpath -o /tmp/rwms-media-service ./cmd/media-service
```

Migration verification must run separately with Flyway and PostgreSQL and cover
clean V1-to-V11 install, V10-to-V11 upgrade, repeat, checksum drift and non-empty
unversioned rejection. MinIO integration checks must use a versioned local/test
bucket; Kafka checks must use the canonical topics and broker acknowledgements.
