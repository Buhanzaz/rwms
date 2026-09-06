# RWMS Media Service

[Русская версия](README.ru.md)

`media-service` is the single stateful Go runtime for upload authorization,
metadata, immutable MinIO object generations, video processing and media
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

ManagerApp and WorkerApp physically orient still images and produce `SMALL`,
`MEDIUM`, and `LARGE` WebP variants whose combined size is at most 1 MiB. They
upload only those variants; the authorized logical `ORIGINAL` aliases the
immutable `LARGE` object. Go never decodes, rotates, compresses, or rewrites
still images. Retained single-source clients remain readable through four
logical aliases to their exact pinned source. Videos retain the
exact uploaded `ORIGINAL` and produce a compressed `PLAYBACK` MP4 with H.264,
yuv420p, optional AAC audio, stripped metadata and a 1280x720 maximum while
preserving aspect ratio and even dimensions. FFprobe validates the source and
result; unproved video dimensions remain SQL `NULL`.

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
- **Task evidence clients:** a `WORKER` principal may access
  `TASK_BOARD_ENTRY/WORK_RESULT` media with exactly one of `worker.tasks` or
  `driver.tasks`, its exact `worker_id`, and its single `warehouse_id`; a token
  carrying both task scopes is rejected. A current non-quarantined task proof
  has separate read and upload audiences: a feed/detail-visible worker may use
  read-only list/original/variant access, while upload creation and finalization
  require the active proof's assigned/evidence worker. Closure retains only the
  historical assigned/evidence readers. Every task-entry authorization locks
  its local proof before binding and audience rows, matching proof replacement
  order so a concurrent projection refresh cannot deadlock media access.
- **Driver-shift evidence:** DriverApp uses the separate canonical
  `DRIVER_SHIFT/SHIFT_EVIDENCE` owner only with `driver.tasks`, its exact
  `worker_id`, warehouse and a current non-quarantined task-board proof.
  Reservation `evidenceId` is the stable media client reference, so replay
  returns one asset and finalized `READY` facts complete that exact task-board
  reservation. WorkerApp and manager identities cannot use this owner.
- **Customer-bound logistics media:** the exact `USER/CUSTOMER`,
  `rwms-customer-android` identity with `customer.rental` as its only domain scope may use only
  `LOGISTICS_SHIPMENT/SHIPMENT` or
  `LOGISTICS_CUSTOMER_PROFILE/PROFILE_AVATAR` media whose current logistics
  owner proof is bound to that JWT subject. Only the OIDC protocol scopes
  `openid`, `profile` and `offline_access` may accompany `customer.rental`; any other scope
  is rejected, including manager, administrator and worker permissions. The selected active warehouse is
  an authorization scope for the profile avatar, not profile identity. Create
  replay, upload session, source/variant content, finalize, list, reads and
  logical delete all recheck the same subject; a customer-shaped malformed
  token, another subject, manager or worker fails closed for profile media.
- **Recoverable delivery:** PostgreSQL owns state and exact replay; Kafka
  carries at-least-once facts through a transactional outbox and idempotent
  consumers. A broker outage cannot make Kafka the media database.

## How the lifecycle works

The canonical HTTP boundary is
[`contracts/openapi/media-service.yaml`](../../contracts/openapi/media-service.yaml).
Interactive clients use the public same-origin media routes through the API
gateway; private `/api/internal/**` routes are service-to-service only.

1. A client creates an upload session with an `Idempotency-Key`, the proven
   owner context, and either one declared source or exactly three declared
   WebP parts. The WebP-bundle checksum is the SHA-256 of the canonical
   `rwms-image-variants-v1` manifest.
2. It streams the source to one same-origin path or uploads `SMALL`, `MEDIUM`,
   and `LARGE` through three independently locked same-origin paths. Each PUT
   is length/SHA/version/ETag verified and may run concurrently; MinIO stays
   private and media-service does not buffer or decode the image.
3. It completes the session with the same idempotency key. The service commits
   the upload fact and processing request atomically. Source-mode finalization
   reloads the expected length, content type, and checksum from the locked
   upload session before accepting immutable object metadata
   ([`FinalizeUpload`](internal/persistence/repository.go)).
4. The durable Kafka worker promotes verified image-part metadata to one READY
   generation without object I/O. For a retained single-source image it points
   all logical image variants at that exact source. Video processing still
   validates the source, preserves its exact original and creates compressed
   MP4 playback. The worker then publishes a safe invalidation; clients refresh
   their scoped projection. For direct CABIN photos in one gallery folder, the
   READY photo with the smallest stable `(sortOrder, attachedAt, mediaId)` tuple
   is the cover, so asynchronous processing order cannot override the title
   selected by the client. Explicit covers from task evidence, inventory, or a
   different folder are not replaced by this rule
   ([`associateProcessedCabinImage`](internal/persistence/cabin_photo_library.go)).
5. Scoped reads stream only the pinned object version through media-service
   with `private, no-store` headers. Logical deletion changes PostgreSQL state
   and emits one fact; it never physically deletes versioned bytes.
6. After an inventory final plan completes, inventory-service may select its
   exact READY finding-owned image generations as the CABIN's current gallery
   folder. Media-service changes only its association and cover projections;
   earlier folders, media rows, variants and MinIO versions remain retained.

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
`db/migration/V11__bounded_media_processing_recovery.sql`, then
`db/migration/V12__video_playback_variant.sql`, then
`db/migration/V13__authoritative_inventory_cabin_photos.sql`, then
`db/migration/V14__task_board_reader_audience.sql`, then
`db/migration/V15__inventory_finding_membership_markers.sql`, then
`db/migration/V16__client_image_variants.sql`, then
`db/migration/V17__consolidate_legacy_cabin_photo_folders.sql`, then
`db/migration/V18__customer_shipment_subject_binding.sql`, then
`db/migration/V19__customer_profile_avatar_owner.sql`, then
`db/migration/V20__driver_shift_media_owner.sql`, then the immutable applied
`db/migration/V21__media_asset_company_boundary.sql`, then the immutable
`db/migration/V22__task_board_worker_profile_avatar_owner.sql`, then
`db/migration/V23__remove_media_company_boundary.sql` before starting the
service. The Go application never migrates, baselines, repairs or silently
adopts a database.

- New local/test databases migrate through V1 to V23.
- V21 and V22 remain immutable applied history. V23 is the forward migration
  that removes the obsolete platform-ownership columns after rejecting a
  database whose rows prove more than one owner. V14 adds
  and backfills the task-entry read audience without deleting owner proofs,
  media rows or objects. V15 replaces only
  `media_inventory_finding_inbox_check2`: canonical departed, refreshed and
  restored membership markers are admitted with a null `owner_revision`, while
  existing inbox rows, owner proofs and media data remain unchanged. V16 adds
  image-bundle session/part metadata, defaults every existing session to
  `SOURCE`, and permits `ORIGINAL` and `LARGE` rows to reference the same
  immutable object; it does not rewrite or delete existing media. V17 groups
  only the one-photo `BACKFILL` associations of each cabin into one legacy
  archive folder and refreshes the cover-folder pointer. It deletes no media,
  association or object, preserves source `media_asset.folder_id` values, and
  leaves inventory, direct-upload and task-evidence folders unchanged.
  V18 adds nullable, owner-scoped shipment subject columns and leaves every
  existing owner unbound. V19 admits the non-structured
  `LOGISTICS_CUSTOMER_PROFILE`/`CUSTOMER_PROFILE` proof, requires its exact
  subject binding, and preserves every existing media, proof and shipment row.
  V20 additively admits `DRIVER_SHIFT/SHIFT_EVIDENCE`, its stable unique
  reservation reference, isolated owner-proof inbox/projection/audiences and
  conflict quarantine; existing assets, proofs and object versions are not
  rewritten or removed. V22 additively admits the task-board worker profile
  avatar owner without rewriting existing assets. V23 only removes the
  obsolete platform-ownership boundary after its single-owner preflight.
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
| `MEDIA_ALLOWED_MIME_TYPES` | Comma-separated allowlist: JPEG, PNG, WebP, MP4 or WebM |
| `MEDIA_UPLOAD_EXPIRY` | Constrained upload capability lifetime, for example `5m` |
| `MEDIA_PROCESSING_TIMEOUT` | Per-job processor timeout |
| `MEDIA_KAFKA_BROKERS` | Comma-separated Kafka bootstrap addresses |
| `MEDIA_KAFKA_INVENTORY_TOPIC` | Canonical inventory fact topic |
| `MEDIA_KAFKA_INVENTORY_OWNER_GROUP` | Dedicated media owner-proof consumer group |
| `MEDIA_KAFKA_INVENTORY_OWNER_DLT_TOPIC` | Media-owned inventory owner consumer DLT |
| `MEDIA_KAFKA_ASSET_RENTAL_ITEM_TOPIC` | Canonical asset rental-item fact topic |
| `MEDIA_KAFKA_CABIN_OWNER_GROUP` | Dedicated dynamic CABIN owner consumer group |
| `MEDIA_KAFKA_DRIVER_SHIFT_OWNER_PROOF_TOPIC` | Canonical task-board driver-shift proof topic |
| `MEDIA_KAFKA_DRIVER_SHIFT_OWNER_PROOF_GROUP` | Dedicated driver-shift proof consumer group |
| `MEDIA_INSTANCE_ID` | Unique safe ASCII lease/fence owner ID |

When a video MIME type is allowed, `MEDIA_MAX_VIDEO_DURATION`,
`MEDIA_MAX_VIDEO_OUTPUT_BYTES` and the comma-separated
`MEDIA_ALLOWED_VIDEO_CODECS` are also required. The output limit bounds both
ffmpeg and the validated derived file and must not exceed
`MEDIA_MAX_UPLOAD_BYTES`. `MEDIA_PROCESSING_TIMEOUT` must cover the slowest
permitted video transcode; the worker lease automatically extends thirty
seconds beyond that timeout.

Optional settings are `MEDIA_HTTP_ADDRESS` (default `:8085`),
`MEDIA_HTTP_READ_TIMEOUT` and `MEDIA_HTTP_WRITE_TIMEOUT` (both default `5m`,
matching the gateway's bounded media-transfer window),
`MEDIA_MANAGEMENT_ADDRESS` (default `127.0.0.1:9095`, an explicit numeric IPv4
or IPv6 loopback host with a non-zero TCP port), and `MEDIA_AUTH_AUDIENCE`
(default `rwms-services`), plus `MEDIA_FFMPEG_EXECUTABLE` (default `ffmpeg`) and
`MEDIA_FFPROBE_EXECUTABLE` (default `ffprobe`). When video is enabled, startup
resolves both executables before opening runtime dependencies; normal
distribution `ffmpeg` packages install both binaries. The processing
group and topic variables have
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
The driver-shift owner settings are fixed to
`rwms.task-board.driver-shift-owner-proof.v1` and
`media-service-driver-shift-owner-proof-v1`.

`production` requires HTTPS issuer/JWKS URLs and TLS MinIO. Plain HTTP and
`MEDIA_MINIO_USE_SSL=false` are accepted only under the explicit `local-test`
profile. Startup and readiness verify bucket versioning; unauthenticated API
requests are rejected before any MinIO operation.

## Storage and owner safety

The bucket must have versioning enabled. Single-source ingress is serialized
per upload session; image-bundle ingress is serialized per variant so the
three bounded PUTs can run concurrently across service instances. Every path
streams the exact declared length to private MinIO. Finalization verifies and
pins the exact object version, ETag, length, content type and checksum; legacy
single-source ingress also retains content sniffing. Public reads stream the pinned version through the media
API with private/no-store headers. The runtime has no unversioned download path
and no physical object delete, retention or orphan-cleanup behavior. The
owner-scoped deletion command is a PostgreSQL soft-delete only: it preserves all
MinIO versions and variant provenance while emitting one DELETED fact. When the
deleted asset is the current CABIN cover, that transaction clears both current
library pointers and advances the library version; CABIN associations, media
rows, variants and versioned object bytes/history remain retained. It does not
automatically select a replacement cover
([`Repository.Delete`](internal/persistence/soft_delete.go)).

Manager public access is restricted to a `USER` JWT with a UUID subject,
exact RWMS scope and warehouse grant. CustomerApp instead uses the exact
customer identity and subject-bound shipment proof above. Canonical
owner/context pairs cover inventory,
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
`photoCount` and at most 100 READY `previews` cover only the logical images in
`media_cabin_photo_library.active_gallery_folder_id`, with the explicit
canonical cover first, the remaining images in stable association order and
exactly one `SMALL` variant per logical image. The private logistics snapshot
uses that same active-folder boundary, returns the full logical `photoCount`
and at most 100 READY references with zero-based presentation positions. The
count includes current-folder images that are still processing, so a consumer
can reject an incomplete or oversized immutable presentation instead of
freezing a truncated list. A library without an active folder produces a zero
count and no current photos. A newer direct upload batch atomically becomes active when
its first image is READY, and its deterministic lowest
`(sortOrder, attachedAt, mediaId)` READY image becomes the cover even when
processing completes out of order. Folder recency is fenced by the newest
retained association timestamp, including images that are still processing, so
a delayed older batch cannot reclaim the pointer. Older folders remain retained
with their boundaries in the full CABIN archive.
MEDIUM, LARGE, ORIGINAL and object-store locations are never returned by the
public projection. A CABIN owner read through `GET /api/media/v1/assets`
remains the full archive: its projected `folderId` comes from the CABIN
association, so inventory photos form one deterministic folder without
changing their finding-owned `media_asset` rows.

Task 1B consumes only canonical FINDING markers and owner-proof facts. A stream
must begin with `inventory.finding.added.v1` version 0; later facts are
contiguous and must retain the bootstrapped warehouse. Event-ID reuse,
warehouse changes, gaps, regressions and owner-revision conflicts quarantine
the affected aggregate and immediately make public owner operations fail
closed. Invalid or exhausted records publish only the sanitized media-owned
DLT contract. Reconciliation is an operator-only file command; there is no
public registration or administrative bypass:

Membership-departed, membership-refreshed and completed-observation-restored
facts are ordering/checkpoint markers only; they never open or close the media
owner proof. `membershipActive` is optional on historical added/inspection
markers. Live Kafka lifecycle markers stay strict: a departed marker must carry
`false` and a refreshed/restored marker must carry `true`. Only the
operator-reviewed reconciliation command accepts exact authoritative legacy
lifecycle bytes that predate `membershipActive`; it infers `false` for departed
and `true` for refreshed/restored without normalizing the wire body or its SHA.
Completed restoration therefore lets the publication saga address retained
finding media without reopening worker upload authority.
[`V15__inventory_finding_membership_markers.sql`](db/migration/V15__inventory_finding_membership_markers.sql)
admits those three markers into the durable inbox while requiring their
`owner_revision` to remain null.

```bash
MEDIA_DATABASE_URL=... media-service reconcile-inventory-owner reviewed-batch.json
```

## Authoritative inventory cabin folders

`PUT /api/internal/media/v1/inventory/outcomes/{inventoryId}/findings/{findingId}/cabin-photos`
accepts only an exact inventory-service SERVICE JWT with sole scope
`media.inventory` and a UUID `Idempotency-Key`. The exact transport shape is
owned by
[`media-service.yaml`](../../contracts/openapi/media-service.yaml); the
serializable transition is owned by
[`inventory_cabin_photos.go`](internal/persistence/inventory_cabin_photos.go).

The transaction requires an active, non-quarantined CABIN binding and a
retained, checkpointed INVENTORY_FINDING binding in the same warehouse. An
unresolved finding quarantine is rejected unless it is a `VERSION_GAP` whose
expected version is strictly later than the retained binding's proof aggregate
version; that bounded case reuses proof checkpointed before the later gap and
does not apply or trust the quarantined events. An active CABIN quarantine
always blocks. Public finding upload and list/original/variant reads still
require active, non-quarantined proof. This SERVICE-only completed-outcome
command may reuse the finding's existing READY evidence after completion closed
that proof; it does not reopen uploads. Every unique selected reference must be
a finding-owned READY IMAGE at its exact current generation, and the cover must
be one of those references. The immutable request fingerprint derives one
stable gallery folder. Exact-key replay returns its frozen receipt; changed key
reuse fails.
The per-CABIN watermark rejects an older completion and a different immutable
source at the same completion time. A new key may reassert the same latest
source after a later task/direct cover change.

At that same completion time, a strictly higher final-plan version for the same
inventory and finding is accepted only when its exact media IDs, generations,
source revision and prior folder associations are unchanged. The transaction
keeps the stable folder and library version, advances only the association
source metadata, receipt and watermark, and rolls back if the photo set drifted.

[`V13__authoritative_inventory_cabin_photos.sql`](db/migration/V13__authoritative_inventory_cabin_photos.sql)
backfills association folders from `media_asset.folder_id`, backfills the
active folder from the existing cover, and adds inventory receipt, watermark
and source-audit state. It does not update or delete `media_asset`,
`media_variant` or object-store data. Inventory associations use the derived
folder in the full CABIN archive. Current cover/previews and the private
logistics presentation include only the active folder and put its explicit
cover first. Existing direct and task-evidence associations stay in history,
and selecting task evidence also selects its own association folder.
[`V17__consolidate_legacy_cabin_photo_folders.sql`](db/migration/V17__consolidate_legacy_cabin_photo_folders.sql)
consolidates only pre-library `BACKFILL` associations into one legacy folder per
cabin; inventory and runtime folder boundaries remain unchanged.

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
For `LOGISTICS_SHIPMENT` and `LOGISTICS_CUSTOMER_PROFILE`, a CustomerApp-bound
proof additionally carries `authorizedSubjectId`. V18 introduces shipment
subject storage; V19 extends checkpoint, receipt and binding constraints to the
profile owner, requires the profile subject and forbids a subject on every
other owner type. The subject therefore participates in payload hashing and
exact replay instead of becoming caller-owned authorization state. Before
binding an avatar, logistics-service validates exactly one current READY
`PROFILE_AVATAR` generation through the private logistics-reference endpoint;
owner, warehouse and authorized subject must all match.
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

## Private asset cabin-creation proof

`POST /api/internal/media/v1/assets/cabin-creation-snapshots` is the narrowly scoped read used by
asset-service to finish its mandatory-photo cabin-creation intent. It accepts only an exact SERVICE
JWT (`sub=client_id=asset-service`, audience `rwms-services`, sole scope `media.asset`) and a
bounded list of cabin/warehouse identities. It reuses the canonical current CABIN photo-library
projection; it does not query or write the asset database.

For each currently bound requested cabin, the response states the active gallery folder, full
logical image count, current READY cover and ordered current-generation READY images. Each image
includes only its opaque media ID, generation, zero-based association index and immutable finalized
source checksum, content type and byte length. Filenames, object-store locations, signed URLs and
bytes are excluded. Missing owner proof, an incomplete/processing gallery, a folder mismatch or a
missing READY cover remains explicit so asset-service can fail closed. This projection adds no
media schema: the finalized metadata and canonical cabin-library association already own the
proof.

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
- [`V12__video_playback_variant.sql`](db/migration/V12__video_playback_variant.sql)
  only expands the `media_variant` check constraint with `PLAYBACK`; existing
  originals and image variants remain unchanged.

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

`POST /api/internal/media/v1/logistics/contractor-task-executions/{entryId}/workers/{workerId}/evidence/{evidenceId}`
is the exact contractor evidence ingress used by logistics-service. The same
exact SERVICE identity/scope is required, `Idempotency-Key` must equal the
reserved `evidenceId`, and `warehouseId` is the only query parameter. The
request is one bounded JPEG or WebP with a known `Content-Length` and lowercase
`X-Content-SHA256`. Media-service derives one stable opaque media ID, records
the named contractor as the `WORKER` actor, and rechecks the current task-board
upload audience before both create and finalize. Bytes are streamed to the
existing private versioned store and verified by length, checksum, MIME sniff,
object version and ETag. An exact retry returns only
`{mediaId,generation,status}` and never another asset, object path, upload
session, URL or credential.

`GET /api/internal/media/v1/logistics/contractor-task-executions/{entryId}/workers/{workerId}/assets/{mediaId}/generations/{generation}/variants/{variant}/content`
is the paired private SMALL/MEDIUM/LARGE stream. It uses the existing exact
task-board worker read proof and a pinned generation, folds every owner,
worker, warehouse, media, generation or variant mismatch into opaque `404`,
and returns the immutable WebP bytes with `private, no-store`. It is not a
general media path; logistics owns any later expiring browser-facing contractor
presentation and never forwards its service credential.

`POST /api/internal/media/v1/logistics/cabin-presentations/snapshots` uses the
same exact SERVICE identity and scope. It accepts one to one hundred unique
CABIN IDs for one warehouse and returns only current canonical bindings plus
the full logical active-folder `photoCount` and at most 100
READY/current-generation images as
`{mediaId,generation,sortOrder,availableVariants}` references, with the cover
first. `photoCount` also includes runtime-available images that are still
processing, which makes an incomplete or oversized set explicit. A library
without an active folder returns zero and an empty `photos` list. Older folders
remain available only through the full CABIN archive. No
browser path, object-store coordinate, signed URL, filename, MIME type or
processing data is returned.

`GET /api/internal/media/v1/logistics/cabin-presentations/assets/{mediaId}/variants/{variant}/content`
is the paired private byte stream. `variant` is exactly `SMALL` or `LARGE`; the
request must name the CABIN, warehouse and generation pinned by an immutable
presentation snapshot. Media verifies the retained canonical association and
the exact generation/variant row, then streams its pinned MinIO object version;
it deliberately does not require the asset to remain current, READY or
non-deleted. The trusted logistics boundary is the membership check: only
logistics may call this route and it must send a `{cabinId,warehouseId,mediaId,
generation,variant}` tuple previously issued by a current snapshot. The
association's `media_generation >= generation` predicate therefore preserves
that historical generation after later processing or soft deletion. Every
scope, association, cabin, warehouse, media, generation and variant mismatch
is an opaque 404. There is no public logistics presentation-media route;
logistics owns any later browser-facing proxy.

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

The build host needs Go 1.25, FFmpeg and FFprobe. Still-image processing has
no CGO or libvips dependency.

```bash
gofmt -w ./cmd ./internal
go test ./...
go build -trimpath -o /tmp/rwms-media-service ./cmd/media-service
```

Migration verification must run separately with Flyway and PostgreSQL and
cover clean V1-to-V19 install, V12-to-current upgrade,
V13-to-V14 reader backfill, V14-to-V15 membership-constraint upgrade, the
additive V16 image-bundle upgrade, V17 legacy-folder consolidation, repeat,
checksum drift and non-empty unversioned rejection. MinIO integration checks
must use a versioned local/test bucket. Kafka integration checks must use a
dedicated local/test broker, never the broker of a running RWMS environment;
the canonical topics and broker acknowledgements are verified inside that
isolated broker.
