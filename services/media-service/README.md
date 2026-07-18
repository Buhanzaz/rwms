# RWMS Media Service

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
`ORIGINAL`. Videos are original-only and use FFmpeg/FFprobe only for an explicit
rotation. An unproved video dimension remains SQL `NULL`.

## Schema startup gate

Flyway is external to this process. Apply
`db/migration/V1__media_schema.sql` and
`db/migration/V2__media_runtime_recovery.sql`, then
`db/migration/V3__inventory_owner_proof.sql` with Flyway before starting the
service. The Go application never migrates, baselines, repairs or silently
adopts a database.

- New local/test databases migrate through V1, V2 and V3.
- A database already at the exact V2 history is upgraded by applying V3.
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
| `MEDIA_MAX_VIDEO_OUTPUT_BYTES` | Maximum encoded video output |
| `MEDIA_ALLOWED_MIME_TYPES` | Comma-separated allowlist: JPEG, PNG, WebP, MP4 or WebM |
| `MEDIA_UPLOAD_EXPIRY` | Constrained upload capability lifetime, for example `5m` |
| `MEDIA_PROCESSING_TIMEOUT` | Per-job processor timeout |
| `MEDIA_KAFKA_BROKERS` | Comma-separated Kafka bootstrap addresses |
| `MEDIA_KAFKA_INVENTORY_TOPIC` | Canonical inventory fact topic |
| `MEDIA_KAFKA_INVENTORY_OWNER_GROUP` | Dedicated media owner-proof consumer group |
| `MEDIA_KAFKA_INVENTORY_OWNER_DLT_TOPIC` | Media-owned inventory owner consumer DLT |
| `MEDIA_INSTANCE_ID` | Unique safe ASCII lease/fence owner ID |

When a video MIME type is allowed, `MEDIA_MAX_VIDEO_DURATION` and the
comma-separated `MEDIA_ALLOWED_VIDEO_CODECS` are also required.

Optional settings are `MEDIA_HTTP_ADDRESS` (default `:8085`) and
`MEDIA_AUTH_AUDIENCE` (default `rwms-services`). The processing group and topic
variables have canonical defaults and may not be changed:
`media-service-processing-v1`, `rwms.media.media.v1` and
`rwms.media.processing.v1`. Terminal processing failures publish only a
hash-only record to
`rwms.media.processing.v1.media-service-processing-v1.dlt`.
The inventory owner consumer settings are likewise fixed to
`rwms.inventory.session.v1`, `media-service-inventory-owner-v1` and
`rwms.inventory.session.v1.media-service-inventory-owner-v1.dlt`.

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
and no delete, retention or orphan-cleanup behavior.

Public access is restricted to a `USER` JWT with a UUID subject, exact RWMS
scope and warehouse grant. The only Stage 7 owner shape is
`INVENTORY_FINDING` with `INSPECTION` context. It is authorized from the local
owner-proof projection, never from caller-supplied owner/warehouse values.
There is no proof TTL: activity is determined by the consumed proof,
checkpoint and quarantine state.

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

The PostgreSQL transport outbox publishes only exact stored bytes. Broker
outages return rows to `PENDING` with bounded DB backoff and never exhaust into
a DLT; only a permanent exact-byte checksum corruption can terminally fail an
outbox row. Processing attempts remain first delivery plus 1s/2s/4s transient
retries; validation is terminal on its actual attempt. A DLT record uses the
source processing-job UUID key for a valid request, or deterministic UUIDv5 in
the OID namespace over SHA-256 of the raw bytes for an invalid request.

## Private logistics readiness validation

`POST /api/internal/media/v1/logistics/references/validate` is the only Stage
8 media receiver. It requires the exact `logistics-service` SERVICE JWT with
matching `sub`/`client_id` and one `media.logistics` scope. The request derives
an opaque owner ID from `documentId:lineId`, permits only
`LOGISTICS_RETURN`, `LOGISTICS_SHIPMENT` and `LOGISTICS_TRANSFER`, and validates
one to twenty unique `{mediaId,generation}` values for the matching warehouse.

The query succeeds only for current `READY` generations and returns only the
validated opaque IDs/generations. It never exposes a URL, object key,
filename, MIME type, processing state or retention policy. It is read-only:
no upload, owner binding, migration, event, outbox, Kafka consumer or object
storage call is made. In particular, it does not depend on the Stage 7
inventory owner-proof projection.

## Local verification

The build host needs Go 1.25, CGO, libvips, FFmpeg and FFprobe.

```bash
gofmt -w ./cmd ./internal
go test ./...
go build -trimpath -o /tmp/rwms-media-service ./cmd/media-service
```

Migration verification must run separately with Flyway and PostgreSQL and cover
clean V1+V2 install, V1-to-V2 upgrade, repeat, checksum drift and non-empty
unversioned rejection. MinIO integration checks must use a versioned local/test
bucket; Kafka checks must use the canonical topics and broker acknowledgements.
